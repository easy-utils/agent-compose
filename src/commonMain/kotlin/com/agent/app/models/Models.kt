package com.agent.app.models

/** Bundled fallback copy of the server capability matrix (canonical api
 * types only). Used until ListProvidersCatalog answers. */
val FALLBACK_API_TYPE_CAPABILITIES: Map<String, List<String>> = mapOf(
    "openai-compatible" to listOf("text", "embedding", "image", "speech", "transcription", "realtime"),
    "openai" to listOf("text", "embedding", "image", "speech", "transcription", "realtime"),
    "anthropic" to listOf("text"),
    "deepseek" to listOf("text"),
    "google" to listOf("text"),
    "vercel-compatible-gateway" to listOf("text", "image", "video", "speech", "transcription", "embedding", "rerank", "realtime"),
    "cohere" to listOf("text", "rerank"),
)

/** The 8 first-class modalities, in section order. */
val MODEL_CAPABILITIES: List<String> = listOf(
    "text", "image", "video", "speech", "transcription", "embedding", "rerank", "realtime",
)


// Domain models — a direct port of flutter/lib/models.dart (the UI subset).

data class Session(
    val id: String,
    val org: String = "",
    val repo: String = "",
    val branch: String = "",
    val model: String = "",
    val variant: String = "",
    val preset: String = "",
    val tipId: String? = null,
    val maxTurns: Int? = null,
    val systemPrompt: String? = null,
    val locale: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val lastOutputTokens: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val unreadCount: Int? = null,
    val lastMessageAt: String = "",
    val lastMessagePreview: String = "",
    val messageSeq: Int = 0,
    /** Generic grouping key (empty = ungrouped). A subsession records its
     *  parent's session name here. */
    val group: String = "",
) {
    val sessionName: String get() = if (org.isNotEmpty()) "$org:$repo:$branch" else id

    fun copyWith(
        unreadCount: Int? = this.unreadCount,
        messageSeq: Int = this.messageSeq,
        model: String = this.model,
        variant: String = this.variant,
        preset: String = this.preset,
        lastMessagePreview: String = this.lastMessagePreview,
        group: String = this.group,
    ) = copy(
        unreadCount = unreadCount,
        messageSeq = messageSeq,
        model = model,
        variant = variant,
        preset = preset,
        lastMessagePreview = lastMessagePreview,
        group = group,
    )
}

data class ToolState(
    val status: String = "",
    val title: String = "",
    val input: Map<String, Any?>? = null,
    val output: String? = null,
    val error: String? = null,
    val data: Map<String, Any?>? = null,
    val changeId: String? = null,
    val diff: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    /** Raw streamed tool-argument JSON (tool-input-delta), shown live until the
     *  complete `input` arrives with `tool-call`. */
    val inputText: String? = null,
)

data class MessagePart(
    val id: String,
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    val toolCallId: String? = null,
    val state: ToolState? = null,
    val code: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val size: Int? = null,
)

data class Message(
    val id: String,
    val role: String,
    val parts: List<MessagePart>,
    val createdAt: String? = null,
    val prevId: String = "",
)

data class ChatPart(
    val id: String,
    val type: String,
    val text: String = "",
    val tool: String = "",
    val state: ToolState? = null,
    val code: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val size: Int? = null,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val status: String, // pending | streaming | complete | error
    val parts: List<ChatPart>,
    val createdAt: String = "",
    val seq: Int? = null,
    val prevId: String = "",
    val isLocal: Boolean = false,
)

enum class UploadState { IDLE, UPLOADING, DONE, ERROR }

data class UploadedFile(
    val code: String,
    val name: String? = null,
    val mime: String? = null,
    val size: Int? = null,
    val localPath: String = "",
    val uploadState: UploadState = UploadState.DONE,
    val error: String? = null,
) {
    val isUploading: Boolean get() = uploadState == UploadState.UPLOADING
    val hasError: Boolean get() = uploadState == UploadState.ERROR
}

