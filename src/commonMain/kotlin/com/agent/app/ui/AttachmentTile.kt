package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.agent.app.AgentApi
import com.agent.app.i18n.I18n.t
import com.agent.app.models.UploadedFile
import com.agent.app.platform.mediaUrl

// AttachmentTile — the composer's attachment chip (flutter `_attachmentChip`):
// a square tile with an image thumbnail (or type icon) and a corner badge for
// upload-in-progress / retry-on-error / remove.

/**
 * Composer attachments as a THREE-per-line wrap (flutter's `Wrap` +
 * `LayoutBuilder`): every tile is a square whose side is
 * `(width - 2*gap)/3` clamped to 40..72, so a row always fits exactly three.
 */
@Composable
fun AttachmentWrap(
    attachments: List<UploadedFile>,
    api: AgentApi?,
    onOpen: (UploadedFile) -> Unit,
    onRetry: (UploadedFile) -> Unit,
    onRemove: (UploadedFile) -> Unit,
) {
    Layout(content = {
        for (a in attachments) {
            AttachmentTile(
                a = a,
                api = api,
                onOpen = { onOpen(a) },
                onRetry = { onRetry(a) },
                onRemove = { onRemove(a) },
            )
        }
    }) { measurables, constraints ->
        val gap = AppSpacing.XS.dp.roundToPx()
        val cols = 3
        val width = constraints.maxWidth.coerceAtLeast(0)
        val dim = ((width - gap * (cols - 1)) / cols).coerceIn(40, 72)
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = dim, maxWidth = dim)) }
        var y = 0
        var x = 0
        layout(width, ((placeables.size + cols - 1) / cols) * (dim + gap)) {
            placeables.forEachIndexed { i, p ->
                if (i > 0 && i % cols == 0) {
                    x = 0
                    y += dim + gap
                }
                p.place(x, y)
                x += dim + gap
            }
        }
    }
    Spacer(Modifier.size(0.dp))
}

@Composable
fun AttachmentTile(
    a: UploadedFile,
    onOpen: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onRemove: () -> Unit,
    api: AgentApi? = null,
    dim: Int = 56,
) {
    val colors = LocalAppColors.current
    Box(
        Modifier.size(dim.dp)
            // flutter: muted@50 fill, radius 6.
            .background(colors.muted.copy(alpha = 0.5f), AppRadius.sm)
            .appClickable(enabled = onOpen != null, shape = AppRadius.sm) { onOpen?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            a.isUploading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.primary)
            else -> AttachmentThumb(a, api, dim)
        }
        CornerBadge(
            modifier = Modifier.align(Alignment.TopEnd),
            tint = when {
                a.isUploading -> Color.Black.copy(alpha = 0.55f)
                a.hasError -> colors.destructive
                else -> Color.Black.copy(alpha = 0.55f)
            },
            onClick = when {
                a.isUploading -> null
                a.hasError -> onRetry
                else -> onRemove
            },
            content = {
                when {
                    a.isUploading -> CircularProgressIndicator(Modifier.size(8.dp), strokeWidth = 1.5.dp, color = Color.White)
                    a.hasError -> Icon(AppIcons.refresh, contentDescription = t("retry"), tint = Color.White, modifier = Modifier.size(12.dp))
                    else -> Icon(AppIcons.close, contentDescription = t("delete"), tint = Color.White, modifier = Modifier.size(12.dp))
                }
            },
        )
    }
}

/** Image thumbnail when the bytes are locally available; a type icon otherwise. */
@Composable
private fun AttachmentThumb(a: UploadedFile, api: AgentApi?, dim: Int) {
    val isImage = a.mime?.startsWith("image/") == true
    var url by remember(a.code, a.localPath) { mutableStateOf<String?>(null) }
    LaunchedEffect(a.code, a.localPath) {
        if (!isImage) return@LaunchedEffect
        try {
            if (a.localPath.isNotEmpty()) {
                url = mediaUrl(com.agent.app.platform.readLocalFile(a.localPath, a.name ?: a.code), a.mime)
            } else if (api != null && a.code.isNotEmpty() && !a.code.startsWith("tmp-")) {
                url = mediaUrl(api.fetchFileBytes(a.code), a.mime)
            }
        } catch (_: Exception) {
        }
    }
    val u = url
    if (isImage && u != null) {
        MediaImage(u, Modifier.size(dim.dp))
    } else {
        Icon(
            when {
                isImage -> AppIcons.image
                a.mime?.startsWith("audio/") == true -> AppIcons.music
                a.mime?.startsWith("video/") == true -> AppIcons.film
                else -> AppIcons.file
            },
            contentDescription = null,
            tint = LocalAppColors.current.mutedForeground,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CornerBadge(
    tint: Color,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(2.dp)
            .size(16.dp)
            .background(tint, AppRadius.pill)
            .appClickable(enabled = onClick != null, shape = AppRadius.pill) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The composer's round slot button — used by BOTH edges (left mic/keyboard,
 *  right send/stop/attach). It is a WHITE circle with a hairline outline in
 *  [tint] and a glyph in the same color (blue = send, red = stop); never a
 *  solid colored fill, matching flutter/webui/swiftui. 42dp so the left and
 *  right slots are the same size as the 42dp field. */
@Composable
fun ComposerCircleButton(
    contentDescription: String,
    tint: Color,
    onClick: (() -> Unit)?,
    icon: ImageVector? = null,
) {
    Box(
        Modifier
            .size(42.dp)
            .appClickable(
                enabled = onClick != null,
                shape = AppRadius.pill,
                hoverWash = false,
            ) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) { ComposerCircleBody(contentDescription, tint, icon) }
}

/** The composer's LEFT slot: the same 42dp outlined circle as
 *  [ComposerCircleButton], rendered in the border/muted tone (mic/keyboard). */
@Composable
fun ComposerLeftCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .size(42.dp)
            .appClickable(
                enabled = enabled,
                shape = AppRadius.pill,
                hoverWash = false,
            ) { onClick() }
            .border(1.2.dp, colors.border, AppRadius.pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.mutedForeground, modifier = Modifier.size(22.dp))
    }
}

/** Just the visual circle (white fill + colored ring + colored glyph). */
@Composable
fun ComposerCircleBody(
    contentDescription: String,
    tint: Color,
    icon: ImageVector? = null,
) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .size(42.dp)
            .background(colors.card, AppRadius.pill)
            .border(1.2.dp, tint, AppRadius.pill),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        } else {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
        }
    }
}

/** One row of the attach bottom sheet (flutter `ListTile`): icon + label. */
@Composable
fun AttachSheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().appClickable(shape = AppRadius.sm, onTap = onClick)
            .padding(horizontal = AppSpacing.LG.dp, vertical = AppSpacing.MD.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(AppSpacing.LG.dp))
        Text2(label)
    }
}

/** Local alias so this file doesn't need the material3 Text import twice. */
@Composable
private fun Text2(label: String) {
    androidx.compose.material3.Text(label, style = AppText.body, color = LocalAppColors.current.foreground)
}

/** A 1px top border (flutter `Border(top: BorderSide(...))`). */
fun Modifier.drawTopBorder(color: Color): Modifier = this.drawBehind {
    val stroke = 1.dp.toPx()
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset.Zero,
        size = androidx.compose.ui.geometry.Size(size.width, stroke),
    )
}
