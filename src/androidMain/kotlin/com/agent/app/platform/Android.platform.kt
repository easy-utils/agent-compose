package com.agent.app.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import com.agent.app.models.BackendCfg
import com.agent.app.models.ChatDraft
import com.agent.app.models.ChatMessage
import com.agent.app.models.ChatPart
import com.agent.app.models.Message
import com.agent.app.models.MessagePart
import com.agent.app.models.Session
import com.agent.app.models.ToolState
import com.agent.app.models.UploadedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

// Android actuals — SharedPreferences + framework SQLite + MediaRecorder +
// ACTION_OPEN_DOCUMENT via the activity bridge.

object AndroidBridge {
    var appContext: Context? = null
    var activity: Activity? = null
}

private fun ctx(): Context = AndroidBridge.appContext
    ?: throw IllegalStateException("AndroidBridge.appContext not set")

/** Where the connection form points by default (PREFILL only — no token is
 *  baked in, so the app always starts at the setup/backends flow). */
private const val DEFAULT_BASE = "https://agent.agent.10.199.64.20.nip.io"
/** Field separator for the serialized backend list (US, 0x01). */
private const val SEP = "\u0001"

actual object Prefs {
    private fun sp() = ctx().getSharedPreferences("agent-compose", Context.MODE_PRIVATE)

    actual fun loadBase(): String = sp().getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
    /** Empty when never connected: the caller must show the setup form. */
    actual fun loadToken(): String = sp().getString("token", "") ?: ""
    actual fun save(base: String, token: String) {
        sp().edit().putString("baseUrl", base).putString("token", token).apply()
    }
    actual fun clearActive() {
        sp().edit().remove("baseUrl").remove("token").apply()
    }
    actual fun themeMode(): String {
        val sp = sp()
        val v = sp.getString("themeMode", null)
        if (v == "system" || v == "light" || v == "dark") return v
        // Legacy boolean pref maps onto the explicit modes (never "system").
        return if (sp.contains("darkMode")) {
            if (sp.getBoolean("darkMode", false)) "dark" else "light"
        } else "system"
    }
    actual fun saveThemeMode(mode: String) = sp().edit().putString("themeMode", mode).apply()
    actual fun systemLangZh(): Boolean =
        java.util.Locale.getDefault().language.startsWith("zh", ignoreCase = true)
    actual fun agentLocale(): String = sp().getString("agentLocale", "follow") ?: "follow"
    actual fun saveAgentLocale(v: String) = sp().edit().putString("agentLocale", v).apply()
    actual fun uiLang(): String = sp().getString("uiLang", "system") ?: "system"
    actual fun saveUiLang(v: String) = sp().edit().putString("uiLang", v).apply()
    private var readScope = ""

    actual fun setReadScope(scope: String) { readScope = scope }

    private fun readKey(): String = "readSeqs.$readScope"

    actual fun readSeqs(): Map<String, Int> {
        if (readScope.isEmpty()) return emptyMap()
        val raw = sp().getString(readKey(), "") ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (pair in raw.split(",")) {
            val i = pair.indexOf('=')
            if (i > 0) pair.substring(i + 1).toIntOrNull()?.let { v -> out[pair.substring(0, i)] = v }
        }
        return out
    }

    actual fun saveReadSeqs(seqs: Map<String, Int>) {
        if (readScope.isEmpty()) return
        sp().edit().putString(readKey(), seqs.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }
    actual fun backends(): List<BackendCfg> {
        val raw = sp().getString("backends", "") ?: return emptyList()
        return raw.split("\n").mapNotNull { l ->
            val p = l.split(SEP)
            // 3 fields = legacy (no username); 4 = name/baseUrl/token/username.
            when (p.size) {
                3 -> BackendCfg(p[0], p[1], p[2])
                4 -> BackendCfg(p[0], p[1], p[2], p[3])
                else -> null
            }
        }
    }
    actual fun upsertBackend(b: BackendCfg) {
        val list = backends().filter { !(it.baseUrl == b.baseUrl && it.token == b.token) } + b
        sp().edit().putString("backends", list.joinToString("\n") { "${it.name}$SEP${it.baseUrl}$SEP${it.token}$SEP${it.username}" }).apply()
    }
    actual fun removeBackend(b: BackendCfg) {
        sp().edit().putString(
            "backends",
            backends().filter { !(it.baseUrl == b.baseUrl && it.token == b.token) }
                .joinToString("\n") { "${it.name}$SEP${it.baseUrl}$SEP${it.token}$SEP${it.username}" },
        ).apply()
    }
}

private const val SCHEMA = """
CREATE TABLE IF NOT EXISTS local_sessions (
  id TEXT PRIMARY KEY, model TEXT DEFAULT '', variant TEXT DEFAULT '', preset TEXT DEFAULT '',
  system_prompt TEXT DEFAULT '', max_turns INTEGER DEFAULT 0, locale TEXT DEFAULT '',
  org TEXT DEFAULT '', repo TEXT DEFAULT '', branch TEXT DEFAULT '',
  server_tip_id TEXT DEFAULT '', message_seq INTEGER DEFAULT 0,
  last_message_at TEXT DEFAULT '', last_message_preview TEXT DEFAULT '',
  updated_at TEXT DEFAULT '', last_synced_at INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS local_messages (
  session_id TEXT NOT NULL, id TEXT NOT NULL, role TEXT, prev_id TEXT DEFAULT '',
  created_at TEXT DEFAULT '', order_key INTEGER, status TEXT DEFAULT 'complete',
  parts_json TEXT DEFAULT '[]', PRIMARY KEY (session_id, id));
CREATE TABLE IF NOT EXISTS local_sync_state (
  session_id TEXT PRIMARY KEY, oldest_id TEXT DEFAULT '', has_more INTEGER DEFAULT 1, tip_id TEXT DEFAULT '');
CREATE TABLE IF NOT EXISTS local_drafts (
  session_id TEXT PRIMARY KEY, draft_text TEXT DEFAULT '', attachments_json TEXT DEFAULT '[]');
CREATE TABLE IF NOT EXISTS read_seqs (session_id TEXT PRIMARY KEY, seq INTEGER DEFAULT 0);
"""

actual class LocalStore private constructor(private val helper: DbHelper) {

    private class DbHelper(
        context: android.content.Context,
        name: String,
    ) : android.database.sqlite.SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
            for (stmt in SCHEMA.split(";")) {
                if (stmt.isNotBlank()) db.execSQL(stmt)
            }
        }

        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, o: Int, n: Int) {
            // cache-only: drop + recreate
            for (t in listOf("local_messages", "local_sync_state")) db.execSQL("DROP TABLE IF EXISTS $t")
            onCreate(db)
        }
    }

    companion object {
        suspend fun open(scope: String): LocalStore = withContext(Dispatchers.IO) {
            LocalStore(DbHelper(ctx(), "agent_app_$scope.sqlite3"))
        }
    }

    private fun <T> read(sql: String, args: Array<String>, fn: (android.database.Cursor) -> T): T =
        helper.readableDatabase.rawQuery(sql, args).use(fn)

    actual suspend fun loadSessions(): List<Session> = withContext(Dispatchers.IO) {
        read("SELECT * FROM local_sessions ORDER BY updated_at DESC", emptyArray()) { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Session(
                            id = c.getString(0), model = c.getString(1) ?: "", variant = c.getString(2) ?: "",
                            preset = c.getString(3) ?: "",
                            systemPrompt = c.getString(4)?.ifEmpty { null },
                            maxTurns = c.getInt(5).takeIf { it > 0 },
                            locale = c.getString(6)?.ifEmpty { null },
                            org = c.getString(7) ?: "", repo = c.getString(8) ?: "", branch = c.getString(9) ?: "",
                            tipId = c.getString(10)?.ifEmpty { null }, messageSeq = c.getInt(11),
                            lastMessageAt = c.getString(12) ?: "", lastMessagePreview = c.getString(13) ?: "",
                            updatedAt = c.getString(14) ?: "",
                        ),
                    )
                }
            }
        }
    }

    actual suspend fun upsertSessions(sessions: List<Session>) {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                for (s in sessions) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO local_sessions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            s.id, s.model, s.variant, s.preset, s.systemPrompt ?: "", s.maxTurns ?: 0,
                            s.locale ?: "", s.org, s.repo, s.branch, s.tipId ?: "", s.messageSeq,
                            s.lastMessageAt, s.lastMessagePreview, s.updatedAt, System.currentTimeMillis() / 1000,
                        ),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    actual suspend fun removeSession(id: String) {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.delete("local_messages", "session_id = ?", arrayOf(id))
            db.delete("local_sync_state", "session_id = ?", arrayOf(id))
            db.delete("local_sessions", "id = ?", arrayOf(id))
        }
    }

    actual suspend fun loadMessages(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        read(
            "SELECT id, role, prev_id, created_at, order_key, status, parts_json FROM local_messages WHERE session_id = ? ORDER BY order_key ASC",
            arrayOf(sessionId),
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    add(chatFromRow(c))
                }
            }
        }
    }

    private fun chatFromRow(c: android.database.Cursor): ChatMessage = ChatMessage(
        id = c.getString(0), role = c.getString(1) ?: "", prevId = c.getString(2) ?: "",
        createdAt = c.getString(3) ?: "", seq = c.getInt(4), status = c.getString(5) ?: "complete",
        parts = DesktopJson.partsFrom(c.getString(6) ?: "[]"),
    )

    actual suspend fun serverTipId(sessionId: String): String = withContext(Dispatchers.IO) {
        read("SELECT tip_id FROM local_sync_state WHERE session_id = ?", arrayOf(sessionId)) { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }
    }

    actual suspend fun oldestCachedId(sessionId: String): String = withContext(Dispatchers.IO) {
        read(
            "SELECT id FROM local_messages WHERE session_id = ? ORDER BY order_key ASC LIMIT 1",
            arrayOf(sessionId),
        ) { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
    }

    actual suspend fun applyServerMessages(sessionId: String, msgs: List<Message>, replace: Boolean, tipId: String) {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                if (replace) db.delete("local_messages", "session_id = ?", arrayOf(sessionId))
                var order = 0
                val q = db.rawQuery("SELECT MAX(order_key) FROM local_messages WHERE session_id = ?", arrayOf(sessionId))
                if (q.moveToFirst() && !q.isNull(0)) order = q.getInt(0) + 1
                q.close()
                for (m in msgs) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO local_messages VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(sessionId, m.id, m.role, m.prevId, m.createdAt ?: "", order++, "complete", DesktopJson.partsOf(m.parts)),
                    )
                }
                syncState(db, sessionId, tipId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    actual suspend fun persistMessages(sessionId: String, msgs: List<ChatMessage>, tipId: String) {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("local_messages", "session_id = ?", arrayOf(sessionId))
                var order = 0
                for (m in msgs) {
                    if (m.isLocal) continue
                    db.execSQL(
                        "INSERT OR REPLACE INTO local_messages VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(sessionId, m.id, m.role, m.prevId, m.createdAt, order++, m.status, DesktopJson.chatPartsOf(m.parts)),
                    )
                }
                syncState(db, sessionId, tipId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun syncState(db: android.database.sqlite.SQLiteDatabase, sessionId: String, tipId: String) {
        var oldest = ""
        val q = db.rawQuery(
            "SELECT id FROM local_messages WHERE session_id = ? ORDER BY order_key ASC LIMIT 1",
            arrayOf(sessionId),
        )
        if (q.moveToFirst()) oldest = q.getString(0) ?: ""
        q.close()
        db.execSQL(
            "INSERT OR REPLACE INTO local_sync_state VALUES (?,?,?,?)",
            arrayOf<Any?>(sessionId, oldest, if (oldest.isEmpty()) 0 else 1, tipId),
        )
    }

    actual suspend fun saveDraft(sessionId: String, text: String, attachments: List<UploadedFile>) {
        withContext(Dispatchers.IO) {
            if (text.isBlank() && attachments.isEmpty()) {
                helper.writableDatabase.delete("local_drafts", "session_id = ?", arrayOf(sessionId))
                return@withContext
            }
            helper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO local_drafts VALUES (?,?,?)",
                arrayOf<Any?>(sessionId, text, DesktopJson.filesOf(attachments)),
            )
        }
    }

    actual suspend fun loadDrafts(): Map<String, ChatDraft> = withContext(Dispatchers.IO) {
        read("SELECT session_id, draft_text, attachments_json FROM local_drafts", emptyArray()) { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(0), ChatDraft(c.getString(1) ?: "", DesktopJson.filesFrom(c.getString(2) ?: "[]")))
                }
            }
        }
    }

    actual suspend fun setReadSeq(sessionId: String, seq: Int) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO read_seqs VALUES (?,?)",
                arrayOf<Any?>(sessionId, seq),
            )
        }
    }

    actual suspend fun loadReadSeqs(): Map<String, Int> = withContext(Dispatchers.IO) {
        read("SELECT session_id, seq FROM read_seqs", emptyArray()) { c ->
            buildMap {
                while (c.moveToNext()) put(c.getString(0), c.getInt(1))
            }
        }
    }
}

