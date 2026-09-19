package com.agent.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.agent.app.AgentApi
import com.agent.app.models.AttachmentRef

// Parses embedded `[附件 <name> | file:<code> | <mime> | <size>]` references
// out of a text part and splits the bubble into inline markdown + file chips
// (flutter message_bubble.dart _FileRefsText / _splitFileRefs).

private val FILE_REF_RE = Regex(
    """\[附件\s+(.+?)\s*\|\s*file:([0-9a-zA-Z]+)\s*\|\s*([^|\]]*)\s*\|\s*([^\]|]*)\]""",
)

private sealed class TextSegment {
    data class Md(val text: String) : TextSegment()
    data class File(val label: String, val code: String, val mime: String?) : TextSegment()
}

private fun splitFileRefs(text: String): List<TextSegment> {
    val out = ArrayList<TextSegment>()
    var idx = 0
    for (m in FILE_REF_RE.findAll(text)) {
        if (m.range.first > idx) out.add(TextSegment.Md(text.substring(idx, m.range.first)))
        val mime = m.groupValues[3].trim().ifEmpty { null }
        out.add(TextSegment.File(m.groupValues[1].trim(), m.groupValues[2], mime))
        idx = m.range.last + 1
    }
    if (idx < text.length) out.add(TextSegment.Md(text.substring(idx)))
    return out
}

@Composable
fun TextWithFileRefs(
    text: String,
    api: AgentApi,
    onOpenMedia: (AttachmentRef) -> Unit,
) {
    val parts = splitFileRefs(text)
    if (parts.none { it is TextSegment.File }) {
        MarkdownText(text)
        return
    }
    Column {
        for (p in parts) {
            when (p) {
                is TextSegment.Md -> if (p.text.isNotBlank()) MarkdownText(p.text.trim())
                is TextSegment.File -> {
                    MessageFilePart(
                        api = api,
                        code = p.code, name = p.label, mime = p.mime, size = null,
                        onOpen = onOpenMedia,
                    )
                }
            }
        }
    }
}
