package com.agent.app.ui

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.number

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agent.app.i18n.I18n.t
import com.agent.app.models.Session
import com.agent.app.store.AppStore
import com.agent.app.store.SiderTab
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch

// SessionListScreen — port of flutter session_list_page.dart: search,
// multi-select delete, long-press actions, new-session prompt, unread badges.

@Composable
fun SessionListScreen(store: AppStore) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var actionsFor by remember { mutableStateOf<Session?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteConfirmFor by remember { mutableStateOf<String?>(null) }
    var forkFor by remember { mutableStateOf<String?>(null) }
    // Subsession tree: parents with children are COLLAPSED by default; this
    // holds the ids the user manually expanded (memory-only by design).
    var expanded by remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(store.sessions, q) {
        if (q.isBlank()) store.sessions
        else store.sessions.filter {
            it.id.contains(q, true) || it.lastMessagePreview.contains(q, true) || it.sessionName.contains(q, true)
        }
    }

    // Rendered list: top-level sessions ordered by recency with their
    // subsessions (group == parent id) nested below when expanded. Depth is 1
    // by protocol; children of missing parents are promoted to the top level so
    // nothing disappears. While searching the list stays flat so subsessions
    // remain findable by name.
    val display = remember(filtered, searching, expanded) {
        if (searching) filtered
        else {
            val byId = filtered.associateBy { it.id }
            val childrenOf = LinkedHashMap<String, MutableList<Session>>()
            val top = ArrayList<Session>()
            for (s in filtered) {
                if (s.group.isNotEmpty() && byId.containsKey(s.group)) {
                    childrenOf.getOrPut(s.group) { ArrayList() }.add(s)
                } else {
                    top.add(s)
                }
            }
            val out = ArrayList<Session>()
            for (s in top) {
                out.add(s)
                val kids = childrenOf[s.id]
                if (kids != null && kids.isNotEmpty() && s.id in expanded) out.addAll(kids)
            }
            out
        }
    }

    fun childCount(id: String): Int = filtered.count { it.group == id }

    LaunchedEffect(Unit) { store.refreshSessions() }

    Column(Modifier.fillMaxSize()) {
        // header
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                selectMode -> {
                    AppIcon(
                        AppIcons.close,
                        contentDescription = t("cancel"),
                        tint = colors.foreground,
                        onClick = { selectMode = false; selected = emptySet() },
                    )
                    Spacer(Modifier.width(AppSpacing.SM.dp))
                    Text(t("selectedCount", selected.size), style = AppText.title, modifier = Modifier.weight(1f))
                    AppIcon(
                        AppIcons.list,
                        contentDescription = t("selectAll"),
                        tint = colors.foreground,
                        onClick = {
                            selected = if (selected.size == display.size) emptySet() else display.map { it.id }.toSet()
                        },
                    )
                    AppIcon(
                        AppIcons.delete,
                        contentDescription = t("delete"),
                        tint = if (selected.isEmpty()) colors.mutedForeground else colors.destructive,
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                store.deleteSessions(selected.toList())
                                selectMode = false
                                selected = emptySet()
                            }
                        },
                    )
                }
                searching -> {
                    AppIcon(
                        AppIcons.back,
                        contentDescription = t("back"),
                        tint = colors.foreground,
                        onClick = { q = ""; searching = false },
                    )
                    Spacer(Modifier.width(AppSpacing.XS.dp))
                    AppTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        modifier = Modifier.weight(1f), plain = true,
                        placeholder = t("searchHint"),
                        leadingIcon = {
                            Icon(AppIcons.search, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(18.dp))
                        },
                    )
                }
                else -> {
                    Text(t("tabChat"), style = AppText.title, modifier = Modifier.weight(1f).padding(start = AppSpacing.SM.dp))
                    AppIcon(
                        AppIcons.search,
                        contentDescription = t("search"),
                        tint = colors.primary,
                        onClick = { searching = true },
                    )
                    AppIcon(
                        AppIcons.list,
                        contentDescription = t("selectSessions"),
                        tint = colors.primary,
                        onClick = { selectMode = true },
                    )
                    AppIcon(
                        AppIcons.add,
                        contentDescription = t("newSession"),
                        tint = colors.primary,
                        onClick = { createOpen = true },
                    )
                }
            }
        }
        AppDivider()
        if (store.sessionError.isNotEmpty()) {
            Text(
                "${t("connectionError")} · ${store.sessionError}",
                color = colors.destructive, style = AppText.meta,
                modifier = Modifier.fillMaxWidth().background(colors.destructive.copy(alpha = 0.1f)).padding(horizontal = AppSpacing.LG.dp, vertical = AppSpacing.SM.dp),
            )
        }
        SectionLabel(
            t("recent"),
            Modifier.padding(
                start = AppSpacing.LG.dp,
                top = AppSpacing.SM.dp,
                end = AppSpacing.MD.dp,
            ),
            uppercase = false,
        )
        var refreshing by remember { mutableStateOf(false) }
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.refreshSessions(); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
        if (display.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("noSessions"), color = colors.mutedForeground, style = AppText.meta)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(display, key = { it.id }) { s ->
                    val isChild = !searching && s.group.isNotEmpty() && filtered.any { it.id == s.group }
                    val kids = if (isChild || searching) 0 else childCount(s.id)
                    SessionRow(
                        session = s,
                        active = s.id == store.activeSessionId,
                        unread = store.isUnread(s),
                        unreadCount = store.unreadCountFor(s),
                        selectMode = selectMode,
                        selected = s.id in selected,
                        childCount = kids,
                        expanded = s.id in expanded,
                        isChild = isChild,
                        onToggleExpand = if (kids > 0) {
                            { expanded = if (s.id in expanded) expanded - s.id else expanded + s.id }
                        } else null,
                        onTap = {
                            if (selectMode) {
                                selected = if (s.id in selected) selected - s.id else selected + s.id
                            } else store.pickSession(s.id)
                        },
                        onLongPress = { if (!selectMode) actionsFor = s },
                    )
                }
            }
        }
        }
    }

    if (createOpen) {
        TextInputDialog(
            title = t("newSession"),
            onDismiss = { createOpen = false },
            onConfirm = { name ->
                createOpen = false
                if (name.isNotBlank()) {
                    scope.launch {
                        try {
                            store.api.createSession(mapOf("name" to name.trim()))
                            store.refreshSessions()
                        } catch (_: Exception) {
                        }
                    }
                }
            },
        )
    }

    actionsFor?.let { s ->
        ActionSheet(
            title = s.sessionName,
            actions = buildList {
                // Fork WITHOUT opening the session; the new branch lands in the
                // list (mirrors the webui session-row context menu).
                add(t("fork") to { forkFor = s.id })
                if (store.isUnread(s)) add(t("markRead") to { store.markSessionRead(s.id) })
                add(t("deleteSession") to { deleteConfirmFor = s.id })
            },
            icons = mapOf(t("fork") to AppIcons.fork),
            onDismiss = { actionsFor = null },
        )
    }

    forkFor?.let { id ->
        TextInputDialog(
            title = t("fork"),
            initial = "",
            confirmLabel = t("fork"),
            onDismiss = { forkFor = null },
            onConfirm = { branch ->
                forkFor = null
                if (branch.isNotBlank()) {
                    scope.launch {
                        try {
                            store.api.fork(id, branch.trim())
                            store.refreshSessions()
                        } catch (_: Exception) {
                        }
                    }
                }
            },
        )
    }

    deleteConfirmFor?.let { id ->
        val label = store.sessions.firstOrNull { it.id == id }?.sessionName ?: id
        ConfirmDialog(
            title = t("deleteSession"),
            body = t("deleteSessionBody", label),
            confirmLabel = t("delete"),
            destructive = true,
            onDismiss = { deleteConfirmFor = null },
            onConfirm = {
                deleteConfirmFor = null
                scope.launch { store.deleteSession(id) }
            },
        )
    }
}

