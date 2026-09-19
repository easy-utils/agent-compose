package com.agent.app.ui

import androidx.compose.ui.Modifier

// Web DnD needs DOM events outside the Compose canvas; not wired yet.
actual fun Modifier.onDropFiles(
    onFiles: (List<String>) -> Unit,
    onFilesBytes: (List<Triple<String, String?, ByteArray>>) -> Unit,
    onDragging: (Boolean) -> Unit,
): Modifier = this
