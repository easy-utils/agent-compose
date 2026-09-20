package com.agent.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.app.i18n.I18n
import com.agent.app.i18n.I18n.t
import com.agent.app.models.BackendCfg
import com.agent.app.models.Preset
import com.agent.app.models.ToolConfig
import com.agent.app.models.ToolInfo
import com.agent.app.platform.Prefs
import com.agent.app.platform.effectiveAgentLocale
import com.agent.app.store.AppPage
import com.agent.app.store.AppStore
import kotlinx.coroutines.launch

// ConfigScreen — port of flutter config.dart: root list + drill-ins
// (appearance / backends / presets / tools / language pickers).

@Composable
fun ConfigScreen(
    store: AppStore,
    themeMode: String,
    onThemeMode: (String) -> Unit,
    onSwitchBackend: () -> Unit,
    onBackendSwitched: (BackendCfg) -> Unit,
    onAddUser: () -> Unit,
    initialId: String?,
) {
    val colors = LocalAppColors.current
    var pickLang by remember { mutableStateOf(false) }
    var pickAgentLocale by remember { mutableStateOf(false) }
    var pickTheme by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (initialId != null) {
                AppIcon(AppIcons.back, contentDescription = t("back"), tint = colors.foreground, onClick = { store.popPage() })
            }
            Text(
                when (initialId) {
                    "providers" -> t("llmProviders")
                    "presets" -> t("presets")
                    "appearance" -> t("appearance")
                    "tools" -> t("tools")
                    "backends" -> t("backendsTitle")
                    else -> t("tabConfig")
                },
                style = AppText.title,
            )
            if (initialId == "presets") {
                Spacer(Modifier.weight(1f))
                AppIcon(AppIcons.add, contentDescription = t("newPreset"), tint = colors.primary, onClick = { store.pushPage(AppPage.PresetFormPage) })
            }
        }

        when (initialId) {
            null -> ConfigRootList(store, onPickTheme = { pickTheme = true }, onPickLang = { pickLang = true }, onPickAgentLocale = { pickAgentLocale = true })
            "appearance" -> AppearanceDetail(themeMode, onPickTheme = { pickTheme = true })
            "backends" -> ConfigBackendsDetail(store, onBackendSwitched, onAddUser)
            "presets" -> ConfigPresetsDetail(store)
            "tools" -> ConfigToolsDetail(store)
        }
    }

    // Tri-state theme (follow system / light / dark); follow-system default.
    if (pickTheme) {
        ActionSheet(
            title = t("appearance"),
            actions = listOf(
                "system" to t("followSystem"),
                "light" to t("themeLight"),
                "dark" to t("themeDark"),
            ).map { (code, label) ->
                label to { onThemeMode(code) }
            },
            onDismiss = { pickTheme = false },
        )
    }
    if (pickLang) {
        ActionSheet(
            title = t("language"),
            actions = listOf("system" to t("followSystem"), "zh" to "中文", "en" to "English").map { (code, label) ->
                label to {
                    Prefs.saveUiLang(code)
                    I18n.set(
                        when {
                            code == "zh" -> com.agent.app.i18n.Lang.ZH
                            code == "en" -> com.agent.app.i18n.Lang.EN
                            else -> if (Prefs.systemLangZh()) com.agent.app.i18n.Lang.ZH else com.agent.app.i18n.Lang.EN
                        }
                    )
                }
            },
            onDismiss = { pickLang = false },
        )
    }
    if (pickAgentLocale) {
        val scope = rememberCoroutineScope()
        ActionSheet(
            title = t("agentLocale"),
            actions = listOf("follow" to t("agentLocaleFollow"), "zh" to "中文", "en" to "English").map { (code, label) ->
                label to {
                    Prefs.saveAgentLocale(code)
                    scope.launch {
                        try {
                            store.api.setConfigKey("locale", Prefs.agentLocale())
                        } catch (_: Exception) {
                        }
                    }
                }
            },
            onDismiss = { pickAgentLocale = false },
        )
    }
}

