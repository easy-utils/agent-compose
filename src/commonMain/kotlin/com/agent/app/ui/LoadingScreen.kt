package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.app.i18n.I18n.t

/**
 * Shared startup / loading surface.
 *
 * One visual contract across all four clients (Flutter, WebUI, Compose,
 * SwiftUI) and, on the web, both the pre-wasm splash in `index.html` and the
 * in-app `Phase.LOADING`: a centered rounded square MARK (each client's own
 * two-letter mark), the app title, and a ring.
 *
 * Compose mark is AC / green #059669. Keep in sync with the other clients.
 */
@Composable
fun LoadingScreen() {
    val colors = LocalAppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color(0xFF059669), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "AC",
                    color = Color.White,
                    style = AppText.mono.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Text(t("appTitle"), style = AppText.body, color = colors.foreground)
            CircularProgressIndicator(
                Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = colors.mutedForeground,
            )
        }
    }
}
