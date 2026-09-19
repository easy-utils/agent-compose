package com.agent.app

import com.agent.app.models.ChatMessage
import com.agent.app.models.FileMeta
import com.agent.app.models.MailboxEntry
import com.agent.app.models.Message
import com.agent.app.models.ModelInfo
import com.agent.app.models.Preset
import com.agent.app.models.ProviderInfo
import com.agent.app.models.ProviderModel
import com.agent.app.models.Session
import com.agent.app.models.SessionListEvent
import com.agent.app.models.StreamEvent
import com.agent.app.models.ToolInfo
import com.agent.app.models.UploadedFile
import kotlinx.coroutines.flow.Flow

// AgentApi — the common-facing facade (port of flutter api.dart). The JVM
// implementation over agent-sdk-kotlin lives in jvmShared; every target
// (desktop + android) compiles the SAME impl.

interface AgentApi {
    val baseUrl: String
    val token: String

    suspend fun listSessions(): List<Session>
    suspend fun createSession(params: Map<String, Any?>): Session
    suspend fun getSession(id: String): Session?
    suspend fun deleteSession(id: String)
    suspend fun renameSession(id: String, name: String): Session?
    suspend fun prompt(id: String, prompt: String, attachments: List<String> = emptyList()): String

    suspend fun uploadFile(name: String, bytes: ByteArray): UploadedFile
    suspend fun fetchFileBytes(code: String): ByteArray
    suspend fun fileHead(code: String): FileMeta

    suspend fun messages(id: String, before: String? = null, limit: Int = 30): Pair<List<Message>, Boolean>
    suspend fun messagesAfter(id: String, after: String, limit: Int = 200): Triple<List<Message>, Boolean, String>

    suspend fun settings(id: String, settings: Map<String, Any?>): Session?
    suspend fun fork(id: String, branch: String): Session?
    suspend fun revert(id: String, messageId: String?)
    suspend fun interrupt(id: String): Boolean
    suspend fun compact(id: String): Boolean
    suspend fun state(id: String): Pair<String, List<Any?>>
    suspend fun mailbox(id: String): List<MailboxEntry>

    fun streamEvents(sessionId: String, since: String = ""): Flow<StreamEvent>
    fun watchSessions(): Flow<SessionListEvent>

    suspend fun setToolConfigValue(extId: String, name: String, value: Any?)
    suspend fun providers(): Map<String, ProviderInfo>
    /** Server capability matrix (ListProvidersCatalog): api type -> capabilities. */
    suspend fun providerCatalog(): Map<String, List<String>>
    suspend fun registerProvider(p: ProviderInfo)
    suspend fun deleteProvider(pid: String)
    suspend fun testProvider(apiType: String, baseUrl: String, apiKey: String, providerId: String = "", model: String? = null, capability: String = "text"): Pair<Boolean, String>
    suspend fun models(providerId: String): List<ModelInfo>
    suspend fun presets(locale: String? = null): List<Preset>
    suspend fun savePreset(p: Preset)
    suspend fun deletePreset(id: String)
    suspend fun tools(locale: String? = null): List<ToolInfo>
    suspend fun setConfigKey(key: String, value: String)
    suspend fun config(key: String): String
    suspend fun toolConfig(): Map<String, Any?>
}

/** Map server history messages to chat-domain bubbles (pure domain logic). */
fun mapMessagesToChat(msgs: List<Message>): List<ChatMessage> = msgs.mapIndexed { i, m ->
    ChatMessage(
        id = m.id,
        role = m.role,
        status = "complete",
        createdAt = m.createdAt ?: "",
        seq = i,
        parts = m.parts.map { p ->
            com.agent.app.models.ChatPart(
                id = p.id,
                type = p.type,
                text = p.text ?: "",
                tool = p.tool ?: "",
                state = p.state,
                code = p.code,
                name = p.name,
                mime = p.mime,
                size = p.size,
            )
        },
    )
}
