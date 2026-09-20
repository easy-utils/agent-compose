package com.agent.app.platform

import com.agent.app.models.BackendCfg
import com.agent.app.models.ChatDraft
import com.agent.app.models.ChatMessage
import com.agent.app.models.ChatPart
import com.agent.app.models.Message
import com.agent.app.models.MessagePart
import com.agent.app.models.Session
import com.agent.app.models.ToolState
import com.agent.app.models.UploadedFile
import com.agent.app.models.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.prefs.Preferences
import kotlinx.serialization.json.Json

// Desktop actuals — sqlite-jdbc mirror + java.util.prefs KV + AWT pickers +
// javax.sound voice recording.

private fun dbFile(scope: String): File =
    File(System.getProperty("user.home"), ".agent-compose/agent_app_$scope.sqlite3")

/** Where the connection form points by default (PREFILL only — no token is
 *  baked in, so the app always starts at the setup/backends flow). */
private const val DEFAULT_BASE = "https://agent.agent.10.199.64.20.nip.io"
/** Field separator for the serialized backend list (US, 0x01). */
private const val SEP = "\u0001"

actual object Prefs {
    private val node: Preferences get() = Preferences.userRoot().node("agent-compose")

    actual fun loadBase(): String = node.get("baseUrl",
        System.getenv("AGENT_BASE_URL") ?: DEFAULT_BASE)
    /** Empty when never connected (AGENT_TOKEN may still inject one for CI). */
    actual fun loadToken(): String = node.get("token", System.getenv("AGENT_TOKEN") ?: "")
    actual fun save(base: String, token: String) {
        node.put("baseUrl", base)
        node.put("token", token)
    }
    actual fun clearActive() {
        node.remove("baseUrl")
        node.remove("token")
    }
    actual fun themeMode(): String {
        val v = node.get("themeMode", null)
        if (v == "system" || v == "light" || v == "dark") return v
        val legacy = node.get("darkMode", null)
        return when (legacy) {
            "true" -> "dark"
            "false" -> "light"
            else -> "system"
        }
    }
    actual fun saveThemeMode(mode: String) = node.put("themeMode", mode)
    actual fun systemLangZh(): Boolean =
        java.util.Locale.getDefault().language.startsWith("zh", ignoreCase = true)
    actual fun agentLocale(): String = node.get("agentLocale", "follow")
    actual fun saveAgentLocale(v: String) = node.put("agentLocale", v)
    actual fun uiLang(): String = node.get("uiLang", "system")
    actual fun saveUiLang(v: String) = node.put("uiLang", v)
    private var readScope = ""

    actual fun setReadScope(scope: String) { readScope = scope }

    private fun readKey(): String = "readSeqs.$readScope"

    actual fun readSeqs(): Map<String, Int> {
        if (readScope.isEmpty()) return emptyMap()
        val raw = node.get(readKey(), "") ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (pair in raw.split(",")) {
            val i = pair.indexOf('=')
            if (i > 0) pair.substring(i + 1).toIntOrNull()?.let { v -> out[pair.substring(0, i)] = v }
        }
        return out
    }

    actual fun saveReadSeqs(seqs: Map<String, Int>) {
        if (readScope.isEmpty()) return
        node.put(readKey(), seqs.entries.joinToString(",") { "${it.key}=${it.value}" })
    }
    actual fun backends(): List<BackendCfg> {
        val raw = node.get("backends", "") ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            // 3 fields = legacy (no username); 4 = name/baseUrl/token/username.
            when (p.size) {
                3 -> BackendCfg(p[0], p[1], p[2])
                4 -> BackendCfg(p[0], p[1], p[2], p[3])
                else -> null
            }
        }
    }
    actual fun upsertBackend(b: BackendCfg) {
        val list = backends().filter { it.baseUrl != b.baseUrl } + b
        node.put("backends", list.joinToString("\n") { "${it.name}$SEP${it.baseUrl}$SEP${it.token}$SEP${it.username}" })
    }
    actual fun removeBackend(b: BackendCfg) {
        node.put(
            "backends",
            backends().filter { !(it.baseUrl == b.baseUrl && it.token == b.token) }
                .joinToString("\n") { "${it.name}$SEP${it.baseUrl}$SEP${it.token}$SEP${it.username}" },
        )
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

actual class LocalStore private constructor(private val conn: Connection) {

    companion object {
        suspend fun open(scope: String): LocalStore = withContext(Dispatchers.IO) {
            dbFile(scope).parentFile?.mkdirs()
            Class.forName("org.sqlite.JDBC")
            val c = DriverManager.getConnection("jdbc:sqlite:${dbFile(scope).absolutePath}")
            c.createStatement().use { it.executeUpdate(SCHEMA) }
            LocalStore(c)
        }
    }

    private fun partToJson(p: ChatPart): Map<String, Any?> = buildMap {
        put("id", p.id); put("type", p.type)
        if (p.text.isNotEmpty()) put("text", p.text)
        if (p.tool.isNotEmpty()) put("tool", p.tool)
        p.state?.let { s ->
            put("state", buildMap {
                put("status", s.status); put("title", s.title)
                s.error?.let { put("error", it) }
                s.output?.let { put("output", it) }
                s.input?.let { put("input", it) }
            })
        }
        p.code?.let { put("code", it) }
        p.name?.let { put("name", it) }
        p.mime?.let { put("mime", it) }
        p.size?.let { put("size", it) }
    }

    private fun messagePartToJson(p: MessagePart): Map<String, Any?> = buildMap {
        put("id", p.id); put("type", p.type)
        p.text?.let { put("text", it) }
        p.tool?.let { put("tool", it) }
        p.toolCallId?.let { put("tool_call_id", it) }
        p.code?.let { put("code", it) }
        p.name?.let { put("name", it) }
        p.mime?.let { put("mime", it) }
        p.size?.let { put("size", it) }
    }

    private fun chatPartFromJson(j: Map<String, Any?>): ChatPart {
        @Suppress("UNCHECKED_CAST")
        val st = j["state"] as? Map<String, Any?>
        return ChatPart(
            id = j["id"]?.toString() ?: "",
            type = j["type"]?.toString() ?: "",
            text = j["text"]?.toString() ?: "",
            tool = j["tool"]?.toString() ?: "",
            state = st?.let {
                ToolState(
                    status = it["status"]?.toString() ?: "",
                    title = it["title"]?.toString() ?: "",
                    output = it["output"]?.toString(),
                    error = it["error"]?.toString(),
                    input = it["input"] as? Map<String, Any?>,
                )
            },
            code = j["code"]?.toString(),
            name = j["name"]?.toString(),
            mime = j["mime"]?.toString(),
            size = (j["size"] as? Number)?.toInt(),
        )
    }

    actual suspend fun loadSessions(): List<Session> = withContext(Dispatchers.IO) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM local_sessions ORDER BY updated_at DESC").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Session(
                                id = rs.getString("id"), model = rs.getString("model") ?: "",
                                variant = rs.getString("variant") ?: "", preset = rs.getString("preset") ?: "",
                                systemPrompt = rs.getString("system_prompt")?.ifEmpty { null },
                                maxTurns = rs.getInt("max_turns").takeIf { it > 0 },
                                locale = rs.getString("locale")?.ifEmpty { null },
                                org = rs.getString("org") ?: "", repo = rs.getString("repo") ?: "",
                                branch = rs.getString("branch") ?: "",
                                tipId = rs.getString("server_tip_id")?.ifEmpty { null },
                                messageSeq = rs.getInt("message_seq"),
                                lastMessageAt = rs.getString("last_message_at") ?: "",
                                lastMessagePreview = rs.getString("last_message_preview") ?: "",
                                updatedAt = rs.getString("updated_at") ?: "",
                            ),
                        )
                    }
                }
            }
        }
    }

    actual suspend fun upsertSessions(sessions: List<Session>) = withContext(Dispatchers.IO) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO local_sessions (id, model, variant, preset, system_prompt, max_turns, locale, org, repo, branch, server_tip_id, message_seq, last_message_at, last_message_preview, updated_at, last_synced_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            for (s in sessions) {
                ps.setString(1, s.id); ps.setString(2, s.model); ps.setString(3, s.variant)
                ps.setString(4, s.preset); ps.setString(5, s.systemPrompt ?: "")
                ps.setInt(6, s.maxTurns ?: 0); ps.setString(7, s.locale ?: "")
                ps.setString(8, s.org); ps.setString(9, s.repo); ps.setString(10, s.branch)
                ps.setString(11, s.tipId ?: ""); ps.setInt(12, s.messageSeq)
                ps.setString(13, s.lastMessageAt); ps.setString(14, s.lastMessagePreview)
                ps.setString(15, s.updatedAt); ps.setLong(16, System.currentTimeMillis() / 1000)
                ps.executeUpdate()
            }
        }
    }

    actual suspend fun removeSession(id: String) = withContext(Dispatchers.IO) {
        for (sql in listOf(
            "DELETE FROM local_messages WHERE session_id = ?",
            "DELETE FROM local_sync_state WHERE session_id = ?",
            "DELETE FROM local_sessions WHERE id = ?",
        )) {
            conn.prepareStatement(sql).use { it.setString(1, id); it.executeUpdate() }
        }
    }

    private fun rowToChat(rs: java.sql.ResultSet): ChatMessage {
        val partsJson = rs.getString("parts_json") ?: "[]"
        @Suppress("UNCHECKED_CAST")
        val parts = (kotlinx.serialization.json.Json.parseToJsonElement(partsJson) as kotlinx.serialization.json.JsonArray)
            .map { el ->
                @Suppress("UNCHECKED_CAST")
                chatPartFromJson((el as kotlinx.serialization.json.JsonObject).toMap().mapValues { (_, v) -> jsonElToAny(v) } as Map<String, Any?>)
            }
        return ChatMessage(
            id = rs.getString("id"), role = rs.getString("role") ?: "",
            status = rs.getString("status") ?: "complete",
            createdAt = rs.getString("created_at") ?: "",
            prevId = rs.getString("prev_id") ?: "",
            seq = rs.getInt("order_key"),
            parts = parts,
        )
    }

    private fun jsonElToAny(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> e.content
        is kotlinx.serialization.json.JsonArray -> e.map { jsonElToAny(it) }
        is kotlinx.serialization.json.JsonObject -> e.toMap().mapValues { (_, v) -> jsonElToAny(v) }
    }

    actual suspend fun loadMessages(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        conn.prepareStatement("SELECT * FROM local_messages WHERE session_id = ? ORDER BY order_key ASC").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rowToChat(rs)) } }
        }
    }

    actual suspend fun serverTipId(sessionId: String): String = withContext(Dispatchers.IO) {
        conn.prepareStatement("SELECT tip_id FROM local_sync_state WHERE session_id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) ?: "" else "" }
        }
    }

    actual suspend fun oldestCachedId(sessionId: String): String = withContext(Dispatchers.IO) {
        conn.prepareStatement("SELECT id FROM local_messages WHERE session_id = ? ORDER BY order_key ASC LIMIT 1").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) ?: "" else "" }
        }
    }

    actual suspend fun applyServerMessages(sessionId: String, msgs: List<Message>, replace: Boolean, tipId: String) =
        withContext(Dispatchers.IO) {
            conn.autoCommit = false
            try {
                if (replace) {
                    conn.prepareStatement("DELETE FROM local_messages WHERE session_id = ?").use { ps ->
                        ps.setString(1, sessionId); ps.executeUpdate()
                    }
                }
                var order = (conn.prepareStatement("SELECT MAX(order_key) FROM local_messages WHERE session_id = ?").use { ps ->
                    ps.setString(1, sessionId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }) + 1
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO local_messages (session_id, id, role, prev_id, created_at, order_key, status, parts_json) VALUES (?,?,?,?,?,?,?,?)",
                ).use { ps ->
                    for (m in msgs) {
                        ps.setString(1, sessionId); ps.setString(2, m.id); ps.setString(3, m.role)
                        ps.setString(4, m.prevId); ps.setString(5, m.createdAt ?: "")
                        ps.setInt(6, order++); ps.setString(7, "complete")
                        ps.setString(8, kotlinx.serialization.json.Json.encodeToString(jsonArrOf(m.parts.map { messagePartToJson(it) })))
                        ps.executeUpdate()
                    }
                }
                upsertSyncStateLocked(sessionId, tipId)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    actual suspend fun persistMessages(sessionId: String, msgs: List<ChatMessage>, tipId: String) =
        withContext(Dispatchers.IO) {
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM local_messages WHERE session_id = ?").use { ps ->
                    ps.setString(1, sessionId); ps.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO local_messages (session_id, id, role, prev_id, created_at, order_key, status, parts_json) VALUES (?,?,?,?,?,?,?,?)",
                ).use { ps ->
                    msgs.filter { !it.isLocal }.forEachIndexed { i, m ->
                        ps.setString(1, sessionId); ps.setString(2, m.id); ps.setString(3, m.role)
                        ps.setString(4, m.prevId); ps.setString(5, m.createdAt)
                        ps.setInt(6, i); ps.setString(7, m.status)
                        ps.setString(8, kotlinx.serialization.json.Json.encodeToString(jsonArrOf(m.parts.map { partToJson(it) })))
                        ps.executeUpdate()
                    }
                }
                upsertSyncStateLocked(sessionId, tipId)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    private fun upsertSyncStateLocked(sessionId: String, tipId: String) {
        val oldest = conn.prepareStatement(
            "SELECT id FROM local_messages WHERE session_id = ? ORDER BY order_key ASC LIMIT 1",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) ?: "" else "" }
        }
        conn.prepareStatement(
            "INSERT OR REPLACE INTO local_sync_state (session_id, oldest_id, has_more, tip_id) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, sessionId); ps.setString(2, oldest)
            ps.setInt(3, if (oldest.isEmpty()) 0 else 1); ps.setString(4, tipId)
            ps.executeUpdate()
        }
    }

    actual suspend fun saveDraft(sessionId: String, text: String, attachments: List<UploadedFile>) {
        withContext(Dispatchers.IO) {
            if (text.isBlank() && attachments.isEmpty()) {
                conn.prepareStatement("DELETE FROM local_drafts WHERE session_id = ?").use { ps ->
                    ps.setString(1, sessionId); ps.executeUpdate()
                }
                return@withContext
            }
            val attJson = attachments.joinToString(",", "[", "]") { a ->
                "{\"code\":\"${a.code}\",\"name\":${jsonStr(a.name)},\"mime\":${jsonStr(a.mime)},\"size\":${a.size ?: 0},\"localPath\":${jsonStr(a.localPath)},\"state\":\"${a.uploadState.name.lowercase()}\"}"
            }
            conn.prepareStatement("INSERT OR REPLACE INTO local_drafts (session_id, draft_text, attachments_json) VALUES (?,?,?)").use { ps ->
                ps.setString(1, sessionId); ps.setString(2, text); ps.setString(3, attJson)
                ps.executeUpdate()
            }
        }
    }

    actual suspend fun loadDrafts(): Map<String, ChatDraft> = withContext(Dispatchers.IO) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT session_id, draft_text, attachments_json FROM local_drafts").use { rs ->
                buildMap {
                    while (rs.next()) {
                        val arr = kotlinx.serialization.json.Json.parseToJsonElement(rs.getString(3) ?: "[]")
                        val atts = (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                            (el as? kotlinx.serialization.json.JsonObject)?.let { o ->
                                UploadedFile(
                                    code = o["code"]?.toString()?.trim('"') ?: "",
                                    name = o["name"]?.toString()?.trim('"')?.ifEmpty { null },
                                    mime = o["mime"]?.toString()?.trim('"')?.ifEmpty { null },
                                    size = o["size"]?.toString()?.toIntOrNull(),
                                    localPath = o["localPath"]?.toString()?.trim('"') ?: "",
                                    uploadState = UploadState.DONE,
                                )
                            }
                        } ?: emptyList()
                        put(rs.getString(1), ChatDraft(rs.getString(2) ?: "", atts.toMutableList()))
                    }
                }
            }
        }
    }

    actual suspend fun setReadSeq(sessionId: String, seq: Int) {
        withContext(Dispatchers.IO) {
            conn.prepareStatement("INSERT OR REPLACE INTO read_seqs (session_id, seq) VALUES (?,?)").use { ps ->
                ps.setString(1, sessionId); ps.setInt(2, seq); ps.executeUpdate()
            }
        }
    }

    actual suspend fun loadReadSeqs(): Map<String, Int> = withContext(Dispatchers.IO) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT session_id, seq FROM read_seqs").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) }
            }
        }
    }
}

private fun jsonArrOf(list: List<Map<String, Any?>>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.Json.parseToJsonElement(
        list.joinToString(",", "[", "]") { mapToJsonStr(it) },
    ) as kotlinx.serialization.json.JsonArray

private fun mapToJsonStr(m: Map<String, Any?>): String =
    m.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${valueToJsonStr(v)}" }

private fun valueToJsonStr(v: Any?): String = when (v) {
    null -> "null"
    is String -> jsonStr(v)
    is Number, is Boolean -> v.toString()
    is Map<*, *> -> mapToJsonStr(v as Map<String, Any?>)
    is List<*> -> v.joinToString(",", "[", "]") { valueToJsonStr(it) }
    else -> jsonStr(v.toString())
}

private fun jsonStr(s: String?): String = "\"${(s ?: "").replace("\\", "\\\\").replace("\"", "\\\"")}\""


/// Desktop JVM has no OS theme bridge (isSystemInDarkTheme() is constant
/// false), so "follow system" resolves to light there — documented limitation.
actual fun installSystemDarkListener(cb: (Boolean) -> Unit) { /* no-op */ }