@Composable
fun SessionRow(
    session: Session,
    active: Boolean,
    unread: Boolean,
    unreadCount: Int,
    selectMode: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    childCount: Int = 0,
    expanded: Boolean = false,
    isChild: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> colors.primary.copy(alpha = 0.14f)
                    active -> colors.primary.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .appClickable(onLongPress = onLongPress, onTap = onTap)
            .padding(horizontal = AppSpacing.MD.dp, vertical = AppSpacing.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Child rows are indented with a left connector so they visually nest
        // under their parent (12 + 10 + 4 = 26px, same as Flutter).
        if (isChild) {
            Spacer(Modifier.width(AppSpacing.MD.dp))
            Box(
                Modifier.width(10.dp).height(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(2.dp).height(34.dp).background(colors.mutedForeground.copy(alpha = 0.35f)))
            }
            Spacer(Modifier.width(AppSpacing.SM.dp - 4.dp))
        }
        if (selectMode) {
            Icon(
                if (selected) AppIcons.success else AppIcons.circle,
                contentDescription = null,
                tint = if (selected) colors.primary else colors.mutedForeground,
                modifier = Modifier.size(24.dp),
            )
        } else {
            // Honeycomb identicon seeded by the session id (flutter ChatAvatar).
            ChatAvatar(seed = session.id, radius = 20.dp)
        }
        Spacer(Modifier.width(AppSpacing.MD.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.sessionName,
                    style = AppText.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) colors.primary else colors.foreground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                // A subsession (group == its parent's name) is marked.
                if (session.group.isNotEmpty() && !isChild) {
                    Spacer(Modifier.width(AppSpacing.XS.dp))
                    BadgePill(t("subsessionBadge"), colors.primary.copy(alpha = 0.14f), colors.primary)
                }
                // Expand chevron + child count for parents.
                if (childCount > 0) {
                    Spacer(Modifier.width(AppSpacing.XS.dp))
                    Row(
                        Modifier
                            .background(colors.mutedForeground.copy(alpha = 0.14f), AppRadius.pill)
                            .appClickable(shape = AppRadius.pill) { onToggleExpand?.invoke() }
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t("subsessionCount", childCount),
                            style = AppText.tiny, color = colors.mutedForeground,
                        )
                        Icon(
                            if (expanded) AppIcons.chevron_up else AppIcons.chevron_down,
                            contentDescription = null,
                            tint = colors.mutedForeground,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                // The trailing timestamp occupies a FIXED-WIDTH, right-aligned
                // slot so every trailing chip ends at the same x on every row.
                val stamp = fmtListTime(session.lastMessageAt.ifEmpty { session.updatedAt })
                Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                    if (stamp.isNotEmpty()) Text(stamp, style = AppText.micro, color = colors.mutedForeground, maxLines = 1)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row {
                Text(
                    session.lastMessagePreview.ifEmpty { session.id },
                    style = AppText.micro, color = colors.mutedForeground,
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (unread && !active && unreadCount > 0) {
                    Spacer(Modifier.width(AppSpacing.XS.dp))
                    UnreadBadge(unreadCount)
                }
            }
        }
    }
}

/** Small tinted capsule used for the subsession badge. */
@Composable
fun BadgePill(label: String, background: androidx.compose.ui.graphics.Color, foreground: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .background(background, AppRadius.pill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = AppText.tiny, color = foreground)
    }
}

/** Red pill unread count (flutter _UnreadBadge). */
@Composable
fun UnreadBadge(count: Int) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .defaultMinSize(minWidth = 18.dp)
            .background(colors.destructive, AppRadius.pill)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = androidx.compose.ui.graphics.Color.White, style = AppText.micro.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

internal fun fmtListTime(iso: String): String = try {
    val d = kotlin.time.Instant.parse(iso)
    val now = kotlin.time.Clock.System.now()
    val mins = (now - d).inWholeMinutes
    val local = kotlinx.datetime.TimeZone.currentSystemDefault()
    val ld = d.toLocalDateTime(local)
    when {
        mins < 1 -> t("timeJustNow")
        mins < 60 -> t("timeMinAgo", mins)
        mins < 60 * 24 -> t("timeHour", mins / 60L)
        mins < 60 * 24 * 7 -> t("timeDay", mins / (60L * 24))
        else -> "${ld.month.number}/${ld.day}"
    }
} catch (_: Exception) {
    ""
}
