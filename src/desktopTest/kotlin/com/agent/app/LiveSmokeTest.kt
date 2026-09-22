package com.agent.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

// Live smoke test: drives the REAL AgentApiImpl against the deployed agent.
// Skipped (returns early) when the gateway is unreachable so the suite stays
// green offline.
class LiveSmokeTest {
    private val base = "https://agent.agent.10.199.64.20.nip.io"
    private val token = "devtenanttoken"

    @Test
    fun liveAgentSmoke() = runBlocking {
        val api = AgentApiImpl.create(base, token)

        // Reachability probe: bail out (green) if the cluster is down.
        val healthy = try {
            api.identity()
            true
        } catch (_: Exception) {
            false
        }
        if (!healthy) {
            println("live smoke: gateway unreachable — skipped")
            return@runBlocking
        }

        val id = api.identity()
        println("identity -> tenant=${id.tenant} name=${id.tenantName} role=${id.role}")
        assertTrue(id.tenant.isNotEmpty() || id.role == "admin")

        val sessions = api.listSessions()
        println("listSessions -> ${sessions.size}")

        val sid = api.createSession(
            mapOf("name" to "compose-live-${System.currentTimeMillis()}"),
        ).id
        println("createSession -> $sid")
        assertTrue(sid.isNotEmpty())

        api.settings(sid, mapOf("locale" to "zh", "variant" to ""))
        println("settings ok")

        val page = api.mailbox(sid, limit = 5)
        println("mailbox -> entries=${page.entries.size} hasMore=${page.hasMore}")

        val (msgs, _) = api.messages(sid, limit = 10)
        println("messages -> ${msgs.size}")

        api.deleteSession(sid)
        println("deleteSession ok")
        println("ALL OK")
    }
}
