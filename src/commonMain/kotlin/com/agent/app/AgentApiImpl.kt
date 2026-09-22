package com.agent.app

import com.agent.app.models.MailboxEntry
import com.agent.app.models.MailboxPage
import com.agent.app.models.Message
import com.agent.app.models.MessagePart
import com.agent.app.models.FileMeta
import com.agent.app.models.Identity
import com.agent.app.models.ModelInfo
import com.agent.app.models.ModelVariantInfo
import com.agent.app.models.Preset
import com.agent.app.models.ProviderInfo
import com.agent.app.models.ProviderModel
import com.agent.app.models.Session
import com.agent.app.models.SessionListEvent
import com.agent.app.models.StreamEvent
import com.agent.app.models.ToolConfig
import com.agent.app.models.ToolConfigField
import com.agent.app.models.ToolInfo
import com.agent.app.models.ToolState
import com.agent.app.models.UploadedFile
import com.agent.app.net.buildAgentTransport
import agentsdk.AgentServiceClient
import agent.v1.CompactRequest
import agent.v1.CreateSessionRequest
import agent.v1.DeletePresetRequest
import agent.v1.DeleteProviderRequest
import agent.v1.DeleteSessionRequest
import agent.v1.FileRef
import agent.v1.ForkRequest
import agent.v1.GetConfigRequest
import agent.v1.GetFileMetaRequest
import agent.v1.GetFileRequest
import agent.v1.GetIdentityRequest
import agent.v1.GetSessionRequest
import agent.v1.GetToolConfigRequest
import agent.v1.IngestFileRequest
import agent.v1.InterruptRequest
import agent.v1.ListMessagesRequest
import agent.v1.ListModelsRequest
import agent.v1.ListPresetsRequest
import agent.v1.ListProvidersCatalogRequest
import agent.v1.ListProvidersRequest
import agent.v1.ListSessionsRequest
import agent.v1.ListToolsRequest
import agent.v1.MailboxRequest
import agent.v1.Provider
import agent.v1.ProviderModel as PbProviderModel
import agent.v1.Preset as PbPreset
import agent.v1.PromptRequest
import agent.v1.RegisterProviderRequest
import agent.v1.RenameRequest
import agent.v1.SetConfigRequest
import agent.v1.SetExtensionConfigRequest
import agent.v1.StateRequest
import agent.v1.TestProviderRequest
import agent.v1.UndoRequest
import agent.v1.UpdateSettingsRequest
import agent.v1.UpsertPresetRequest
import agent.v1.WatchSessionRequest
import agent.v1.WatchSessionsRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import pbandk.ByteArr
import pbandk.wkt.ListValue
import pbandk.wkt.Struct
import pbandk.wkt.Value

// AgentApiImpl — the single implementation of the common AgentApi facade over
// the easy-rpc typed client (agentsdk.AgentServiceClient) + pbandk messages.
// Compiled by EVERY target (desktop, android, wasmJs): the transport differs
// per platform (OkHttp vs Ktor fetch), the RPC surface and pb->model mapping
// do not.

fun valueToJson(v: Value?): Any? = when (v?.kind) {
    is Value.Kind.NullValue -> null
    is Value.Kind.NumberValue -> v.numberValue
    is Value.Kind.StringValue -> v.stringValue
    is Value.Kind.BoolValue -> v.boolValue
    is Value.Kind.StructValue -> structToJson(v.structValue)
    is Value.Kind.ListValue -> v.listValue?.values?.map { valueToJson(it) } ?: emptyList<Any?>()
    else -> null
}

fun structToJson(st: Struct?): Map<String, Any?> {
    if (st == null) return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in st.fields) out[k] = valueToJson(v)
    return out
}

