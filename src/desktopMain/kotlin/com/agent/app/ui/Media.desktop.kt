package com.agent.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

// Desktop media rendering: decode image bytes with the bundled Skia and draw
// via the common Image composable; audio/video fall back to a plain card.

@Composable
actual fun MediaImage(url: String, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        bitmap = try {
            withContext(Dispatchers.IO) {
                val path = url.removePrefix("file://")
                Image.makeFromEncoded(java.io.File(path).readBytes()).toComposeImageBitmap()
            }
        } catch (_: Exception) {
            failed = true
            null
        }
    }
    val bmp = bitmap
    when {
        bmp != null -> Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
        !failed -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Box(modifier.background(Color(0x22000000)))
    }
}

@Composable
actual fun MediaVideoPlayer(url: String, modifier: Modifier) = MediaFallback(modifier, "video")

@Composable
actual fun MediaAudioPlayer(url: String, modifier: Modifier) = MediaFallback(modifier, "audio")
