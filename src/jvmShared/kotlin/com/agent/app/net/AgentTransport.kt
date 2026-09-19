package com.agent.app.net

import easyrpc.InterceptorTransport
import easyrpc.MetadataInterceptor
import easyrpc.OkHttpTransport
import easyrpc.Transport
import okhttp3.OkHttpClient

/**
 * Agent transport wiring for the JVM targets (desktop + android).
 *
 * The typed messages/clients come from `agent-sdk-kotlin`; this file owns only
 * the transport: the OkHttp client (with the app's CA trust, see
 * [buildAgentOkHttpClient]) plus the bearer-token metadata interceptor,
 * assembled through the easy-rpc composition root.
 */
actual fun buildAgentTransport(baseUrl: String, token: String): Transport {
    val inner = OkHttpTransport(
        client = buildAgentOkHttpClient(),
        base = baseUrl.trimEnd('/'),
    )
    val ics = if (token.isEmpty()) {
        emptyList()
    } else {
        listOf<easyrpc.Interceptor>(
            MetadataInterceptor(mapOf("Authorization" to listOf("Bearer $token"))),
        )
    }
    return if (ics.isEmpty()) inner else InterceptorTransport(ics, inner)
}

/** Query-string access is web-only; the JVM has no document location. */
actual fun currentQuery(name: String): String? = null