fun jsonToValue(v: Any?): Value = when (v) {
    null -> Value(kind = Value.Kind.NullValue())
    is String -> Value(kind = Value.Kind.StringValue(v))
    is Boolean -> Value(kind = Value.Kind.BoolValue(v))
    is Int -> Value(kind = Value.Kind.NumberValue(v.toDouble()))
    is Long -> Value(kind = Value.Kind.NumberValue(v.toDouble()))
    is Float -> Value(kind = Value.Kind.NumberValue(v.toDouble()))
    is Double -> Value(kind = Value.Kind.NumberValue(v))
    is Map<*, *> -> Value(kind = Value.Kind.StructValue(structFromJson(v)))
    is List<*> -> Value(kind = Value.Kind.ListValue(ListValue(v.map { jsonToValue(it) })))
    else -> Value(kind = Value.Kind.StringValue(v.toString()))
}

private fun structFromJson(m: Map<*, *>): Struct =
    Struct(fields = m.entries.associate { it.key.toString() to jsonToValue(it.value) })

fun sessionFromPb(s: agent.v1.Session): Session = Session(
    id = s.name,
    org = s.org,
    repo = s.repo,
    branch = s.branch,
    model = s.model,
    variant = s.variant,
    preset = s.preset,
    tipId = s.tipId.ifEmpty { null },
    maxTurns = if (s.maxTurns == 0) null else s.maxTurns,
    systemPrompt = s.systemPrompt.ifEmpty { null },
    locale = s.locale.ifEmpty { null },
    inputTokens = s.inputTokens,
    outputTokens = s.outputTokens,
    totalTokens = s.totalTokens,
    lastInputTokens = s.lastInputTokens,
    lastOutputTokens = s.lastOutputTokens,
    createdAt = s.createdAt,
    updatedAt = s.updatedAt,
    unreadCount = s.unreadCount,
    lastMessageAt = s.lastMessageAt,
    lastMessagePreview = s.lastMessagePreview,
    messageSeq = s.messageSeq,
    group = s.group,
)

fun messageFromPb(m: agent.v1.Message): Message {
    val decoded = m.parts.map { it to decodeJson(it.data) }
    val results = HashMap<String, Map<String, Any?>>()
    for ((p, d) in decoded) {
        if (p.type == "tool_result") {
            (d["tool_use_id"] as? String)?.takeIf { it.isNotEmpty() }?.let { results[it] = d }
        }
    }
    val parts = ArrayList<MessagePart>()
    for ((p, d) in decoded) {
        when (p.type) {
            "text" -> parts.add(MessagePart(p.id, "text", text = d["text"] as? String ?: ""))
            "reasoning" -> parts.add(MessagePart(p.id, "reasoning", text = d["text"] as? String ?: ""))
            "summary", "compaction" -> parts.add(MessagePart(p.id, "compaction", text = d["summary"] as? String ?: ""))
            "file" -> parts.add(
                MessagePart(
                    p.id, "file",
                    code = d["code"] as? String ?: "",
                    name = d["name"] as? String ?: "",
                    mime = d["mime"] as? String,
                    size = (d["size"] as? Double)?.toInt(),
                ),
            )
            "tool" -> {
                val callId = (d["id"] as? String) ?: p.messageId
                val res = results[callId]
                val content = res?.get("content")
                parts.add(
                    MessagePart(
                        p.id, "tool",
                        tool = d["name"] as? String ?: "",
                        toolCallId = callId,
                        state = ToolState(
                            status = if (res != null) "complete" else "running",
                            title = d["name"] as? String ?: "",
                            input = d["input"] as? Map<String, Any?>,
                            output = content as? String,
                            data = res?.get("metadata") as? Map<String, Any?>,
                        ),
                    ),
                )
            }
            "tool_result" -> {
                val id = (d["tool_use_id"] as? String) ?: p.messageId
                if (results[id] != null && m.parts.any { it.type == "tool" }) {
                    // merged into its tool part above
                } else {
                    val content = d["content"]
                    parts.add(
                        MessagePart(
                            p.id, "tool", tool = "", toolCallId = id,
                            state = ToolState(status = "complete", output = content as? String),
                        ),
                    )
                }
            }
        }
    }
    return Message(id = m.id, role = m.role, parts = parts, createdAt = m.createdAt.ifEmpty { null }, prevId = m.prevId, source = m.source)
}

