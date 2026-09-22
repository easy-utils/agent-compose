package com.agent.app.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agent.app.AgentApi
import com.agent.app.mapMessagesToChat
import com.agent.app.models.ChatMessage
import com.agent.app.models.ChatPart
import com.agent.app.models.Message
import com.agent.app.models.StreamEvent
import com.agent.app.models.ToolState
import com.agent.app.models.UploadedFile
import com.agent.app.platform.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

// MessagesController — port of flutter messages.dart: local-first boot,
// tip-anchored incremental sync, one long-lived watchSession stream with
// backoff reconnect, eid dedup and run boundaries.

class MessagesController(
    private val api: AgentApi,
    private val getSessionId: () -> String,
    private val local: LocalStore?,
    private val scope: CoroutineScope,
    private val sendFailed: (Exception) -> String,
) {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
    var sending by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var hasMore by mutableStateOf(false)
    var revision by mutableStateOf(0)

    private var syncedTipId = ""
    private var syncedOldestId = ""

    /**
     * Local ERROR bubbles ("send failed" / stream error) are NOT part of the
     * server chain, so every authoritative refresh (fetchMessages / baseline /
     * mergeServer) would drop them — the user saw the error flash and vanish.
     * Keep them in a side list merged back on every rebuild, the same way
     * flutter/webui keep local in-flight bubbles. Cleared per session in init.
     */
    private val localErrors = mutableListOf<ChatMessage>()

    private var streamJob: Job? = null
    private var reconnectJob: Job? = null
    private var subSid: String? = null
    private var streamingId: String? = null
    private var nextSeq = 1_000_000
    private var reconnectAttempt = 0

    private val seenEids = HashSet<String>()
    private var activeRunId: String? = null
    private var awaitingRun = false

    /**
     * Set by [stop] (a LOCAL interrupt): the server may still flush a few
     * `text-start`/`text-delta` events before its abort takes effect, and those
     * would re-create a SECOND streaming bubble after [finishStreaming] already
     * closed the first (two truncated bubbles). While true, inbound turn
     * content is dropped until the run's terminal event arrives.
     */
    private var suppressRunContent = false

    /**
     * Set while a LOCAL undo/revert (retry / edit) is in flight. The server
     * publishes `chain-changed` for that undo, and the default handler would
     * `clearStreaming()` the fresh streaming bubble [send] just created for the
     * resend. Suppress that one self-inflicted notification.
     */
    private var localUndoInFlight = false

    private var idleProbeJob: Job? = null
    private var lastActivity = 0L

    val sorted: List<ChatMessage>
        get() = dedupeById(messages)
            .sortedWith(compareBy({ it.seq ?: Int.MAX_VALUE }, { it.createdAt }, { it.id }))

    /**
     * Collapse entries that share an id, preferring the authoritative (server,
     * non-local) copy. Retry/resend can transiently hold BOTH a local
     * placeholder and the server row that supersedes it under the same id
     * (the prompt is persisted server-side before the client adopts the id);
     * two entries with one id make `LazyColumn(items(key = { it.id }))` throw
     * `IllegalArgumentException: Key ... was already used`. Always unique here
     * so the message list can never crash the scene.
     */
    private fun dedupeById(list: List<ChatMessage>): List<ChatMessage> {
        if (list.size < 2) return list
        val best = LinkedHashMap<String, ChatMessage>(list.size)
        for (m in list) {
            val cur = best[m.id]
            best[m.id] = when {
                cur == null -> m
                cur.isLocal && !m.isLocal -> m
                !cur.isLocal && m.isLocal -> cur
                else -> m
            }
        }
        return if (best.size == list.size) list else best.values.toList()
    }

    /**
     * Converge with the server on demand. Called when the session-list stream
     * reports this session advanced (another device sent a message / turn
     * events landed) — the per-session event stream carries no event for a
     * message appended elsewhere, so without this the open chat never shows it.
     */
    fun onExternalActivity() {
        if (sending) return
        scope.launch { reconcile() }
    }

    fun init() {
        val sid = getSessionId()
        if (sid.isEmpty()) return
        localErrors.clear()
        scope.launch { boot(sid) }
    }

    private suspend fun boot(sid: String) {
        hydrateFromLocal(sid)
        sync(sid)
        recover()
        connect(sid)
    }

    private suspend fun hydrateFromLocal(sid: String) {
        val l = local ?: return
        try {
            val cached = l.loadMessages(sid)
            syncedTipId = if (cached.isEmpty()) "" else l.serverTipId(sid)
            syncedOldestId = if (cached.isEmpty()) "" else l.oldestCachedId(sid)
            if (cached.isNotEmpty()) {
                messages = cached + inFlightLocal()
                renumber()
                revision++
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun sync(sid: String) {
        val l = local
        loading = messages.isEmpty()
        revision++
        try {
            // The ANCHOR decides incremental vs baseline; it must NOT depend on
            // whether a local mirror exists (web has none). Otherwise web never
            // builds an anchor, never merges server messages, and misses
            // cross-device user messages.
            val cacheConsistent = messages.isEmpty() ||
                (syncedTipId.isNotEmpty() && syncedOldestId.isNotEmpty() && l?.oldestCachedId(sid) == syncedOldestId)
            if (syncedTipId.isNotEmpty() && cacheConsistent) {
                val (msgs, resync, tipId) = api.messagesAfter(sid, syncedTipId)
                when {
                    resync -> baseline(sid)
                    msgs.isEmpty() && messages.isEmpty() -> baseline(sid)
                    else -> {
                        mergeServer(msgs, tipId)
                        l?.persistMessages(sid, messages, tipId)
                    }
                }
            } else {
                baseline(sid)
            }
        } catch (_: Exception) {
        }
        loading = false
        revision++
    }

    private suspend fun baseline(sid: String) {
        try {
            val (msgs, more) = api.messages(sid, null, 50)
            val chat = mapMessagesToChat(msgs)
            messages = inFlightLocal() + localErrors + chat
            renumber()
            hasMore = more
            // Always record the sync anchor, with or without a local mirror: the
            // anchor is what makes the live stream incremental and lets
            // reconcile()/mergeServer() converge this view with the server.
            syncedTipId = chat.lastOrNull()?.id ?: ""
            local?.applyServerMessages(sid, msgs, true, syncedTipId)
            syncedOldestId = local?.oldestCachedId(sid) ?: (chat.firstOrNull()?.id ?: "")
        } catch (_: Exception) {
        }
    }

    private fun mergeServer(msgs: List<Message>, tipId: String) {
        val hasServer = msgs.isNotEmpty()
        val chat = mapMessagesToChat(msgs)
        val byId = LinkedHashMap<String, ChatMessage>()
        for (m in messages) {
            if (!m.isLocal) {
                byId[m.id] = m
                continue
            }
            val inFlight = m.status == "streaming" || m.status == "pending"
            if (!hasServer || inFlight) byId["local:${m.id}"] = m
        }
        for (m in chat) byId[m.id] = m
        // Local error bubbles are not server chain members: keep them visible.
        for (m in localErrors) byId["local:${m.id}"] = m
        messages = byId.values.toList()
        renumber()
        syncedTipId = tipId
    }

    private fun renumber() {
        // Stable sort by createdAt ONLY (sortedWith/sortedBy are stable in
        // Kotlin): ties keep the CURRENT array order. Ties must NOT be broken
        // by id — a locally-appended user bubble and the assistant streaming
        // placeholder created right after it share a millisecond, and "m…"
        // sorts before "u…" — which would draw the assistant reply ABOVE its
        // user message (same fix as flutter/webui/swiftui).
        // Dedupe first: retry/resend can momentarily hold a local placeholder
        // and its server row under one id (see [dedupeById]); rendering two
        // LazyColumn items with the same id throws.
        val ordered = dedupeById(messages).sortedBy { it.createdAt }
        messages = ordered.mapIndexed { i, m -> m.copy(seq = i) }
        nextSeq = (messages.maxOfOrNull { it.seq ?: -1 } ?: -1) + 1
    }

    private suspend fun fetchMessages(before: String? = null) {
        loading = true
        revision++
        try {
            val sid = getSessionId()
            val (msgs, more) = api.messages(sid, before, 50)
            val chat = mapMessagesToChat(msgs)
            if (before != null) {
                val existing = messages.map { it.id }.toHashSet()
                messages = chat.filter { it.id !in existing } + messages
            } else {
                messages = inFlightLocal() + localErrors + chat
            }
            renumber()
            hasMore = more
        } catch (_: Exception) {
        }
        loading = false
        revision++
        val l = local ?: return
        try {
            l.persistMessages(getSessionId(), messages, syncedTipId)
            syncedOldestId = l.oldestCachedId(getSessionId())
        } catch (_: Exception) {
        }
    }

    private suspend fun recover() {
        try {
            val (status) = api.state(getSessionId())
            if (status == "busy" || status == "running") {
                sending = true
                if (messages.none { it.status == "streaming" }) {
                    streamingId = "recover-${com.agent.app.util.nowMillis()}"
                    messages = messages + ChatMessage(
                        id = streamingId!!,
                        role = "assistant",
                        status = "streaming",
                        parts = emptyList(),
                        createdAt = nowIso(),
                        isLocal = true,
                        seq = allocSeq(),
                    )
                }
                revision++
            }
        } catch (_: Exception) {
        }
    }

    private fun connect(sid: String) {
        reconnectJob?.cancel()
        streamJob?.cancel()
        subSid = sid
        reconnectAttempt = 0
        lastActivity = com.agent.app.util.nowMillis()
        idleProbeJob?.cancel()
        seenEids.clear()
        activeRunId = null
        awaitingRun = true
        streamJob = scope.launch {
            try {
                api.streamEvents(sid, syncedTipId).collect { handleEvent(it) }
                onStreamClosed(sid)
            } catch (_: Exception) {
                onStreamClosed(sid)
            }
        }
        startIdleProbe()
    }

    private fun clearStreaming() {
        streamingId = null
        activeRunId = null
        if (messages.any { it.status == "streaming" }) {
            messages = messages.filter { it.status != "streaming" }
        }
    }

    private fun onStreamClosed(sid: String) {
        if (subSid != null && subSid != sid) return
        syncIdle()
        if (sid != getSessionId()) return
        if (reconnectAttempt >= 10) return
        val delayMs = min(30_000L, 1000L shl reconnectAttempt)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            connect(sid)
        }
    }

    private fun startIdleProbe() {
        idleProbeJob?.cancel()
        idleProbeJob = scope.launch {
            while (true) {
                delay(30_000)
                if (com.agent.app.util.nowMillis() - lastActivity < 30_000) continue
                try {
                    val (st) = api.state(getSessionId())
                    if (st == "busy" || st == "running") {
                        if (!sending) {
                            sending = true
                            revision++
                        }
                    } else {
                        syncIdle()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun syncIdle() {
        if (!sending) return
        finishStreaming()
    }

    private fun handleEvent(ev: StreamEvent) {
        lastActivity = com.agent.app.util.nowMillis()
        if (ev.eid.isNotEmpty()) {
            if (!seenEids.add(ev.eid)) return
            if (seenEids.size > 20000) seenEids.clear()
        }
        if (awaitingRun) {
            awaitingRun = false
            clearStreaming()
        }
        val run = ev.runId
        if (run.isNotEmpty() && run != activeRunId) {
            if (activeRunId != null) clearStreaming()
            activeRunId = run
        }
        val params = ev.params
        when (ev.event) {
            // The server AUTHORED this message's id and chain anchor. This is
            // the ONLY place user bubbles are created (no client-optimistic
            // row). `streaming:true` opens the assistant step's bubble, whose
            // deltas then arrive under the same id.
            "message-added" -> {
                val addedId = params["message_id"] as String? ?: ""
                val prevId = params["prev_id"] as String? ?: ""
                val role = params["role"] as String? ?: "assistant"
                val streaming = params["streaming"] == true
                val src = params["source"] as String? ?: ""
                if (addedId.isNotEmpty()) {
                    if (streaming && role == "assistant") {
                        val prevStream = streamingId
                        if (prevStream != null && prevStream != addedId) {
                            messages = messages.map {
                                if (it.id == prevStream && it.status == "streaming") {
                                    it.copy(status = "complete")
                                } else it
                            }
                        }
                        streamingId = addedId
                        ensureStreamingMsgAt(addedId, prevId)
                    } else if (role == "user") {
                        upsertServerMessage(addedId, prevId, "user", src)
                    }
                    revision++
                }
            }
            "start-step", "text-start", "reasoning-start", "tool-input-start" -> {
                if (suppressRunContent) return
                val current = streamingId?.let { id -> messages.firstOrNull { it.id == id } }
                val hasToolPart = current?.parts?.any { it.type == "tool" } ?: false
                val sid = ensureStreamingMsg(ev.event == "start-step" || (ev.event == "text-start" && hasToolPart))
                val pid = params["id"] as String?
                when (ev.event) {
                    "text-start" -> if (pid != null) ensurePart(sid, pid, "text")
                    "reasoning-start" -> if (pid != null) ensurePart(sid, "r$pid", "reasoning")
                    // The tool bubble appears as soon as the model starts
                    // emitting the call, before its arguments are complete.
                    "tool-input-start" -> if (pid != null) {
                        startToolPart(sid, pid, (params["toolName"] ?: params["name"] ?: "tool") as String)
                    }
                }
            }
            "tool-input-delta" -> {
                if (suppressRunContent) return
                val pid = params["id"] as? String ?: return
                val delta = params["delta"] as? String ?: return
                appendToolInput(pid, delta)
            }
            "text-delta" -> {
                if (suppressRunContent) return
                val pid = params["id"] as? String
                val text = params["text"] as? String
                if (pid != null && text != null) {
                    appendDelta(ensureStreamingMsg(false), pid, text, false)
                }
            }
            "reasoning-delta" -> {
                if (suppressRunContent) return
                val pid = params["id"] as? String
                val text = params["text"] as? String
                if (pid != null && text != null) {
                    appendDelta(ensureStreamingMsg(false), "r$pid", text, true)
                }
            }
            "tool-call" -> {
                if (suppressRunContent) return
                val sid = ensureStreamingMsg(false)
                val tcId = (params["toolCallId"] ?: params["id"]) as? String
                if (tcId != null) {
                    addToolPart(
                        sid, tcId,
                        (params["toolName"] ?: params["name"] ?: "tool") as String,
                        params["input"],
                    )
                }
            }
            "tool-result" -> {
                val tcId = (params["toolCallId"] ?: params["id"]) as? String ?: return
                updateToolResult(
                    tcId, params["formatted"] ?: params["output"] ?: params["result"],
                    changeId = params["change_id"] as? String,
                    diff = params["diff"] as? String,
                    additions = (params["additions"] as? Number)?.toInt(),
                    deletions = (params["deletions"] as? Number)?.toInt(),
                    data = params["data"] as? Map<String, Any?>,
                )
            }
            "tool-error" -> {
                val tcId = (params["toolCallId"] ?: params["id"]) as? String ?: return
                val errObj = params["error"]
                val errMsg = when (errObj) {
                    is String -> errObj
                    is Map<*, *> -> ((errObj["message"] ?: params["message"]) ?: "tool error").toString()
                    else -> (params["message"] ?: "tool error").toString()
                }
                updateToolResult(tcId, null, errorMsg = errMsg)
            }
            "tool-output-denied" -> {
                val tcId = (params["toolCallId"] ?: params["id"]) as? String ?: return
                updateToolResult(tcId, null, errorMsg = "denied")
            }
            "file", "reasoning-file" -> {
                // A streamed media part the agent has already offloaded to the
                // blob store; `code` is the file code. Render it as a file part
                // (same path as a persisted file part) on the streaming bubble.
                val code = params["code"] as? String
                if (code.isNullOrEmpty()) return
                val sid = ensureStreamingMsg(false)
                val partId = "f$code"
                setMsg(sid) { m ->
                    if (m.parts.any { it.id == partId }) m
                    else m.copy(
                        parts = m.parts + ChatPart(
                            id = partId, type = "file", code = code,
                            name = params["name"] as? String,
                            mime = (params["mediaType"] ?: params["mime"]) as? String,
                        ),
                    )
                }
            }
            "turn-complete" -> {
                suppressRunContent = false
                finishStreaming()
            }
            "chain-changed" -> {
                if (localUndoInFlight) {
                    // Our own undo (retry / edit): the resend that follows will
                    // create the next streaming bubble; do not clear it here.
                    return
                }
                clearStreaming()
                sending = false
                revision++
                scope.launch { fetchMessages() }
            }
            "status" -> {
                val stype = params["type"]
                if (stype == "busy" || stype == "running") {
                    sending = true
                    revision++
                } else {
                    suppressRunContent = false
                    finishStreaming()
                }
            }
            "error", "provider-error" -> {
                val errObj = params["error"]
                val content = when (errObj) {
                    is String -> errObj
                    is Map<*, *> -> ((errObj["message"] ?: params["message"]) ?: "Unknown error").toString()
                    else -> (params["message"] ?: "Unknown error").toString()
                }
                addError(content)
                sending = false
                revision++
            }
        }
    }

    private fun allocSeq(): Int = nextSeq++

    private fun inFlightLocal(): List<ChatMessage> =
        messages.filter { it.isLocal && (it.status == "streaming" || it.status == "pending") }

    private fun nowIso(): String = kotlin.time.Clock.System.now().toString()

    private fun ensureStreamingMsg(forceNew: Boolean): String {
        streamingId?.let { id ->
            val existing = messages.firstOrNull { it.id == id }
            if (existing != null && (!forceNew || existing.parts.isEmpty())) return id
        }
        val id = "m${com.agent.app.util.nowMillis()}"
        streamingId = id
        messages = messages + ChatMessage(
            id = id,
            role = "assistant",
            status = "streaming",
            parts = emptyList(),
            createdAt = nowIso(),
            isLocal = true,
            seq = allocSeq(),
        )
        // Bump immediately so the FIRST frame (a reasoning/text/tool part)
        // renders at once — otherwise the optimistic "thinking…" bubble
        // lingers until the next unrelated revision.
        revision++
        return id
    }

    /** Open (or reuse) the server-authored streaming assistant bubble for the
     *  id announced by `message-added{streaming:true}`. No id is minted here. */
    private fun ensureStreamingMsgAt(id: String, prevId: String) {
        if (messages.any { it.id == id }) {
            streamingId = id
            return
        }
        messages = messages + ChatMessage(
            id = id,
            role = "assistant",
            status = "streaming",
            parts = emptyList(),
            prevId = prevId,
            createdAt = nowIso(),
            isLocal = true,
            seq = allocSeq(),
        )
        revision++
    }

    /** Render a persisted (non-streaming) row announced via `message-added`
     *  using the server-authored id/position — the user prompt in particular. */
    private fun upsertServerMessage(id: String, prevId: String, role: String, source: String) {
        if (messages.any { it.id == id }) return
        messages = messages + ChatMessage(
            id = id,
            role = role,
            status = "complete",
            parts = emptyList(),
            prevId = prevId,
            source = source,
            createdAt = nowIso(),
            seq = allocSeq(),
        )
    }

    private fun setMsg(id: String, fn: (ChatMessage) -> ChatMessage) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return
        messages = messages.toMutableList().also { it[idx] = fn(it[idx]) }
        revision++
    }

    private fun ensurePart(msgId: String, partId: String, type: String) {
        setMsg(msgId) { m ->
            if (m.parts.any { it.id == partId }) m
            else m.copy(parts = m.parts + ChatPart(id = partId, type = type))
        }
    }

    /**
     * Append one streamed delta and mark the message dirty. No coalescing:
     * every event updates immediately, exactly like flutter/webui (per-event
     * setState). The append happens on the UI dispatcher for the stream
     * consumer, so a single delta is cheap and the list jump-scroll keeps up.
     */
    private fun appendDelta(msgId: String, partId: String, delta: String, reasoning: Boolean) {
        val idx = messages.indexOfFirst { it.id == msgId }
        if (idx < 0) return
        val m = messages[idx]
        val pidx = m.parts.indexOfFirst { it.id == partId }
        val parts = m.parts.toMutableList()
        if (pidx >= 0) {
            parts[pidx] = parts[pidx].copy(text = parts[pidx].text + delta)
        } else {
            parts.add(ChatPart(id = partId, type = if (reasoning) "reasoning" else "text", text = delta))
        }
        messages = messages.toMutableList().also { it[idx] = m.copy(parts = parts) }
        revision++
    }

    private fun addToolPart(msgId: String, partId: String, name: String, input: Any?) {
        val inputMap = input as? Map<String, Any?>
        setMsg(msgId) { m ->
            val parts = m.parts.toMutableList()
            val part = ChatPart(id = partId, type = "tool", tool = name, state = ToolState("running", name, inputMap))
            val pidx = parts.indexOfFirst { it.id == partId }
            if (pidx >= 0) parts[pidx] = part else parts.add(part)
            m.copy(parts = parts)
        }
    }

    /** Create/replace a tool part the moment its input streaming begins. */
    private fun startToolPart(msgId: String, partId: String, name: String) {
        setMsg(msgId) { m ->
            if (m.parts.any { it.id == partId }) return@setMsg m
            m.copy(
                parts = m.parts + ChatPart(
                    id = partId, type = "tool", tool = name,
                    state = ToolState(status = "running", title = name, inputText = ""),
                ),
            )
        }
    }

    /** Accumulate streamed tool-argument JSON for the live preview. */
    private fun appendToolInput(partId: String, delta: String) {
        val sid = streamingId ?: return
        setMsg(sid) { m ->
            val parts = m.parts.map { p ->
                if (p.id != partId) p
                else {
                    val old = p.state ?: ToolState()
                    p.copy(state = old.copy(inputText = (old.inputText ?: "") + delta))
                }
            }
            m.copy(parts = parts)
        }
    }

    private fun updateToolResult(
        partId: String,
        result: Any?,
        errorMsg: String? = null,
        changeId: String? = null,
        diff: String? = null,
        additions: Int? = null,
        deletions: Int? = null,
        data: Map<String, Any?>? = null,
    ) {
        val sid = streamingId ?: return
        setMsg(sid) { m ->
            val parts = m.parts.map { p ->
                if (p.id != partId) p
                else {
                    val old = p.state ?: ToolState()
                    val output = when (result) {
                        is String -> result
                        null -> null
                        else -> pretty(result)
                    }
                    p.copy(
                        state = old.copy(
                            status = if (errorMsg != null) "error" else "complete",
                            error = errorMsg ?: old.error,
                            output = output ?: old.output,
                            data = data ?: old.data,
                            changeId = changeId ?: old.changeId,
                            diff = diff ?: old.diff,
                            additions = additions ?: old.additions,
                            deletions = deletions ?: old.deletions,
                        ),
                    )
                }
            }
            m.copy(parts = parts)
        }
    }

    private fun finishStreaming() {
        messages = messages.map { if (it.status == "streaming") it.copy(status = "complete") else it }
        streamingId = null
        activeRunId = null
        sending = false
        revision++
        scope.launch { reconcile() }
    }

    private suspend fun reconcile() {
        val sid = getSessionId()
        try {
            // Network convergence runs even without a local mirror (web): this
            // adopts server ids, brings in messages sent from OTHER clients, and
            // supersedes local placeholders. Only persistence is gated on local.
            if (syncedTipId.isEmpty()) {
                baseline(sid)
                return
            }
            val (msgs, resync, tipId) = api.messagesAfter(sid, syncedTipId)
            if (resync) {
                baseline(sid)
                return
            }
            mergeServer(msgs, tipId)
            revision++
            local?.persistMessages(sid, messages, syncedTipId)
            syncedOldestId = local?.oldestCachedId(sid) ?: syncedOldestId
        } catch (_: Exception) {
        }
    }

    private fun addError(text: String) {
        // Stop the in-flight stream so a late turn-complete / status:idle can
        // not rebuild the list without the error bubble.
        streamingId = null
        activeRunId = null
        val now = com.agent.app.util.nowMillis()
        val err = ChatMessage(
            id = "err$now",
            role = "error",
            status = "error",
            parts = listOf(ChatPart(id = "p$now", type = "text", text = text)),
            createdAt = nowIso(),
            isLocal = true,
            seq = allocSeq(),
        )
        localErrors.add(err)
        messages = messages.filter { it.status != "streaming" } + err
    }

    suspend fun send(text: String, attachments: List<UploadedFile> = emptyList()) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || sending) return
        sending = true
        val codes = attachments.map { it.code }
        // No client-optimistic user bubble: the server AUTHORS the message id
        // and chain position and announces it via `message-added{role:user}`
        // once the running turn drains the mailbox. We only show the composer
        // spinner until the send RPC is accepted.
        revision++
        try {
            api.prompt(getSessionId(), trimmed, codes)
            // The send is durable at `accepted`; the bubble follows the stream.
        } catch (e: Exception) {
            addError(sendFailed(e))
            sending = false
            revision++
        }
    }

    fun stop() {
        // Drop any in-flight content the server has not yet aborted, so it can
        // not spawn a second bubble after we close the first.
        suppressRunContent = true
        scope.launch {
            try {
                api.interrupt(getSessionId())
            } catch (_: Exception) {
            }
            finishStreaming()
        }
    }

    suspend fun revert(messageId: String) {
        if (sending) {
            try {
                api.interrupt(getSessionId())
            } catch (_: Exception) {
            }
        }
        localUndoInFlight = true
        try {
            api.revert(getSessionId(), messageId)
            clearStreaming()
            sending = false
            fetchMessages()
        } finally {
            localUndoInFlight = false
        }
    }

    suspend fun resendFrom(msg: ChatMessage, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (sending) {
            try {
                api.interrupt(getSessionId())
            } catch (_: Exception) {
            }
        }
        val codes = msg.parts.filter { it.type == "file" }.mapNotNull { it.code?.takeIf { c -> c.isNotEmpty() } }
        localUndoInFlight = true
        try {
            api.revert(getSessionId(), msg.id)
            clearStreaming()
            sending = false
            fetchMessages()
        } finally {
            localUndoInFlight = false
        }
        send(trimmed, codes.map { UploadedFile(code = it) })
    }

    suspend fun loadMore() {
        if (!hasMore || loading) return
        val first = sorted.firstOrNull() ?: return
        fetchMessages(first.id)
    }

    fun dispose() {
        reconnectJob?.cancel()
        idleProbeJob?.cancel()
        streamJob?.cancel()
    }

    private fun pretty(o: Any): String = prettyAny(o)
}

private fun prettyAny(o: Any): String = when (o) {
    is Map<*, *> -> o.entries.joinToString(",\n", "{\n", "\n}") { (k, v) -> "  \"$k\": ${prettyAny(v ?: "null")}" }
    is List<*> -> o.joinToString(",\n", "[\n", "\n]") { "  ${prettyAny(it ?: "null")}" }
    is String -> "\"${o.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    else -> o.toString()
}
