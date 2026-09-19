package com.agent.app.platform

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

import com.agent.app.models.BackendCfg
import com.agent.app.models.ChatDraft
import com.agent.app.models.ChatMessage
import com.agent.app.models.Message
import com.agent.app.models.Session
import com.agent.app.models.UploadedFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Web (wasmJs) actuals: localStorage prefs + browser file/voice/clipboard
// helpers (all JS interop lives in Js.kt). There is no sqlite mirror on web
// (the server is the source of truth), so LocalStore is a no-op.

actual object Prefs {
    /** Where the connection form points by default (PREFILL only — no token is
     *  ever baked in, so the app always starts at the setup/backends flow). */
    private const val DEFAULT_BASE = "https://standalone-agent.temp.10.199.64.20.nip.io"
    private const val SEP = "\u0001"

    actual fun loadBase(): String = jsLocalGet("agent.baseUrl") ?: DEFAULT_BASE
    /** Empty when never connected: the caller must show the setup form. */
    actual fun loadToken(): String = jsLocalGet("agent.token") ?: ""
    actual fun save(base: String, token: String) {
        jsLocalSet("agent.baseUrl", base)
        jsLocalSet("agent.token", token)
    }
    actual fun clearActive() {
        jsLocalRemove("agent.baseUrl")
        jsLocalRemove("agent.token")
    }
    actual fun themeMode(): String {
        val v = jsLocalGet("agent.themeMode")
        if (v == "system" || v == "light" || v == "dark") return v
        val legacy = jsLocalGet("agent.darkMode")
        return when (legacy) {
            "1" -> "dark"
            "0" -> "light"
            else -> "system"
        }
    }
    actual fun saveThemeMode(mode: String) { jsLocalSet("agent.themeMode", mode) }
    actual fun systemLangZh(): Boolean = jsSystemLangZh()
    actual fun agentLocale(): String = jsLocalGet("agent.agentLocale") ?: "follow"
    actual fun saveAgentLocale(v: String) { jsLocalSet("agent.agentLocale", v) }
    actual fun uiLang(): String = jsLocalGet("agent.uiLang") ?: "system"
    actual fun saveUiLang(v: String) { jsLocalSet("agent.uiLang", v) }
    private var readScope = ""

    actual fun setReadScope(scope: String) { readScope = scope }

    private fun readKey(): String = "agent.readSeqs.$readScope"

    actual fun readSeqs(): Map<String, Int> {
        if (readScope.isEmpty()) return emptyMap()
        val raw = jsLocalGet(readKey()) ?: return emptyMap()
        return try {
            val el = Json.parseToJsonElement(raw)
            (el as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content.toInt() } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    actual fun saveReadSeqs(seqs: Map<String, Int>) {
        if (readScope.isEmpty()) return
        jsLocalSet(readKey(), JsonObject(seqs.mapValues { JsonPrimitive(it.value) }).toString())
    }
    actual fun backends(): List<BackendCfg> {
        val raw = jsLocalGet("agent.backends") ?: return emptyList()
        return raw.split("\n").mapNotNull {
            val p = it.split(SEP)
            if (p.size == 3) BackendCfg(p[0], p[1], p[2]) else null
        }
    }
    actual fun upsertBackend(b: BackendCfg) {
        val list = backends().filter { !(it.baseUrl == b.baseUrl && it.token == b.token) } + b
        jsLocalSet("agent.backends", list.joinToString("\n") { "${it.name}$SEP${it.baseUrl}$SEP${it.token}" })
    }
    actual fun removeBackend(b: BackendCfg) {
        jsLocalSet(
            "agent.backends",
            backends().filter { !(it.baseUrl == b.baseUrl && it.token == b.token) }
                .joinToString("\n") { "${it.name}$SEP${it.baseUrl}$SEP${it.token}" },
        )
    }
}

actual class LocalStore private constructor(private val impl: WebLocalStore) {
    actual suspend fun loadSessions(): List<Session> = impl.loadSessions()
    actual suspend fun upsertSessions(sessions: List<Session>) = impl.upsertSessions(sessions)
    actual suspend fun removeSession(id: String) = impl.removeSession(id)
    actual suspend fun loadMessages(sessionId: String): List<ChatMessage> = impl.loadMessages(sessionId)
    actual suspend fun serverTipId(sessionId: String): String = impl.serverTipId(sessionId)
    actual suspend fun oldestCachedId(sessionId: String): String = impl.oldestCachedId(sessionId)
    actual suspend fun applyServerMessages(sessionId: String, msgs: List<Message>, replace: Boolean, tipId: String) =
        impl.applyServerMessages(sessionId, msgs, replace, tipId)
    actual suspend fun persistMessages(sessionId: String, msgs: List<ChatMessage>, tipId: String) =
        impl.persistMessages(sessionId, msgs, tipId)
    actual suspend fun saveDraft(sessionId: String, text: String, attachments: List<UploadedFile>) =
        impl.saveDraft(sessionId, text, attachments)
    actual suspend fun loadDrafts(): Map<String, ChatDraft> = impl.loadDrafts()
    actual suspend fun setReadSeq(sessionId: String, seq: Int) = impl.setReadSeq(sessionId, seq)
    actual suspend fun loadReadSeqs(): Map<String, Int> = impl.loadReadSeqs()

    companion object {
        internal suspend fun open(scope: String): LocalStore? {
            val impl = WebLocalStore.open(scope) ?: return null
            return LocalStore(impl)
        }
    }
}

actual suspend fun openLocalStore(scope: String): LocalStore? = LocalStore.open(scope)

actual class VoiceRecorder actual constructor() {
    actual suspend fun hasPermission(): Boolean = true

    /** The browser prompts during getUserMedia, so there is nothing to ask yet. */
    actual suspend fun requestPermission(): Boolean = true

    actual suspend fun start(): Boolean =
        kotlin.coroutines.suspendCoroutine { cont ->
            jsVoiceStart { ok -> cont.resume(ok) }
        }

    actual suspend fun stop(): PickedFile? =
        kotlin.coroutines.suspendCoroutine { cont ->
            jsVoiceStop { b64 ->
                if (b64 == null) cont.resume(null)
                else cont.resume(
                    PickedFile(
                        "voice-${com.agent.app.util.nowMillis()}.webm",
                        "audio/webm",
                        decodeB64(b64),
                    ),
                )
            }
        }

    actual suspend fun cancel() { jsVoiceCancel() }
    actual fun dispose() { jsVoiceCancel() }
}

internal fun decodeB64(s: String): ByteArray {
    val bin = jsAtob(s)
    return ByteArray(bin.length) { bin[it].code.toByte() }
}

actual suspend fun takePhoto(): List<PickedFile> = emptyList()

actual suspend fun pickFiles(mimeFilter: String?): List<PickedFile> =
    kotlin.coroutines.suspendCoroutine { cont ->
        jsPickFiles(mimeFilter) { jsonStr ->
            val out = ArrayList<PickedFile>()
            try {
                val arr = Json.parseToJsonElement(jsonStr)
                (arr as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val name = (o["name"] as? JsonPrimitive)?.content ?: "file"
                    val mime = (o["mime"] as? JsonPrimitive)?.content ?: "application/octet-stream"
                    val b64 = (o["b64"] as? JsonPrimitive)?.content ?: ""
                    val bytes = decodeB64(b64)
                    PickedFileRegistry.put(name, bytes)
                    out.add(PickedFile(name, mime, bytes))
                }
            } catch (_: Exception) {
            }
            cont.resume(out)
        }
    }

/** In-memory registry of picked files this session (web has no file paths). */
object PickedFileRegistry {
    private val byKey = HashMap<String, ByteArray>()
    fun put(key: String, bytes: ByteArray) { byKey[key] = bytes }
    fun get(key: String): ByteArray? = byKey[key]
}

actual suspend fun readLocalFile(localPath: String, name: String): ByteArray =
    PickedFileRegistry.get(localPath.ifEmpty { name }) ?: error("unreadable: $localPath")

actual suspend fun mediaUrl(bytes: ByteArray, mime: String?): String =
    objectUrlFromBytes(bytes, mime)

actual suspend fun downloadFile(name: String, mime: String?, bytes: ByteArray): String {
    val url = objectUrlFromBytes(bytes, mime)
    jsTriggerDownload(url, name)
    return name
}

/** Base64 of raw bytes (no String round-trip through the JVM charset). */
fun b64Of(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (b in bytes) sb.append((b.toInt() and 0xff).toChar())
    return jsBtoa(sb.toString())
}

private fun objectUrlFromBytes(bytes: ByteArray, mime: String?): String =
    jsObjectUrl(b64Of(bytes), mime)

actual suspend fun confirm(title: String, body: String): Boolean =
    jsConfirm(jsJoinConfirm(title, body))

actual suspend fun promptText(title: String, initial: String): String? =
    jsPrompt(title, initial)?.takeIf { it.isNotBlank() }

actual fun copyToClipboard(text: String) { jsWriteClipboard(text) }


/// Web: isSystemInDarkTheme() is constant on wasm — bridge the OS
/// prefers-color-scheme media query through JS so "follow system" is live.
actual fun installSystemDarkListener(cb: (Boolean) -> Unit) {
    jsInstallSystemDarkListener(cb)
}
