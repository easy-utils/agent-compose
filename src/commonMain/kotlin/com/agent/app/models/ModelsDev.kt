package com.agent.app.models

import com.agent.app.platform.httpGetText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// ModelsDev — port of flutter `services/models_dev.dart` / webui
// `lib/modelsdev.ts`: the models.dev provider catalogue (served by the
// models.opencode.ai mirror) used to prefill the Add-Provider form with a
// provider's default base URL, API type (from the SDK package name) and model
// catalogue.
//
// The document is ~4.7 MB, so it is cached in memory for [TTL_MS]. (Flutter
// persists it in SharedPreferences and webui in CacheStorage; neither store is
// available/appropriate on every Compose target — java.util.prefs caps a value
// at 8 KB — so this client keeps the optimisation in-process. The user only
// pays the fetch when they actually open the template picker.)

private const val URL = "https://models.opencode.ai/api.json"
private const val TTL_MS = 60L * 60L * 1000L

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private var cached: List<MdProvider>? = null
private var cachedAt: Long = 0L

/** One model of a template provider. */
data class MdModel(
    val id: String,
    val name: String,
    val contextLimit: Int?,
    val output: List<String>,
    val attachment: Boolean = false,
) {
    /** The generation capability derived from the output modalities, matching
     *  flutter `MdModel.capability` (video wins, then image-only, then
     *  audio-only, else text). */
    val capability: String
        get() = when {
            output.contains("video") -> "video"
            output.contains("image") && !output.contains("text") -> "image"
            output.contains("audio") && !output.contains("text") -> "speech"
            else -> "text"
        }
}

/** One template provider (a models.dev entry). */
data class MdProvider(
    val id: String,
    val name: String,
    val npm: String,
    val api: String,
    val models: List<MdModel>,
)

object ModelsDev {
    /**
     * Load the catalogue (in-memory cached for [TTL_MS]; [forceRefresh]
     * bypasses the cache). Throws on a fetch/parse failure — the caller shows a
     * toast, exactly like flutter/webui.
     */
    suspend fun load(forceRefresh: Boolean = false): List<MdProvider> {
        val now = com.agent.app.util.nowMillis()
        val hit = cached
        if (!forceRefresh && hit != null && now - cachedAt <= TTL_MS) return hit
        val providers = parse(httpGetText(URL))
        cached = providers
        cachedAt = now
        return providers
    }

    /** models.dev `npm` package -> the platform api_type (flutter `npmToType`). */
    fun npmToType(npm: String): String = when {
        npm.isEmpty() || npm.contains("openai-compatible") -> "openai-compatible"
        npm.contains("anthropic") -> "anthropic"
        npm.contains("openai") -> "openai"
        npm.contains("google") || npm.contains("gemini") -> "gemini"
        else -> npm
            .replaceFirst("@ai-sdk/", "")
            .replaceFirst(Regex("-ai-sdk-provider$"), "")
    }

    private fun parse(body: String): List<MdProvider> {
        val root = json.parseToJsonElement(body).jsonObject
        val out = ArrayList<MdProvider>(root.size)
        for ((key, value) in root) {
            val o = value as? JsonObject ?: continue
            val models = ArrayList<MdModel>()
            (o["models"] as? JsonObject)?.forEach { (mid, mv) ->
                val mo = mv as? JsonObject ?: return@forEach
                val limit = (mo["limit"] as? JsonObject)?.get("context").asIntOrNull()
                val output = ((mo["modalities"] as? JsonObject)?.get("output") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                models.add(
                    MdModel(
                        id = (mo["id"] as? JsonPrimitive)?.contentOrNull ?: mid,
                        name = (mo["name"] as? JsonPrimitive)?.contentOrNull ?: mid,
                        contextLimit = limit,
                        output = output,
                        attachment = (mo["attachment"] as? JsonPrimitive)?.contentOrNull == "true",
                    ),
                )
            }
            out.add(
                MdProvider(
                    id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: key,
                    name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: key,
                    npm = (o["npm"] as? JsonPrimitive)?.contentOrNull ?: "",
                    api = (o["api"] as? JsonPrimitive)?.contentOrNull ?: "",
                    models = models,
                ),
            )
        }
        out.sortBy { it.name.lowercase() }
        return out
    }
}

private fun kotlinx.serialization.json.JsonElement?.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
