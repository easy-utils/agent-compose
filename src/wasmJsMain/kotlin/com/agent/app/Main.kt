package com.agent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.agent.app.i18n.I18n
import com.agent.app.models.BackendCfg
import com.agent.app.models.backendNameFor
import com.agent.app.platform.Prefs
import com.agent.app.platform.jsInstallHistoryNav
import com.agent.app.platform.jsSyncHistoryNav
import com.agent.app.store.AppStore
import com.agent.app.ui.AgentAppRoot
import com.agent.app.ui.AppTheme
import com.agent.app.ui.Phase
import kotlinx.browser.document
import kotlinx.coroutines.launch

// Web (wasmJs) entrypoint. Compose for Web renders into an HTML canvas; the
// backend defaults to the k3s standalone agent (overridable via
// ?base=…&token=… query params).

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val qBase = com.agent.app.net.currentQuery("base")
    val qToken = com.agent.app.net.currentQuery("token")
    if (!qBase.isNullOrEmpty()) Prefs.save(qBase, qToken ?: Prefs.loadToken())
    else if (!qToken.isNullOrEmpty()) Prefs.save(Prefs.loadBase(), qToken)

    // Compose Multiplatform 1.12 removed the CanvasBasedWindow entrypoint in
    // favour of ComposeViewport (the container element is cleared and a canvas
    // created inside it).
    ComposeViewport(viewportContainerId = "ComposeTarget") {
        var phase by remember { mutableStateOf(Phase.LOADING) }
        var store by remember { mutableStateOf<AppStore?>(null) }
        var themeMode by remember { mutableStateOf(Prefs.themeMode()) }
        val dark = com.agent.app.ui.rememberDarkFlag(themeMode)
        val scope = rememberCoroutineScope()

        remember {
            // Tri-state language pref: explicit zh/en, else follow the SYSTEM
                // language (zh for any Chinese locale, en otherwise).
                val lang = Prefs.uiLang()
                I18n.set(
                    when {
                        lang == "zh" -> com.agent.app.i18n.Lang.ZH
                        lang == "en" -> com.agent.app.i18n.Lang.EN
                        else -> if (Prefs.systemLangZh()) com.agent.app.i18n.Lang.ZH else com.agent.app.i18n.Lang.EN
                    }
                )
        }

        fun boot() {
            phase = Phase.LOADING
            val base = Prefs.loadBase()
            val token = Prefs.loadToken()
            if (base.isBlank() || token.isBlank()) {
                phase = Phase.SETUP
                return
            }
            scope.launch {
                val api = AgentApiImpl.create(base, token)
                Prefs.setReadScope(scopeOf(base, token))
                val local = try {
                    com.agent.app.platform.openLocalStore(scopeOf(base, token))
                } catch (_: Exception) {
                    null
                }
                store = AppStore(api, local, scope)
                phase = Phase.APP
            }
        }
        remember { boot() }

        AppTheme(dark) {
            // Browser Back / edge-swipe drives the in-app navigation stack.
            // Every in-app forward push mirrors a history entry; a popstate pops
            // one page and re-arms a sentinel entry (pushState does not fire
            // popstate, so this never recurses).  Without it the phone's back
            // gesture did nothing / left the SPA, so no page obeyed it.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                jsInstallHistoryNav(
                    depth = { store?.currentStack?.size?.minus(1) ?: 0 },
                    onBack = { store?.popPage() },
                )
            }
            val navDepth = store?.currentStack?.size ?: 0
            androidx.compose.runtime.LaunchedEffect(navDepth) { jsSyncHistoryNav() }
            AgentAppRoot(
                store = store,
                phase = phase,
                themeMode = themeMode,
                onThemeMode = { themeMode = it; Prefs.saveThemeMode(it) },
                onSwitchBackend = { phase = Phase.BACKENDS },
                onBackendSwitched = { b: BackendCfg ->
                    Prefs.save(b.baseUrl, b.token)
                    Prefs.upsertBackend(b)
                    boot()
                },
                onConnect = { base, token, done ->
                    scope.launch {
                        try {
                            val api = AgentApiImpl.create(base, token)
                            api.listSessions()
                            Prefs.save(base, token)
                            Prefs.upsertBackend(BackendCfg(backendNameFor(base), base, token))
                            Prefs.setReadScope(scopeOf(base, token))
                            val local = try {
                                com.agent.app.platform.openLocalStore(scopeOf(base, token))
                            } catch (_: Exception) {
                                null
                            }
                            store = AppStore(api, local, scope)
                            phase = Phase.APP
                            done(true)
                        } catch (_: Exception) {
                            done(false)
                        }
                    }
                },
                onLogout = {
                    Prefs.clearActive()
                    store = null
                    phase = Phase.SETUP
                },
            )
        }
    }
}
