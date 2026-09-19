package com.agent.app.ui

import androidx.compose.ui.Modifier

actual fun Modifier.onDropFiles(
    onFiles: (List<String>) -> Unit,
    onFilesBytes: (List<Triple<String, String?, ByteArray>>) -> Unit,
    onDragging: (Boolean) -> Unit,
): Modifier = this