private fun decodeJson(data: String): Map<String, Any?> {
    if (data.isEmpty()) return emptyMap()
    return try {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(data)
        elToAny(el) as? Map<String, Any?> ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun elToAny(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive ->
        if (e.isString) e.content else e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
    is kotlinx.serialization.json.JsonArray -> e.map { elToAny(it) }
    is kotlinx.serialization.json.JsonObject -> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in e) out[k] = elToAny(v)
        out
    }
}

class AgentApiImpl private constructor(
    override val baseUrl: String,
    override val token: String,
    private val agent: AgentServiceClient,
) : AgentApi {
    companion object {
        fun create(baseUrl: String, token: String): AgentApi =
            AgentApiImpl(baseUrl, token, AgentServiceClient(buildAgentTransport(baseUrl, token)))
    }

    override suspend fun listSessions(): List<Session> =
        agent.listSessions(ListSessionsRequest()).sessions.map(::sessionFromPb)

    override suspend fun identity(): Identity {
        val r = agent.getIdentity(GetIdentityRequest())
        return Identity(tenant = r.tenant, tenantName = r.tenantName, role = r.role)
    }

    override suspend fun createSession(params: Map<String, Any?>): Session {
        val r = agent.createSession(
            CreateSessionRequest(
                name = params["name"] as? String ?: "",
                model = (params["model"] as? String) ?: "",
                variant = (params["variant"] as? String) ?: "",
                preset = (params["preset"] as? String) ?: "",
                org = (params["org"] as? String) ?: "",
                repo = (params["repo"] as? String) ?: "",
                branch = (params["branch"] as? String) ?: "",
            ),
        )
        return Session(id = r.sessionName)
    }

    override suspend fun getSession(id: String): Session? =
        agent.getSession(GetSessionRequest(id)).session?.let(::sessionFromPb)

    override suspend fun deleteSession(id: String) {
        agent.deleteSession(DeleteSessionRequest(id))
    }

    override suspend fun renameSession(id: String, name: String): Session? =
        agent.rename(RenameRequest(id = id, name = name)).session?.let(::sessionFromPb)

    override suspend fun prompt(id: String, prompt: String, attachments: List<String>): String {
        var messageId = ""
        agent.prompt(
            PromptRequest(
                id = id,
                prompt = prompt,
                attachments = attachments.map { FileRef(code = it) },
            ),
        ).collect { e ->
            if (e.event == "accepted") messageId = e.params["message_id"] ?: ""
        }
        return messageId
    }

    override suspend fun uploadFile(name: String, bytes: ByteArray): UploadedFile {
        val r = agent.ingestFile(IngestFileRequest(data = ByteArr(bytes), name = name))
        // The agent DERIVES the content type from the bytes; adopt its answer.
        return UploadedFile(code = r.code, name = name, mime = r.mime, size = bytes.size)
    }

    override suspend fun fetchFileBytes(code: String): ByteArray =
        agent.getFile(GetFileRequest(code)).data.array

    override suspend fun fileHead(code: String): FileMeta {
        val r = agent.getFileMeta(GetFileMetaRequest(code))
        return FileMeta(
            contentType = r.mime.ifEmpty { null },
            length = r.size.toLong(),
            width = r.width,
            height = r.height,
            durationMs = r.durationMs,
            thumbCode = r.thumbCode?.ifEmpty { null },
            thumbhash = r.thumbhash?.ifEmpty { null },
        )
    }

    override suspend fun messages(id: String, before: String?, limit: Int): Pair<List<Message>, Boolean> {
        val r = agent.listMessages(
            ListMessagesRequest(id = id, limit = limit, before = before ?: ""),
        )
        val msgs = r.messages.map(::messageFromPb)
        return msgs to (msgs.size >= limit)
    }

    override suspend fun messagesAfter(id: String, after: String, limit: Int): Triple<List<Message>, Boolean, String> {
        val r = agent.listMessages(ListMessagesRequest(id = id, limit = limit, after = after))
        return Triple(r.messages.map(::messageFromPb), r.resync, r.tipId)
    }

    override suspend fun settings(id: String, settings: Map<String, Any?>): Session? {
        // Only model / preset / locale / variant are client-editable (proto
        // v0.18 dropped max_turns/system_prompt/group from UpdateSettingsRequest;
        // those are governed by the preset). Empty model/preset mean "leave
        // unchanged"; locale/variant use '' to clear an override.
        return agent.updateSettings(
            UpdateSettingsRequest(
                id = id,
                model = (settings["model"] as? String) ?: "",
                preset = (settings["preset"] as? String) ?: "",
                locale = (settings["locale"] as? String) ?: "",
                variant = (settings["variant"] as? String) ?: "",
            ),
        ).session?.let(::sessionFromPb)
    }

    override suspend fun fork(id: String, branch: String): Session? =
        agent.fork(ForkRequest(id = id, name = branch)).session?.let(::sessionFromPb)

    override suspend fun revert(id: String, messageId: String?) {
        agent.undo(UndoRequest(id = id, messageId = messageId ?: ""))
    }

    override suspend fun interrupt(id: String): Boolean =
        agent.interrupt(InterruptRequest(id)).ok

    override suspend fun compact(id: String): Boolean =
        agent.compact(CompactRequest(id)).ok

    override suspend fun state(id: String): Pair<String, List<Any?>> {
        val st = structToJson(agent.state(StateRequest(id)).state)
        return ((st["status"] as? String) ?: "idle") to ((st["parts"] as? List<Any?>) ?: emptyList())
    }

    override suspend fun mailbox(id: String, before: String, limit: Int): MailboxPage {
        val r = agent.mailbox(MailboxRequest(id = id, before = before, limit = limit))
        return MailboxPage(
            hasMore = r.hasMore,
            entries = r.mailbox.map { m ->
                MailboxEntry(
                    id = m.id, msgType = m.msgType, payload = m.payload,
                    effectiveAt = m.effectiveAt.ifEmpty { null }, status = m.status,
                    createdAt = m.createdAt, consumedAt = m.consumedAt.ifEmpty { null },
                    source = m.source,
                )
            },
        )
    }

    override fun streamEvents(sessionId: String, since: String): Flow<StreamEvent> = flow {
        agent.watchSession(WatchSessionRequest(id = sessionId, since = since)).collect { e ->
            val params = structToJson(e.params)
            val runId = params["run_id"] as? String ?: ""
            emit(StreamEvent(e.event, params, e.eid, runId))
        }
    }

    override fun watchSessions(): Flow<SessionListEvent> = flow {
        agent.watchSessions(WatchSessionsRequest()).collect { e ->
            emit(
                SessionListEvent(
                    snapshot = e.snapshot,
                    upserts = e.upserts.map(::sessionFromPb),
                    removed = e.removed,
                ),
            )
        }
    }

    override suspend fun setToolConfigValue(extId: String, name: String, value: Any?) {
        agent.setExtensionConfig(
            SetExtensionConfigRequest(
                extId = extId, name = name,
                value = if (value == null) Value(kind = Value.Kind.NullValue())
                else Value(kind = Value.Kind.StringValue(value.toString())),
            ),
        )
    }

    override suspend fun providers(): Map<String, ProviderInfo> {
        val r = agent.listProviders(ListProvidersRequest())
        val out = LinkedHashMap<String, ProviderInfo>()
        for (p in r.providers) {
            out[p.providerId] = ProviderInfo(
                providerId = p.providerId,
                capability = p.capability,
                apiType = p.apiType,
                baseUrl = p.baseUrl,
                apiKey = p.apiKey,
                headers = p.headers,
                models = p.models.map { m ->
                    ProviderModel(id = m.id, name = m.name.ifEmpty { m.id }, contextLimit = m.contextLimit, modelType = m.modelType)
                },
            )
        }
        return out
    }

    override suspend fun providerCatalog(): Map<String, List<String>> =
        agent.listProvidersCatalog(ListProvidersCatalogRequest()).apiTypes
            .mapValues { (_, v) -> v?.capabilities ?: emptyList() }

    override suspend fun registerProvider(p: ProviderInfo) {
        agent.registerProvider(
            RegisterProviderRequest(
                provider = Provider(
                    providerId = p.providerId,
                    capability = p.capability,
                    apiType = p.apiType,
                    baseUrl = p.baseUrl,
                    apiKey = p.apiKey,
                    headers = p.headers,
                    models = p.models.map { m ->
                        PbProviderModel(id = m.id, name = m.name, contextLimit = m.contextLimit ?: 0, modelType = m.modelType)
                    },
                ),
            ),
        )
    }

    override suspend fun deleteProvider(pid: String) {
        agent.deleteProvider(DeleteProviderRequest(pid))
    }

    override suspend fun testProvider(
        apiType: String,
        baseUrl: String,
        apiKey: String,
        providerId: String,
        model: String?,
        capability: String,
    ): Pair<Boolean, String> {
        val r = agent.testProvider(
            TestProviderRequest(
                providerId = providerId, apiType = apiType,
                baseUrl = baseUrl, apiKey = apiKey,
                model = model ?: "", capability = capability,
            ),
        )
        return r.ok to r.result
    }

    override suspend fun models(providerId: String): List<ModelInfo> {
        if (providerId.isEmpty()) return emptyList()
        return agent.listModels(ListModelsRequest(providerId)).models.map { m ->
            ModelInfo(
                id = m.id, name = m.name, providerId = providerId,
                contextLimit = m.contextLimit,
                variants = m.variants.map { v -> ModelVariantInfo(v.id, v.name, v.description) },
            )
        }
    }

    override suspend fun presets(locale: String?): List<Preset> =
        agent.listPresets(ListPresetsRequest(locale ?: "")).presets.map { p ->
            Preset(
                id = p.id, systemPrompt = p.systemPrompt,
                tools = p.tools, maxTurns = p.maxTurns, isSystem = p.isSystem,
            )
        }

    override suspend fun savePreset(p: Preset) {
        agent.upsertPreset(
            UpsertPresetRequest(
                preset = PbPreset(
                    id = p.id, systemPrompt = p.systemPrompt,
                    maxTurns = p.maxTurns, tools = p.tools,
                ),
            ),        )
    }

    override suspend fun deletePreset(id: String) {
        agent.deletePreset(DeletePresetRequest(id))
    }

    override suspend fun tools(locale: String?): List<ToolInfo> =
        agent.listTools(ListToolsRequest(locale ?: "")).tools.map { t ->
            ToolInfo(
                name = t.name, description = t.description, category = t.category,
                parameters = structToJson(t.parameters).takeIf { it.isNotEmpty() },
                configFields = t.configFields.map { c ->
                    ToolConfigField(key = c.name, label = c.description.ifEmpty { c.name }, type = c.type)
                },
                config = t.configFields.map { c ->
                    ToolConfig(
                        name = c.name, type = c.type,
                        kind = c.kind, capability = c.capability,
                        enumValues = c.enumValues,
                        defaultValue = valueToJson(c.default),
                        description = c.description, scope = c.scope,
                    )
                },
                requiredConfig = t.requiredConfig,
            )
        }

    override suspend fun setConfigKey(key: String, value: String) {
        agent.setConfig(SetConfigRequest(key = key, value = value))
    }

    override suspend fun config(key: String): String =
        agent.getConfig(GetConfigRequest(key)).value

    override suspend fun toolConfig(): Map<String, Any?> {
        val cfg = agent.getToolConfig(GetToolConfigRequest()).config ?: return emptyMap()
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in cfg.values) m[k] = valueToJson(v)
        return m
    }
}
