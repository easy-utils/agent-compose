package com.agent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.agent.app.i18n.I18n
import com.agent.app.models.BackendCfg
import com.agent.app.models.backendNameFor
import com.agent.app.platform.Prefs
import com.agent.app.platform.openLocalStore
import com.agent.app.store.AppStore
import com.agent.app.ui.AgentAppRoot
import com.agent.app.ui.AppTheme
import com.agent.app.ui.Phase
import kotlinx.coroutines.launch

// Desktop entrypoint — prefs bootstrap, store assembly and the setup gate.

fun main() = application {
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
            val connScope = scopeOf(base, token)
            Prefs.setReadScope(connScope)
            val local = try {
                openLocalStore(connScope)
            } catch (_: Exception) {
                null
            }
            store = AppStore(api, local, scope)
            phase = Phase.APP
        }
    }
    remember { boot() }

    Window(onCloseRequest = ::exitApplication, title = "Easy Agent") {
        AppTheme(dark) {
            AgentAppRoot(
                store = store,
                phase = phase,
                themeMode = themeMode,
                onThemeMode = { themeMode = it; Prefs.saveThemeMode(it) },
                onSwitchBackend = { phase = Phase.BACKENDS },
                onBackendSwitched = { b: BackendCfg ->
                    Prefs.save(b.baseUrl, b.token)
                    Prefs.upsertBackend(b)
                    phase = Phase.LOADING
                    boot()
                    // Refresh the cached username (older entries may predate
                    // GetIdentity, or the tenant name may have changed).
                    scope.launch {
                        val name = AgentApiImpl.create(b.baseUrl, b.token).resolveUsername()
                        if (name.isNotEmpty()) Prefs.upsertBackend(b.copy(username = name, name = name))
                    }
                },
                onConnect = { base, token, done ->
                    scope.launch {
                        try {
                            val api = AgentApiImpl.create(base, token)
                            api.listSessions() // verify
                            val username = api.resolveUsername()
                            Prefs.save(base, token)
                            Prefs.upsertBackend(BackendCfg(
                                name = username.ifEmpty { backendNameFor(base) },
                                baseUrl = base,
                                token = token,
                                username = username,
                            ))
                            val connScope = scopeOf(base, token)
                            Prefs.setReadScope(connScope)
                            val local = try {
                                openLocalStore(connScope)
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
