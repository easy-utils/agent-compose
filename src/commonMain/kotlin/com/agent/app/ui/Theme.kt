package com.agent.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.agent.app.platform.installSystemDarkListener
import com.agent.app.resources.Res
// Extension properties live in a sibling file of Res; Kotlin needs each one
// imported explicitly (Compose Resources' generated accessor convention).
import com.agent.app.resources.NotoColorEmoji
import com.agent.app.resources.NotoSansMono_Regular
import com.agent.app.resources.NotoSansMono_SemiBold
import com.agent.app.resources.NotoSansSC_Regular
import com.agent.app.resources.NotoSansSC_SemiBold
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Design tokens — port of flutter theme/app_theme.dart (opencode palette).
//
// The reference implementation (flutter) expresses its look through
// `ThemeData`: ~20 component-level themes plus a fully populated ColorScheme,
// so writing `TextField()` / `Card()` with no arguments is already correct.
// Compose has no equivalent component table, so this file reproduces the same
// result with two halves:
//   1. a complete MaterialTheme (ColorScheme + Shapes + Typography), and
//   2. the handful of wrapper composables below (AppTextField / AppSelect /
//      appClickable / AppDivider / appCard) that take the place of the
//      component themes.

data class AppColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val popover: Color,
    val primary: Color,
    val onPrimary: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val destructive: Color,
    val border: Color,
    val input: Color,
    val success: Color,
    val warning: Color,
) {
    companion object {
        val DARK = AppColors(
            background = Color(0xFF0a0a0a),
            foreground = Color(0xFFeeeeee),
            card = Color(0xFF141414),
            popover = Color(0xFF1e1e1e),
            primary = Color(0xFFfab283),
            onPrimary = Color(0xFF000000),
            muted = Color(0xFF181818),
            mutedForeground = Color(0xFF808080),
            accent = Color(0xFF9d7cd8),
            destructive = Color(0xFFe06c75),
            border = Color(0xFF484848),
            input = Color(0xFF3c3c3c),
            success = Color(0xFF7fd88f),
            warning = Color(0xFFf5a742),
        )
        val LIGHT = AppColors(
            background = Color(0xFFffffff),
            foreground = Color(0xFF1a1a1a),
            card = Color(0xFFfafafa),
            popover = Color(0xFFf5f5f5),
            primary = Color(0xFF3b7dd8),
            onPrimary = Color(0xFFffffff),
            muted = Color(0xFFf1f1f1),
            mutedForeground = Color(0xFF8a8a8a),
            accent = Color(0xFFd68c27),
            destructive = Color(0xFFd1383d),
            border = Color(0xFFb8b8b8),
            input = Color(0xFFd4d4d4),
            success = Color(0xFF3d9a57),
            warning = Color(0xFFd68c27),
        )
    }
}

object AppSpacing {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
}

object AppBars {
    const val HEIGHT = 48
}

object AppLayout {
    const val COMPACT_BELOW = 640
    const val WIDE_THRESHOLD = 1024
    const val RAIL_WIDTH = 96
}

/** Corner radii — mirrors flutter `AppRadius` (sm 6 / md 8 / lg 12). */
object AppRadius {
    const val SM = 6
    const val MD = 8
    const val LG = 12

    val sm = RoundedCornerShape(SM.dp)
    val md = RoundedCornerShape(MD.dp)
    val lg = RoundedCornerShape(LG.dp)

    /** Capsules / pills (badges, chips, unread counts). */
    val pill = RoundedCornerShape(999.dp)
}

/**
 * Type scale — mirrors flutter `AppTypography` (micro 10 / meta 12 / body 14)
 * plus the few in-between sizes the reference uses for titles.
 *
 * The font family is set by [AppTheme] through [family] / [monoFamily] BEFORE
 * the first content() call, and the styles below are computed getters so a
 * single assignment reaches every call site.
 *
 * Why not `LocalTextStyle`: Material3's `Text` does
 * `style.merge(TextStyle(fontFamily = fontFamily, …))` — the *passed* style
 * wins over `LocalTextStyle`, so a caller doing `Text(style = AppText.meta)`
 * silently dropped any family we injected there. Computing the family into the
 * styles themselves is the only way to cover all 130+ call sites.
 */
object AppText {
    /** Bundled UI family (Noto Sans SC 400/600); injected by [AppTheme]. */
    var family: FontFamily = FontFamily.Default
    /** Bundled monospace family (Noto Sans Mono 400/600). */
    var monoFamily: FontFamily = FontFamily.Monospace

