package com.agent.app.platform

import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

// Thin JS bridge to `@sqlite.org/sqlite-wasm`. The Kotlin/Wasm side never
// touches the sqlite3 namespace object directly; every call goes through these
// @JsFun snippets, which keep the module/DB handles in a global registry keyed
// by an int.
//
// The module is loaded at RUNTIME (see SqliteModule.kt) so its `import.meta.url`
// asset resolution (sqlite3.wasm + OPFS proxy worker) stays relative to the
// SERVED files, not the build machine.

@JsFun(
    """
() => {
  if (!window.__agentSqlite) {
    window.__agentSqlite = { mod: null, dbs: {}, next: 1, init: null };
  }
  return window.__agentSqlite;
}
""",
)
private external fun sqliteRegistry(): JsAny

/**
 * Initialise the module (idempotent) and open a database. `scope` names the
 * OPFS file (`agent-compose-<scope>.sqlite3`); when OPFS is unavailable the
 * bridge falls back to an in-memory DB so the app still works.
 * Returns the DB handle id, or -1 on failure.
 */
@JsFun(
    """
async (scope, initPromise, cb) => {
  const reg = window.__agentSqlite;
  try {
    if (!reg.init) {
      reg.init = initPromise.then((fn) => fn()).then((sqlite3) => {
        reg.mod = sqlite3;
        return sqlite3;
      });
    }
    const sqlite3 = await reg.init;
    let db;
    const name = 'agent-compose-' + scope + '.sqlite3';
    if (sqlite3.oo1.OpfsDb) {
      db = new sqlite3.oo1.OpfsDb(name);
    } else {
      console.warn('[sqlite] OPFS unavailable - falling back to in-memory');
      db = new sqlite3.oo1.DB(':memory:', 'c');
    }
    const id = reg.next++;
    reg.dbs[id] = db;
    cb(id);
  } catch (e) {
    console.warn('[sqlite] open failed:', e);
    cb(-1);
  }
}
""",
)
private external fun sqliteOpen(
    scope: String,
    initPromise: JsAny,
    cb: (Int) -> Unit,
)

/**
 * Run one statement. `bindJson` is a JSON array of values; rows come back as a
 * JSON array of objects (empty string when no rows are expected).
 */
@JsFun(
    """
(id, sql, bindJson, cb) => {
  const reg = window.__agentSqlite;
  const db = reg && reg.dbs[id];
  if (!db) { cb(''); return; }
  let bind = [];
  try { bind = JSON.parse(bindJson); } catch (_) {}
  try {
    const rows = db.exec({ sql, bind, rowMode: 'object', returnValue: 'resultRows' });
    cb(JSON.stringify(rows || []));
  } catch (e) {
    console.warn('[sqlite] exec failed:', e, sql);
    cb('');
  }
}
""",
)
private external fun sqliteExec(
    id: Int,
    sql: String,
    bindJson: String,
    cb: (String) -> Unit,
)

/** True when [table] already has [column] (synchronous PRAGMA probe). */
@JsFun(
    """
(id, table, col) => {
  const reg = window.__agentSqlite;
  const db = reg && reg.dbs[id];
  if (!db) return false;
  try {
    const rows = db.exec({ sql: 'PRAGMA table_info(' + table + ')', rowMode: 'object', returnValue: 'resultRows' });
    return (rows || []).some(r => r && r.name === col);
  } catch (_) {
    return false;
  }
}
""",
)
private external fun sqliteHasColumn(id: Int, table: String, col: String): Boolean

internal object SqliteBridge {
    /** Kotlin-side handle for the single open DB. */
    var dbId: Int = -1
        private set

    suspend fun open(scope: String, initPromise: JsAny): Boolean {
        if (dbId >= 0) return true
        sqliteRegistry()
        val id = kotlin.coroutines.suspendCoroutine { cont ->
            sqliteOpen(scope, initPromise, { cont.resume(it) })
        }
        dbId = id
        return id >= 0
    }

    /** Executes and returns the raw JSON rows (or "[]"). */
    suspend fun rows(sql: String, bind: List<Any?> = emptyList()): String {
        if (dbId < 0) return "[]"
        val bindJson = jsonArray(bind)
        return kotlin.coroutines.suspendCoroutine { cont ->
            sqliteExec(dbId, sql, bindJson, { cont.resume(it.ifEmpty { "[]" }) })
        }
    }

    /** Write helper. Failures are surfaced through the JS bridge's warn. */
    fun exec(sql: String, bind: List<Any?> = emptyList()) {
        if (dbId < 0) return
        val bindJson = jsonArray(bind)
        sqliteExec(dbId, sql, bindJson) { }
    }

    fun hasColumn(table: String, col: String): Boolean {
        if (dbId < 0) return false
        return sqliteHasColumn(dbId, table, col)
    }

    /**
     * Bind values are serialised with kotlinx.serialization, NOT by hand: a
     * hand-rolled escaper only handled `\` and `"`, so a value containing a
     * literal newline/control char produced invalid JSON, `JSON.parse` on the JS
     * side failed, the bind array silently became empty, and SQLite saw NULLs
     * (`NOT NULL constraint failed: local_messages.session_id`).
     */
    private fun jsonArray(values: List<Any?>): String = buildJsonArray {
        for (v in values) {
            when (v) {
                null -> add(JsonNull)
                is String -> add(JsonPrimitive(v))
                is Boolean -> add(JsonPrimitive(v))
                is Int -> add(JsonPrimitive(v))
                is Long -> add(JsonPrimitive(v))
                is Double -> add(JsonPrimitive(v))
                is Float -> add(JsonPrimitive(v))
                else -> add(JsonPrimitive(v.toString()))
            }
        }
    }.toString()
}
