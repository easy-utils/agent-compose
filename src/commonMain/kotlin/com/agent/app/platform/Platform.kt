package com.agent.app.platform

import com.agent.app.models.UploadedFile

/** A picked media/file source before upload. */
data class PickedFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    /** Local preview handle (file path / uri) where available. */
    val localPath: String = "",
)

expect class VoiceRecorder() {
    /** Whether the OS currently grants microphone access (no prompt). */
    suspend fun hasPermission(): Boolean

    /**
     * Ensures microphone access, prompting the user on platforms that have a
     * runtime permission model (Android). Returns the resulting grant state;
     * always true on desktop/web, where there is nothing to prompt for.
     */
    suspend fun requestPermission(): Boolean

    /** Starts capture. False when permission is missing or the device failed. */
    suspend fun start(): Boolean
    suspend fun stop(): PickedFile?
    suspend fun cancel()
    fun dispose()
}

/** The effective agent locale: the 'follow' pref resolves to the UI language
 *  (zh for a Chinese UI, en otherwise), matching flutter's
 *  `Prefs.effectiveAgentLocale`. Passed to ListPresets/ListTools so the server
 *  resolves the localized prompt instead of falling back to English. */
fun effectiveAgentLocale(): String {
    val pref = Prefs.agentLocale()
    return if (pref == "follow") {
        if (com.agent.app.i18n.I18n.lang == com.agent.app.i18n.Lang.ZH) "zh" else "en"
    } else {
        pref
    }
}

/** Opens a native multi-file picker; empty when cancelled. */
expect suspend fun pickFiles(mimeFilter: String?): List<PickedFile>

/** Takes a photo with the platform camera; empty when unsupported/cancelled
 *  (desktop + web have no camera contract, matching flutter's image_picker). */
expect suspend fun takePhoto(): List<PickedFile>

/** Persistence: connection prefs + backends + read watermarks (KV store). */
expect object Prefs {
    fun loadBase(): String
    fun loadToken(): String
    fun save(base: String, token: String)
    fun clearActive()

    /** Tri-state theme pref: "system" (the DEFAULT) | "light" | "dark".
     *  Older installs stored the boolean darkMode — it maps onto the explicit
     *  modes, never back to "system" (the user chose). */
    fun themeMode(): String
    fun saveThemeMode(mode: String)

    /** Whether the SYSTEM UI language is Chinese (resolves the "system"
     *  language preference; en otherwise). */
    fun systemLangZh(): Boolean

    fun agentLocale(): String
    fun saveAgentLocale(v: String)

    /** UI language pref: "system" (the DEFAULT) | "zh" | "en". */
    fun uiLang(): String
    fun saveUiLang(v: String)

    /** The connection scope (gateway+token) the read watermarks are stored
     *  under. Set by the app before the store is built; isolates users. */
    fun setReadScope(scope: String)
    fun readSeqs(): Map<String, Int>
    fun saveReadSeqs(seqs: Map<String, Int>)
    fun backends(): List<com.agent.app.models.BackendCfg>
    fun upsertBackend(b: com.agent.app.models.BackendCfg)
    /** A saved user is identified by the FULL connection (baseUrl + token):
     *  one host may serve several tenants. */
    fun removeBackend(b: com.agent.app.models.BackendCfg)
}

/** Live system dark-mode tracking for the "follow system" theme. Android is
 *  reactive through isSystemInDarkTheme(); the web bridges the OS
 *  prefers-color-scheme media query through JS (its isSystemInDarkTheme() is
 *  constant); desktop has no bridge and stays a no-op. */
expect fun installSystemDarkListener(cb: (Boolean) -> Unit)

/** The sqlite mirror (schema identical to flutter's Drift DB). */
expect class LocalStore {
    suspend fun loadSessions(): List<com.agent.app.models.Session>
    suspend fun upsertSessions(sessions: List<com.agent.app.models.Session>)
    suspend fun removeSession(id: String)
    suspend fun loadMessages(sessionId: String): List<com.agent.app.models.ChatMessage>
    suspend fun serverTipId(sessionId: String): String
    suspend fun oldestCachedId(sessionId: String): String
    suspend fun applyServerMessages(sessionId: String, msgs: List<com.agent.app.models.Message>, replace: Boolean, tipId: String)
    suspend fun persistMessages(sessionId: String, msgs: List<com.agent.app.models.ChatMessage>, tipId: String)
    suspend fun saveDraft(sessionId: String, text: String, attachments: List<UploadedFile>)
    suspend fun loadDrafts(): Map<String, com.agent.app.models.ChatDraft>
    suspend fun setReadSeq(sessionId: String, seq: Int)
    suspend fun loadReadSeqs(): Map<String, Int>
}

expect suspend fun openLocalStore(scope: String): LocalStore?

/** Re-reads bytes for a previously picked local file (draft retry). */
expect suspend fun readLocalFile(localPath: String, name: String): ByteArray

/** data-url / object-url for a downloaded attachment (platform rendering). */
expect suspend fun mediaUrl(bytes: ByteArray, mime: String?): String

/** Saves bytes as a downloaded file; returns the target path/name. */
expect suspend fun downloadFile(name: String, mime: String?, bytes: ByteArray): String

/** Ask the user a yes/no question natively (used sparingly). */
expect suspend fun confirm(title: String, body: String): Boolean

/** Prompt the user for a line of text natively. */
expect suspend fun promptText(title: String, initial: String): String?

/** Copy text to the platform clipboard. */
expect fun copyToClipboard(text: String)

/**
 * Plain HTTPS GET returning the response body, used for the models.dev provider
 * template catalogue (a public JSON document fetched from every platform).
 * Throws on a non-2xx status or transport failure.
 */
expect suspend fun httpGetText(url: String): String