    val micro get() = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontFamily = family)
    val tiny get() = TextStyle(fontSize = 9.sp, lineHeight = 13.sp, fontFamily = family)
    val meta get() = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = family)
    val small get() = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = family)
    val body get() = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFamily = family)
    /** Page/app-bar titles — flutter pins `appBarTheme.titleTextStyle` to
     *  `body` + w600 (14sp semibold). */
    val title get() = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, fontFamily = family,
    )
    val screenTitle get() = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontFamily = family)
    val mono get() = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = monoFamily)

    val label get() = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = family)
}

/**
 * The bundled UI families. `LocalAppFontFamily` carries Noto Sans SC
 * (Regular 400 + SemiBold 600 statics), `LocalAppMonoFamily` carries Noto Sans
 * Mono plus Noto Sans SC as the CJK fallback — the same faces and weights the
 * Flutter/WebUI/SwiftUI clients bundle, so glyph rasterization matches.
 */
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
val LocalAppMonoFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** Monospace style for code / paths / diffs, with the bundled family applied. */
@Composable
fun monoStyle(base: TextStyle = AppText.mono): TextStyle =
    base.copy(fontFamily = LocalAppMonoFamily.current)

val LocalAppColors = staticCompositionLocalOf { AppColors.DARK }

/**
 * Resolve the tri-state theme pref ("system" | "light" | "dark") into the dark
 * flag the theme consumes. "system" is live: Android reacts through
 * isSystemInDarkTheme() (LocalConfiguration), the web through the JS
 * prefers-color-scheme bridge (its isSystemInDarkTheme() is constant), desktop
 * has no OS bridge and resolves to light.
 */
@Composable
fun rememberDarkFlag(themeMode: String): Boolean {
    val system = isSystemInDarkTheme()
    var bridgeDark by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        installSystemDarkListener { bridgeDark = it }
    }
    return when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> bridgeDark ?: system
    }
}

private val AppShapes = Shapes(
    extraSmall = AppRadius.sm,
    small = AppRadius.md,
    medium = AppRadius.md,
    large = AppRadius.lg,
    extraLarge = RoundedCornerShape(16.dp),
)

