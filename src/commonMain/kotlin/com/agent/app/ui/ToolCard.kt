package com.agent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.agent.app.i18n.I18n.t
import com.agent.app.models.ChatPart
import com.agent.app.models.ToolState

// ToolCard — the tool call card (flutter widgets/tool_part.dart). EVERY tool
// renders the exact same card: a borderless muted card whose header shows
// status → ONE fixed glyph (`tools` / primary) → name → italic title →
// chevron, and whose SECTIONS are bordered sub-boxes (input / content /
// metadata) that each fold independently. Nothing varies by tool family; a
// result carrying file refs in its `data.files` additionally
// renders media cards in the content section.

/** flutter `toolDisplayName`: `todowrite` shows as `todo`. */
fun toolDisplayName(name: String): String = if (name == "todowrite") "todo" else name

private fun prettyJson(m: Map<String, Any?>): String = buildString {
    append("{\n")
    m.entries.forEachIndexed { i, (k, v) ->
        append("  \"$k\": ")
        append(
            when (v) {
                null -> "null"
                is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
                is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { (mk, mv) -> "\"$mk\": ${mv ?: "null"}" }
                is List<*> -> v.joinToString(", ", "[", "]") { it?.toString()?.let { s -> "\"$s\"" } ?: "null" }
                else -> v.toString()
            },
        )
        if (i < m.size - 1) append(",")
        append("\n")
    }
    append("}")
}

/**
 * mediaRefs — produced-file refs in a tool result's `data.files` (each
 * `{code, mime, name, bytes}`, or a list of them). Every client collects the
 * same single key.
 */
private fun mediaRefs(data: Map<String, Any?>?): List<Triple<String, String?, String?>> {
    if (data == null) return emptyList()
    val out = ArrayList<Triple<String, String?, String?>>()
    fun collect(v: Any?) {
        val m = v as? Map<*, *> ?: return
        val code = m["code"] as? String
        if (!code.isNullOrEmpty()) {
            out.add(Triple(code, m["mime"] as? String, m["name"] as? String))
        }
    }
    when (val v = data["files"]) {
        is List<*> -> v.forEach { collect(it) }
        null -> {}
        else -> collect(v)
    }
    return out
}

