package com.agent.app.ui

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.number

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.app.messages.MessagesController
import com.agent.app.i18n.I18n.t
import com.agent.app.models.ChatMessage
import com.agent.app.models.modelRefOf
import com.agent.app.models.ModelInfo
import com.agent.app.models.Preset
import com.agent.app.models.ProviderInfo
import com.agent.app.models.UploadedFile
import com.agent.app.platform.PickedFile
import com.agent.app.platform.VoiceRecorder
import com.agent.app.platform.copyToClipboard
import com.agent.app.platform.effectiveAgentLocale
import com.agent.app.platform.pickFiles
import com.agent.app.platform.takePhoto
import com.agent.app.store.AppStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ChatScreen — port of flutter screens/chat.dart.

@Composable
fun ChatScreen(store: AppStore) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val sid = store.activeSessionId ?: return
    val session = store.activeSession

    val ctrl = remember(sid) {
        MessagesController(
            store.api, { sid }, store.local, scope,
        ) { e -> t("sendFailed", e.message ?: e.toString()) }
    }
    DisposableEffect(sid) {
        ctrl.init()
        onDispose { ctrl.dispose() }
    }

    // Cross-device convergence: the per-session stream does NOT carry an event
    // when another device appends a message, but the session LIST does (the
    // message-fact KV advances this session's messageSeq). Whenever that
    // advances for the OPEN session, re-converge the message list — exactly what
    // makes a message sent elsewhere appear here live.
    val observedSeq = store.activeSession?.messageSeq ?: 0
    LaunchedEffect(sid, observedSeq) {
        if (observedSeq > 0) ctrl.onExternalActivity()
    }

    var providers by remember { mutableStateOf<Map<String, ProviderInfo>>(emptyMap()) }
    var presets by remember { mutableStateOf<List<Preset>>(emptyList()) }
    LaunchedEffect(sid) {
        try {
            providers = store.api.providers()
        } catch (_: Exception) {
        }
        try {
            presets = store.api.presets(effectiveAgentLocale())
        } catch (_: Exception) {
        }
    }

    // draft restore + persistence
    var text by remember(sid) { mutableStateOf(store.chatDrafts[sid]?.text ?: "") }
    var attachments by remember(sid) { mutableStateOf(store.chatDrafts[sid]?.attachments?.toList() ?: emptyList()) }
    // Persist the typed text DEBOUNCED (webui: 300ms) instead of per keystroke.
    var draftJob by remember { mutableStateOf<Job?>(null) }
    fun saveTextSoon() {
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(300)
            store.saveDraftText(sid, text)
        }
    }
    DisposableEffect(sid) {
        onDispose {
            // Flush on session switch / teardown so a trailing <300ms edit
            // is never lost (the closure reads THIS composition's text/sid).
            draftJob?.cancel()
            store.saveDraftText(sid, text)
        }
    }
    var recording by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var forkOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var viewerFor by remember { mutableStateOf<com.agent.app.models.AttachmentRef?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Transient hint shown over the composer (mic denied / clip too short /
    // compact feedback / menu action errors).
    var hint by remember { mutableStateOf("") }
    var hintError by remember { mutableStateOf(false) }
    // Whether capture is actually running, and whether the user already let go
    // while the permission prompt / start() was still in flight. Together they
    // close the race where a fast press-release during the prompt would leave
    // MediaRecorder recording forever.
    var voiceStarted by remember { mutableStateOf(false) }
    var voiceReleased by remember { mutableStateOf(false) }
    // Hold-to-talk elapsed label (flutter ticks a 200ms Timer while recording).
    var voiceElapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        if (!recording) {
            voiceElapsed = 0L
            return@LaunchedEffect
        }
        val started = com.agent.app.util.nowMillis()
        while (true) {
            voiceElapsed = com.agent.app.util.nowMillis() - started
            kotlinx.coroutines.delay(200)
        }
    }
    // Upload jobs in flight. The chat scope runs on the main dispatcher, so a
    // plain set is safe and stays commonMain (no java.util).
    val inflightUploads = remember { mutableSetOf<kotlinx.coroutines.Job>() }
    val recorder = remember { VoiceRecorder() }
    DisposableEffect(Unit) { onDispose { recorder.dispose() } }

    fun persist() {
        store.saveDraftText(sid, text)
        store.saveDraftAttachments(sid, attachments)
    }

    val listState = rememberLazyListState()
    // Follow-bottom (flutter `_followBottom` / webui): only a USER scroll
    // changes the intent — dragging away from the bottom pauses the follow so
    // streaming never fights the user reading history; returning to the
    // bottom (within 80px) re-arms it. Programmatic auto-scrolls land at the
    // bottom and keep it armed.
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow<Boolean?> {
            if (!listState.isScrollInProgress) null
            else {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                if (last == null) {
                    true
                } else {
                    last.offset + last.size >= info.viewportEndOffset - 80
                }
            }
        }.collect { nearBottom -> if (nearBottom != null) followBottom = nearBottom }
    }
    // Auto-load older history when the user scrolls near the top (flutter
    // chat.dart `_onScroll`: pixels < 80 && hasMore && !loading).
    LaunchedEffect(listState, ctrl.hasMore) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx <= 1 && ctrl.hasMore && !ctrl.loading && ctrl.messages.isNotEmpty()) {
                    ctrl.loadMore()
                }
            }
    }
    LaunchedEffect(ctrl.revision) {
        // Only follow while armed — a user reading history is never yanked
        // down by streaming deltas (+1 for the load-earlier header row).
        // INSTANT jump (not animateScrollToItem): flutter/webui pin the list
        // with a non-animated scroll, and an animation restarted on every
        // streamed delta is what made the bubble look like it kept redrawing.
        val idx = ctrl.sorted.lastIndex + (if (ctrl.hasMore) 1 else 0)
        if (followBottom && ctrl.messages.isNotEmpty() && idx >= 0) {
            // scrollToItem only aligns the item's TOP into the viewport; the
            // newest message must sit at the BOTTOM (flutter jumps to
            // maxScrollExtent). scrollToItem(index, offset) with a huge offset
            // clamps to the end, giving the same bottom-pinned result.
            listState.scrollToItem(idx, Int.MAX_VALUE)
        }
    }
    // Initial pin + re-pin when the CONTENT height changes (streaming text /
    // async images grow the last item). This mirrors flutter's
    // ScrollMetricsNotification handler and fixes opening at the top of the
    // history instead of the newest message.
    LaunchedEffect(listState, ctrl.messages.isNotEmpty()) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.index ?: -1, last?.size ?: 0)
        }.collect { (_, lastIndex, _) ->
            val idx = ctrl.sorted.lastIndex + (if (ctrl.hasMore) 1 else 0)
            if (ctrl.messages.isNotEmpty() && idx >= 0 &&
                (followBottom || lastIndex < idx)
            ) {
                listState.scrollToItem(idx, Int.MAX_VALUE)
            }
        }
    }

    fun uploadOne(src: PickedFile, targetCode: String? = null) {
        val pending = UploadedFile(
            code = targetCode ?: "tmp-${com.agent.app.util.nowMillis()}-${src.name}",
            name = src.name, mime = src.mime, size = src.bytes.size,
            localPath = src.localPath,
            uploadState = com.agent.app.models.UploadState.UPLOADING,
        )
        attachments = if (targetCode == null) attachments + pending
        else attachments.map { if (it.code == targetCode) pending else it }
        val job = scope.launch {
            try {
                val done = store.api.uploadFile(src.name, src.bytes)
                attachments = attachments.map { if (it.code == pending.code) done else it }
            } catch (e: Exception) {
                attachments = attachments.map {
                    if (it.code == pending.code) it.copy(
                        uploadState = com.agent.app.models.UploadState.ERROR,
                        error = e.message,
                    ) else it
                }
            }
            store.saveDraftAttachments(sid, attachments)
        }
        inflightUploads.add(job)
        job.invokeOnCompletion { inflightUploads.remove(job) }
    }

    // ALL-or-NOTHING send (flutter _send): wait for every in-flight upload,
    // then refuse the batch if any attachment has no server code yet.
    fun doSend() {
        val body = text
        if (body.isBlank() && attachments.isEmpty()) return
        if (sending || ctrl.sending) return
        scope.launch {
            sending = true
            inflightUploads.toList().forEach { it.join() }
            val failed = attachments.count { it.hasError || it.code.isEmpty() || it.code.startsWith("tmp-") }
            if (failed > 0) {
                sending = false
                return@launch
            }
            val files = attachments.filter { !it.code.startsWith("tmp-") }
            text = ""
            attachments = emptyList()
            store.clearDraft(sid)
            sending = false
            ctrl.send(body, files)
        }
    }

    fun upload(list: List<PickedFile>) {
        for (src in list) uploadOne(src)
    }

    /**
     * Hold-to-talk release. Stops the clip and uploads it through the normal
     * attachment path, or surfaces the "too short" hint. A release that lands
     * before start() finished only marks the intent; beginRecording() then
     * completes the clip, which is how flutter's pending-start guard behaves.
     */
    fun endRecording() {
        scope.launch {
            if (!voiceStarted) {
                // Still waiting on the permission prompt; let the start path
                // finish the clip as soon as it lands.
                voiceReleased = true
                return@launch
            }
            voiceStarted = false
            val clip = recorder.stop()
            if (clip != null) {
                upload(listOf(clip))
            } else {
                hintError = true
                hint = t("voiceTooShort")
            }
        }
    }

    /**
     * Hold-to-talk press. Asks for the microphone permission first (the
     * platform prompts on Android) and reports a denial instead of handing
     * work to a MediaRecorder that would throw. Mirrors flutter's
     * VoiceRecorder.start() -> showToast(voicePermission).
     */
    fun beginRecording() {
        voiceReleased = false
        scope.launch {
            if (!recorder.requestPermission()) {
                hintError = true
                hint = t("voicePermission")
                return@launch
            }
            hintError = false
            if (!recorder.start()) {
                // flutter shows the same hint when start() fails for any reason
                hintError = true
                hint = t("voicePermission")
                return@launch
            }
            voiceStarted = true
            recording = true
            hint = ""
            // The user already let go while we were prompting/starting: finish
            // the clip immediately instead of recording until the next press.
            if (voiceReleased) {
                voiceReleased = false
                endRecording()
            }
        }
    }

    fun retryUpload(a: UploadedFile) {
        // Re-uploads need the original bytes; only files picked this session
        // (still resolvable through the platform) can be retried.
        scope.launch {
            try {
                val bytes = com.agent.app.platform.readLocalFile(a.localPath, a.name ?: a.code)
                uploadOne(PickedFile(a.name ?: "file", a.mime ?: "application/octet-stream", bytes, a.localPath), targetCode = a.code)
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---- top bar (flutter _topBar): 48px Stack, back + lamp + ctx on the
        // left, the session-NAME pill CENTERED, the overflow menu on the right.
        Box(Modifier.fillMaxWidth().height(AppBars.HEIGHT.dp)) {
            Row(
                Modifier.align(Alignment.CenterStart).padding(start = AppSpacing.XS.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(
                    AppIcons.back,
                    contentDescription = t("back"),
                    tint = colors.foreground,
                    size = 22.dp,
                    onClick = { store.popPage() },
                )
                Spacer(Modifier.width(AppSpacing.XS.dp))
                Box(Modifier.size(8.dp).background(if (ctrl.sending) colors.warning else colors.success, CircleShape))
                val ctx = (session?.lastInputTokens ?: 0) + (session?.lastOutputTokens ?: 0)
                if (ctx > 0) {
                    Spacer(Modifier.width(AppSpacing.SM.dp))
                    Text(fmtContext(ctx), style = AppText.micro, color = colors.mutedForeground)
                }
            }
            // Centered session-name pill (fixed 96..160 width, ellipsized).
            Box(Modifier.align(Alignment.Center)) {
                SessionNamePill(
                    name = session?.id ?: t("chatTitle"),
                    onClick = { infoOpen = true },
                )
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = AppSpacing.XS.dp)) {
                AppIcon(
                    AppIcons.more_vertical,
                    contentDescription = t("settingsTitle"),
                    tint = colors.foreground,
                    size = 22.dp,
                    onClick = { menuOpen = true },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = colors.popover,
                ) {
                    DropdownMenuItem(
                        text = { Text(t("compactHistory"), style = AppText.meta) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                try {
                                    val created = store.api.compact(sid)
                                    hintError = false
                                    hint = t(if (created) "historyCompacted" else "nothingToCompact")
                                } catch (e: Exception) {
                                    hintError = true
                                    hint = e.message ?: e.toString()
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(t("mailbox"), style = AppText.meta) },
                        onClick = { menuOpen = false; store.pushPage(com.agent.app.store.AppPage.ChatOverlayPage) },
                    )
                    HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        text = { Text(t("fork"), style = AppText.meta) },
                        onClick = { menuOpen = false; forkOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text(t("deleteSession"), style = AppText.meta, color = colors.destructive) },
                        onClick = { menuOpen = false; deleteConfirm = true },
                    )
                }
            }
        }
        AppDivider()

        // ---- messages ----
        Box(Modifier.weight(1f).onDropFiles(
            onFiles = {},
            onFilesBytes = { items ->
                upload(items.map { PickedFile(it.first, it.second ?: "application/octet-stream", it.third) })
            },
            onDragging = { dragging = it },
        )) {
            if (ctrl.loading && ctrl.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.MD.dp, vertical = AppSpacing.MD.dp),
                ) {
                    if (ctrl.hasMore) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    if (ctrl.loading) t("loading") else t("loadEarlier"),
                                    style = AppText.small,
                                    color = colors.primary,
                                    modifier = Modifier
                                        .appClickable(hoverWash = false, onTap = {
                                            if (!ctrl.loading) scope.launch { ctrl.loadMore() }
                                        })
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                    items(ctrl.sorted, key = { it.id }) { msg ->
                        MessageBubble(msg = msg, api = store.api, onUndo = { scope.launch { ctrl.revert(msg.id) } }, onResend = { scope.launch { ctrl.resendFrom(msg, it) } }, onOpenMedia = { viewerFor = it })
                    }
                }
            }
            // Drag hover overlay — same affordance as the Flutter/WebUI/SwiftUI
            // clients (centered card: download glyph + dropToAttach).
            if (dragging) {
                Box(
                    Modifier.fillMaxSize().background(colors.primary.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(colors.card, AppRadius.lg)
                            .border(1.5.dp, colors.primary, AppRadius.lg)
                            .padding(AppSpacing.MD.dp),
                    ) {
                        Icon(AppIcons.download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(AppSpacing.SM.dp))
                        Text(t("dropToAttach"), style = AppText.body, color = colors.foreground)
                    }
                }
            }
        }

        // ---- composer (flutter `_composer`): card fill + top border, 12/4/12/0
        // padding, attachments as a 3-per-line Wrap, the shared 42px shell
        // around the field / hold-to-talk, and ONE morphing button on the right.
        Column(
            Modifier.fillMaxWidth()
                .background(colors.card)
                .then(Modifier.drawTopBorder(colors.border.copy(alpha = 0.5f))),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = AppSpacing.MD.dp, end = AppSpacing.MD.dp, top = AppSpacing.XS.dp, bottom = AppSpacing.XS.dp)) {
                if (attachments.isNotEmpty()) {
                    AttachmentWrap(
                        attachments = attachments,
                        api = store.api,
                        onOpen = { a ->
                            if (a.code.isNotEmpty() && a.uploadState == com.agent.app.models.UploadState.DONE) {
                                viewerFor = com.agent.app.models.AttachmentRef(a.code, a.name ?: "", a.mime)
                            }
                        },
                        onRetry = { a -> retryUpload(a) },
                        onRemove = { a ->
                            attachments = attachments.filter { it.code != a.code }
                            store.saveDraftAttachments(sid, attachments)
                        },
                    )
                }
                if (hint.isNotEmpty()) {
                    Toast(hint, error = hintError)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Left: mic/keyboard toggle in the SAME 42dp outlined circle
                    // as the action button on the right, so the pair is
                    // symmetric and shares one vertical center.
                    Box(Modifier.size(42.dp)) {
                        ComposerLeftCircle(
                            icon = if (voiceMode) AppIcons.keyboard else AppIcons.mic,
                            contentDescription = if (voiceMode) t("keyboardMode") else t("voiceMode"),
                            enabled = !ctrl.sending,
                            onClick = { voiceMode = !voiceMode },
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.SM.dp))
                    if (voiceMode) {
                        // Hold-to-talk: press and hold records, release sends.
                        // Same shell as the field, so the two never differ in
                        // height (flutter `_composerBox`).
                        Box(
                            Modifier.weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                                .background(
                                    if (recording) colors.destructive.copy(alpha = 0.12f) else colors.muted,
                                    AppRadius.md,
                                )
                                .border(
                                    1.dp,
                                    if (recording) colors.destructive else colors.border.copy(alpha = 0.6f),
                                    AppRadius.md,
                                )
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        beginRecording()
                                        try {
                                            waitForUpOrCancellation()
                                        } finally {
                                            // endRecording() itself no-ops when start()
                                            // never succeeded (permission denied),
                                            // so no stale recorder is left open.
                                            recording = false
                                            endRecording()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (recording) "${t("releaseToSend")} · ${fmtDuration(voiceElapsed)}" else t("holdToTalk"),
                                style = AppText.body.copy(
                                    color = if (recording) colors.destructive else colors.mutedForeground,
                                    fontWeight = if (recording) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                        }
                    } else {
                        // The shared shell owns the fill/radius/border; the field
                        // itself is decoration-free (plain), exactly like flutter's
                        // `isCollapsed` + no-border decoration.
                        Box(
                            Modifier.weight(1f).defaultMinSize(minHeight = 42.dp)
                                .background(colors.muted, AppRadius.md)
                                .border(1.dp, colors.border.copy(alpha = 0.6f), AppRadius.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppTextField(
                                value = text,
                                onValueChange = { text = it; saveTextSoon() },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.MD.dp),
                                plain = true,
                                placeholder = if (attachments.isEmpty()) t("typeMessage") else null,
                                maxLines = 6,
                                onPreviewKeyEvent = { e ->
                                    // Desktop/web keyboard path: Enter sends, Ctrl/Cmd+Enter
                                    // inserts a newline; phones use the IME action instead.
                                    val type = e.type
                                    val key0 = e.key
                                    when {
                                        type == KeyEventType.KeyDown && key0 == Key.Enter &&
                                            !e.isShiftPressed && !e.isCtrlPressed && !e.isMetaPressed -> {
                                            if (text.isNotBlank() || attachments.any { !it.code.startsWith("tmp-") }) {
                                                doSend()
                                            }
                                            true
                                        }
                                        type == KeyEventType.KeyDown && key0 == Key.Enter &&
                                            (e.isCtrlPressed || e.isMetaPressed) -> {
                                            text += "\n"
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(AppSpacing.SM.dp))
                    // Right: ONE morphing button — abort (running) / spinner
                    // (finishing uploads) / send (has content) / plus (empty →
                    // the attach bottom sheet).
                    val canSend = text.isNotBlank() || attachments.any { !it.code.startsWith("tmp-") }
                    when {
                        ctrl.sending -> ComposerCircleButton(
                            icon = AppIcons.stop,
                            contentDescription = t("abort"),
                            tint = colors.destructive,
                            onClick = { ctrl.stop() },
                        )
                        sending -> ComposerCircleButton(
                            contentDescription = t("connecting"),
                            tint = colors.primary,
                            onClick = {},
                        )
                        canSend -> ComposerCircleButton(
                            icon = AppIcons.send,
                            contentDescription = t("send"),
                            tint = colors.primary,
                            onClick = { doSend() },
                        )
                        else -> ComposerCircleButton(
                            icon = AppIcons.add,
                            contentDescription = t("attach"),
                            tint = colors.mutedForeground,
                            onClick = { attachOpen = true },
                        )
                    }
                }
            }
        }
    }

    // Attach bottom sheet (flutter `_openAttachSheet`): camera / gallery / file.
    if (attachOpen) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { attachOpen = false },
            containerColor = colors.card,
        ) {
            AttachSheetRow(AppIcons.camera, t("takePhoto")) {
                attachOpen = false
                scope.launch { upload(takePhoto()) }
            }
            AttachSheetRow(AppIcons.image, t("chooseImage")) {
                attachOpen = false
                scope.launch { upload(pickFiles("image/*")) }
            }
            AttachSheetRow(AppIcons.attach, t("chooseFile")) {
                attachOpen = false
                scope.launch { upload(pickFiles(null)) }
            }
            Spacer(Modifier.height(AppSpacing.SM.dp))
        }
    }

    // ---- dialogs ----
    if (settingsOpen) {
        SessionSettingsDialog(
            store = store,
            sid = sid,
            providers = providers,
            presets = presets,
            onDismiss = { settingsOpen = false },
        )
    }
    if (menuOpen) {
        // Rendered by the anchored DropdownMenu in the top bar; nothing to do.
    }
    viewerFor?.let { ref ->
        MediaViewerDialog(api = store.api, code = ref.code, name = ref.name, mime = ref.mime, onDismiss = { viewerFor = null })
    }

    if (infoOpen) {
        SessionInfoDialog(
            session = session, sid = sid,
            onDismiss = { infoOpen = false },
            onEdit = { infoOpen = false; settingsOpen = true },
        )
    }

    if (forkOpen) {
        TextInputDialog(
            title = t("fork"),
            onDismiss = { forkOpen = false },
            onConfirm = { branch ->
                forkOpen = false
                if (branch.isNotBlank()) scope.launch { store.forkSession(branch) }
            },
        )
    }
    if (deleteConfirm) {
        val s = store.activeSession
        val label = if (s != null && s.org.isNotEmpty()) "${s.org}/${s.repo}/${s.branch}" else sid
        ConfirmDialog(
            title = t("deleteSession"),
            body = t("deleteSessionBody", label),
            confirmLabel = t("delete"),
            destructive = true,
            onDismiss = { deleteConfirm = false },
            onConfirm = {
                deleteConfirm = false
                scope.launch { store.deleteSession(sid) }
            },
        )
    }
}

/** mm:ss label for the hold-to-talk timer (flutter `_formatDuration`). */
fun fmtDuration(millis: Long): String {
    val total = (millis / 1000).toInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

fun fmtContext(tokens: Int): String = when {
    tokens <= 0 -> ""
    tokens >= 1_000_000 -> "${(tokens / 100_000) / 10.0}M"
    tokens >= 10_000 -> "${tokens / 1000}k"
    else -> "${(tokens / 100) / 10.0}k"
}

/** Fixed-width session-name pill centered in the chat top bar (flutter
 *  `_SessionNamePill`): 96..160 wide, primary@14 fill, NO stroke, meta w600. */
@Composable
fun SessionNamePill(name: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .widthIn(min = 96.dp, max = 160.dp)
            .appClickable(shape = AppRadius.pill, hoverWash = false, onTap = onClick)
            .background(colors.primary.copy(alpha = 0.14f), AppRadius.pill)
            .padding(horizontal = AppSpacing.MD.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = AppText.meta.copy(fontWeight = FontWeight.SemiBold),
            color = colors.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun SessionSettingsDialog(
    store: AppStore,
    sid: String,
    providers: Map<String, ProviderInfo>,
    presets: List<Preset>,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val session = store.activeSession
    var selectedRef by remember { mutableStateOf(session?.model ?: "") }
    var variant by remember { mutableStateOf(session?.variant ?: "") }
    var preset by remember { mutableStateOf(session?.preset ?: "") }
    var locale by remember { mutableStateOf(session?.locale ?: "") }
    var allModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(true) }

    LaunchedEffect(providers) {
        val out = ArrayList<ModelInfo>()
        for (pid in providers.keys) {
            try {
                out.addAll(store.api.models(pid))
            } catch (_: Exception) {
            }
        }
        allModels = out
        loadingModels = false
        if (out.isNotEmpty() && out.none { modelRefOf(it) == selectedRef }) {
            selectedRef = modelRefOf(out.first())
        }
    }
    val variants = allModels.firstOrNull { modelRefOf(it) == selectedRef }?.variants ?: emptyList()
    if (variants.isNotEmpty() && variants.none { it.id == variant }) variant = ""

    SimpleDialog(onDismiss) {
        Text(t("settingsTitle"), style = AppText.title)
        Spacer(Modifier.height(AppSpacing.MD.dp))
        AppSelect(
            label = t("modelLabel"),
            options = allModels.map { modelRefOf(it) to modelRefOf(it) }.toMutableList()
                .also { if (selectedRef.isNotEmpty() && allModels.none { modelRefOf(it) == selectedRef }) it.add(0, selectedRef to selectedRef) },
            value = selectedRef,
            loading = loadingModels,
            onSelect = { selectedRef = it },
        )
        if (variants.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.MD.dp))
            AppSelect(
                label = t("variantLabel"),
                options = listOf("" to t("variantNone")) + variants.map { it.id to it.name.ifEmpty { it.id } },
                value = variant,
                onSelect = { variant = it },
            )
        }
        Spacer(Modifier.height(AppSpacing.MD.dp))
        // Keep the session's current preset selectable even when not listed.
        AppSelect(
            label = t("presetLabel"),
            options = presets.map { it.id to it.id }
                .let { if (preset.isNotEmpty() && presets.none { pp -> pp.id == preset }) listOf(preset to preset) + it else it },
            value = preset,
            onSelect = { preset = it },
        )
        Spacer(Modifier.height(AppSpacing.MD.dp))
        AppSelect(
            label = t("agentLocale"),
            options = listOf("" to t("agentLocaleFollow"), "zh" to "中文", "en" to "English"),
            value = locale,
            onSelect = { locale = it },
        )
        Spacer(Modifier.height(AppSpacing.MD.dp))
        Text(t("turnsByPreset"), style = AppText.micro, color = colors.mutedForeground)
        Text(t("sysPromptByPreset"), style = AppText.micro, color = colors.mutedForeground)
        Spacer(Modifier.height(AppSpacing.MD.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(t("cancel"), Modifier.appClickable(hoverWash = false, onTap = onDismiss).padding(8.dp), style = AppText.small)
            Spacer(Modifier.width(AppSpacing.SM.dp))
            Box(
                Modifier.appClickable(shape = AppRadius.sm) {
                    onDismiss()
                    scope.launch {
                        try {
                            val updated = store.api.settings(
                                sid,
                                buildMap {
                                    if (selectedRef.isNotEmpty()) put("model", selectedRef)
                                    put("variant", variant)
                                    if (preset.isNotEmpty()) put("preset", preset)
                                    put("locale", locale)
                                },
                            )
                            updated?.let { store.applySession(it) }
                        } catch (_: Exception) {
                        }
                    }
                }.background(colors.primary, AppRadius.sm).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(t("save"), color = colors.onPrimary, style = AppText.small)
            }
        }
    }
}

@Composable
fun SimpleDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.appCard()
                .padding(AppSpacing.LG.dp),
        ) {
            content()
        }
    }
}