private val AppTypography = Typography(
    titleLarge = AppText.title,
    titleMedium = AppText.body.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = AppText.meta.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = AppText.body,
    bodyMedium = AppText.body,
    bodySmall = AppText.meta,
    labelLarge = AppText.small.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = AppText.meta,
    labelSmall = AppText.micro,
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) AppColors.DARK else AppColors.LIGHT
    // Bundled faces (tools/gen-fonts.py): Noto Sans SC 400/600 for the UI and
    // Noto Sans Mono 400/600 for code, with Noto Sans SC chained behind the
    // mono face so CJK inside code blocks still renders. The same files ship
    // with the Flutter / WebUI / SwiftUI clients, so glyph rasterization is
    // identical across all four.
    // The colour-emoji face rides in the SAME family: Skia walks the family's
    // fonts per glyph, so an emoji codepoint missing from Noto Sans SC falls
    // through to NotoColorEmoji instead of the engine's remote fallback
    // service (which would fetch fonts.gstatic.com — the app must not depend
    // on a CDN, and the other clients bundle the same emoji font).
    val sans = FontFamily(
        Font(Res.font.NotoSansSC_Regular, FontWeight.Normal),
        Font(Res.font.NotoSansSC_SemiBold, FontWeight.SemiBold),
        // A DISTINCT weight key: FontFamily entries are keyed by
        // (weight, style), so a second Normal/Normal face would be dropped.
        // On web Skia may still prefer its remote fallback for emoji, but
        // Android/desktop have none — without this face they render tofu.
        Font(Res.font.NotoColorEmoji, FontWeight.Normal),
    )
    val mono = FontFamily(
        Font(Res.font.NotoSansMono_Regular, FontWeight.Normal),
        Font(Res.font.NotoSansMono_SemiBold, FontWeight.SemiBold),
    )
    // Publish to the type scale BEFORE composing content: every AppText getter
    // reads these, so all 130+ `style = AppText.x` call sites pick up the
    // bundled family without touching a single one of them.
    AppText.family = sans
    AppText.monoFamily = mono
    // Every ColorScheme role the built-in components read is pinned to a
    // palette token. Roles left at their Material defaults (the missing
    // onSurfaceVariant / surfaceContainerHighest / secondaryContainer in the
    // previous theme) are what made dialogs, menus and chips fall back to the
    // purple/grey M3 baseline.
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primary.copy(alpha = 0.20f),
            onPrimaryContainer = c.primary,
            secondary = c.accent,
            onSecondary = c.onPrimary,
            secondaryContainer = c.muted,
            onSecondaryContainer = c.foreground,
            tertiary = c.accent,
            onTertiary = c.onPrimary,
            background = c.background,
            onBackground = c.foreground,
            surface = c.background,
            onSurface = c.foreground,
            surfaceVariant = c.muted,
            onSurfaceVariant = c.mutedForeground,
            surfaceContainerLowest = c.background,
            surfaceContainerLow = c.card,
            surfaceContainer = c.card,
            surfaceContainerHigh = c.popover,
            surfaceContainerHighest = c.muted,
            inverseSurface = c.foreground,
            inverseOnSurface = c.background,
            error = c.destructive,
            onError = Color(0xFF000000),
            errorContainer = c.destructive.copy(alpha = 0.20f),
            onErrorContainer = c.destructive,
            outline = c.border,
            outlineVariant = c.border.copy(alpha = 0.5f),
            scrim = Color(0xFF000000),
        )
    } else {
        lightColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primary.copy(alpha = 0.12f),
            onPrimaryContainer = c.primary,
            secondary = c.accent,
            onSecondary = c.onPrimary,
            secondaryContainer = c.muted,
            onSecondaryContainer = c.foreground,
            tertiary = c.accent,
            onTertiary = c.onPrimary,
            background = c.background,
            onBackground = c.foreground,
            surface = c.background,
            onSurface = c.foreground,
            surfaceVariant = c.muted,
            onSurfaceVariant = c.mutedForeground,
            surfaceContainerLowest = c.background,
            surfaceContainerLow = c.card,
            surfaceContainer = c.card,
            surfaceContainerHigh = c.popover,
            surfaceContainerHighest = c.muted,
            inverseSurface = c.foreground,
            inverseOnSurface = c.background,
            error = c.destructive,
            onError = Color(0xFFFFFFFF),
            errorContainer = c.destructive.copy(alpha = 0.12f),
            onErrorContainer = c.destructive,
            outline = c.border,
            outlineVariant = c.border.copy(alpha = 0.5f),
            scrim = Color(0xFF000000),
        )
    }
    CompositionLocalProvider(
        LocalAppColors provides c,
        LocalAppFontFamily provides sans,
        LocalAppMonoFamily provides mono,
        // Material components that build their own style (buttons, menus) read
        // LocalTextStyle; AppText carries the family for explicit call sites.
        androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = sans),
        // Material3's `Text` resolves its color as
        // `color ?: style.color ?: LocalContentColor.current`, and
        // LocalContentColor DEFAULTS TO BLACK — so every Text() without an
        // explicit color stayed black on our dark background (the "dark mode
        // text is not white" bug; Flutter did not have it because ThemeData
        // carries the text theme colors). Pin the content color to the
        // palette's foreground so both themes read correctly.
        androidx.compose.material3.LocalContentColor provides c.foreground,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = AppShapes,
            // Material defaults (title* / body* / label*) also run through the
            // bundled family, so a component we did not restyle still matches.
            typography = AppTypography.copy(
                displayLarge = AppTypography.displayLarge.copy(fontFamily = sans),
                displayMedium = AppTypography.displayMedium.copy(fontFamily = sans),
                displaySmall = AppTypography.displaySmall.copy(fontFamily = sans),
                headlineLarge = AppTypography.headlineLarge.copy(fontFamily = sans),
                headlineMedium = AppTypography.headlineMedium.copy(fontFamily = sans),
                headlineSmall = AppTypography.headlineSmall.copy(fontFamily = sans),
                titleLarge = AppText.title.copy(fontFamily = sans),
                titleMedium = AppText.body.copy(fontWeight = FontWeight.SemiBold, fontFamily = sans),
                titleSmall = AppText.meta.copy(fontWeight = FontWeight.SemiBold, fontFamily = sans),
                bodyLarge = AppText.body.copy(fontFamily = sans),
                bodyMedium = AppText.body.copy(fontFamily = sans),
                bodySmall = AppText.meta.copy(fontFamily = sans),
                labelLarge = AppText.small.copy(fontWeight = FontWeight.SemiBold, fontFamily = sans),
                labelMedium = AppText.meta.copy(fontFamily = sans),
                labelSmall = AppText.micro.copy(fontFamily = sans),
            ),
            content = content,
        )
    }
}

