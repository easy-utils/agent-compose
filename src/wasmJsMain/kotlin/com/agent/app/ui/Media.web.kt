package com.agent.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.agent.app.platform.b64Of
import com.agent.app.platform.jsAtob
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

// Web (wasm) media rendering: object URL → fetch bytes via a JS bridge →
// decode with the bundled Skia. Audio/video fall back to a plain card.

@JsFun("(url, cb) => { fetch(url).then(r => r.arrayBuffer()).then(b => { const u8 = new Uint8Array(b); let s=''; const CH=0x8000; for (let i=0;i<u8.length;i+=CH) s += String.fromCharCode.apply(null, u8.subarray(i,i+CH)); cb(btoa(s)); }).catch(() => cb('')); }")
private external fun jsFetchB64(url: String, cb: (String) -> Unit)

private suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.Default) {
    kotlin.coroutines.suspendCoroutine { cont: kotlin.coroutines.Continuation<ByteArray> ->
        jsFetchB64(url) { b64 ->
            cont.resume(runCatching { decodeB64Kmp(b64) }.getOrDefault(ByteArray(0)))
        }
    }
}

private fun decodeB64Kmp(b64: String): ByteArray {
    if (b64.isEmpty()) return ByteArray(0)
    val bin = jsAtob(b64)
    val out = ByteArray(bin.length)
    for (i in bin.indices) out[i] = (bin[i].code and 0xff).toByte()
    return out
}

@Composable
actual fun MediaImage(url: String, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        bitmap = try {
            val bytes = fetchBytes(url)
            if (bytes.isEmpty()) null else Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Throwable) {
            null
        } ?: run { failed = true; null }
    }
    val bmp = bitmap
    when {
        bmp != null -> Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
        !failed -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(24.dp)) }
        else -> Box(modifier.background(Color(0x22000000)))
    }
}

@Composable
actual fun MediaVideoPlayer(url: String, modifier: Modifier) = MediaFallback(modifier, "video")

@Composable
actual fun MediaAudioPlayer(url: String, modifier: Modifier) = MediaFallback(modifier, "audio")