// ---- message bubble ----

@Composable
fun MessageBubble(
    msg: ChatMessage,
    api: com.agent.app.AgentApi,
    onUndo: () -> Unit,
    onResend: (String) -> Unit,
    onOpenMedia: (com.agent.app.models.AttachmentRef) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    val isSystem = msg.role == "system" || msg.role == "event"
    val isStreaming = msg.status == "streaming"

    val ordered = remember(msg.parts) {
        msg.parts.filter { it.type == "reasoning" } + msg.parts.filter { it.type != "reasoning" }
    }

    // Long-press action sheet / edit + undo dialogs live with the bubble.
    var bubbleActions by remember(msg.id) { mutableStateOf(false) }
    var editOpen by remember(msg.id) { mutableStateOf(false) }
    var editSeed by remember(msg.id) { mutableStateOf("") }
    var undoConfirm by remember(msg.id) { mutableStateOf(false) }
    var retryConfirm by remember(msg.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(bottom = AppSpacing.SM.dp + 4.dp),
        horizontalAlignment = when {
            isSystem -> Alignment.CenterHorizontally
            isUser -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        if (isStreaming && ordered.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.mutedForeground)
                Spacer(Modifier.width(AppSpacing.SM.dp))
                Text(t("thinking"), style = AppText.micro, color = colors.mutedForeground)
            }
        } else {
            // The bubble HUGS its content (no width cap) — flutter only wraps at
            // the available width, it never pins a 92% block.
            Column(
                Modifier
                    // Right-click (desktop) / long-press off text (touch) opens
                    // the action sheet. Text itself is wrapped in a
                    // SelectionContainer below, so drag/long-press ON text does
                    // native selection instead (matches webui/SwiftUI).
                    .bubbleMenuGestures { bubbleActions = true }
                    .background(
                        when {
                            isError -> colors.destructive.copy(alpha = 0.10f)
                            isSystem -> colors.muted.copy(alpha = 0.30f)
                            isUser -> colors.primary.copy(alpha = 0.12f)
                            else -> colors.card
                        },
                        AppRadius.md,
                    )
                    .border(
                        1.dp,
                        when {
                            isError -> colors.destructive.copy(alpha = 0.4f)
                            isSystem -> colors.mutedForeground.copy(alpha = 0.25f)
                            isUser -> colors.primary.copy(alpha = 0.4f)
                            else -> colors.border.copy(alpha = 0.5f)
                        },
                        AppRadius.md,
                    )
                    .padding(horizontal = AppSpacing.MD.dp, vertical = AppSpacing.SM.dp + 2.dp),
            ) {
                if (isError) {
                    Text(t("error"), style = AppText.micro.copy(fontWeight = FontWeight.SemiBold), color = colors.destructive)
                    Spacer(Modifier.height(4.dp))
                }
                // Native text selection (drag-select on desktop/web, long-press
                // handles on touch) — identical to flutter's `selectable: true`.
                SelectionContainer {
                    Column {
                        for ((i, part) in ordered.withIndex()) {
                            if (i > 0) Spacer(Modifier.height(AppSpacing.SM.dp))
                            when (part.type) {
                                "text" -> TextWithFileRefs(
                                    text = part.text.orEmpty(), api = api,
                                    onOpenMedia = onOpenMedia,
                                )
                                "reasoning" -> ReasoningBlock(part.text, isStreaming)
                                "compaction" -> CompactionBlock(part.text)
                                "file" -> MessageFilePart(
                                    api = api,
                                    code = part.code ?: "", name = part.name, mime = part.mime, size = part.size,
                                    onOpen = onOpenMedia,
                                )
                                "tool" -> ToolCard(part, isStreaming, api = api, onOpenMedia = onOpenMedia)
                            }
                        }
                    }
                }
            }
            if (!isStreaming && !isSystem) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasText = msg.parts.any { it.type == "text" || it.type == "reasoning" }
                    if (hasText) {
                        IconAction(AppIcons.copy, t("copy")) {
                            copyToClipboard(
                                msg.parts.filter { it.type == "text" || it.type == "reasoning" }
                                    .joinToString("\n") { it.text },
                            )
                        }
                    }
                    if (isUser) {
                        IconAction(AppIcons.refresh, t("retry")) { retryConfirm = true }
                        IconAction(AppIcons.edit, t("edit")) {
                            editSeed = msg.parts.filter { it.type == "text" }.joinToString("\n") { it.text }
                            editOpen = true
                        }
                    }
                    IconAction(AppIcons.undo, t("undo")) { undoConfirm = true }
                    Spacer(Modifier.width(AppSpacing.XS.dp))
                    Text(fmtTime(msg.createdAt), style = AppText.micro, color = colors.mutedForeground)
                }
            }
        }
    }

    // Long-press action sheet (flutter `_actions`), same entries/order.
    if (bubbleActions) {
        val hasText = msg.parts.any { it.type == "text" || it.type == "reasoning" }
        val canUndoOnly = !hasText && !isUser
        ActionSheet(
            title = if (hasText) msg.parts.firstOrNull { it.type == "text" }?.text?.take(60).orEmpty() else "",
            actions = buildList {
                if (hasText) add(t("copy") to {
                    copyToClipboard(
                        msg.parts.filter { it.type == "text" || it.type == "reasoning" }
                            .joinToString("\n") { it.text },
                    )
                })
                if (isUser) {
                    add(t("retry") to { retryConfirm = true })
                    add(t("edit") to {
                        editSeed = msg.parts.filter { it.type == "text" }.joinToString("\n") { it.text }
                        editOpen = true
                    })
                }
                if (!canUndoOnly || hasText || isUser) add(t("undo") to { undoConfirm = true })
            },
            onDismiss = { bubbleActions = false },
        )
    }

    if (editOpen) {
        TextInputDialog(
            title = t("editMessage"),
            initial = editSeed,
            confirmLabel = t("apply"),
            onDismiss = { editOpen = false },
            onConfirm = { v ->
                editOpen = false
                val trimmed = v.trim()
                if (trimmed.isNotEmpty()) onResend(trimmed)
            },
        )
    }
    if (undoConfirm) {
        ConfirmDialog(
            title = t("undoTitle"),
            body = t("undoBody"),
            confirmLabel = t("undo"),
            destructive = true,
            onDismiss = { undoConfirm = false },
            onConfirm = {
                undoConfirm = false
                onUndo()
            },
        )
    }
    if (retryConfirm) {
        // Retry withdraws the message (and everything after) then resends —
        // destructive, so confirm first (same policy as undo).
        ConfirmDialog(
            title = t("retryTitle"),
            body = t("retryBody"),
            confirmLabel = t("retry"),
            onDismiss = { retryConfirm = false },
            onConfirm = {
                retryConfirm = false
                onResend(msg.parts.filter { it.type == "text" }.joinToString("\n") { it.text })
            },
        )
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = colors.mutedForeground,
        modifier = Modifier
            .appClickable(shape = AppRadius.sm, onTap = onClick)
            .padding(2.dp)
            .size(14.dp),
    )
    Spacer(Modifier.width(2.dp))
}