// ---- shared building blocks (the Compose stand-in for the flutter component
// ---- themes) ---------------------------------------------------------------

/** Menu gestures for a bubble/card that must keep its TEXT natively selectable.
 *
 *  - Touch: LONG-PRESS (500ms, no movement) off text opens [onMenu].
 *  - Mouse: RIGHT-CLICK opens [onMenu]; left press/drag is left entirely to
 *    text selection (so clicking anywhere does NOT pop the menu).
 *
 *  Runs on the **Main** pass and skips when the down is already consumed — a
 *  `SelectionContainer` child consumes the press on text (mouse and touch), so
 *  pressing text never opens the menu. This is what makes the behaviour match
 *  flutter/webui: long-press ON text = selection, long-press on the bubble
 *  chrome = menu.
 *
 *  We deliberately avoid `combinedClickable`, whose pointer handling competes
 *  with `SelectionContainer`'s text selection.
 */
@Composable
fun Modifier.bubbleMenuGestures(onMenu: () -> Unit): Modifier {
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    return this.pointerInput(onMenu) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                // Already claimed by a descendant (text selection) => ignore.
                if (down.isConsumed) {
                    while (currentEvent.changes.any { it.pressed }) {
                        awaitPointerEvent(PointerEventPass.Main)
                    }
                    continue
                }
                val isMouse = currentEvent.changes.any { it.type == PointerType.Mouse }
                if (isMouse) {
                    // Right/middle click opens the menu; a left click is a
                    // selection gesture and is ignored here.
                    if (currentEvent.buttons.isSecondaryPressed ||
                        currentEvent.buttons.isTertiaryPressed
                    ) {
                        onMenu()
                        down.consume()
                    }
                    while (currentEvent.changes.any { it.pressed }) {
                        awaitPointerEvent(PointerEventPass.Main)
                    }
                    continue
                }
                // Touch: long-press with no movement opens the menu. The elapsed
                // time comes from the event's own uptime (reliable on wasm).
                val startTime = down.uptimeMillis
                var moved = false
                var fired = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = e.changes.any { it.pressed }
                    if (e.changes.any { it.positionChanged() }) moved = true
                    if (!fired && !moved && e.changes.isNotEmpty() &&
                        e.changes.first().uptimeMillis - startTime >= longPressMs
                    ) {
                        fired = true
                        onMenu()
                        down.consume()
                    }
                    if (!pressed) break
                }
            }
        }
    }
}

/** Focus/hover-aware click. Adds the hand cursor on desktop/web and the same
 *  `hover:bg-muted` wash the shadcn components use (webui has ~85 hover:
 *  rules; Compose had none). */
@Composable
fun Modifier.appClickable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(0.dp),
    hoverWash: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit,
): Modifier {
    val colors = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    return this
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .background(
            if (enabled && hoverWash && hovered) colors.muted.copy(alpha = 0.55f) else Color.Transparent,
            shape,
        )
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onLongClick = onLongPress,
            onClick = onTap,
        )
}

/** Hand cursor without a hover wash (header icon buttons). */
fun Modifier.appCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** Card surface: card fill + hairline border + lg radius (flutter cardTheme). */
@Composable
fun Modifier.appCard(
    radius: Shape = AppRadius.lg,
    fill: Color? = null,
): Modifier {
    val colors = LocalAppColors.current
    return this
        .background(fill ?: colors.card, radius)
        .border(1.dp, colors.border.copy(alpha = 0.6f), radius)
}

/** Uppercase section caption (flutter `_SectionHeader`). */
@Composable
fun SectionLabel(label: String, modifier: Modifier = Modifier, uppercase: Boolean = true) {
    Text(
        if (uppercase) label.uppercase() else label,
        style = AppText.micro.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        color = LocalAppColors.current.mutedForeground,
        modifier = modifier,
    )
}

/** Hairline divider matching flutter dividerTheme (border @ 0.5, 1dp). */
@Composable
fun AppDivider(modifier: Modifier = Modifier, color: Color? = null) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = color ?: LocalAppColors.current.border.copy(alpha = 0.5f),
    )
}

// ---- form controls ---------------------------------------------------------

