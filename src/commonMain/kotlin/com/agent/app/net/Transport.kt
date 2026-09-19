package com.agent.app.net

import easyrpc.Transport

/**
 * Platform transport for the typed agent client. JVM targets build an OkHttp
 * transport with the app's CA trust + bearer interceptor; web uses the
 * easy-rpc KMP default (Ktor fetch). Both carry the same generated client.
 */
expect fun buildAgentTransport(baseUrl: String, token: String): Transport

/** Read a URL query parameter; null where the platform has no document location. */
expect fun currentQuery(name: String): String?
