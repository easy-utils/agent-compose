package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agent.app.AgentApi
import com.agent.app.i18n.I18n
import com.agent.app.i18n.I18n.t
import com.agent.app.models.BackendCfg
import com.agent.app.models.backendNameFor
import com.agent.app.platform.Prefs
import com.agent.app.store.AppPage
import com.agent.app.store.AppStore
import com.agent.app.store.AuthExpired
import com.agent.app.store.SiderTab
import kotlinx.coroutines.launch

// Root: setup gate + backends manager + the responsive two-tab shell — port
// of flutter main.dart.

enum class Phase { LOADING, SETUP, BACKENDS, APP }

@Composable
fun AgentAppRoot(store: AppStore?, phase: Phase, themeMode: String, onThemeMode: (String) -> Unit, onSwitchBackend: () -> Unit, onBackendSwitched: (BackendCfg) -> Unit, onConnect: (String, String, onDone: (Boolean) -> Unit) -> Unit, onLogout: () -> Unit) {
    // 401/403 anywhere → one-tap "sign in again" (flutter auth_gate.dart).
    val authTick = AuthExpired.tick
    if (authTick > 0 && !AuthExpired.dialogOpen && phase == Phase.APP) {
        AuthExpired.dialogOpen = true
    }
    if (AuthExpired.dialogOpen) {
        ConfirmDialog(
            title = t("authExpiredTitle"),
            body = t("authExpiredBody"),
            confirmLabel = t("signInAgain"),
            destructive = true,
            onDismiss = {
                AuthExpired.dialogOpen = false
                AuthExpired.reset()
            },
            onConfirm = {
                AuthExpired.reset()
                onLogout()
            },
        )
    }
    // The ROOT owns the themed background: the setup/backends phases render
    // outside Shell, and without this they painted onto whatever was behind the
    // canvas (white), so light/dark never changed the login page.
    Box(
        Modifier.fillMaxSize()
            .background(LocalAppColors.current.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (phase) {
            Phase.LOADING -> LoadingScreen()
            Phase.SETUP -> SetupScreen(initialBase = Prefs.loadBase(), onConnect = onConnect)
            Phase.BACKENDS -> BackendsScreen(activeBase = store?.api?.baseUrl ?: "", onSwitch = onBackendSwitched, onAdd = onLogout, onBack = { /* phase swapped by host */ })
            Phase.APP -> {
                val s = store ?: return
                Shell(s, themeMode, onThemeMode, onSwitchBackend, onBackendSwitched, onLogout)
            }
        }
    }
}

@Composable
fun SetupScreen(initialBase: String, onConnect: (String, String, (Boolean) -> Unit) -> Unit) {
    var base by remember { mutableStateOf(initialBase) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val colors = LocalAppColors.current

    Column(
        Modifier.fillMaxSize().padding(AppSpacing.XL.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.width(480.dp)) {
            Text(t("appTitle"), style = AppText.screenTitle, color = colors.foreground)
            Spacer(Modifier.height(AppSpacing.XL.dp))
            AppTextField(
                value = base,
                onValueChange = { base = it; error = "" },
                label = t("gatewayUrl"),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppSpacing.MD.dp))
            AppTextField(
                value = token,
                onValueChange = { token = it; error = "" },
                label = t("tokenLabel"),
                enabled = !busy,
                singleLine = true,
                isPassword = !showToken,
                trailingIcon = {
                    Icon(
                        if (showToken) AppIcons.eye_off else AppIcons.eye,
                        contentDescription = null,
                        tint = colors.mutedForeground,
                        modifier = Modifier.appCursor().clickable { showToken = !showToken }.padding(8.dp).size(18.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.SM.dp))
                Text(error, color = colors.destructive, style = AppText.meta)
            }
            Spacer(Modifier.height(AppSpacing.XL.dp))
            Box(
                Modifier.fillMaxWidth()
                    .height(40.dp)
                    .background(
                        if (base.isNotBlank() && token.isNotBlank()) colors.primary
                        else colors.primary.copy(alpha = 0.5f),
                        AppRadius.md,
                    )
                    .clickable(enabled = !busy && base.isNotBlank() && token.isNotBlank()) {
                        busy = true
                        onConnect(base.trim(), token.trim()) { ok ->
                            busy = false
                            if (!ok) error = t("connectionError", "verify failed")
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (busy) t("connecting") else t("connect"), color = colors.onPrimary, style = AppText.body)
            }
        }
    }
}

@Composable
fun BackendsScreen(activeBase: String, onSwitch: (BackendCfg) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    var backends by remember { mutableStateOf(Prefs.backends()) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp).padding(horizontal = AppSpacing.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("backendsTitle"), style = AppText.title)
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = AppSpacing.LG.dp)) {
            if (backends.isEmpty()) {
                Text(t("noSavedBackends"), color = colors.mutedForeground, style = AppText.meta, modifier = Modifier.padding(AppSpacing.MD.dp))
            }
            for (b in backends) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = AppSpacing.XS.dp)
                        .appCard(fill = colors.card)
                        .appClickable(shape = AppRadius.lg) { onSwitch(b) }
                        .padding(horizontal = AppSpacing.MD.dp, vertical = AppSpacing.SM.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (b.baseUrl == activeBase) AppIcons.target else AppIcons.server,
                        contentDescription = null,
                        tint = if (b.baseUrl == activeBase) colors.primary else colors.mutedForeground,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.MD.dp))
                    Column(Modifier.weight(1f)) {
                        // Prefer the resolved username (GetIdentity); fall back
                        // to the stored name / host for legacy entries.
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
                        },
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.MD.dp))
            Row(
                Modifier.fillMaxWidth().appClickable { onAdd() }.padding(AppSpacing.MD.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.add, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Text(t("addBackend"), style = AppText.body)
            }
        }
    }
}

