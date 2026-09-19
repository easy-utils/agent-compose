package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.agent.app.i18n.I18n.t
import com.agent.app.models.MODEL_CAPABILITIES
import com.agent.app.models.MdProvider
import com.agent.app.models.ModelsDev
import com.agent.app.models.ProviderDraft
import com.agent.app.models.ProviderInfo
import com.agent.app.models.ProviderModel
import com.agent.app.store.AppPage
import com.agent.app.store.AppStore
import kotlinx.coroutines.launch

/** Localized label for an api type tag (falls back to the raw tag). */
fun apiTypeLabel(tag: String): String = when (tag) {
    "openai-compatible" -> t("apiTypeOpenaiCompat")
    "openai" -> t("apiTypeOpenai")
    "anthropic" -> t("apiTypeAnthropic")
    "gemini", "google" -> t("apiTypeGemini")
    "deepseek" -> t("apiTypeDeepseek")
    "cohere" -> t("apiTypeCohere")
    "vercel-compatible-gateway" -> t("apiTypeGateway")
    else -> tag
}

// ProvidersListScreen — port of flutter ProvidersListScreen.

@Composable
fun ProvidersListScreen(store: AppStore, showBack: Boolean) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<Map<String, ProviderInfo>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var defaultModel by remember { mutableStateOf("") }
    var pickDefault by remember { mutableStateOf(false) }
    var seenRevision by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        try {
            defaultModel = store.api.config("default_model")
        } catch (_: Exception) {
        }
        try {
            providers = store.api.providers()
        } catch (_: Exception) {
        }
        loading = false
    }
    LaunchedEffect(store.providersRevision) {
        if (seenRevision != -1 && seenRevision != store.providersRevision) {
            try {
                providers = store.api.providers()
            } catch (_: Exception) {
            }
        }
        seenRevision = store.providersRevision
    }

    val all = providers.values.toList()
    // One flat list: every provider (gateways included) is an ordinary row.
    val allProviders = all.sortedBy { it.providerId }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) AppIcon(AppIcons.back, contentDescription = t("back"), tint = colors.foreground, onClick = { store.popPage() })
            Text(t("llmProviders"), style = AppText.title, modifier = Modifier.weight(1f))
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (allProviders.isEmpty()) {
                item { Text(t("noProviders"), color = colors.mutedForeground, style = AppText.meta, modifier = Modifier.padding(horizontal = AppSpacing.LG.dp)) }
            }
            // ONE SECTION PER MODALITY (semantic grouping). Only TEXT carries
            // the tenant default model.
            for (cap in MODEL_CAPABILITIES) {
                item(key = "hdr-$cap") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = AppSpacing.LG.dp, end = AppSpacing.SM.dp, top = AppSpacing.MD.dp, bottom = AppSpacing.XS.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t(capabilityLabelKey(cap)).uppercase(),
                            style = AppText.micro,
                            color = colors.mutedForeground,
                            modifier = Modifier.weight(1f),
                        )
                        AppIcon(
                            AppIcons.add,
                            contentDescription = t("addProvider"),
                            tint = colors.primary,
                            size = 18.dp,
                            onClick = {
                                store.beginProviderDraft(null, cap)
                                store.pushPage(AppPage.ProviderFormPage)
                            },
                        )
                    }
                }
                if (cap == "text") {
                    item(key = "default") {
                        Row(
                            Modifier.fillMaxWidth().appClickable { pickDefault = true }.padding(horizontal = AppSpacing.LG.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.star, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(AppSpacing.MD.dp))
                            Column {
                                Text(t("defaultModel"), style = AppText.body)
                                Text(defaultModel.ifEmpty { t("none") }, style = AppText.micro, color = colors.mutedForeground, maxLines = 1)
                            }
                        }
                    }
                }
                val rows = allProviders.filter { it.capability == cap }
                items(rows, key = { it.providerId }) { p ->
                    Row(
                        Modifier.fillMaxWidth().appClickable {
                            store.beginProviderDraft(p)
                            store.pushPage(AppPage.ProviderFormPage)
                        }.padding(horizontal = AppSpacing.LG.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(capabilityIcon(cap), contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(AppSpacing.MD.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.providerId, style = AppText.body)
                            Text("${apiTypeLabel(p.apiType)} · ${t("modelsCount", p.models.size)}", style = AppText.micro, color = colors.mutedForeground)
                        }
                        Icon(AppIcons.chevron_right, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (pickDefault) {
        val refs = allProviders.filter { it.capability == "text" }.flatMap { p ->
            p.models.filter { (it.contextLimit ?: 0) > 0 }.map { "${p.providerId}/${it.id}" }
        }.sorted().let {
            if (defaultModel.isNotEmpty() && it.none { r -> r == defaultModel }) listOf(defaultModel) + it else it
        }
        ActionSheet(
            title = t("defaultModel"),
            actions = (listOf("") + refs).map { ref ->
                (ref.ifEmpty { t("none") }) to {
                    pickDefault = false
                    if (ref != defaultModel) {
                        scope.launch {
                            try {
                                store.api.setConfigKey("default_model", ref)
                                defaultModel = ref
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            },
            onDismiss = { pickDefault = false },
        )
    }
}

// ProviderFormScreen — port of flutter ProviderFormScreen.

@Composable
fun ProviderFormScreen(store: AppStore, showBack: Boolean) {
    val draft = store.providerDraft ?: return
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(draft.id) }
    var url by remember { mutableStateOf(draft.baseUrl) }
    var key by remember { mutableStateOf(draft.apiKey) }
    var apiType by remember { mutableStateOf(draft.apiType) }
    var busy by remember { mutableStateOf(false) }
    // Register/test failures rendered inline (same feedback the toast gives
    // the flutter/webui clients).
    var registerError by remember { mutableStateOf("") }
    // models.dev provider-template picker (parity with flutter/webui).
    var templateOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                AppIcons.back,
                contentDescription = t("back"),
                tint = colors.foreground,
                onClick = {
                    store.endProviderDraft()
                    store.popPage()
                },
            )
            Text(if (draft.isEdit) t("settingsTitle") else t("addProvider"), style = AppText.title)
        }
        LazyColumn(Modifier.fillMaxSize().padding(AppSpacing.LG.dp)) {
            item {
                // Template (models.dev) picker — same affordance as flutter's
                // `_pickTemplate` row / webui's ProviderForm template button.
                Row(
                    Modifier.fillMaxWidth().appClickable(shape = AppRadius.md) { templateOpen = true }
                        .border(1.dp, colors.border.copy(alpha = 0.6f), AppRadius.md)
                        .padding(horizontal = AppSpacing.MD.dp, vertical = AppSpacing.SM.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.server, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(AppSpacing.SM.dp))
                    Text(t("providerTemplate"), style = AppText.label, modifier = Modifier.weight(1f))
                    Text(t("providerTemplateHint"), style = AppText.micro, color = colors.mutedForeground)
                }
                Spacer(Modifier.height(AppSpacing.MD.dp))
                AppTextField(value = id, onValueChange = { id = it; draft.id = it }, enabled = !draft.isEdit, singleLine = true, label = t("providerIdReq"), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(AppSpacing.MD.dp))
                AppSelect(
                    label = t("apiType"),
                    // Only the protocols whose wire format serves this modality.
                    options = store.providerCatalog.filterValues { it.contains(draft.capability) }
                        .keys.sorted().ifEmpty { setOf("openai-compatible") }
                        .map { it to apiTypeLabel(it) },
                    value = apiType,
                    onSelect = { apiType = it; draft.apiType = it },
                )
                Spacer(Modifier.height(AppSpacing.MD.dp))
                AppTextField(value = url, onValueChange = { url = it; draft.baseUrl = it }, singleLine = true, label = t("baseUrlReq"), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(AppSpacing.MD.dp))
                AppTextField(
                    value = key,
                    onValueChange = { key = it; draft.apiKey = it },
                    singleLine = true,
                    label = t("apiKeyReq"),
                    isPassword = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AppSpacing.LG.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("modelsLabel"), style = AppText.label, modifier = Modifier.weight(1f))
                    AppIcon(
                        AppIcons.add,
                        contentDescription = t("addModel"),
                        tint = colors.primary,
                        onClick = { store.pushPage(AppPage.ProviderModelsPage(null)) },
                    )
                }
            }
            items(draft.models.toList(), key = { it.id }) { m ->
                ModelRow(m, { store.pushPage(AppPage.ProviderModelsPage(m.id)) }) {
                    draft.models.remove(m)
                    store.bumpSessionRevision()
                }
            }
            item {
                Spacer(Modifier.height(AppSpacing.LG.dp))
                if (registerError.isNotEmpty()) {
                    Toast(registerError, error = true)
                    Spacer(Modifier.height(AppSpacing.SM.dp))
                }
                Box(
                    Modifier.fillMaxWidth().height(40.dp).appClickable(enabled = id.isNotBlank() && url.isNotBlank() && !busy, shape = AppRadius.md, hoverWash = false) {
                        scope.launch {
                            busy = true
                            registerError = ""
                            try {
                                store.api.registerProvider(
                                    ProviderInfo(id.trim(), draft.capability, apiType, url.trim(), key, emptyMap(), draft.models.toList()),
                                )
                                store.bumpProvidersRevision()
                                store.endProviderDraft()
                                store.popPage()
                            } catch (e: Exception) {
                                // Surface the failure (bad base URL / key) —
                                // flutter/webui toast it; silently un-busying
                                // the button reads as "nothing happened".
                                registerError = e.message ?: e.toString()
                            }
                            busy = false
                        }
                    }.background(colors.primary, AppRadius.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (busy) t("registering") else if (draft.isEdit) t("save") else t("register"), color = colors.onPrimary, style = AppText.body)
                }
            }
        }
    }

    if (templateOpen) {
        TemplatePickerDialog(
            onDismiss = { templateOpen = false },
            onPick = { p ->
                templateOpen = false
                // Prefill exactly like flutter `_pickTemplate` / webui
                // `pickTemplate`: id, base URL, api type and the model list.
                val newId = p.id.ifEmpty { p.name.lowercase().replace(Regex("\\s+"), "-") }
                id = newId
                draft.id = newId
                if (p.api.isNotEmpty()) {
                    url = p.api
                    draft.baseUrl = p.api
                }
                apiType = ModelsDev.npmToType(p.npm)
                draft.apiType = apiType
                val isText = draft.capability == "text"
                draft.models.clear()
                draft.models.addAll(
                    p.models.map { m ->
                        ProviderModel(
                            id = m.id,
                            name = m.name,
                            contextLimit = if (isText) (m.contextLimit?.toLong() ?: 0L) else 0L,
                            modelType = if (isText) "text" else draft.capability,
                        )
                    },
                )
                store.bumpSessionRevision()
            },
        )
    }
}

/**
 * models.dev provider picker (bottom sheet in flutter/webui; a centered dialog
 * here — Compose Multiplatform has no built-in modal bottom sheet in the
 * common UI). Search by provider name / npm package / model id, exactly like
 * the web filter.
 */
@Composable
private fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onPick: (MdProvider) -> Unit,
) {
    val colors = LocalAppColors.current
    var all by remember { mutableStateOf<List<MdProvider>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            all = ModelsDev.load()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }

    val filtered = remember(all, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) all
        else all.filter { p ->
            p.name.lowercase().contains(needle) ||
                p.npm.lowercase().contains(needle) ||
                p.models.any { it.id.lowercase().contains(needle) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.appCard().padding(AppSpacing.LG.dp).height(520.dp).fillMaxWidth(),
        ) {
            Text(t("providerTemplate"), style = AppText.title)
            Spacer(Modifier.height(AppSpacing.SM.dp))
            AppTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = t("searchModels"),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpacing.SM.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                error.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error, style = AppText.meta, color = colors.destructive)
                }
                else -> LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(filtered, key = { i, p -> "$i:${p.npm}:${p.id}" }) { _, p ->
                        Row(
                            Modifier.fillMaxWidth().appClickable(shape = AppRadius.sm) { onPick(p) }
                                .padding(horizontal = AppSpacing.SM.dp, vertical = AppSpacing.SM.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.server, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(AppSpacing.SM.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = AppText.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                Text(p.id, style = AppText.micro, color = colors.mutedForeground, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelRow(m: ProviderModel, onTap: () -> Unit, onRemove: () -> Unit) {
    val colors = LocalAppColors.current
    val isText = m.modelType == "text" || m.modelType.isEmpty()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .appClickable(onTap = onTap)
            .padding(horizontal = AppSpacing.MD.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(capabilityIcon(if (isText) "text" else m.modelType), contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(AppSpacing.SM.dp))
        Text(
            m.id, style = AppText.mono.copy(fontFamily = LocalAppMonoFamily.current),
            modifier = Modifier.weight(1f), maxLines = 1,
        )
        Text(
            if (isText) "${t("capText")} · ${m.contextLimit}" else t(capabilityLabelKey(m.modelType)),
            style = AppText.micro, color = colors.mutedForeground,
        )
        AppIcon(AppIcons.close, contentDescription = t("delete"), tint = colors.mutedForeground, size = 16.dp, onClick = onRemove)
    }
}

// ProviderModelScreen — single TEXT model entry form + live test.

@Composable
fun ProviderModelScreen(store: AppStore, modelId: String?, showBack: Boolean) {
    val draft = store.providerDraft ?: return
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val existing = draft.models.firstOrNull { it.id == modelId }
    var mid by remember { mutableStateOf(existing?.id ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var ctx by remember { mutableStateOf(existing?.contextLimit?.takeIf { it > 0 }?.toString() ?: "") }
    // The model's modality IS the provider's (semantic grouping): read-only.
    val capability = draft.capability
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(AppIcons.back, contentDescription = t("back"), tint = colors.foreground, onClick = { store.popPage() })
            Text(if (modelId != null) t("editModel") else t("addModel"), style = AppText.title, modifier = Modifier.weight(1f))
            Text(
                t("save"),
                Modifier.appClickable(enabled = mid.isNotBlank(), hoverWash = false) {
                    // context_limit is required (> 0) only for TEXT models.
                    val c = if (capability == "text") (ctx.toLongOrNull() ?: 0) else 0
                    if (capability == "text" && c <= 0) return@appClickable
                    if (modelId != null) draft.models.removeAll { it.id == modelId }
                    draft.models.removeAll { it.id == mid.trim() }
                    draft.models.add(ProviderModel(mid.trim(), name.trim().ifEmpty { mid.trim() }, c, capability))
                    store.popPage()
                }.padding(6.dp),
                color = colors.primary,
            )
        }
        Column(Modifier.fillMaxSize().padding(AppSpacing.LG.dp)) {
            AppTextField(value = mid, onValueChange = { mid = it }, singleLine = true, label = t("modelIdReq"), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppSpacing.MD.dp))
            AppTextField(value = name, onValueChange = { name = it }, singleLine = true, label = t("modelName"), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppSpacing.MD.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(capabilityIcon(capability), contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Text(t(capabilityLabelKey(capability)), style = AppText.meta, color = colors.mutedForeground)
            }
            if (capability == "text") {
                Spacer(Modifier.height(AppSpacing.MD.dp))
                AppTextField(value = ctx, onValueChange = { ctx = it }, singleLine = true, label = t("contextLengthLabel"), modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(AppSpacing.MD.dp))
                Text(t("nonTextModelHint"), style = AppText.micro, color = colors.mutedForeground)
            }
            Spacer(Modifier.height(AppSpacing.LG.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.flask,
                contentDescription = null,
                tint = if (testing) colors.mutedForeground else colors.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Text(
                t("testModel"),
                Modifier.appClickable(enabled = mid.isNotBlank() && !testing) {
                    scope.launch {
                        testing = true
                        testMsg = ""
                        try {
                            val (ok, result) = store.api.testProvider(
                                draft.apiType, draft.baseUrl, draft.apiKey, draft.id,
                                "${draft.id}/${mid.trim()}", capability,
                            )
                            testMsg = (if (ok) t("testModelOk", result) else result.ifEmpty { t("testFailed") })
                        } catch (e: Exception) {
                            testMsg = e.message ?: e.toString()
                        }
                        testing = false
                    }
                }.padding(4.dp),
                color = colors.primary, style = AppText.small,
            )
            }
            if (testMsg.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.SM.dp))
                Text(testMsg, style = AppText.micro.copy(fontFamily = LocalAppMonoFamily.current), maxLines = 6)
            }
        }
    }
}

// PresetFormScreen — port of flutter preset_form.dart.

@Composable
fun PresetFormScreen(store: AppStore, showBack: Boolean) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var sysPrompt by remember { mutableStateOf("") }
    var maxTurns by remember { mutableStateOf("25") }
    var tools by remember { mutableStateOf<List<com.agent.app.models.ToolInfo>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            tools = store.api.tools(null)
        } catch (_: Exception) {
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(AppIcons.back, contentDescription = t("back"), tint = colors.foreground, onClick = { store.popPage() })
            Text(t("newPreset"), style = AppText.title, modifier = Modifier.weight(1f))
            Text(
                t("save"),
                Modifier.appClickable(enabled = id.isNotBlank() && !saving, hoverWash = false) {
                    scope.launch {
                        saving = true
                        try {
                            store.api.savePreset(
                                com.agent.app.models.Preset(id.trim(), sysPrompt, selected.toList(), maxTurns.toIntOrNull() ?: 25),
                            )
                            store.popPage()
                        } catch (_: Exception) {
                        }
                        saving = false
                    }
                }.padding(6.dp),
                color = colors.primary,
            )
        }
        Column(Modifier.fillMaxSize().padding(AppSpacing.LG.dp), verticalArrangement = Arrangement.spacedBy(AppSpacing.MD.dp)) {
            AppTextField(value = id, onValueChange = { id = it }, singleLine = true, label = t("presetId"), modifier = Modifier.fillMaxWidth())
            AppTextField(value = sysPrompt, onValueChange = { sysPrompt = it }, label = t("systemPrompt"), minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            AppTextField(value = maxTurns, onValueChange = { maxTurns = it }, singleLine = true, label = t("maxTurns"), modifier = Modifier.fillMaxWidth())
            Text("${t("tools")} · ${selected.size}", style = AppText.meta)
            LazyColumn {
                items(tools, key = { it.name }) { tl ->
                    val on = tl.name in selected
                    Text(
                        tl.name,
                        style = AppText.micro,
                        color = if (on) colors.primary else colors.mutedForeground,
                        modifier = Modifier
                            .appClickable(
                                onTap = {
                                    selected = if (on) selected - tl.name else selected + tl.name
                                },
                            )
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}



/** Localized capability label key (flutter capabilityLabel). */
fun capabilityLabelKey(capability: String): String = when (capability) {
    "image" -> "capImage"
    "video" -> "capVideo"
    "speech" -> "capSpeech"
    "transcription" -> "capTranscription"
    "embedding" -> "capEmbedding"
    "reranking", "rerank" -> "capReranking"
    "realtime" -> "capRealtime"
    else -> "capText"
}

/**
 * Capability glyph — the FOUR-client canonical set (flutter `capabilityIcon`,
 * webui CapabilityIcon.svelte): image/video/speech/transcription/embedding/
 * reranking share one slot per capability, text falls back to `chat`.
 */
fun capabilityIcon(capability: String): ImageVector = when (capability) {
    "image" -> AppIcons.image
    "video" -> AppIcons.video
    "speech" -> AppIcons.audio
    "transcription" -> AppIcons.mic_vocal
    "embedding" -> AppIcons.scatter
    "reranking", "rerank" -> AppIcons.grip
    "realtime" -> AppIcons.bolt
    else -> AppIcons.chat
}
