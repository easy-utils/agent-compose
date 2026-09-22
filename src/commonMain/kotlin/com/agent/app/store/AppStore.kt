package com.agent.app.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import com.agent.app.AgentApi
import com.agent.app.models.ChatDraft
import com.agent.app.models.ProviderDraft
import com.agent.app.models.ProviderInfo
import com.agent.app.models.Session
import com.agent.app.platform.LocalStore
import com.agent.app.models.FALLBACK_API_TYPE_CAPABILITIES
import com.agent.app.platform.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Navigation model — port of flutter navigation.dart (per-tab page stacks).

enum class SiderTab { CHAT, CONFIG }

sealed class AppPage(val key: String) {
    data object ChatListPage : AppPage("chat_list")
    data object ChatSessionPage : AppPage("chat_session")
    data object ChatOverlayPage : AppPage("chat_overlay")
    data object ConfigRootPage : AppPage("config_root")
    data class ConfigSubPage(val id: String) : AppPage("config_sub_$id")
    data object ProvidersListPage : AppPage("providers_list")
    data object PresetFormPage : AppPage("preset_form_new")
    data object ProviderFormPage : AppPage("provider_form")
    data class ProviderModelsPage(val modelId: String?) : AppPage("provider_model_${modelId ?: "new"}")
}

fun rootPageFor(tab: SiderTab): AppPage = when (tab) {
    SiderTab.CHAT -> AppPage.ChatListPage
    SiderTab.CONFIG -> AppPage.ConfigRootPage
}

// AppStore — port of flutter store.dart over Compose mutable state.

