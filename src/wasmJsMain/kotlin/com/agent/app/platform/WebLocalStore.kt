package com.agent.app.platform

import com.agent.app.models.ChatDraft
import com.agent.app.models.ChatMessage
import com.agent.app.models.ChatPart
import com.agent.app.models.Message
import com.agent.app.models.MessagePart
import com.agent.app.models.Session
import com.agent.app.models.ToolState
import com.agent.app.models.UploadState
import com.agent.app.models.UploadedFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Web (wasmJs) sqlite mirror — schema and semantics identical to the desktop
 * JDBC store and to flutter's Drift DB / webui's sqlite-wasm store. Persists to
 * OPFS via `@sqlite.org/sqlite-wasm` (falls back to in-memory when OPFS is
 * unavailable, e.g. a non-secure context).
 */
internal class WebLocalStore private constructor() {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val SCHEMA = """
CREATE TABLE IF NOT EXISTS local_sessions (
  id TEXT PRIMARY KEY, model TEXT DEFAULT '', variant TEXT DEFAULT '', preset TEXT DEFAULT '',
  system_prompt TEXT DEFAULT '', max_turns INTEGER DEFAULT 0, locale TEXT DEFAULT '',
  org TEXT DEFAULT '', repo TEXT DEFAULT '', branch TEXT DEFAULT '',
  server_tip_id TEXT DEFAULT '', message_seq INTEGER DEFAULT 0,
  group_key TEXT DEFAULT '',
  last_message_at TEXT DEFAULT '', last_message_preview TEXT DEFAULT '',
  updated_at TEXT DEFAULT '', last_synced_at INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS local_messages (
  session_id TEXT NOT NULL, id TEXT NOT NULL, role TEXT, prev_id TEXT DEFAULT '',
  created_at TEXT DEFAULT '', order_key INTEGER, status TEXT DEFAULT 'complete',
  parts_json TEXT DEFAULT '[]', source TEXT DEFAULT '', PRIMARY KEY (session_id, id));
CREATE TABLE IF NOT EXISTS local_sync_state (
  session_id TEXT PRIMARY KEY, oldest_id TEXT DEFAULT '', has_more INTEGER DEFAULT 1, tip_id TEXT DEFAULT '');
CREATE TABLE IF NOT EXISTS local_drafts (
  session_id TEXT PRIMARY KEY, draft_text TEXT DEFAULT '', attachments_json TEXT DEFAULT '[]');
CREATE TABLE IF NOT EXISTS read_seqs (session_id TEXT PRIMARY KEY, seq INTEGER DEFAULT 0);
"""

