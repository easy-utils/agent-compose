package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.app.i18n.I18n.t
import com.agent.app.models.MailboxEntry
import com.agent.app.store.AppStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private const val PAGE_SIZE = 30

/** (msgType, source) → icon + localized label + accent. `source` is an open
 *  string: user | session:{name} | system:{name} | other. */
private data class MailboxMeta(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val origin: String = "",
)

@Composable
private fun metaOf(msgType: String, source: String): MailboxMeta {
    val colors = LocalAppColors.current
    return when {
        msgType == "interrupt" -> MailboxMeta(AppIcons.stop, t("mailboxInterrupt"), colors.destructive)
        msgType != "trigger" -> MailboxMeta(AppIcons.bolt, t("mailboxEvent"), colors.warning)
        source == "user" -> MailboxMeta(AppIcons.user, t("mailboxPrompt"), colors.primary)
        source.startsWith("session:") -> MailboxMeta(
            AppIcons.chat, t("mailboxFromSession"), Color(0xFF0284C7),
            origin = source.removePrefix("session:"),
        )
        source.startsWith("system:") -> MailboxMeta(
            AppIcons.bolt, t("mailboxFromSystem"), Color(0xFF7C3AED),
            origin = source.removePrefix("system:"),
        )
        else -> MailboxMeta(AppIcons.bolt, t("mailboxPrompt"), colors.primary, origin = source)
    }
}

@Composable
fun MailboxScreen(store: AppStore) {
    val colors = LocalAppColors.current
    val sid = store.activeSessionId ?: return
    var entries by remember { mutableStateOf<List<MailboxEntry>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(sid) {
        try {
            val page = store.api.mailbox(sid, limit = PAGE_SIZE)
            entries = page.entries
            hasMore = page.hasMore
            error = ""
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }

    // Infinite scroll: load the next older page near the bottom.
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= entries.size - 3
        }.distinctUntilChanged().filter { it }.collect {
            if (!loadingMore && hasMore && entries.isNotEmpty()) {
                loadingMore = true
                try {
                    val page = store.api.mailbox(sid, before = entries.last().id, limit = PAGE_SIZE)
                    entries = entries + page.entries
                    hasMore = page.hasMore
                } catch (_: Exception) {
                }
                loadingMore = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.back, contentDescription = t("back"), tint = colors.foreground, onClick = { store.popPage() })
            Text(t("mailbox"), style = AppText.title)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(8.dp))
            }
        } else if (error.isNotEmpty()) {
            Text(error, color = colors.destructive, style = AppText.meta, modifier = Modifier.padding(16.dp))
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("noMessages"), color = colors.mutedForeground, style = AppText.meta)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(AppSpacing.LG.dp)) {
                items(entries, key = { it.id }) { e ->
                    val consumed = e.consumedAt != null
                    val meta = metaOf(e.msgType, e.source)
                    Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.XS.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.background(meta.color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(meta.icon, contentDescription = null, tint = meta.color, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(meta.label, style = AppText.micro.copy(fontWeight = FontWeight.SemiBold), color = meta.color)
                                if (meta.origin.isNotEmpty()) {
                                    Text(" · ${meta.origin}", style = AppText.micro, color = meta.color.copy(alpha = 0.8f))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (consumed) t("consumed") else t("pending"),
                                style = AppText.micro,
                                color = if (consumed) colors.success else colors.mutedForeground,
                            )
                        }
                        if (e.payload.isNotEmpty()) {
                            Text(
                                e.payload.take(400),
                                style = AppText.micro.copy(fontFamily = LocalAppMonoFamily.current),
                                maxLines = 8,
                            )
                        }
                    }
                }
                if (hasMore || loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(AppSpacing.MD.dp), contentAlignment = Alignment.Center) {
                            if (loadingMore) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp))
                            } else {
                                Text(t("loadEarlier"), style = AppText.meta, color = colors.primary,
                                    modifier = Modifier.appClickable { })
                            }
                        }
                    }
                }
            }
        }
    }
}