@Composable
fun ToolCard(
    part: ChatPart,
    streaming: Boolean,
    api: com.agent.app.AgentApi? = null,
    onOpenMedia: (com.agent.app.models.AttachmentRef) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val state = part.state ?: return
    var open by remember { mutableStateOf(true) }
    var inputOpen by remember { mutableStateOf(true) }
    var contentOpen by remember { mutableStateOf(true) }
    var metaOpen by remember { mutableStateOf(true) }

    val running = streaming && state.status == "running"
    val hasError = state.status == "error"
    val input = state.input ?: emptyMap()
    val data = state.data ?: emptyMap()
    val changeId = state.changeId ?: (data["change_id"] as? String)
    val diff = state.diff ?: (data["diff"] as? String)
    val hasMeta = !changeId.isNullOrEmpty() || !diff.isNullOrEmpty() ||
        (state.additions ?: 0) > 0 || (state.deletions ?: 0) > 0

    val name = toolDisplayName(part.tool.ifEmpty { state.title }.ifEmpty { "tool" })

    Column(
        // flutter: NO outer border; error tint destructive@5, else muted@35.
        // Hugs content like the message bubble (flutter has no width modifier).
        Modifier.widthIn(max = 520.dp).background(
            if (hasError) colors.destructive.copy(alpha = 0.05f) else colors.muted.copy(alpha = 0.35f),
            AppRadius.sm,
        ),
    ) {
        // header: status → ONE fixed glyph → name → italic title → chevron
        Row(
            Modifier.fillMaxWidth().appClickable { open = !open }.padding(horizontal = AppSpacing.SM.dp, vertical = AppSpacing.XS.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    hasError -> AppIcons.error
                    running -> AppIcons.more
                    else -> AppIcons.success
                },
                contentDescription = null,
                tint = if (hasError) colors.destructive else if (running) colors.warning else colors.success,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Icon(AppIcons.tools, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Text(
                name,
                style = AppText.meta.copy(fontWeight = FontWeight.SemiBold),
                color = colors.mutedForeground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.title.isNotEmpty()) {
                Spacer(Modifier.width(AppSpacing.XS.dp))
                Text(
                    state.title,
                    style = AppText.micro.copy(fontStyle = FontStyle.Italic),
                    color = colors.mutedForeground,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                if (open) AppIcons.chevron_up else AppIcons.chevron_down,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(14.dp),
            )
        }
        if (open) {
            if (input.isNotEmpty()) {
                ToolSection(t("toolInputParams"), AppIcons.braces, inputOpen, onToggle = { inputOpen = !inputOpen }) {
                    MonoBody(prettyJson(input))
                }
            } else if (!state.inputText.isNullOrEmpty()) {
                // Arguments still streaming (tool-input-delta): show the raw
                // JSON as it arrives, before `tool-call` delivers the parsed input.
                ToolSection(t("toolInputParams"), AppIcons.braces, inputOpen, onToggle = { inputOpen = !inputOpen }) {
                    MonoBody(state.inputText!!)
                }
            }
            if (hasError) {
                ToolSection(t("error"), AppIcons.error, true, destructive = true) {
                    MonoBody(state.error ?: "", color = colors.destructive)
                }
            }
            ToolSection(t("content"), AppIcons.file, contentOpen, onToggle = { contentOpen = !contentOpen }) {
                Column(Modifier.fillMaxWidth()) {
                    if (running) {
                        Text(
                            t("running"),
                            style = AppText.micro.copy(fontStyle = FontStyle.Italic),
                            color = colors.mutedForeground,
                        )
                    } else if (!state.output.isNullOrEmpty()) {
                        MonoBody(state.output)
                    }
                    // Produced-file refs the tool emitted in `data.files`
                    // render as first-class media cards — the "file field" case.
                    if (api != null) {
                        for (ref in mediaRefs(data)) {
                            Spacer(Modifier.size(AppSpacing.XS.dp))
                            MessageFilePart(
                                api = api,
                                code = ref.first,
                                name = ref.third,
                                mime = ref.second,
                                size = null,
                                onOpen = onOpenMedia,
                            )
                        }
                    }
                }
            }
            if (hasMeta) {
                ToolSection(t("metadata"), AppIcons.info, metaOpen, onToggle = { metaOpen = !metaOpen }) {
                    Column(Modifier.fillMaxWidth()) {
                        changeId?.takeIf { it.isNotEmpty() }?.let {
                            MetaRow(AppIcons.commit, "change_id", it, colors.primary)
                        }
                        val adds = state.additions ?: 0
                        val dels = state.deletions ?: 0
                        if (adds > 0 || dels > 0) {
                            MetaRow(
                                AppIcons.diff,
                                "diff",
                                "+$adds -$dels",
                                if (dels > 0) colors.destructive else colors.success,
                            )
                        }
                        diff?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.size(AppSpacing.XS.dp))
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(colors.muted.copy(alpha = 0.4f), AppRadius.sm)
                                    .heightIn(max = 220.dp)
                                    .padding(AppSpacing.XS.dp),
                            ) {
                                MonoTextScroll(it)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A collapsible labelled section inside a tool card (flutter `_Section`):
 *  8px outer margins, `background@50` fill, `border@50` (destructive@40 when
 *  marking an error) and a chevron + icon + label header. */
@Composable
private fun ToolSection(
    title: String,
    icon: ImageVector,
    open: Boolean,
    destructive: Boolean = false,
    onToggle: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxWidth()
            .padding(start = AppSpacing.SM.dp, end = AppSpacing.SM.dp, bottom = AppSpacing.SM.dp)
            .background(colors.background.copy(alpha = 0.5f), AppRadius.sm)
            .border(
                1.dp,
                if (destructive) colors.destructive.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.5f),
                AppRadius.sm,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().appClickable { onToggle() }
                .padding(horizontal = AppSpacing.SM.dp, vertical = AppSpacing.XS.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) AppIcons.chevron_down else AppIcons.chevron_right,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) colors.destructive else colors.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(AppSpacing.XS.dp))
            Text(
                title,
                style = AppText.micro,
                color = if (destructive) colors.destructive else colors.mutedForeground,
            )
        }
        if (open) {
            Box(Modifier.padding(start = AppSpacing.SM.dp, end = AppSpacing.SM.dp, bottom = AppSpacing.SM.dp)) {
                content()
            }
        }
    }
}

/** Metadata row: icon + label + value (flutter `_Row`). */
@Composable
private fun MetaRow(icon: ImageVector, label: String, value: String, color: Color) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = AppSpacing.XS.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(AppSpacing.XS.dp))
        Text(label, style = AppText.micro, color = colors.mutedForeground)
        Spacer(Modifier.width(AppSpacing.SM.dp))
        Text(
            value,
            style = AppText.micro.copy(fontFamily = LocalAppMonoFamily.current),
            color = color,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Selectable mono body, maxHeight 220, scrollable (flutter `_MonoText`). */
@Composable
private fun MonoBody(text: String, color: Color? = null) {
    val colors = LocalAppColors.current
    Box(
        Modifier.fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(AppSpacing.XS.dp),
    ) {
        MonoTextScroll(text, color ?: colors.foreground)
    }
}

@Composable
private fun MonoTextScroll(text: String, color: Color = LocalAppColors.current.foreground) {
    val scroll = rememberScrollState()
    Text(
        text,
        style = AppText.tiny.copy(fontFamily = LocalAppMonoFamily.current),
        color = color,
        modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
    )
}
