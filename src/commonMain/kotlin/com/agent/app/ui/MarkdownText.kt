package com.agent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberStreamingMarkdownState

// MarkdownText — the compose counterpart of flutter_markdown_plus / web
// marked+hljs, backed by `multiplatform-markdown-renderer-m3` (JetBrains
// `org.jetbrains:markdown` GFM parser + Compose M3 components).
//
// STREAMING: chat text grows one delta at a time. `Markdown(content = …)` uses
// `rememberMarkdownState`, which REPARSES THE WHOLE STRING asynchronously and
// runs the result through `conflate()` — intermediate updates are dropped and
// only the newest snapshot is drawn, so the body appeared to "spread out" in
// chunks instead of typing character-by-character (the reasoning block, a
// plain `Text`, typed smoothly and made the mismatch obvious).
//
// The library's streaming API is the fix: a `StreamingMarkdownState` is fed the
// appended SUFFIX only and reparses just the unstable tail, so each delta
// paints immediately and in order — the same per-delta feel as `Text` and as
// flutter_markdown_plus.
//
// NO SIZE ANIMATION: the library's default `markdownAnimations` applies
// `Modifier.animateContentSize()` to every text element, so each delta smoothly
// TWEENS the height (the body visually "stretched" evenly) while the reasoning
// block, a plain `Text`, grew line-by-line. Flutter (`MarkdownBody` + instant
// `jumpTo`) and webui (no CSS transition + rAF jump) both reflow instantly, so
// the size animation is disabled here: the bubble now grows a whole line at a
// time and the bottom-pinned list jumps down with it.

/** Chat-bubble markdown: the shared type scale, no external link handling. */
@Composable
fun MarkdownText(src: String, fontSizeSp: Int = 14, muted: Boolean = false) {
    val app = LocalAppColors.current
    val textColor = if (muted) app.mutedForeground else app.foreground
    // The bundled UI family (Noto Sans SC 400/600) comes from the theme.
    val sans = LocalAppFontFamily.current
    val mono = LocalAppMonoFamily.current
    val base = TextStyle(fontSize = fontSizeSp.sp, color = textColor, fontFamily = sans)

    val typography = markdownTypography(
        h1 = base.copy(fontSize = (fontSizeSp + 5).sp, fontWeight = FontWeight.SemiBold),
        h2 = base.copy(fontSize = (fontSizeSp + 3).sp, fontWeight = FontWeight.SemiBold),
        h3 = base.copy(fontSize = (fontSizeSp + 1).sp, fontWeight = FontWeight.SemiBold),
        h4 = base.copy(fontWeight = FontWeight.SemiBold),
        h5 = base.copy(fontWeight = FontWeight.SemiBold),
        h6 = base.copy(fontWeight = FontWeight.SemiBold),
        text = base,
        paragraph = base,
        ordered = base,
        bullet = base,
        list = base,
        quote = base.copy(color = app.mutedForeground, fontStyle = FontStyle.Italic),
        code = TextStyle(fontSize = (fontSizeSp - 2).sp, fontFamily = mono, color = app.foreground),
        inlineCode = TextStyle(fontSize = (fontSizeSp - 1).sp, fontFamily = mono, color = app.foreground),
        // 0.39+ replaced the `link` TextStyle with a TextLinkStyles bundle
        // (hover/pressed variants). Text colours live in the Typography.
        textLink = TextLinkStyles(
            style = base.copy(color = app.primary, fontWeight = FontWeight.Medium).toSpanStyle(),
            hoveredStyle = base.copy(color = app.primary, fontWeight = FontWeight.Medium).toSpanStyle(),
        ),
        table = base.copy(fontSize = (fontSizeSp - 1).sp),
    )
    val colors = markdownColor(
        text = textColor,
        codeBackground = app.muted,
        inlineCodeBackground = app.muted,
        dividerColor = app.border,
        tableBackground = app.muted.copy(alpha = 0.4f),
    )
    StreamingMarkdownBody(src = src, colors = colors, typography = typography)
}

/**
 * Feeds [src] into a [StreamingMarkdownState] append-only while it only grows
 * (the streaming case). When the text is REPLACED (edit / retry / adopting the
 * server copy of a message), the prefix no longer matches and the state is
 * rebuilt from scratch via [rebuildKey].
 *
 * `MarkdownText` is called once per message item (LazyColumn keys by message
 * id), so this state is naturally scoped to one bubble.
 */
@Composable
private fun StreamingMarkdownBody(
    src: String,
    colors: com.mikepenz.markdown.model.MarkdownColors,
    typography: com.mikepenz.markdown.model.MarkdownTypography,
) {
    var rebuildKey by remember { mutableStateOf(0) }
    key(rebuildKey) {
        val state = rememberStreamingMarkdownState(lookupLinks = false)
        LaunchedEffect(src) {
            val have = state.content.toString()
            when {
                // Append-only growth: hand over JUST the new suffix so only the
                // unstable tail is reparsed and every delta paints in order.
                src.length > have.length && src.startsWith(have) ->
                    state.append(src.substring(have.length))
                // Diverged (or shortened): the accumulated AST no longer
                // describes this text — rebuild from the full string.
                src != have -> rebuildKey++
            }
        }
        // Chat bubbles must HUG their content (flutter MarkdownBody has no width
        // modifier): fillMaxWidth here forced EVERY bubble to the full row width.
        Markdown(
            streamingMarkdownState = state,
            colors = colors,
            typography = typography,
            modifier = Modifier.wrapContentSize(align = Alignment.TopStart, unbounded = false),
            padding = markdownPadding(block = 2.dp, listIndent = 12.dp),
            // Identity modifier => NO animateContentSize: reflow instantly, a
            // whole line at a time, exactly like flutter/webui streaming.
            animations = markdownAnimations(animateTextSize = { this }),
        )
    }
}
