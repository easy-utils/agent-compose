package com.agent.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agent.app.i18n.I18n.t
import com.agent.app.models.MailboxEntry
import com.agent.app.store.AppStore

@Composable
fun MailboxScreen(store: AppStore) {
    val colors = LocalAppColors.current
    val sid = store.activeSessionId ?: return
    var entries by remember { mutableStateOf<List<MailboxEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(sid) {
        try {
            entries = store.api.mailbox(sid)
            error = ""
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
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
            LazyColumn(Modifier.fillMaxSize().padding(AppSpacing.LG.dp)) {
                items(entries, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.XS.dp)) {
                        Row {
                            Text(e.msgType, style = AppText.meta)
                            Spacer(Modifier.weight(1f))
                            Text(
                                e.status,
                                style = AppText.micro,
                                color = if (e.status == "consumed") colors.success else colors.warning,
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
            }
        }
    }
}
