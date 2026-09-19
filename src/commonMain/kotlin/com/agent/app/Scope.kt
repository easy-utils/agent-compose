package com.agent.app

/**
 * Local-storage identity for the ACTIVE connection (gateway base URL + bearer
 * token).
 *
 * WHY: the tenant lives inside the token (a client can never name it) and one
 * gateway host can serve several tenants, so every per-connection cache — the
 * sqlite mirror (sessions / messages / drafts / read watermarks) and the
 * persisted read watermarks — is keyed by this scope. Switching users starts
 * from a clean slate instead of leaking another tenant's data.
 *
 * djb2 mod 2^31, computed in Long so every intermediate stays below 2^53 — the
 * same value the Dart / TypeScript / Swift ports produce.
 */
fun scopeOf(baseUrl: String, token: String): String {
    var h = 5381L
    for (b in "$baseUrl\n$token".encodeToByteArray()) {
        h = ((h * 33) + (b.toLong() and 0xFFL)) and 0x7fffffffL
    }
    return h.toString(16).padStart(8, '0')
}