// Minimal JSON used by the sqlite rows (self-contained; no serialization dep
// on android to keep the APK small).
internal object DesktopJson {
    fun partsOf(list: List<MessagePart>): String =
        list.joinToString(",", "[", "]") {
            """{"id":"${it.id}","type":"${it.type}"""" +
                (it.text?.let { t -> ",\"text\":${str(t)}" } ?: "") +
                (it.tool?.let { t -> ",\"tool\":${str(t)}" } ?: "") +
                (it.code?.let { t -> ",\"code\":${str(t)}" } ?: "") +
                (it.name?.let { t -> ",\"name\":${str(t)}" } ?: "") +
                (it.mime?.let { t -> ",\"mime\":${str(t)}" } ?: "") +
                (it.size?.let { t -> ",\"size\":$t" } ?: "") +
                "}"
        }

    fun chatPartsOf(list: List<ChatPart>): String =
        list.joinToString(",", "[", "]") {
            """{"id":"${it.id}","type":"${it.type}"""" +
                (if (it.text.isNotEmpty()) ",\"text\":${str(it.text)}" else "") +
                (if (it.tool.isNotEmpty()) ",\"tool\":${str(it.tool)}" else "") +
                (it.state?.let { s ->
                    ",\"state\":{\"status\":\"${s.status}\",\"title\":\"${s.title}\"" +
                        (s.output?.let { o -> ",\"output\":${str(o)}" } ?: "") + "}"
                } ?: "") +
                (it.code?.let { t -> ",\"code\":${str(t)}" } ?: "") +
                (it.name?.let { t -> ",\"name\":${str(t)}" } ?: "") +
                (it.mime?.let { t -> ",\"mime\":${str(t)}" } ?: "") +
                (it.size?.let { t -> ",\"size\":$t" } ?: "") +
                "}"
        }

