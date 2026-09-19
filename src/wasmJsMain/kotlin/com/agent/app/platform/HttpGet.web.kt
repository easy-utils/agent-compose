package com.agent.app.platform

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Web (wasmJs) actual for [httpGetText]: a fetch GET bridged through the same
 * @JsFun style as the rest of the browser interop (Kotlin/Wasm has no `dynamic`).
 */
actual suspend fun httpGetText(url: String): String = suspendCoroutine { cont ->
    jsHttpGet(
        url,
        { body -> cont.resume(body) },
        { code, msg -> cont.resumeWithException(IllegalStateException("$code: $msg")) },
    )
}
