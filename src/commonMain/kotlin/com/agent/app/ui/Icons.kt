package com.agent.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Toolbar icon button: hand cursor + hover wash (webui `rounded p-1.5
// hover:bg-muted`), so desktop/web click targets feel alive. The header rows
// inside every screen use this instead of a bare `clickable`.

/** Rounded-square icon button used across config/provider lists. */
val IconButtonShape = RoundedCornerShape(8.dp)

@Composable
fun AppIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    tint: Color = LocalAppColors.current.mutedForeground,
    size: Dp = 18.dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val m = if (onClick != null) {
        modifier
            .appClickable(enabled = enabled, shape = IconButtonShape, onTap = onClick)
            .padding(6.dp)
            .size(size)
    } else {
        modifier.size(size)
    }
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (enabled) tint else colors.mutedForeground.copy(alpha = 0.5f),
        modifier = m,
    )
}

/** A tinted round-rect button around an icon (nav rail / toolbar action). */
@Composable
fun IconChip(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    background: Color,
    size: Dp = 20.dp,
    padding: Dp = 8.dp,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .appClickable(shape = IconButtonShape, onTap = onClick)
            .padding(padding)
            .size(size),
    )
}
