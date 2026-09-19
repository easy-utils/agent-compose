package com.agent.app.platform

import com.agent.app.net.buildAgentOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * JVM/Android actual for [httpGetText]: a plain GET over the app's OkHttp
 * client (system CAs plus the bundled ingress CA — the models.dev catalogue is
 * public HTTPS, but reusing the one client keeps TLS config in a single place).
 */
actual suspend fun httpGetText(url: String): String = withContext(Dispatchers.IO) {
    val client = buildAgentOkHttpClient()
    client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.string() ?: error("empty body")
    }
}