/** Relative time label port of flutter's `_BubbleActions._fmtTime`. */
fun fmtTime(iso: String): String = try {
    val d = kotlin.time.Instant.parse(iso)
    val now = kotlin.time.Clock.System.now()
    val mins = (now - d).inWholeMinutes
    val local = kotlinx.datetime.TimeZone.currentSystemDefault()
    val ld = d.toLocalDateTime(local)
    val nd = now.toLocalDateTime(local)
    val hm = "${com.agent.app.util.pad2(ld.hour)}:${com.agent.app.util.pad2(ld.minute)}"
    when {
        mins < 1 -> com.agent.app.i18n.I18n.t("timeJustNow")
        mins < 60 -> com.agent.app.i18n.I18n.t("timeMinAgo", mins)
        ld.date == nd.date -> hm
        else -> "${ld.month.number}/${ld.day} $hm"
    }
} catch (_: Exception) {
    ""
}

/** Collapsible block without Material's expansion chrome (flutter
 *  `_CollapseBlock`): a chevron + label row that toggles the body. */
@Composable
fun CollapseBlock(
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    labelWeight: FontWeight = FontWeight.Normal,
    initiallyOpen: Boolean,
    container: @Composable (content: @Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    var open by remember(initiallyOpen) { mutableStateOf(initiallyOpen) }
    container {
        // Hug the content (flutter `_CollapseBlock` has no width modifier):
        // a collapsed reasoning/compaction block must not span the whole row.
        Column {
            Row(
                Modifier.appClickable(hoverWash = false) { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (open) AppIcons.chevron_down else AppIcons.chevron_right,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(AppSpacing.XS.dp))
                Text(label, style = AppText.micro.copy(fontWeight = labelWeight), color = labelColor)
            }
            if (open) content()
        }
    }
}

/** Reasoning (thinking) block: amber LEFT border, warning@5 fill, right-only
 *  radius — exactly flutter `_ReasoningBlock`. Expanded while streaming. */
@Composable
fun ReasoningBlock(text: String, streaming: Boolean) {
    val colors = LocalAppColors.current
    // Right-only radius (flutter: topRight/bottomRight = 6).
    val shape = RoundedCornerShape(topStart = 0.dp, topEnd = AppRadius.SM.dp, bottomEnd = AppRadius.SM.dp, bottomStart = 0.dp)
    CollapseBlock(
        label = t("thinkLabel") + if (streaming) "..." else "",
        labelColor = colors.warning,
        labelWeight = FontWeight.SemiBold,
        initiallyOpen = streaming,
        container = { body ->
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .clip(shape)
                    .background(colors.warning.copy(alpha = 0.05f))
                    // The 2dp amber stripe is DRAWN (not a Row child): a
                    // fillMaxHeight bar inside a scrollable Row has an infinite
                    // max-height constraint and would collapse to zero.
                    .drawBehind {
                        drawRect(
                            color = colors.warning,
                            topLeft = androidx.compose.ui.geometry.Offset.Zero,
                            size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
                        )
                    }
                    .padding(
                        start = AppSpacing.MD.dp, top = AppSpacing.XS.dp,
                        end = AppSpacing.SM.dp, bottom = AppSpacing.SM.dp,
                    ),
            ) { body() }
        },
    ) {
        Spacer(Modifier.height(AppSpacing.XS.dp))
        Text(text, style = AppText.meta, color = colors.mutedForeground)
    }
}