class AppStore(
    val api: AgentApi,
    val local: LocalStore?,
    private val scope: CoroutineScope,
) {
    var siderTab by mutableStateOf(SiderTab.CHAT)
    var sessions by mutableStateOf<List<Session>>(emptyList())
    var activeSessionId by mutableStateOf<String?>(null)
    var sessionError by mutableStateOf("")
    var sessionRevision by mutableStateOf(0)
    var providersRevision by mutableStateOf(0)

    /** Capability matrix (ListProvidersCatalog): api type -> capabilities.
     * Seeded with the bundled fallback; refreshed from the server. */
    var providerCatalog by mutableStateOf(FALLBACK_API_TYPE_CAPABILITIES)

    fun refreshProviderCatalog() {
        scope.launch {
            try {
                val c = api.providerCatalog()
                if (c.isNotEmpty()) providerCatalog = c
            } catch (_: Throwable) {
                /* keep the fallback */
            }
        }
    }
    var providerDraft by mutableStateOf<ProviderDraft?>(null)
    var chatDrafts by mutableStateOf<Map<String, ChatDraft>>(emptyMap())
    var readSeqs by mutableStateOf<Map<String, Int>>(emptyMap())

    // SnapshotStateList so in-place add/remove/subList.clear() are observed by
    // Compose (a plain MutableList mutated in place does not trigger
    // recomposition, which made the config sub-page only appear after a tab
    // switch forced a full re-read).
    private val stacks: Map<SiderTab, SnapshotStateList<AppPage>> = mapOf(
        SiderTab.CHAT to mutableStateListOf(rootPageFor(SiderTab.CHAT)),
        SiderTab.CONFIG to mutableStateListOf(rootPageFor(SiderTab.CONFIG)),
    )

    private var watchJob: Job? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var firstSnapshot = true

    init {
        scope.launch { hydrateLocal() }
        startSessionWatch()
    }

    private suspend fun hydrateLocal() {
        val l = local ?: return
        try {
            // MERGE (never clobber): the stream's first snapshot can land before
            // this read resolves and seed read watermarks; overwriting with the
            // older DB copy would flash every row back as unread.
            readSeqs = l.loadReadSeqs() + readSeqs
            chatDrafts = l.loadDrafts()
        } catch (_: Exception) {
        }
    }

    /**
     * First-ever snapshot on this device: seed read watermarks so historical
     * sessions do not all pop up as unread. Mirrors the seeds to the local DB
     * as well, so a cold start whose DB read loses the race cannot repopulate
     * from an empty table and flash every row unread again (flutter
     * store._firstSnapshot does the same).
     */
    private fun seedFirstSnapshot() {
        if (!firstSnapshot) return
        firstSnapshot = false
        val seeded = readSeqs.toMutableMap()
        for (s in sessions) {
            if (!seeded.containsKey(s.id)) {
                seeded[s.id] = s.messageSeq
                val seq = s.messageSeq
                val id = s.id
                scope.launch { local?.setReadSeq(id, seq) }
            }
        }
        readSeqs = seeded
        Prefs.saveReadSeqs(seeded)
    }

    fun startSessionWatch() {
        watchJob?.cancel()
        reconnectJob?.cancel()
        watchJob = scope.launch {
            try {
                api.watchSessions().collect { ev ->
                    applySessionEvent(ev.snapshot, ev.upserts, ev.removed)
                }
                onWatchClosed()
            } catch (e: Exception) {
                if (isAuthError(e)) AuthExpired.notifyAuth()
                onWatchClosed()
            }
        }
    }

    private fun onWatchClosed() {
        if (attempt >= 20) return
        val secs = minOf(30, 1 shl minOf(attempt, 5))
        attempt++
        reconnectJob = scope.launch {
            delay(secs * 1000L)
            startSessionWatch()
        }
    }

    /** Assign the session list, ALWAYS ordered most-recent-first. The server
     *  snapshot is ordered by `updated_at`, but a live upsert only advances a
     *  row's `lastMessageAt` in place — without this re-sort the row's timestamp
     *  changes while its position does not. Recency: lastMessageAt -> updatedAt
     *  -> createdAt. */
    private fun assignSessions(list: List<Session>) {
        sessions = list.sortedByDescending { recency(it) }
    }

    private fun recency(s: Session): Long =
        listOf(s.lastMessageAt, s.updatedAt, s.createdAt)
            .firstNotNullOfOrNull { parseIsoMillis(it) } ?: 0L

    private fun parseIsoMillis(v: String): Long? {
        if (v.isEmpty()) return null
        return try {
            kotlin.time.Instant.parse(v).toEpochMilliseconds()
        } catch (_: Exception) {
            null
        }
    }

    private fun applySessionEvent(snapshot: Boolean, upserts: List<Session>, removed: List<String>) {
        attempt = 0
        if (snapshot) {
            assignSessions(upserts)
            seedFirstSnapshot()
        } else {
            val next = sessions.toMutableList()
            for (s in upserts) {
                val i = next.indexOfFirst { it.id == s.id }
                if (i == -1) next.add(s) else next[i] = s
            }
            next.apply { if (removed.isNotEmpty()) removeAll { it.id in removed } }
            assignSessions(next)
        }
        activeSession?.let { a ->
            val read = readSeqs[a.id] ?: -1
            if (read < a.messageSeq) {
                readSeqs = readSeqs + (a.id to a.messageSeq)
                Prefs.saveReadSeqs(readSeqs)
            }
        }
        sessionError = ""
    }

    val activeSession: Session? get() = sessions.firstOrNull { it.id == activeSessionId }
    fun sessionById(id: String): Session? = sessions.firstOrNull { it.id == id }

    suspend fun refreshSessions() {
        try {
            assignSessions(api.listSessions())
            sessionError = ""
        } catch (e: Exception) {
            if (isAuthError(e)) AuthExpired.notifyAuth()
            sessionError = e.message ?: e.toString()
        }
    }

    suspend fun deleteSession(id: String) {
        try {
            api.deleteSession(id)
            local?.removeSession(id)
        } catch (_: Exception) {
        }
        if (activeSessionId == id) closeSession()
        refreshSessions()
    }

    suspend fun deleteSessions(ids: List<String>): List<String> {
        val failed = ArrayList<String>()
        var closedActive = false
        for (id in ids) {
            try {
                api.deleteSession(id)
                local?.removeSession(id)
                if (activeSessionId == id) {
                    activeSessionId = null
                    closedActive = true
                }
            } catch (_: Exception) {
                failed.add(id)
            }
        }
        if (closedActive) closeSession()
        refreshSessions()
        return failed
    }

    suspend fun forkSession(branch: String): Boolean {
        val id = activeSessionId ?: return false
        return try {
            val s = api.fork(id, branch)
            activeSessionId = s?.id
            refreshSessions()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pickSession(id: String) {
        activeSessionId = id
        markSessionRead(id)
        pushPage(AppPage.ChatSessionPage)
    }

    fun markSessionRead(id: String) {
        val seq = sessionById(id)?.messageSeq ?: readSeqs[id] ?: 0
        readSeqs = readSeqs + (id to seq)
        Prefs.saveReadSeqs(readSeqs)
        scope.launch { local?.setReadSeq(id, seq) }
        sessions = sessions.map { if (it.id == id) it.copyWith(unreadCount = 0) else it }
    }

    fun unreadCountFor(s: Session): Int {
        val read = readSeqs[s.id] ?: return s.messageSeq
        return maxOf(0, s.messageSeq - read)
    }

    fun isUnread(s: Session): Boolean = unreadCountFor(s) > 0

    // ---- drafts ----

    fun draftFor(sessionId: String): ChatDraft {
        val existing = chatDrafts[sessionId]
        if (existing != null) return existing
        val d = ChatDraft()
        chatDrafts = chatDrafts + (sessionId to d)
        return d
    }

    fun saveDraftText(sessionId: String, text: String) {
        val d = draftFor(sessionId)
        if (d.text == text) return
        d.text = text
        if (text.isBlank() && d.attachments.isEmpty()) {
            chatDrafts = chatDrafts - sessionId
            scope.launch { local?.saveDraft(sessionId, "", emptyList()) }
            return
        }
        scope.launch { local?.saveDraft(sessionId, d.text, d.attachments) }
    }

    fun saveDraftAttachments(sessionId: String, attachments: List<com.agent.app.models.UploadedFile>) {
        val d = draftFor(sessionId)
        d.attachments = attachments.toMutableList()
        if (d.text.isBlank() && d.attachments.isEmpty()) {
            chatDrafts = chatDrafts - sessionId
            scope.launch { local?.saveDraft(sessionId, "", emptyList()) }
            return
        }
        scope.launch { local?.saveDraft(sessionId, d.text, d.attachments) }
    }

    fun clearDraft(sessionId: String) {
        chatDrafts = chatDrafts - sessionId
        scope.launch { local?.saveDraft(sessionId, "", emptyList()) }
    }

    // ---- provider draft ----

    fun bumpProvidersRevision() {
        providersRevision++
    }

    fun beginProviderDraft(existing: ProviderInfo?, capability: String = "text") {
        providerDraft = existing?.let { ProviderDraft.from(it) }
            ?: ProviderDraft(capability = capability)
    }

    fun endProviderDraft() {
        providerDraft = null
    }

    fun closeSession() {
        activeSessionId = null
        val list = stackFor(SiderTab.CHAT)
        if (list.size > 1) list.subList(1, list.size).clear()
    }

    fun bumpSessionRevision() {
        sessionRevision++
    }

    fun applySession(updated: Session) {
        assignSessions(sessions.map { if (it.id == updated.id) updated else it })
        bumpSessionRevision()
    }

    fun switchTab(tab: SiderTab) {
        siderTab = tab
    }

    // ---- navigation stacks ----

    private fun stackFor(tab: SiderTab): SnapshotStateList<AppPage> =
        stacks.getValue(tab)

    val currentStack: List<AppPage> get() = stackFor(siderTab)
    val topPage: AppPage get() = currentStack.last()

    fun pushPage(page: AppPage) {
        val list = stackFor(siderTab)
        val idx = list.indexOfFirst { it.key == page.key }
        if (idx != -1) list.subList(idx, list.size).clear()
        list.add(page)
        bumpSessionRevision()
    }

    fun pushSibling(page: AppPage) {
        val list = stackFor(siderTab)
        if (list.size > 1) list.subList(1, list.size).clear()
        pushPage(page)
    }

    fun popPage() {
        val list = stackFor(siderTab)
        if (list.size > 1) {
            list.removeAt(list.size - 1)
            if (siderTab == SiderTab.CHAT && list.size == 1) {
                activeSessionId = null
            }
            bumpSessionRevision()
        }
    }

    val canPopPage: Boolean get() = currentStack.size > 1
}

/** True when the RPC failed because the token is missing/invalid/revoked. */
fun isAuthError(e: Throwable): Boolean {
    val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
    return m.contains("unauthenticated", true) ||
        m.contains("permission_denied", true) ||
        m.contains("PERMISSION_DENIED", true) ||
        m.contains("UNAUTHENTICATED", true) ||
        // Android/desktop: connect-kotlin surfaces "HTTP 401/403" from OkHttp.
        Regex("\b(401|403)\b").containsMatchIn(m)
}