    fun filesOf(list: List<UploadedFile>): String =
        list.joinToString(",", "[", "]") {
            """{"code":"${it.code}","name":${str(it.name)},"mime":${str(it.mime)},"size":${it.size ?: 0},"localPath":${str(it.localPath)},"state":"${it.uploadState.name.lowercase()}"}"""
        }

    fun partsFrom(json: String): List<ChatPart> = parseObjs(json).map { chatPart(it) }
    fun filesFrom(json: String): MutableList<UploadedFile> =
        parseObjs(json).map {
            UploadedFile(
                code = it["code"]?.toString() ?: "",
                name = it["name"]?.toString(),
                mime = it["mime"]?.toString(),
                size = (it["size"] as? Double)?.toInt(),
            )
        }.toMutableList()

    private fun chatPart(m: Map<String, Any?>): ChatPart {
        val st = m["state"] as? Map<String, Any?>
        return ChatPart(
            id = m["id"]?.toString() ?: "", type = m["type"]?.toString() ?: "",
            text = m["text"]?.toString() ?: "", tool = m["tool"]?.toString() ?: "",
            state = st?.let {
                ToolState(
                    status = it["status"]?.toString() ?: "", title = it["title"]?.toString() ?: "",
                    output = it["output"]?.toString(),
                )
            },
            code = m["code"]?.toString(), name = m["name"]?.toString(),
            mime = m["mime"]?.toString(), size = (m["size"] as? Double)?.toInt(),
        )
    }

