package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Media expect declarations + a shared fallback card. Actual renderers live in
// Media.android.kt / Media.desktop.kt / Media.web.kt (Compose Multiplatform
// still ships no cross-platform image/video/audio widgets of its own here).

@Composable
expect fun MediaImage(url: String, modifier: Modifier)

@Composable
expect fun MediaVideoPlayer(url: String, modifier: Modifier)

@Composable
expect fun MediaAudioPlayer(url: String, modifier: Modifier)

/** Shared stand-in for kinds the platform layer cannot play inline yet. */
@Composable
fun MediaFallback(modifier: Modifier, kind: String = "media") {
    val colors = LocalAppColors.current
    Box(
        modifier.background(colors.muted, AppRadius.md).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (kind == "audio") AppIcons.music else AppIcons.film,
                contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(kind, style = AppText.micro, color = colors.mutedForeground)
        }
    }
}
