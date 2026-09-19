# targetSdk 37 (Android 17) verification notes

## What was verified on-device
An API 37 emulator (google_apis_ps16k, x86_64, 16 KB page size) was booted and the
release APK installed successfully:

    adb install -r android/build/outputs/apk/release/android-release.apk
    Performing Streamed Install
    Success
    pm path easy.agent.compose
    package:/data/app/~~.../easy.agent.compose-.../base.apk

The guest reported ro.build.version.sdk=37 and PAGE_SIZE=16384.

## Why interaction could not be completed
The host has no /dev/kvm, so the emulator ran under TCG (software) emulation.
In that mode `system_server` repeatedly tripped its 60s Watchdog during boot and
was killed (logcat: "*** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on
main thread (main) for 69s", stack: PackageManager.getPersistentApplications ->
ActivityManagerService.startPersistentApps), which surfaced as
"Can't find service: package" for `am start`, `pm path` and `dumpsys`.
Hot starts and `-prop ro.hw_timeout_multiplier=20` reduced but did not remove
this, so `am start` / UI interaction / runtime-permission flows could not be
exercised here.

## Behaviour-change exposure audit (static)
Android 17's targetSdk-37-only behaviour changes and this app's exposure:

| Android 17 change (targetSdk 37) | Exposure | Evidence |
|---|---|---|
| RemoteViews bitmap/icon memory limit (widgets) | none | no AppWidget/RemoteViews usage |
| New lock-free MessageQueue | none | no reflection on MessageQueue; only Class.forName("org.sqlite.JDBC") (desktop-only path) |
| static final fields unmodifiable | none | no reflective field writes (grep: no getDeclaredField/setAccessible) |
| Background audio hardening (foreground service WIU) | none | no audio playback, no AudioManager/MediaPlayer/audio focus, no foreground service; the only audio API is MediaRecorder for voice capture while the Activity is in the foreground |
| Orientation/resizability ignored on sw>=600dp | none | no screenOrientation / resizeableActivity / maxAspectRatio in the manifest; the UI is already adaptive (BoxWithConstraints + WindowInsets.safeDrawing) |
| CJKV IME accessibility text attributes | none | no custom InputConnection (standard Compose text fields) |
| ECH (TLS) | none | ECH is opt-in/transparent; the app pins the private CA via network_security_config, and TrustManager/CA validation is unchanged |
| 16 KB page size support | satisfied | the only shipped .so (libandroidx.graphics.path.so) has p_align 0x4000 = 16384 |

Also checked: predictive back. The app declares no `android:enableOnBackInvokedCallback`
and registers no BackHandler, so it keeps the pre-33 default (system back closes
the Activity) — unchanged by targetSdk 37.

## Runtime permission (fixed in the same change)
The manifest declared RECORD_AUDIO but nothing ever requested it:
`VoiceRecorder.hasPermission()` returned `true` unconditionally, so on a fresh
install the first hold-to-talk failed silently (`MediaRecorder.start()` threw
and `catch (_: Exception) { false }` swallowed it).

Fixed by giving the recorder a real permission path, mirroring flutter's
`VoiceRecorder.start() -> hasPermission() -> showToast(voicePermission)`:
- `hasPermission()` now queries `checkSelfPermission(RECORD_AUDIO)`.
- `requestPermission()` prompts through the Activity and awaits the answer.
  Only an Activity can show the dialog, so the same bridge split used for the
  file picker is reused: the library exposes `PermissionBridge.launcher` +
  `PermissionBus.deliver(granted)`, and `:android` installs an
  `ActivityResultContracts.RequestPermission()` launcher.
- `start()` refuses to run without the grant.
- The composer now shows the `voicePermission` / `voiceTooShort` hints
  (the existing Toast composable) instead of failing silently.
- A fast press-release while the prompt is still open no longer leaves the
  recorder running: the release marks intent and the start path finishes the
  clip, matching flutter's pending-start guard.
Desktop and web keep `requestPermission() = true` (no runtime permission model).