        suspend fun open(scope: String): WebLocalStore? {
            val ok = try {
                SqliteBridge.open(scope, sqliteInitPromise())
            } catch (e: Throwable) {
                false
            }
            if (!ok || SqliteBridge.dbId < 0) return null
            val store = WebLocalStore()
            store.createSchema()
            return store
        }
    }

    private fun createSchema() {
        for (stmt in SCHEMA.split(";")) {
            val s = stmt.trim()
            if (s.isNotEmpty()) SqliteBridge.exec(s)
        }
        // v4 migration: the generic group_key column (CREATE TABLE IF NOT EXISTS
        // never adds a column to an existing table). Only ALTER when it is
        // missing — a blind ALTER logs "duplicate column name" on every start
        // (the JS bridge reports exec failures via console.warn).
        val hasGroupKey = SqliteBridge.hasColumn("local_sessions", "group_key")
        if (!hasGroupKey) {
            SqliteBridge.exec("ALTER TABLE local_sessions ADD COLUMN group_key TEXT DEFAULT ''")
        }
        // v5: the message ORIGIN `source` column. When missing, drop the message
        // cache + anchors so the next open refetches with source intact.
        val hasSource = SqliteBridge.hasColumn("local_messages", "source")
        if (!hasSource) {
            SqliteBridge.exec("ALTER TABLE local_messages ADD COLUMN source TEXT DEFAULT ''")
            SqliteBridge.exec("DELETE FROM local_messages")
            SqliteBridge.exec("DELETE FROM local_sync_state")
        }
    }

    private suspend fun rows(sql: String, bind: List<Any?> = emptyList()): List<JsonObject> {
        val raw = SqliteBridge.rows(sql, bind)
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return (el as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun str(o: JsonObject, k: String): String =
        (o[k] as? JsonPrimitive)?.content ?: ""

    private fun int(o: JsonObject, k: String): Int =
        (o[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun long(o: JsonObject, k: String): Long =
        (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun jsonElToAny(e: JsonElement?): Any? = when (e) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
        is JsonArray -> e.map { jsonElToAny(it) }
        is JsonObject -> e.mapValues { (_, v) -> jsonElToAny(v) }
    }

    // ---- (de)serialisation of parts ----

    private fun partToJson(p: ChatPart): String {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = p.id; m["type"] = p.type
        if (p.text.isNotEmpty()) m["text"] = p.text
        if (p.tool.isNotEmpty()) m["tool"] = p.tool
        p.state?.let { s ->
            val st = LinkedHashMap<String, Any?>()
            st["status"] = s.status; st["title"] = s.title
            s.error?.let { st["error"] = it }
            s.output?.let { st["output"] = it }
            s.input?.let { st["input"] = it }
            m["state"] = st
        }
        p.code?.let { m["code"] = it }
        p.name?.let { m["name"] = it }
        p.mime?.let { m["mime"] = it }
        p.size?.let { m["size"] = it }
        return valueToJsonStr(m)
    }

    private fun messagePartToJson(p: MessagePart): String {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = p.id; m["type"] = p.type
        p.text?.let { m["text"] = it }
        p.tool?.let { m["tool"] = it }
        p.toolCallId?.let { m["tool_call_id"] = it }
        p.code?.let { m["code"] = it }
        p.name?.let { m["name"] = it }
        p.mime?.let { m["mime"] = it }
        p.size?.let { m["size"] = it }
        return valueToJsonStr(m)
    }

    private fun chatPartFromJson(o: JsonObject): ChatPart {
        val st = o["state"] as? JsonObject
        return ChatPart(
            id = str(o, "id"),
            type = str(o, "type"),
            text = str(o, "text"),
            tool = str(o, "tool"),
            state = st?.let {
                ToolState(
                    status = str(it, "status"),
                    title = str(it, "title"),
                    output = (it["output"] as? JsonPrimitive)?.content,
                    error = (it["error"] as? JsonPrimitive)?.content,
                    input = (jsonElToAny(it["input"]) as? Map<String, Any?>),
                )
            },
            code = (o["code"] as? JsonPrimitive)?.content,
            name = (o["name"] as? JsonPrimitive)?.content,
            mime = (o["mime"] as? JsonPrimitive)?.content,
            size = (o["size"] as? JsonPrimitive)?.content?.toIntOrNull(),
        )
    }

    private fun rowToChat(o: JsonObject): ChatMessage {
        val partsEl = runCatching { json.parseToJsonElement(str(o, "parts_json").ifEmpty { "[]" }) }.getOrNull()
        val parts = (partsEl as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::chatPartFromJson) } ?: emptyList()
        return ChatMessage(
            id = str(o, "id"),
            role = str(o, "role"),
            status = str(o, "status").ifEmpty { "complete" },
            parts = parts,
            createdAt = str(o, "created_at"),
            seq = int(o, "order_key"),
            prevId = str(o, "prev_id"),
            source = str(o, "source"),
            isLocal = false,
        )
    }

    // ---- interface ----

    suspend fun loadSessions(): List<Session> =
        rows("SELECT * FROM local_sessions ORDER BY updated_at DESC").map { o ->
            Session(
                id = str(o, "id"), model = str(o, "model"), variant = str(o, "variant"),
                preset = str(o, "preset"), systemPrompt = str(o, "system_prompt").ifEmpty { null },
                maxTurns = int(o, "max_turns").takeIf { it > 0 },
                locale = str(o, "locale").ifEmpty { null },
                org = str(o, "org"), repo = str(o, "repo"), branch = str(o, "branch"),
                tipId = str(o, "server_tip_id").ifEmpty { null },
                messageSeq = int(o, "message_seq"),
                lastMessageAt = str(o, "last_message_at"),
                lastMessagePreview = str(o, "last_message_preview"),
                updatedAt = str(o, "updated_at"),
                group = str(o, "group_key"),
            )
        }

    suspend fun upsertSessions(sessions: List<Session>) {
        val now = nowSec()
        for (s in sessions) {
            SqliteBridge.exec(
                "INSERT OR REPLACE INTO local_sessions (id, model, variant, preset, system_prompt, max_turns, locale, org, repo, branch, server_tip_id, message_seq, group_key, last_message_at, last_message_preview, updated_at, last_synced_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(
                    s.id, s.model, s.variant, s.preset, s.systemPrompt ?: "", s.maxTurns ?: 0,
                    s.locale ?: "", s.org, s.repo, s.branch, s.tipId ?: "", s.messageSeq,
                    s.group, s.lastMessageAt, s.lastMessagePreview, s.updatedAt, now,
                ),
            )
        }
    }

    suspend fun removeSession(id: String) {
        SqliteBridge.exec("DELETE FROM local_messages WHERE session_id = ?", listOf(id))
        SqliteBridge.exec("DELETE FROM local_sync_state WHERE session_id = ?", listOf(id))
        SqliteBridge.exec("DELETE FROM local_sessions WHERE id = ?", listOf(id))
    }

    suspend fun loadMessages(sessionId: String): List<ChatMessage> =
        rows("SELECT * FROM local_messages WHERE session_id = ? ORDER BY order_key ASC", listOf(sessionId))
            .map(::rowToChat)

    suspend fun serverTipId(sessionId: String): String {
        val r = rows("SELECT tip_id FROM local_sync_state WHERE session_id = ?", listOf(sessionId))
        return r.firstOrNull()?.let { str(it, "tip_id") } ?: ""
    }

    suspend fun oldestCachedId(sessionId: String): String {
        val r = rows("SELECT id FROM local_messages WHERE session_id = ? ORDER BY order_key ASC LIMIT 1", listOf(sessionId))
        return r.firstOrNull()?.let { str(it, "id") } ?: ""
    }

    suspend fun applyServerMessages(sessionId: String, msgs: List<Message>, replace: Boolean, tipId: String) {
        if (replace) SqliteBridge.exec("DELETE FROM local_messages WHERE session_id = ?", listOf(sessionId))
        var order = rows("SELECT MAX(order_key) AS m FROM local_messages WHERE session_id = ?", listOf(sessionId))
            .firstOrNull()?.let { if (it["m"] is JsonNull) 0 else int(it, "m") } ?: 0
        for (m in msgs) {
            val partsJson = "[" + m.parts.map { messagePartToJson(it) }.joinToString(",") + "]"
            SqliteBridge.exec(
                "INSERT OR REPLACE INTO local_messages (session_id, id, role, prev_id, created_at, order_key, status, parts_json, source) VALUES (?,?,?,?,?,?,?,?,?)",
                listOf(sessionId, m.id, m.role, m.prevId, m.createdAt ?: "", ++order, "complete", partsJson, m.source),
            )
        }
        upsertSyncState(sessionId, tipId)
    }

    suspend fun persistMessages(sessionId: String, msgs: List<ChatMessage>, tipId: String) {
        SqliteBridge.exec("DELETE FROM local_messages WHERE session_id = ?", listOf(sessionId))
        var i = 0
        for (m in msgs) {
            if (m.isLocal) continue
            val partsJson = "[" + m.parts.map { partToJson(it) }.joinToString(",") + "]"
            SqliteBridge.exec(
                "INSERT OR REPLACE INTO local_messages (session_id, id, role, prev_id, created_at, order_key, status, parts_json, source) VALUES (?,?,?,?,?,?,?,?,?)",
                listOf(sessionId, m.id, m.role, m.prevId, m.createdAt, i++, m.status, partsJson, m.source),
            )
        }
        upsertSyncState(sessionId, tipId)
    }

    private suspend fun upsertSyncState(sessionId: String, tipId: String) {
        val oldest = oldestCachedId(sessionId)
        SqliteBridge.exec(
            "INSERT OR REPLACE INTO local_sync_state (session_id, oldest_id, has_more, tip_id) VALUES (?,?,?,?)",
            listOf(sessionId, oldest, if (oldest.isEmpty()) 0 else 1, tipId),
        )
    }

    suspend fun saveDraft(sessionId: String, text: String, attachments: List<UploadedFile>) {
        if (text.isBlank() && attachments.isEmpty()) {
            SqliteBridge.exec("DELETE FROM local_drafts WHERE session_id = ?", listOf(sessionId))
            return
        }
        val attJson = "[" + attachments.joinToString(",") { a ->
            valueToJsonStr(
                linkedMapOf(
                    "code" to a.code, "name" to a.name, "mime" to a.mime,
                    "size" to (a.size ?: 0), "localPath" to a.localPath,
                    "state" to a.uploadState.name.lowercase(),
                ),
            )
        } + "]"
        SqliteBridge.exec(
            "INSERT OR REPLACE INTO local_drafts (session_id, draft_text, attachments_json) VALUES (?,?,?)",
            listOf(sessionId, text, attJson),
        )
    }

    suspend fun loadDrafts(): Map<String, ChatDraft> {
        val out = LinkedHashMap<String, ChatDraft>()
        for (o in rows("SELECT session_id, draft_text, attachments_json FROM local_drafts")) {
            val arr = runCatching { json.parseToJsonElement(str(o, "attachments_json").ifEmpty { "[]" }) }.getOrNull()
            val atts = (arr as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let {
                    UploadedFile(
                        code = str(it, "code"),
                        name = str(it, "name").ifEmpty { null },
                        mime = str(it, "mime").ifEmpty { null },
                        size = int(it, "size").takeIf { s -> s > 0 },
                        localPath = str(it, "localPath"),
                        uploadState = UploadState.DONE,
                    )
                }
            }?.toMutableList() ?: mutableListOf()
            out[str(o, "session_id")] = ChatDraft(str(o, "draft_text"), atts)
        }
        return out
    }

    suspend fun setReadSeq(sessionId: String, seq: Int) {
        SqliteBridge.exec(
            "INSERT OR REPLACE INTO read_seqs (session_id, seq) VALUES (?,?)",
            listOf(sessionId, seq),
        )
    }

    suspend fun loadReadSeqs(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (o in rows("SELECT session_id, seq FROM read_seqs")) {
            out[str(o, "session_id")] = int(o, "seq")
        }
        return out
    }

    private fun nowSec(): Long = com.agent.app.util.nowMillis() / 1000

    /** JSON-encode via kotlinx.serialization (see SqliteBridge.jsonArray). */
    private fun valueToJsonStr(v: Any?): String = toJsonElement(v).toString()

    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(
            v.entries.associate { (k, vv) -> k.toString() to toJsonElement(vv) },
        )
        is List<*> -> JsonArray(v.map { toJsonElement(it) })
        else -> JsonPrimitive(v.toString())
    }

}