/** The single text field used across the app — flutter's
 *  `inputDecorationTheme` equivalent: muted fill, 8dp outline that turns
 *  primary on focus, floating in-box label, 14sp value / 12sp label.
 *
 *  `plain = true` drops fill and outline for fields that live inside their own
 *  shell (the chat composer), mirroring flutter's `isCollapsed` + no-border
 *  decoration. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isPassword: Boolean = false,
    plain: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
) {
    val colors = LocalAppColors.current
    val transformation =
        if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else VisualTransformation.None
    val labelSlot: (@Composable () -> Unit)? = label?.let { { Text(it, style = AppText.meta) } }
    val placeholderSlot: (@Composable () -> Unit)? =
        placeholder?.let { { Text(it, style = AppText.body, color = colors.mutedForeground) } }
    val textModifier = if (onPreviewKeyEvent != null) {
        modifier.fillMaxWidth().onPreviewKeyEvent(onPreviewKeyEvent)
    } else {
        modifier.fillMaxWidth()
    }

    if (plain) {
        // Decoration-free field for callers that own their own shell (the chat
        // composer). A Material `TextField` always reserves TextFieldDefaults
        // .MinHeight (56dp), which would make the composer taller than
        // Flutter's 42dp shell — `BasicTextField` has no such minimum.
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = textModifier,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = AppText.body.copy(color = colors.foreground),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            visualTransformation = transformation,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholderSlot != null) placeholderSlot()
                    inner()
                }
            },
        )
        return
    }


    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = textModifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        label = labelSlot,
        placeholder = placeholderSlot,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = transformation,
        textStyle = AppText.body,
        shape = AppRadius.md,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.foreground,
            unfocusedTextColor = colors.foreground,
            disabledTextColor = colors.mutedForeground,
            focusedContainerColor = colors.muted,
            unfocusedContainerColor = colors.muted,
            disabledContainerColor = colors.muted.copy(alpha = 0.5f),
            cursorColor = colors.primary,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.border.copy(alpha = 0.6f),
            disabledBorderColor = colors.border.copy(alpha = 0.3f),
            errorBorderColor = colors.destructive,
            focusedLabelColor = colors.primary,
            unfocusedLabelColor = colors.mutedForeground,
            disabledLabelColor = colors.mutedForeground.copy(alpha = 0.6f),
            focusedPlaceholderColor = colors.mutedForeground,
            unfocusedPlaceholderColor = colors.mutedForeground,
            focusedTrailingIconColor = colors.mutedForeground,
            unfocusedTrailingIconColor = colors.mutedForeground,
        ),
    )
}

/** In-place select — flutter `DropdownButtonFormField` with the same box as
 *  [AppTextField]: muted fill, outline, floating in-box label, chevron. The
 *  option list opens directly below the field. */
@Composable
fun AppSelect(
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = LocalAppColors.current
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == value }?.second?.ifEmpty { value }
        ?: value.ifEmpty { options.firstOrNull()?.second ?: "—" }

    Column(modifier) {
        Box(Modifier.padding(top = if (label != null) 6.dp else 0.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .appClickable(enabled = enabled, shape = AppRadius.md) { open = !open }
                    .border(
                        1.dp,
                        if (open) colors.primary else colors.border.copy(alpha = 0.6f),
                        AppRadius.md,
                    )
                    .background(colors.muted, AppRadius.md)
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        shown,
                        style = AppText.body,
                        color = if (enabled) colors.foreground else colors.mutedForeground,
                        // Long `provider_id/model_id` refs wrap instead of
                        // being clipped; the row grows to fit (parity with the
                        // flutter isExpanded dropdown).
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(
                    AppIcons.chevron_down,
                    contentDescription = null,
                    tint = colors.mutedForeground,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (label != null) {
                // Floating label sitting in a notch of the outline — the same
                // treatment OutlinedTextField gives AppTextField.
                Text(
                    label,
                    style = AppText.meta,
                    color = if (open) colors.primary else colors.mutedForeground,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 12.dp, y = (-7).dp)
                        .background(colors.background, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
            }
        }
        // Anchored OVERLAY menu (Material DropdownMenu): the option list floats
        // above the layout and does NOT grow the surrounding card, matching the
        // webui <select> / flutter dropdown / swiftui Menu behaviour. A plain
        // click always fires (no long-press), and the menu is scrollable so a
        // long option list never overflows the screen.
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.popover,
            modifier = Modifier.heightIn(max = 320.dp).widthIn(min = 220.dp),
        ) {
            for ((v, l) in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            l.ifEmpty { v },
                            style = AppText.body,
                            color = if (v == value) colors.primary else colors.foreground,
                            fontWeight = if (v == value) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (v == value) {
                        {
                            Icon(
                                AppIcons.check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(v)
                    },
                )
            }
        }
    }
}