/** File metadata from GetFileMeta. The optional media facts are populated
 *  (server-side, best-effort) only for supported image/video/audio files. */
data class FileMeta(
    val contentType: String? = null,
    val length: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbCode: String? = null,
    val thumbhash: String? = null,
)

data class ChatDraft(
    var text: String = "",
    var attachments: MutableList<UploadedFile> = mutableListOf(),
)

/** Reference to an attachment to open in the media viewer. */
data class AttachmentRef(val code: String, val name: String, val mime: String?)

data class MailboxEntry(
    val id: String,
    val msgType: String,
    val payload: String,
    val effectiveAt: String? = null,
    val status: String,
    val createdAt: String,
    val consumedAt: String? = null,
)

data class Preset(
    val id: String,
    val systemPrompt: String = "",
    val tools: List<String> = emptyList(),
    val maxTurns: Int = 25,
    val isSystem: Boolean = false,
)

data class ToolConfigField(
    val key: String,
    val label: String,
    val type: String,
    val placeholder: String = "",
)

data class ToolConfig(
    val name: String,
    val type: String,
    /** `value` (ordinary knob) or `model` (provider_id/model_id reference). */
    val kind: String = "value",
    /** When kind == "model": the modality the reference must match. */
    val capability: String = "",
    val enumValues: List<String> = emptyList(),
    val defaultValue: Any? = null,
    val description: String = "",
    val scope: String = "global",
) {
    val isModelRef: Boolean get() = kind == "model"
}

data class ToolInfo(
    val name: String,
    val description: String = "",
    val category: String = "",
    val parameters: Map<String, Any?>? = null,
    val configFields: List<ToolConfigField> = emptyList(),
    val config: List<ToolConfig> = emptyList(),
    val requiredConfig: List<String> = emptyList(),
)

data class ProviderModel(
    val id: String,
    val name: String,
    val contextLimit: Long? = 0,
    val modelType: String = "",
)

data class ProviderInfo(
    val providerId: String,
    /** The single modality this provider serves (semantic grouping). */
    val capability: String = "text",
    val apiType: String,
    val baseUrl: String,
    val apiKey: String,
    val headers: Map<String, String> = emptyMap(),
    val models: List<ProviderModel> = emptyList(),
)

data class ProviderDraft(
    val originalId: String? = null,
    var id: String = "",
    var capability: String = "text",
    var apiType: String = "openai-compatible",
    var baseUrl: String = "",
    var apiKey: String = "",
    var models: MutableList<ProviderModel> = mutableListOf(),
) {
    val isEdit: Boolean get() = originalId != null

    companion object {
        fun from(p: ProviderInfo) = ProviderDraft(
            originalId = p.providerId,
            id = p.providerId,
            capability = p.capability,
            apiType = p.apiType,
            baseUrl = p.baseUrl,
            apiKey = p.apiKey,
            models = p.models.toMutableList(),
        )
    }
}

data class ModelVariantInfo(val id: String, val name: String = "", val description: String = "")

data class ModelInfo(
    val id: String,
    val name: String,
    val providerId: String = "",
    val contextLimit: Long? = 0,
    val variants: List<ModelVariantInfo> = emptyList(),
)

/** Canonical "provider_id/model_id" reference. */
fun modelRefOf(m: ModelInfo): String = if (m.providerId.isNotEmpty()) "${m.providerId}/${m.id}" else m.id

data class BackendCfg(
    val name: String,
    val baseUrl: String,
    val token: String,
)

fun backendNameFor(baseUrl: String): String {
    // Extract the host without java.net (works on every KMP target).
    val noScheme = baseUrl.substringAfter("://", baseUrl)
    val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return host.ifEmpty { baseUrl }
}

// ---- stream events ----

data class StreamEvent(
    val event: String,
    val params: Map<String, Any?>,
    val eid: String = "",
    val runId: String = "",
)

data class SessionListEvent(
    val snapshot: Boolean,
    val upserts: List<Session>,
    val removed: List<String>,
)
