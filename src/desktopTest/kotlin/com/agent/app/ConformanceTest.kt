package com.agent.app

import com.agent.app.messages.MessagesController
import com.agent.app.models.ChatMessage
import com.agent.app.models.FileMeta
import com.agent.app.models.Identity
import com.agent.app.models.MailboxPage
import com.agent.app.models.Message
import com.agent.app.models.MessagePart
import com.agent.app.models.ModelInfo
import com.agent.app.models.Preset
import com.agent.app.models.ProviderInfo
import com.agent.app.models.Session
import com.agent.app.models.SessionListEvent
import com.agent.app.models.StreamEvent
import com.agent.app.models.ToolInfo
import com.agent.app.models.ToolState
import com.agent.app.models.UploadedFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Behavioural conformance: replay the SHARED scenario manifest (vendored from
// easy-utils/agent-tools/conformance/scenarios.json) through the REAL
// MessagesController against a scripted fake AgentApi, then compare a
// normalized state snapshot. This proves the state machine behaves like the
// webui reference (abcp-sdk/webui/src/lib/messages.test.ts) — the string guards
// cannot. Every scenario id in the manifest appears below.
class ConformanceTest {

    /** A faithful in-memory fake: the persisted chain + an open push channel. */
    private class FakeServer {
        val chain = mutableListOf<Message>()
        val chan = Channel<StreamEvent>(Channel.UNLIMITED)
        private var promptErr: String? = null
        private val status = "idle"

        fun failPrompt(msg: String) { promptErr = msg }
        fun clearPromptError() { promptErr = null }
        fun persist(vararg msgs: Message) { chain.addAll(msgs) }
        fun push(ev: StreamEvent) { chan.trySend(ev) }

        fun api(): AgentApi = object : AgentApi {
            override val baseUrl = "test"
            override val token = "test"
            override suspend fun prompt(id: String, prompt: String, attachments: List<String>): String {
                promptErr?.let { throw RuntimeException(it) }
                return "accepted"
            }
            override suspend fun messages(id: String, before: String?, limit: Int): Pair<List<Message>, Boolean> =
                chain.toList() to false
            override suspend fun messagesAfter(id: String, after: String, limit: Int): Triple<List<Message>, Boolean, String> {
                val tip = chain.lastOrNull()?.id ?: ""
                if (after.isEmpty()) return Triple(chain.toList(), false, tip)
                val i = chain.indexOfFirst { it.id == after }
                if (i < 0) return Triple(chain.toList(), true, tip)
                return Triple(chain.drop(i + 1), false, tip)
            }
            override suspend fun state(id: String): Pair<String, List<Any?>> = status to emptyList()
            override suspend fun mailbox(id: String, before: String, limit: Int): MailboxPage =
                MailboxPage(emptyList(), false)
            override fun streamEvents(sessionId: String, since: String): Flow<StreamEvent> = chan.receiveAsFlow()
            override suspend fun listSessions(): List<Session> = emptyList()
            override suspend fun identity(): Identity = Identity()
            override suspend fun createSession(params: Map<String, Any?>): Session = Session(id = "x")
            override suspend fun getSession(id: String): Session? = null
            override suspend fun deleteSession(id: String) {}
            override suspend fun renameSession(id: String, name: String): Session? = null
            override suspend fun uploadFile(name: String, bytes: ByteArray): UploadedFile = UploadedFile(code = "c")
            override suspend fun fetchFileBytes(code: String): ByteArray = ByteArray(0)
            override suspend fun fileHead(code: String): FileMeta = FileMeta()
            override suspend fun settings(id: String, settings: Map<String, Any?>): Session? = null
            override suspend fun fork(id: String, branch: String): Session? = null
            override suspend fun revert(id: String, messageId: String?) {}
            override suspend fun interrupt(id: String): Boolean = true
            override suspend fun compact(id: String): Boolean = true
            override fun watchSessions(): Flow<SessionListEvent> = flow {}
            override suspend fun setToolConfigValue(extId: String, name: String, value: Any?) {}
            override suspend fun providers(): Map<String, ProviderInfo> = emptyMap()
            override suspend fun providerCatalog(): Map<String, List<String>> = emptyMap()
            override suspend fun registerProvider(p: ProviderInfo) {}
            override suspend fun deleteProvider(pid: String) {}
            override suspend fun testProvider(apiType: String, baseUrl: String, apiKey: String, providerId: String, model: String?, capability: String): Pair<Boolean, String> = false to ""
            override suspend fun models(providerId: String): List<ModelInfo> = emptyList()
            override suspend fun presets(locale: String?): List<Preset> = emptyList()
            override suspend fun savePreset(p: Preset) {}
            override suspend fun deletePreset(id: String) {}
            override suspend fun tools(locale: String?): List<ToolInfo> = emptyList()
            override suspend fun setConfigKey(key: String, value: String) {}
            override suspend fun config(key: String): String = ""
            override suspend fun toolConfig(): Map<String, Any?> = emptyMap()
        }
    }

    // ---- scenario manifest (mirrors conformance/scenarios.json) ----
    private val scenarioIds = listOf(
        "boot_empty", "happy_path", "replay_reorder", "multi_step", "eid_dedup",
        "reconnect_persisted", "tool_error", "model_error", "send_failure", "error_transient",
    )

    private var eid = 0
    private fun ev(event: String, params: Map<String, Any?>, eid: String = "e${this.eid++}") =
        StreamEvent(event = event, params = params, eid = eid, runId = "r1")

    private fun msg(id: String, role: String, prevId: String, parts: List<MessagePart>) =
        Message(id = id, role = role, prevId = prevId, parts = parts, source = "")

    private fun text(id: String, t: String) = MessagePart(id = id, type = "text", text = t)
    private fun reasoning(id: String, t: String) = MessagePart(id = id, type = "reasoning", text = t)
    /** The domain part the real mapper produces from a persisted `tool_result`
     *  wire part (no output is carried on the server row). */
    private fun tool(id: String, name: String) =
        MessagePart(id = id, type = "tool", tool = name, state = ToolState(status = "complete"))

    @Test
    fun conformanceScenarios() {
        val manifest = javaClass.classLoader
            ?.getResourceAsStream("conformance/scenarios.json")
            ?.readBytes()?.decodeToString()
        assertNotNull(manifest, "vendored conformance manifest missing")
        for (id in scenarioIds) assertNotNull(manifest!!.contains(id)) { "manifest missing $id" }

        for (id in scenarioIds) replay(id)
    }

    private fun replay(id: String) {
        runTest {
            val server = FakeServer()
            // backgroundScope: the controller's idle-probe / reconnect loops are
            // cancelled when the test body ends (they never complete on their own).
            val ctrl = MessagesController(server.api(), { "s1" }, null, backgroundScope)
            ctrl.init()
            runCurrent()
            suspend fun settle() { repeat(30) { runCurrent() } }
            fun snap() = ctrl.messages.toList()

            when (id) {
                "boot_empty" -> {
                    settle()
                    assertEquals(0, snap().size, id)
                    assertEquals(false, ctrl.sending, id)
                }

                "happy_path" -> {
                    ctrl.send("hi"); settle()
                    server.push(ev("status", mapOf("type" to "busy")))
                    server.persist(msg("u1", "user", "", listOf(text("p0", "hi"))))
                    server.push(ev("message-added", mapOf("message_id" to "u1", "prev_id" to "", "role" to "user")))
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "u1", "role" to "assistant", "streaming" to true)))
                    server.push(ev("text-start", mapOf("id" to "t0", "message_id" to "a1")))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Hello")))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to " world")))
                    server.push(ev("tool-input-start", mapOf("id" to "tc1", "toolName" to "web.search")))
                    server.push(ev("tool-input-delta", mapOf("id" to "tc1", "delta" to "{\"q\":\"x\"}")))
                    server.push(ev("tool-call", mapOf("toolCallId" to "tc1", "toolName" to "web.search", "input" to mapOf("q" to "x"))))
                    server.push(ev("tool-result", mapOf("toolCallId" to "tc1", "output" to "found 1")))
                    settle()
                    // Mid-stream checkpoint: one user + one streaming assistant,
                    // the tool part complete with its live output.
                    assertEquals(listOf("u1", "a1"), snap().map { it.id }, id)
                    val mid = snap().first { it.id == "a1" }
                    assertEquals("streaming", mid.status, id)
                    assertEquals("Hello world", mid.parts.first { it.type == "text" }.text, id)
                    val toolPart = mid.parts.first { it.type == "tool" }
                    assertEquals("complete", toolPart.state?.status, id)
                    assertEquals("found 1", toolPart.state?.output, id)
                    assertEquals(true, ctrl.sending, id)

                    server.persist(msg("a1", "assistant", "u1", listOf(text("t0", "Hello world"), tool("tc1", "web.search"))))
                    server.push(ev("turn-complete", mapOf("reason" to "stop")))
                    settle()
                    assertEquals(false, ctrl.sending, id)
                    assertEquals(listOf("u1", "a1"), snap().map { it.id }, id)
                    val a1 = snap().first { it.id == "a1" }
                    assertEquals("complete", a1.status, id)
                    assertEquals(false, a1.isLocal, id)
                    assertEquals(1, snap().count { it.id == "a1" }, id)
                }

                "replay_reorder" -> {
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Hi", "message_id" to "a1")))
                    settle()
                    assertEquals(1, snap().count { it.id == "a1" }, id)
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "", "role" to "assistant", "streaming" to true)))
                    settle()
                    val hits = snap().filter { it.id == "a1" }
                    assertEquals(1, hits.size, id)
                    assertEquals("Hi", hits[0].parts.map { it.text }.joinToString(""), id)
                    assertEquals("streaming", hits[0].status, id)
                }

                "multi_step" -> {
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "", "role" to "assistant", "streaming" to true)))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "step one", "message_id" to "a1")))
                    settle()
                    assertEquals("streaming", snap().first { it.id == "a1" }.status, id)
                    server.persist(msg("a1", "assistant", "", listOf(text("t0", "step one"))))
                    server.push(ev("message-added", mapOf("message_id" to "a2", "prev_id" to "a1", "role" to "assistant", "streaming" to true)))
                    server.push(ev("text-delta", mapOf("id" to "t1", "text" to "step two", "message_id" to "a2")))
                    settle()
                    assertEquals("complete", snap().first { it.id == "a1" }.status, id)
                    assertEquals("streaming", snap().first { it.id == "a2" }.status, id)
                    server.persist(msg("a2", "assistant", "a1", listOf(text("t1", "step two"))))
                    server.push(ev("turn-complete", mapOf("reason" to "stop")))
                    settle()
                    assertEquals(listOf("a1", "a2"), snap().map { it.id }, id)
                    assertEquals(true, snap().all { it.status == "complete" }, id)
                    assertEquals(true, snap().all { !it.isLocal }, id)
                }

                "eid_dedup" -> {
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "", "role" to "assistant", "streaming" to true)))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Ha"), eid = "dup-eid"))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Ha"), eid = "dup-eid"))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Ha"), eid = "dup-eid"))
                    settle()
                    assertEquals(1, snap().count { it.id == "a1" }, id)
                    assertEquals("Ha", snap().first { it.id == "a1" }.parts.map { it.text }.joinToString(""), id)
                }

                "reconnect_persisted" -> {
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "", "role" to "assistant", "streaming" to true)))
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Hello", "message_id" to "a1")))
                    server.push(ev("reasoning-delta", mapOf("id" to "r0", "text" to "think", "message_id" to "a1")))
                    settle()
                    server.persist(msg("a1", "assistant", "", listOf(text("srv-t0", "Hello"), reasoning("srv-r0", "think"))))
                    server.push(ev("turn-complete", mapOf("reason" to "stop")))
                    settle()
                    val before = snap().first { it.id == "a1" }
                    assertEquals(false, before.isLocal, id)
                    assertEquals(2, before.parts.size, id)
                    // Reconnect: the SAME deltas (stream ids) must be ignored.
                    server.push(ev("text-delta", mapOf("id" to "t0", "text" to "Hello", "message_id" to "a1")))
                    server.push(ev("reasoning-delta", mapOf("id" to "r0", "text" to "think", "message_id" to "a1")))
                    settle()
                    val after = snap().first { it.id == "a1" }
                    assertEquals(2, after.parts.size, id)
                    assertEquals(1, after.parts.count { it.type == "reasoning" }, id)
                    assertEquals("Hello", after.parts.filter { it.type == "text" }.map { it.text }.joinToString(""), id)
                }

                "tool_error" -> {
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "", "role" to "assistant", "streaming" to true)))
                    server.push(ev("tool-call", mapOf("toolCallId" to "tc9", "toolName" to "boom")))
                    server.push(ev("tool-error", mapOf("toolCallId" to "tc9", "error" to mapOf("message" to "kaput"))))
                    settle()
                    val toolPart = snap().first { it.id == "a1" }.parts.first { it.id == "tc9" }
                    assertEquals("error", toolPart.state?.status, id)
                    assertEquals("kaput", toolPart.state?.error, id)
                }

                "model_error" -> {
                    server.push(ev("status", mapOf("type" to "busy")))
                    server.push(ev("message-added", mapOf("message_id" to "a1", "prev_id" to "", "role" to "assistant", "streaming" to true)))
                    settle()
                    assertEquals(true, ctrl.sending, id)
                    server.push(ev("error", mapOf("error" to mapOf("message" to "upstream 500"))))
                    settle()
                    assertEquals(false, ctrl.sending, id)
                    val err = snap().first { it.role == "error" }
                    assertEquals("error", err.status, id)
                    assertEquals(true, err.isLocal, id)
                    assertEquals("model", err.errorKind, id)
                    assertEquals(true, err.parts.first().text.contains("upstream 500"), id)
                }

                "send_failure" -> {
                    server.failPrompt("mailbox down")
                    ctrl.send("hi"); settle()
                    assertEquals(false, ctrl.sending, id)
                    val err = snap().first { it.role == "error" }
                    assertEquals("send", err.errorKind, id)
                    assertEquals(true, err.parts.first().text.contains("mailbox down"), id)
                }

                "error_transient" -> {
                    server.failPrompt("mailbox down")
                    ctrl.send("hi"); settle()
                    assertEquals(true, snap().any { it.role == "error" }, id)
                    server.clearPromptError()
                    ctrl.send("again"); settle()
                    // The prior error was cleared BEFORE the RPC, and this send
                    // succeeded, so no error remains.
                    assertEquals(0, snap().count { it.role == "error" }, id)
                }
            }
            ctrl.dispose()
            server.chan.close()
        }
    }
}
