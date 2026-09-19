package easy.agent.compose

import com.agent.app.AgentApiImpl
import com.agent.app.scopeOf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.agent.app.i18n.I18n
import com.agent.app.models.BackendCfg
import com.agent.app.models.backendNameFor
import com.agent.app.platform.AndroidBridge
import com.agent.app.platform.Prefs
import com.agent.app.platform.openLocalStore
import com.agent.app.platform.pickFiles
import com.agent.app.store.AppStore
import com.agent.app.ui.AgentAppRoot
import com.agent.app.ui.AppTheme
import com.agent.app.ui.Phase
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidBridge.appContext = applicationContext
        AndroidBridge.activity = this
        installCrashLog()
        // File picking: the library owns the suspend API, this module owns the
        // ActivityResult contract. Results are handed back to the library.
        val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            com.agent.app.platform.PickerBus.deliver(uris)
        }
        // Camera capture for the "+" bottom sheet (flutter `_pickImage(camera)`):
        // TakePicture writes into our cache dir and we read it back as a byte[].
        var photoUri: android.net.Uri? = null
        val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = photoUri
            photoUri = null
            com.agent.app.platform.PickerBus.deliver(if (ok && uri != null) listOf(uri) else emptyList())
        }
        // Microphone permission: declared in the manifest, requested on demand.
        // Without this the first hold-to-talk silently failed on a fresh install.
        val micPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            com.agent.app.platform.PermissionBus.deliver(granted)
        }
        setContent {
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
                com.agent.app.platform.FilePickBridge.launcher = { mime -> picker.launch(arrayOf(mime ?: "*/*")) }
                com.agent.app.platform.FilePickBridge.cameraLauncher = {
                    val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
                    val f = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", f,
                    )
                    photoUri = uri
                    takePicture.launch(uri)
                }
                com.agent.app.platform.PermissionBridge.launcher = { permission -> micPermission.launch(permission) }
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
                    // Any failure (TLS, transport, store) must not tear the
                    // process down: fall back to the setup screen.
                    try {
                        val api = AgentApiImpl.create(base, token)
                        val connScope = scopeOf(base, token)
                        Prefs.setReadScope(connScope)
                        val local = try {
                            openLocalStore(connScope)
                        } catch (_: Throwable) {
                            null
                        }
                        store = AppStore(api, local, scope)
                        phase = Phase.APP
                    } catch (_: Throwable) {
                        store = null
                        phase = Phase.SETUP
                    }
                }
            }
            remember { boot() }

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
                        boot()
                    },
                    onConnect = { base, token, done ->
                        scope.launch {
                            try {
                                val api = AgentApiImpl.create(base, token)
                                api.listSessions()
                                Prefs.save(base, token)
                                Prefs.upsertBackend(BackendCfg(backendNameFor(base), base, token))
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
                            } catch (t: Throwable) {
                                android.util.Log.e("AgentCompose", "connect failed", t)
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

    /** Appends any uncaught throwable to filesDir/crash.log so a crash can be
     *  diagnosed without adb. */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                f.appendText(
                    "\n=== ${java.util.Date()} ${thread.name} ===\n" +
                        android.util.Log.getStackTraceString(t) + "\n",
                )
                android.util.Log.e("AgentCompose", "uncaught", t)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, t)
        }
    }
}
