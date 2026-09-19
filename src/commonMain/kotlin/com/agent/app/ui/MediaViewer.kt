package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.app.AgentApi
import com.agent.app.i18n.I18n.t
import com.agent.app.platform.downloadFile
import com.agent.app.platform.mediaUrl
import kotlinx.coroutines.launch
import kotlin.time.Clock

// MediaViewer — fullscreen image view + generic save/download for other kinds
// (port of flutter media_attachment.dart openImageFullscreen / save card).

/** Fetch a media handle (data/object URL) for an attachment code. */
suspend fun resolveMediaUrl(api: AgentApi, code: String, mime: String?): String =
    mediaUrl(api.fetchFileBytes(code), mime)

@Composable
fun MediaViewerDialog(api: AgentApi, code: String, name: String, mime: String?, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var url by remember(code) { mutableStateOf<String?>(null) }
    var failed by remember(code) { mutableStateOf(false) }

    LaunchedEffect(code) {
        try {
            url = resolveMediaUrl(api, code, mime)
        } catch (_: Exception) {
            failed = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).appClickable(hoverWash = false) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.close,
                contentDescription = t("close"),
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).appClickable { onDismiss() }.padding(16.dp).size(22.dp),
            )
            when {
                failed -> Text(t("loadError", "fetch failed"), color = Color.White, style = AppText.small)
                url == null -> CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
                (mime ?: "").startsWith("image/") -> MediaImage(url!!, Modifier.fillMaxSize().padding(24.dp))
                (mime ?: "").startsWith("video/") -> MediaVideoPlayer(url!!, Modifier.fillMaxWidth().height(260.dp))
                (mime ?: "").startsWith("audio/") -> MediaAudioPlayer(url!!, Modifier.fillMaxWidth().height(72.dp))
                else -> SaveCard(api, code, name, mime, url!!)
            }
        }
    }
}

@Composable
private fun SaveCard(api: AgentApi, code: String, name: String, mime: String?, url: String) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var savedTo by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.Column(
        Modifier.appCard().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.file, contentDescription = null, tint = colors.primary, modifier = Modifier.size(30.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        Box(Modifier.width(240.dp)) {
            Text(
                name.ifEmpty { code }, style = AppText.small, maxLines = 2,
                color = colors.foreground, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        Box(
            Modifier.appClickable(shape = AppRadius.md) {
                scope.launch {
                    try {
                        val bytes = api.fetchFileBytes(code)
                        savedTo = downloadFile(name.ifEmpty { code }, mime, bytes)
                    } catch (_: Exception) {
                    }
                }
            }.background(colors.primary, AppRadius.md).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.download, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Text(t("save"), color = colors.onPrimary, style = AppText.meta)
            }
        }
        savedTo?.let {
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            Text(t("savedToDownloads", it), style = AppText.micro, color = colors.mutedForeground)
        }
    }
}