@Composable
private fun ConfigRootList(store: AppStore, onPickTheme: () -> Unit, onPickLang: () -> Unit, onPickAgentLocale: () -> Unit) {
    val colors = LocalAppColors.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { Section(t("appearance")) }
        item { Tile(AppIcons.palette, t("appearance")) { store.pushSibling(AppPage.ConfigSubPage("appearance")) } }
        item { Section(t("backendSection")) }
        item {
            Row(
                Modifier.fillMaxWidth().appClickable { store.pushSibling(AppPage.ConfigSubPage("backends")) }.padding(horizontal = AppSpacing.LG.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.swap, contentDescription = null, tint = colors.destructive, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(AppSpacing.MD.dp))
                Text(t("switchBackend"), color = colors.destructive, style = AppText.body)
            }
        }
        item { Section(t("llm")) }
        item { Tile(AppIcons.server, t("llmProviders")) { store.pushSibling(AppPage.ProvidersListPage) } }
        item { Tile(AppIcons.sparkles, t("presets")) { store.pushSibling(AppPage.ConfigSubPage("presets")) } }
        item { Section(t("workspace")) }
        item { Tile(AppIcons.tools, t("tools")) { store.pushSibling(AppPage.ConfigSubPage("tools")) } }
        item { Section(t("language")) }
        item { Tile(AppIcons.globe, t("language"), onPickLang) }
        item { Tile(AppIcons.language, t("agentLocale"), onPickAgentLocale) }
    }
}

@Composable
private fun Section(label: String) {
    SectionLabel(
        label,
        Modifier.padding(
            start = AppSpacing.LG.dp,
            top = AppSpacing.LG.dp,
            end = AppSpacing.LG.dp,
            bottom = AppSpacing.XS.dp,
        ),
    )
}

