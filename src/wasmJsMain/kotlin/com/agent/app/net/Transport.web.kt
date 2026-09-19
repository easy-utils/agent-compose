package com.agent.app.net

import com.agent.app.platform.jsQueryParam
import easyrpc.Transport
import easyrpc.connect

/**
 * Web (wasmJs) transport: the easy-rpc KMP composition root builds the
 * platform-default Ktor fetch transport and installs the bearer metadata
 * interceptor. The generated client is shared with the JVM targets.
 */
actual fun buildAgentTransport(baseUrl: String, token: String): Transport =
    connect(baseUrl, token = token)

actual fun currentQuery(name: String): String? = jsQueryParam(name)