    // extremely small JSON object-array parser (sufficient for cached rows)
    fun parseObjs(json: String): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var i = 0
        val n = json.length
        fun skip() {
            while (i < n && json[i].isWhitespace()) i++
        }
        fun parseValue(): Any? {
            skip()
            if (i >= n) return null
            return when (json[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    skip()
                    if (i < n && json[i] == '}') {
                        i++
                        return m
                    }
                    while (i < n) {
                        val k = parseValue() as String
                        skip()
                        if (i < n && json[i] == ':') i++
                        m[k] = parseValue()
                        skip()
                        if (i < n && json[i] == ',') {
                            i++
                            continue
                        }
                        if (i < n && json[i] == '}') {
                            i++
                            break
                        }
                    }
                    m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    skip()
                    if (i < n && json[i] == ']') {
                        i++
                        return l
                    }
                    while (i < n) {
                        l.add(parseValue())
                        skip()
                        if (i < n && json[i] == ',') {
                            i++
                            continue
                        }
                        if (i < n && json[i] == ']') {
                            i++
                            break
                        }
                    }
                    l
                }
                '"' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < n && json[i] != '"') {
                        if (json[i] == '\\' && i + 1 < n) {
                            i++
                            when (json[i]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                else -> sb.append(json[i])
                            }
                        } else {
                            sb.append(json[i])
                        }
                        i++
                    }
                    i++
                    sb.toString()
                }
                else -> {
                    val start = i
                    while (i < n && json[i] != ',' && json[i] != '}' && json[i] != ']' && !json[i].isWhitespace()) i++
                    val raw = json.substring(start, i)
                    raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw.toBooleanStrictOrNull() ?: raw
                }
            }
        }
        skip()
        if (i < n && json[i] == '[') {
            i++
            skip()
            if (i < n && json[i] == ']') return out
            while (i < n) {
                val v = parseValue()
                if (v is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    out.add(v as Map<String, Any?>)
                }
                skip()
                if (i < n && json[i] == ',') {
                    i++
                    continue
                }
                if (i < n && json[i] == ']') break
            }
        }
        return out
    }

    private fun str(s: String?): String = "\"" + (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

// ---- voice ----

actual class VoiceRecorder actual constructor() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    private val recordPermission = android.Manifest.permission.RECORD_AUDIO

    actual suspend fun hasPermission(): Boolean =
        ctx().checkSelfPermission(recordPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Prompts through the Activity (only an Activity can show a permission
     * dialog) and awaits the user's answer. Returns false when the app did not
     * install a launcher, so the caller shows the "denied" hint instead of
     * failing silently.
     */
    actual suspend fun requestPermission(): Boolean =
        suspendCancellableCoroutine { cont ->
            if (ctx().checkSelfPermission(recordPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                cont.resume(true)
                return@suspendCancellableCoroutine
            }
            val launcher = PermissionBridge.launcher
            if (launcher == null) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            PermissionBus.pending = cont
            launcher(recordPermission)
        }

    actual suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (ctx().checkSelfPermission(recordPermission) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext false
        }
        try {
            @Suppress("DEPRECATION")
            val r = MediaRecorder()
            val f = File.createTempFile("voice", ".m4a", ctx().cacheDir)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            true
        } catch (_: Exception) {
            false
        }
    }

    actual suspend fun stop(): PickedFile? = withContext(Dispatchers.IO) {
        val r = recorder ?: return@withContext null
        recorder = null
        try {
            r.stop()
        } catch (_: Exception) {
        }
        r.release()
        val f = file ?: return@withContext null
        file = null
        if (f.length() == 0L) return@withContext null
        PickedFile(f.name, "audio/mp4", f.readBytes(), f.absolutePath)
    }

    actual suspend fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (_: Exception) {
        }
        r.release()
        file?.delete()
        file = null
    }

    actual fun dispose() {
        recorder?.release()
        recorder = null
    }
}