// Shell — responsive: <640 bottom bar (hidden in an open chat), >=640 rail,
// per-tab stack panes (phone=1, tablet=2).

@Composable
fun Shell(store: AppStore, themeMode: String, onThemeMode: (String) -> Unit, onSwitchBackend: () -> Unit, onBackendSwitched: (BackendCfg) -> Unit, onAddUser: () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < AppLayout.COMPACT_BELOW.dp
        ShellContent(store, compact, themeMode, onThemeMode, onSwitchBackend, onBackendSwitched, onAddUser)
    }
}

@Composable
private fun ShellContent(store: AppStore, compact: Boolean, themeMode: String, onThemeMode: (String) -> Unit, onSwitchBackend: () -> Unit, onBackendSwitched: (BackendCfg) -> Unit, onAddUser: () -> Unit) {
    val colors = LocalAppColors.current
    val stack = store.currentStack
    val n = if (compact) 1 else 2
    val start = maxOf(0, minOf(stack.size - n, stack.size - 1))
    val panes = stack.subList(start, stack.size)
    val hideBottom = compact && store.siderTab == SiderTab.CHAT && store.activeSessionId != null

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.weight(1f)) {
            if (!compact) {
                // Flutter NavigationRail: 96dp rail, indicator pill around the
                // icon only (primary @15%), 12sp labels below.
                Column(
                    Modifier.width(AppLayout.RAIL_WIDTH.dp).fillMaxHeight()
                        .background(colors.card).border(0.5.dp, colors.border),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(AppSpacing.MD.dp))
                    for (tb in listOf(SiderTab.CHAT to AppIcons.chat, SiderTab.CONFIG to AppIcons.settings)) {
                        val active = store.siderTab == tb.first
                        Column(
                            Modifier
                                .appClickable(shape = AppRadius.pill, hoverWash = !active) { store.switchTab(tb.first) }
                                .padding(horizontal = AppSpacing.SM.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.width(56.dp).height(32.dp)
                                    .background(
                                        if (active) colors.primary.copy(alpha = 0.15f) else Color.Transparent,
                                        AppRadius.pill,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(tb.second, contentDescription = null, tint = if (active) colors.primary else colors.mutedForeground, modifier = Modifier.size(22.dp))
                            }
                            Text(
                                t(if (tb.first == SiderTab.CHAT) "tabChat" else "tabConfig"),
                                style = AppText.meta,
                                color = if (active) colors.primary else colors.mutedForeground,
                            )
                        }
                    }
                }
            }
            Row(Modifier.weight(1f)) {
                panes.forEachIndexed { i, page ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (i > 0) Modifier.border(0.5.dp, colors.border) else Modifier),
                    ) {
                        PageHost(store, page, isTop = i == panes.lastIndex, themeMode, onThemeMode, onSwitchBackend, onBackendSwitched, onAddUser)
                    }
                }
            }
        }
        if (compact && !hideBottom) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).background(colors.card).border(0.5.dp, colors.border),
            ) {
                for (tb in listOf(SiderTab.CHAT to AppIcons.chat, SiderTab.CONFIG to AppIcons.settings)) {
                    val active = store.siderTab == tb.first
                    Column(
                        Modifier.weight(1f).fillMaxHeight().appClickable { store.switchTab(tb.first) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(tb.second, contentDescription = null, tint = if (active) colors.primary else colors.mutedForeground, modifier = Modifier.size(22.dp))
                        Text(
                            t(if (tb.first == SiderTab.CHAT) "tabChat" else "tabConfig"),
                            style = AppText.micro,
                            color = if (active) colors.primary else colors.mutedForeground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PageHost(
    store: AppStore,
    page: AppPage,
    isTop: Boolean,
    themeMode: String,
    onThemeMode: (String) -> Unit,
    onSwitchBackend: () -> Unit,
    onBackendSwitched: (BackendCfg) -> Unit,
    onAddUser: () -> Unit,
) {
    when (page) {
        AppPage.ChatListPage -> SessionListScreen(store)
        AppPage.ChatSessionPage -> ChatScreen(store)
        AppPage.ChatOverlayPage -> MailboxScreen(store)
        AppPage.ConfigRootPage -> ConfigScreen(store, themeMode, onThemeMode, onSwitchBackend, onBackendSwitched, onAddUser, null)
        is AppPage.ConfigSubPage -> ConfigScreen(store, themeMode, onThemeMode, onSwitchBackend, onBackendSwitched, onAddUser, page.id)
        AppPage.ProvidersListPage -> ProvidersListScreen(store, isTop)
        AppPage.ProviderFormPage -> ProviderFormScreen(store, isTop)
        is AppPage.ProviderModelsPage -> ProviderModelScreen(store, page.modelId, isTop)
        AppPage.PresetFormPage -> PresetFormScreen(store, isTop)
    }
}
