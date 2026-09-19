package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agent.app.AgentApi
import com.agent.app.i18n.I18n.t
import com.agent.app.models.AttachmentRef
import com.agent.app.platform.mediaUrl

// In-message file/media part (flutter message_bubble.dart): an image renders
// inline with tap-to-fullscreen; audio/video show inline players; anything
// else is a file chip that opens the save card.

@Composable
fun MessageFilePart(
    api: AgentApi,
    code: String,
    name: String?,
    mime: String?,
    size: Int?,
    onOpen: (AttachmentRef) -> Unit,
) {
    val colors = LocalAppColors.current
    val isImage = mime?.startsWith("image/") == true
    val isAudio = mime?.startsWith("audio/") == true
    val isVideo = mime?.startsWith("video/") == true
    var url by remember(code) { mutableStateOf<String?>(null) }

    LaunchedEffect(code) {
        try {
            url = mediaUrl(api.fetchFileBytes(code), mime)
        } catch (_: Exception) {
        }
    }

    val open: () -> Unit = { onOpen(AttachmentRef(code, name ?: "", mime)) }
    when {
        isImage -> {
            val u = url
            if (u != null) {
                MediaImage(
                    u,
                    Modifier.fillMaxWidth().height(180.dp)
                        .background(colors.muted, AppRadius.sm)
                        .appClickable(shape = AppRadius.sm, onTap = open),
                )
            } else {
                InlinePlaceholder()
            }
        }
        isVideo -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.file, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Text(name ?: code, style = AppText.meta, color = colors.primary, modifier = Modifier.appClickable(onTap = open))
        }
        isAudio -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.file, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Text(name ?: code, style = AppText.meta, color = colors.primary, modifier = Modifier.appClickable(onTap = open))
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.file, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Text(
                buildString {
                    append(name ?: code)
                    size?.let { append("  ·  ${formatBytes(it)}") }
                },
                style = AppText.meta, color = colors.primary,
                modifier = Modifier.appClickable(onTap = open),
            )
        }
    }
}

@Composable
private fun InlinePlaceholder() {
    Box(
        Modifier.width(180.dp).height(120.dp)
            .background(LocalAppColors.current.muted, AppRadius.sm),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.image_off,
            contentDescription = null,
            tint = LocalAppColors.current.mutedForeground,
            modifier = Modifier.size(24.dp),
        )
    }
}

internal fun formatBytes(n: Int): String = when {
    n >= 1 shl 20 -> oneDecimal(n / 1048576.0) + " MB"
    n >= 1 shl 10 -> oneDecimal(n / 1024.0) + " KB"
    else -> "$n B"
}

private fun oneDecimal(v: Double): String {
    val r = (v * 10).toLong() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