// ---- file picking (ACTION_OPEN_DOCUMENT through the activity bridge) ----

actual suspend fun pickFiles(mimeFilter: String?): List<PickedFile> =
    suspendCancellableCoroutine { cont ->
        val launcher = FilePickBridge.launcher
        if (launcher == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        PickerBus.pending = cont
        launcher(mimeFilter)
    }

/**
 * Bridge between this AAR and the :android application module: the app owns the
 * Activity (and therefore the ActivityResult contract), while this module owns
 * the suspend `pickFiles` API. The app installs [launcher]; results come back
 * through [PickerBus.deliver].
 */
object FilePickBridge {
    /** Set by the app to `ActivityResultLauncher.launch`. */
    var launcher: ((String?) -> Unit)? = null

    /** Set by the app to launch the camera (TakePicture contract). */
    var cameraLauncher: (() -> Unit)? = null
}

/** Camera capture (flutter `_pickImage(ImageSource.camera)`). Empty when the
 *  app never installed [FilePickBridge.cameraLauncher] or the user cancelled. */
actual suspend fun takePhoto(): List<PickedFile> =
    suspendCancellableCoroutine { cont ->
        val launcher = FilePickBridge.cameraLauncher
        if (launcher == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        PickerBus.pending = cont
        launcher()
    }

/**
 * Same split as [FilePickBridge] but for runtime permissions: only the Activity
 * can request them, so the app installs [launcher] and reports the outcome via
 * [PermissionBus.deliver].
 */
object PermissionBridge {
    /** Set by the app to `ActivityResultLauncher<String>.launch`. */
    var launcher: ((String) -> Unit)? = null
}

object PermissionBus {
    var pending: kotlin.coroutines.Continuation<Boolean>? = null

    /** Called from the app's RequestPermission ActivityResult callback. */
    fun deliver(granted: Boolean) {
        val cont = pending ?: return
        pending = null
        cont.resume(granted)
    }
}

object PickerBus {
    var pending: kotlin.coroutines.Continuation<List<PickedFile>>? = null

    /** Called from MainActivity.onActivityResult. */
    fun deliver(uris: List<Uri>) {
        val cont = pending ?: return
        pending = null
        if (uris.isEmpty()) {
            cont.resume(emptyList())
            return
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val out = uris.mapNotNull { u ->
                try {
                    val cr = ctx().contentResolver
                    var name = "file"
                    cr.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) name = c.getString(idx) ?: name
                        }
                    }
                    val bytes = cr.openInputStream(u)?.use { it.readBytes() } ?: return@mapNotNull null
                    val mime = cr.getType(u) ?: "application/octet-stream"
                    PickedFile(name, mime, bytes)
                } catch (_: Exception) {
                    null
                }
            }
            cont.resume(out)
        }
    }

}