/** Compaction checkpoint block (flutter `_CompactionBlock`): muted@40 box,
 *  collapsed by default, foldable. */
@Composable
fun CompactionBlock(text: String) {
    val colors = LocalAppColors.current
    CollapseBlock(
        label = t("compactedLabel"),
        labelColor = colors.mutedForeground,
        initiallyOpen = false,
        container = { body ->
            Column(
                Modifier.widthIn(max = 520.dp)
                    .background(colors.muted.copy(alpha = 0.4f), AppRadius.sm)
                    .border(1.dp, colors.border.copy(alpha = 0.5f), AppRadius.sm)
                    .padding(horizontal = AppSpacing.MD.dp, vertical = AppSpacing.SM.dp),
            ) { body() }
        },
    ) {
        Spacer(Modifier.height(AppSpacing.XS.dp))
        Text(text, style = AppText.meta, color = colors.mutedForeground)
    }
}


/** Centered session-info card (flutter _SessionInfoDialog): every row shown,
 *  with placeholders when unset; Edit opens the settings dialog. */
@Composable
fun SessionInfoDialog(session: com.agent.app.models.Session?, sid: String, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val colors = LocalAppColors.current
    val none = t("none")
    val modelRef = session?.model?.ifEmpty { null } ?: none
    val variant = session?.variant?.ifEmpty { null } ?: t("variantNone")
    val preset = session?.preset?.ifEmpty { null } ?: none
    val locale = session?.locale?.ifEmpty { null } ?: t("agentLocaleFollow")
    val group = session?.group?.ifEmpty { null } ?: none
    val rows = listOf(
        t("modelLabel") to modelRef,
        t("variantLabel") to variant,
        t("presetLabel") to preset,
        t("agentLocale") to locale,
        // Generic grouping key (a subsession shows its parent session here).
        t("sessionGroupLabel") to group,
    )
    SimpleDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.chat, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(AppSpacing.SM.dp))
            Text(
                session?.id ?: sid,
                style = AppText.meta.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(AppSpacing.MD.dp))
        for ((i, row) in rows.withIndex()) {
            if (i > 0) AppDivider(color = colors.border.copy(alpha = 0.4f))
            Row(
                Modifier.fillMaxWidth().padding(vertical = AppSpacing.SM.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    row.first,
                    style = AppText.micro,
                    color = colors.mutedForeground,
                    modifier = Modifier.width(96.dp),
                )
                Text(row.second, style = AppText.meta.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.height(AppSpacing.MD.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(t("cancel"), Modifier.appClickable(hoverWash = false, onTap = onDismiss).padding(8.dp), style = AppText.small)
            Spacer(Modifier.width(AppSpacing.SM.dp))
            Row(
                Modifier.appClickable(shape = AppRadius.sm, hoverWash = false) { onDismiss(); onEdit() }
                    .background(colors.primary.copy(alpha = 0.12f), AppRadius.sm)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.edit, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.XS.dp))
                Text(t("edit"), style = AppText.small, color = colors.primary)
            }
        }
    }
}
