package com.agent.app.ui

import androidx.compose.ui.Modifier

/**
 * Drag-and-drop file intake (desktop/web where a real filesystem or browser
 * drop event exists). Returns Modifier unchanged on platforms without DnD.
 *
 * [onDragging] mirrors the other clients' drop overlay: called true while a
 * compatible drag hovers the target and false when it leaves or drops, so the
 * UI can show the same "drop to attach" affordance.
 */
expect fun Modifier.onDropFiles(
    onFiles: (List<String>) -> Unit,
    onFilesBytes: (List<Triple<String, String?, ByteArray>>) -> Unit,
    onDragging: (Boolean) -> Unit = {},
): Modifier