actual suspend fun openLocalStore(scope: String): LocalStore? = try {
    LocalStore.open(scope)
} catch (_: Exception) {
    null
}

actual suspend fun readLocalFile(localPath: String, name: String): ByteArray = withContext(Dispatchers.IO) {
    if (localPath.startsWith("content://")) {
        ctx().contentResolver.openInputStream(android.net.Uri.parse(localPath))?.use { it.readBytes() }
            ?: error("unreadable: $localPath")
    } else {
        val f = File(localPath)
        if (f.canRead()) f.readBytes() else error("unreadable: $localPath")
    }
}

actual suspend fun mediaUrl(bytes: ByteArray, mime: String?): String = withContext(Dispatchers.IO) {
    val f = File.createTempFile("media", ".bin", ctx().cacheDir)
    f.writeBytes(bytes)
    android.net.Uri.fromFile(f).toString()
}

actual suspend fun downloadFile(name: String, mime: String?, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val dir = File(ctx().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "").apply { mkdirs() }
    val f = File(dir, name)
    f.writeBytes(bytes)
    f.absolutePath
}

actual suspend fun confirm(title: String, body: String): Boolean =
    suspendCancellableCoroutine { cont ->
        val act = AndroidBridge.activity
        if (act == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        android.app.AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                cont.resume(true)
            }
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                d.dismiss()
                cont.resume(false)
            }
            .setOnCancelListener { cont.resume(false) }
            .show()
    }

actual suspend fun promptText(title: String, initial: String): String? =
    suspendCancellableCoroutine { cont ->
        val act = AndroidBridge.activity
        if (act == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val input = android.widget.EditText(act).apply { setText(initial) }
        android.app.AlertDialog.Builder(act)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                cont.resume(input.text.toString().takeIf { it.isNotBlank() })
            }
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                d.dismiss()
                cont.resume(null)
            }
            .setOnCancelListener { cont.resume(null) }
            .show()
    }


actual fun copyToClipboard(text: String) {
    val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text))
}


/// Android: reactive through isSystemInDarkTheme() (LocalConfiguration) — no
/// extra bridge needed.
actual fun installSystemDarkListener(cb: (Boolean) -> Unit) { /* no-op */ }