@Composable
private fun Tile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onTap: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().appClickable(onTap = onTap).padding(horizontal = AppSpacing.LG.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(AppSpacing.SM.dp))
        Text(label, style = AppText.body, modifier = Modifier.weight(1f))
        Icon(AppIcons.chevron_right, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AppearanceDetail(themeMode: String, onPickTheme: () -> Unit) {
    val colors = LocalAppColors.current
    // Tri-state theme row (follow system / light / dark), follow-system default.
    val label = when (themeMode) {
        "light" -> t("themeLight")
        "dark" -> t("themeDark")
        else -> t("followSystem")
    }
    Row(
        Modifier.fillMaxWidth().appClickable(onTap = onPickTheme).padding(AppSpacing.LG.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.palette, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(AppSpacing.SM.dp))
        Text(t("appearance"), style = AppText.body, modifier = Modifier.weight(1f))
        Text(label, style = AppText.meta, color = colors.mutedForeground)
        Spacer(Modifier.width(AppSpacing.XS.dp))
        Icon(AppIcons.chevron_right, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ConfigBackendsDetail(
    store: AppStore,
    onBackendSwitched: (BackendCfg) -> Unit,
    onAddUser: () -> Unit,
) {
    val colors = LocalAppColors.current
    var backends by remember { mutableStateOf(Prefs.backends()) }
    var toast by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = AppSpacing.LG.dp)) {
        if (backends.isEmpty()) {
            item { Text(t("noSavedBackends"), color = colors.mutedForeground, style = AppText.meta, modifier = Modifier.padding(AppSpacing.MD.dp)) }
        }
        if (toast.isNotEmpty()) {
            item { Toast(toast) }
        }
        items(backends, key = { it.baseUrl }) { b ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = AppSpacing.XS.dp)
                    .appClickable { onBackendSwitched(b) }
                    .padding(vertical = AppSpacing.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (b.baseUrl == store.api.baseUrl) AppIcons.target else AppIcons.server,
                    contentDescription = null,
                    tint = if (b.baseUrl == store.api.baseUrl) colors.primary else colors.mutedForeground,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(AppSpacing.MD.dp))
                Column(Modifier.weight(1f)) {
                    // Prefer the resolved username (GetIdentity).
                    Text(
                        b.username.ifEmpty { b.name.ifEmpty { b.baseUrl } },
                        style = AppText.body,
                    )
                    Text(b.baseUrl, style = AppText.micro, color = colors.mutedForeground, maxLines = 1)
                }
                AppIcon(
                    AppIcons.delete,
                    contentDescription = t("delete"),
                    tint = colors.mutedForeground,
                    onClick = {
                        Prefs.removeBackend(b)
                        backends = Prefs.backends()
                        toast = t("saved")
                    },
                )
            }
        }
        // Add another user: clears the active connection and returns to the
        // setup form (the Flutter `addBackend` entry).
        item {
            Spacer(Modifier.height(AppSpacing.MD.dp))
            AppDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = AppSpacing.SM.dp)
                    .appClickable(onTap = { onAddUser() })
                    .padding(vertical = AppSpacing.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.add,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(AppSpacing.MD.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("addBackend"), style = AppText.body)
                    Text(t("addBackendHint"), style = AppText.micro, color = colors.mutedForeground, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ConfigPresetsDetail(store: AppStore) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Preset?>(null) }
    var deleteFor by remember { mutableStateOf<Preset?>(null) }
    var defaultPreset by remember { mutableStateOf("") }
    var pickDefault by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            presets = store.api.presets(effectiveAgentLocale())
        } catch (_: Exception) {
        }
        try {
            defaultPreset = store.api.config("default_preset")
        } catch (_: Exception) {
        }
        loading = false
    }

    if (loading) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(AppSpacing.LG.dp)) {
        if (presets.isEmpty()) {
            item { Text(t("noPresets"), color = colors.mutedForeground, style = AppText.meta) }
        }
        // Default preset tile (writes `default_preset`, applied to new sessions).
        item {
            Row(
                Modifier.fillMaxWidth().appClickable { pickDefault = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.star, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("defaultPreset"), style = AppText.body.copy(fontWeight = FontWeight.SemiBold))
                    Text(defaultPreset.ifEmpty { t("none") }, style = AppText.micro, color = colors.mutedForeground)
                }
                Icon(AppIcons.chevron_right, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
            }
        }
        items(presets, key = { it.id }) { p ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = AppSpacing.XS.dp)
                    .appClickable { if (!p.isSystem) editing = p },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.id, style = AppText.body, modifier = Modifier.weight(1f))
                    if (p.isSystem) {
                        Text("system", style = AppText.micro, color = colors.mutedForeground)
                    } else {
                        AppIcon(
                            AppIcons.delete,
                            contentDescription = t("delete"),
                            tint = colors.mutedForeground,
                            size = 16.dp,
                            onClick = { deleteFor = p },
                        )
                    }
                }
                if (p.systemPrompt.isNotEmpty()) {
                    Text(p.systemPrompt.take(120), style = AppText.micro, color = colors.mutedForeground, maxLines = 2)
                }
                Text("${t("maxTurns")}: ${p.maxTurns}", style = AppText.micro, color = colors.mutedForeground)
            }
        }
    }

    if (toast.isNotEmpty()) {
        Toast(toast)
    }

    if (pickDefault) {
        ActionSheet(
            title = t("defaultPreset"),
            actions = buildList {
                add(t("none") to {
                    defaultPreset = ""
                    scope.launch { try { store.api.setConfigKey("default_preset", "") } catch (_: Exception) {} }
                })
                for (p in presets.sortedBy { it.id }) add(p.id to {
                    defaultPreset = p.id
                    scope.launch { try { store.api.setConfigKey("default_preset", p.id) } catch (_: Exception) {} }
                })
            },
            onDismiss = { pickDefault = false },
        )
    }

    editing?.let { p ->
        PresetEditDialog(
            initial = p,
            toolsApi = store.api,
            onDismiss = { editing = null },
            onSave = { id, prompt, turns, tools ->
                editing = null
                scope.launch {
                    try {
                        store.api.savePreset(Preset(id, prompt, tools, turns))
                        presets = store.api.presets(effectiveAgentLocale())
                        toast = t("saved")
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }
    deleteFor?.let { p ->
        ConfirmDialog(
            title = t("deletePreset"),
            body = p.id,
            confirmLabel = t("delete"),
            destructive = true,
            onDismiss = { deleteFor = null },
            onConfirm = {
                deleteFor = null
                scope.launch {
                    try {
                        store.api.deletePreset(p.id)
                        presets = store.api.presets(effectiveAgentLocale())
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }
}

@Composable
fun PresetEditDialog(initial: Preset, toolsApi: com.agent.app.AgentApi, onDismiss: () -> Unit, onSave: (String, String, Int, List<String>) -> Unit) {
    val colors = LocalAppColors.current
    var id by remember { mutableStateOf(initial.id) }
    var prompt by remember { mutableStateOf(initial.systemPrompt) }
    var turns by remember { mutableStateOf(initial.maxTurns.toString()) }
    var selected by remember { mutableStateOf(initial.tools.toSet()) }
    var tools by remember { mutableStateOf<List<com.agent.app.models.ToolInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            tools = toolsApi.tools(null)
        } catch (_: Exception) {
        }
    }

    SimpleDialog(onDismiss) {
        if (initial.isSystem) {
            // System presets are immutable — read-only view (flutter _systemPresetView).
            SectionLabel(t("presetId"))
            Text(initial.id, style = AppText.body.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(AppSpacing.SM.dp))
            SectionLabel(t("systemPrompt"))
            Text(initial.systemPrompt, style = AppText.tiny.copy(fontFamily = LocalAppMonoFamily.current))
            Spacer(Modifier.height(AppSpacing.SM.dp))
            Text("${t("tools")} · \${initial.tools.size}", style = AppText.meta)
            for (tool in initial.tools) {
                Text(tool, style = AppText.micro, color = colors.mutedForeground)
            }
            Spacer(Modifier.height(AppSpacing.MD.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(t("close"), Modifier.appClickable(onTap = onDismiss).padding(8.dp), style = AppText.small, color = colors.primary)
            }
        } else {
            Text(t("editPreset"), style = AppText.title)
            Spacer(Modifier.height(AppSpacing.MD.dp))
            AppTextField(value = id, onValueChange = { id = it }, singleLine = true, label = t("presetId"), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppSpacing.SM.dp))
            AppTextField(value = prompt, onValueChange = { prompt = it }, label = t("systemPrompt"), minLines = 3, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppSpacing.SM.dp))
            AppTextField(value = turns, onValueChange = { turns = it }, singleLine = true, label = t("maxTurns"), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppSpacing.SM.dp))
            Text("${t("tools")} · \${selected.size}", style = AppText.meta)
            // Tool whitelist FilterChips (flutter _presetEditView).
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                for (tl in tools) {
                    val on = tl.name in selected
                    Text(
                        tl.name, style = AppText.micro,
                        color = if (on) colors.onPrimary else colors.foreground,
                        modifier = Modifier
                            .appClickable(shape = AppRadius.pill, hoverWash = !on) { selected = if (on) selected - tl.name else selected + tl.name }
                            .background(if (on) colors.primary else colors.muted, AppRadius.pill)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.MD.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text(t("cancel"), Modifier.appClickable(onTap = onDismiss).padding(8.dp), style = AppText.small)
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Text(
                    t("save"),
                    Modifier.appClickable { onSave(id.trim(), prompt, turns.toIntOrNull() ?: 25, selected.toList()) }.padding(8.dp),
                    style = AppText.small, color = colors.primary,
                )
            }
        }
    }
}


@Composable
private fun ConfigToolsDetail(store: AppStore) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<ToolInfo>>(emptyList()) }
    var config by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var providers by remember { mutableStateOf<Map<String, com.agent.app.models.ProviderInfo>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<String?>(null) }
    // Observable so a selection/edit immediately re-renders the picker (a plain
    // mutableMapOf does not trigger recomposition).
    val drafts = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        try {
            tools = store.api.tools(effectiveAgentLocale())
        } catch (_: Exception) {
        }
        try {
            config = store.api.toolConfig()
        } catch (_: Exception) {
        }
        try {
            providers = store.api.providers()
        } catch (_: Exception) {
        }
        loading = false
    }

    if (loading) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    val cats = tools.groupBy { it.category.ifEmpty { "other" } }

    LazyColumn(Modifier.fillMaxSize().padding(AppSpacing.LG.dp)) {
        for ((cat, list) in cats) {
            item { SectionLabel(cat) }
            items(list, key = { it.name }) { tl ->
                val knobs = tl.config
                @Suppress("UNCHECKED_CAST")
                val values = (config[tl.name] as? Map<String, Any?>) ?: emptyMap()
                val hasConfig = values.isNotEmpty()
                val requiredMissing = tl.requiredConfig.any { values[it] == null || values[it].toString().isEmpty() }
                // Card per tool (flutter/webui/swiftui parity): header row +
                // collapsible body, card fill + hairline border + md radius.
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = AppSpacing.SM.dp, bottom = AppSpacing.XS.dp)
                        .appCard(radius = AppRadius.md),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .appClickable { expanded = if (expanded == tl.name) null else tl.name }
                            .padding(horizontal = AppSpacing.MD.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tl.name, style = AppText.mono.copy(fontFamily = LocalAppMonoFamily.current), modifier = Modifier.weight(1f))
                        if (knobs.isEmpty()) {
                            Text(t("noConfig"), style = AppText.micro, color = colors.mutedForeground)
                        } else {
                            Text(
                                when {
                                    requiredMissing -> t("requiredConfig")
                                    hasConfig -> t("configured")
                                    else -> t("needsConfig")
                                },
                                style = AppText.micro,
                                color = when {
                                    requiredMissing -> colors.destructive
                                    hasConfig -> colors.success
                                    else -> colors.warning
                                },
                            )
                        }
                        Icon(if (expanded == tl.name) AppIcons.chevron_down else AppIcons.chevron_right, contentDescription = null, tint = colors.mutedForeground, modifier = Modifier.size(16.dp))
                    }
                    if (expanded == tl.name) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(Modifier.drawTopBorder(colors.border.copy(alpha = 0.6f)))
                                .padding(AppSpacing.MD.dp),
                        ) {
                            if (tl.description.isNotEmpty()) {
                                Text(tl.description, style = AppText.micro, color = colors.mutedForeground)
                            }
                            for (knob in knobs) {
                                val key = "${tl.name}.${knob.name}"
                                val current = drafts[key] ?: values[knob.name]?.toString() ?: ""
                                Text("${knob.name}${if (tl.requiredConfig.contains(knob.name)) " *" else ""}", style = AppText.label)
                                if (knob.description.isNotEmpty()) {
                                    Text(knob.description, style = AppText.micro, color = colors.mutedForeground)
                                }
                                // A selection-type knob saves IMMEDIATELY on pick
                                // (no Save button); only free text needs one.
                                fun save(v: String) {
                                    drafts[key] = v
                                    scope.launch {
                                        try {
                                            // The owning EXTENSION id is
                                            // `tool.category`; the tool name is
                                            // NOT an extension.
                                            store.api.setToolConfigValue(tl.category, knob.name, v)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                when {
                                    knob.isModelRef -> {
                                        // Declared model reference: pick from the
                                        // models registered under the knob's modality.
                                        val refs = providers.values
                                            .filter { it.capability == knob.capability }
                                            .flatMap { p -> p.models.map { "${p.providerId}/${it.id}" } }
                                            .sorted()
                                        AppSelect(
                                            label = knob.name,
                                            options = listOf("" to t("none")) +
                                                refs.map { it to it },
                                            value = current,
                                            onSelect = { save(it) },
                                        )
                                    }
                                    knob.type == "enum" && knob.enumValues.isNotEmpty() -> {
                                        AppSelect(
                                            label = knob.name,
                                            options = listOf("" to t("none")) +
                                                knob.enumValues.map { it to it },
                                            value = current,
                                            onSelect = { save(it) },
                                        )
                                    }
                                    knob.type == "boolean" -> {
                                        AppSelect(
                                            label = knob.name,
                                            options = listOf(
                                                "" to t("none"),
                                                "true" to "true",
                                                "false" to "false",
                                            ),
                                            value = current,
                                            onSelect = { save(it) },
                                        )
                                    }
                                    else -> {
                                        // Free text / number: field + an explicit
                                        // Save button on the RIGHT of the row
                                        // (always enabled), matching webui.
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AppSpacing.SM.dp),
                                        ) {
                                            AppTextField(
                                                value = current,
                                                onValueChange = { drafts[key] = it },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f),
                                                label = knob.name,
                                                placeholder = knob.defaultValue?.toString() ?: "",
                                            )
                                            Box(
                                                Modifier
                                                    .appClickable { save(drafts[key] ?: current) }
                                                    .background(colors.primary, AppRadius.md)
                                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                            ) {
                                                Text(t("save"), style = AppText.meta, color = colors.onPrimary)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(AppSpacing.SM.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
