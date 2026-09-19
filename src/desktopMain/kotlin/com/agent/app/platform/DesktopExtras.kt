package com.agent.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

// Desktop voice recorder — javax.sound 16-bit PCM capture → WAV bytes
// (the agent ingests any mime; WAV keeps it dependency-free).

actual class VoiceRecorder actual constructor() {
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private val buf = java.io.ByteArrayOutputStream()

    actual suspend fun hasPermission(): Boolean = true

    /** Desktop has no runtime permission model: the OS prompts on first use. */
    actual suspend fun requestPermission(): Boolean = true

    actual suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            val fmt = AudioFormat(16_000f, 16, 1, true, false)
            val info = javax.sound.sampled.DataLine.Info(TargetDataLine::class.java, fmt)
            val l = AudioSystem.getLine(info) as TargetDataLine
            l.open(fmt)
            l.start()
            line = l
            buf.reset()
            thread = Thread {
                val chunk = ByteArray(4096)
                while (line === l && l.isOpen) {
                    val n = l.read(chunk, 0, chunk.size)
                    if (n > 0) buf.write(chunk, 0, n)
                }
            }.also { it.isDaemon = true; it.start() }
            true
        } catch (_: Exception) {
            false
        }
    }

    actual suspend fun stop(): PickedFile? = withContext(Dispatchers.IO) {
        val l = line ?: return@withContext null
        line = null
        try {
            l.stop()
            l.close()
        } catch (_: Exception) {
        }
        thread?.join(500)
        thread = null
        val pcm = buf.toByteArray()
        if (pcm.isEmpty()) return@withContext null
        val wav = pcmToWav(pcm, 16_000f, 16, 1)
        val name = "voice-${System.currentTimeMillis()}.wav"
        val tmp = File.createTempFile("voice", ".wav").apply {
            writeBytes(wav)
            deleteOnExit()
        }
        PickedFile(name, "audio/wav", wav, tmp.absolutePath)
    }

    actual suspend fun cancel() {
        line = null
        try {
            thread?.interrupt()
        } catch (_: Exception) {
        }
        thread = null
        buf.reset()
    }

    actual fun dispose() {
        line = null
    }
}

private fun pcmToWav(pcm: ByteArray, rate: Float, bits: Int, channels: Int): ByteArray {
    val byteRate = (rate * bits * channels / 8).toInt()
    val out = java.io.ByteArrayOutputStream(44 + pcm.size)
    fun le16(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
    fun le32(v: Int) = out.write(
        byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
        ),
    )
    out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels)
    le32(rate.toInt()); le32(byteRate); le16(bits * channels / 8); le16(bits)
    out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
    return out.toByteArray()
}

/** Desktop has no camera contract (flutter's image_picker has none either). */
actual suspend fun takePhoto(): List<PickedFile> = emptyList()

actual suspend fun pickFiles(mimeFilter: String?): List<PickedFile> = withContext(Dispatchers.IO) {
    val dlg = java.awt.FileDialog(null as java.awt.Frame?, "Choose files", java.awt.FileDialog.LOAD)
    dlg.isMultipleMode = true
    mimeFilter?.let {
        // best-effort extension mapping for the two composer entries
        val ext = when {
            it.startsWith("image/") -> "*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp"
            else -> "*"
        }
        dlg.file = ext
    }
    dlg.isVisible = true
    dlg.files?.map { f ->
        PickedFile(f.name, java.nio.file.Files.probeContentType(f.toPath()) ?: "application/octet-stream", f.readBytes(), f.absolutePath)
    } ?: emptyList()
}

actual suspend fun openLocalStore(scope: String): LocalStore? = try {
    LocalStore.open(scope)
} catch (_: Exception) {
    null
}

actual suspend fun readLocalFile(localPath: String, name: String): ByteArray = withContext(Dispatchers.IO) {
    val f = File(localPath.ifEmpty { name })
    if (f.canRead()) f.readBytes() else error("unreadable: $localPath")
}

actual suspend fun mediaUrl(bytes: ByteArray, mime: String?): String = withContext(Dispatchers.IO) {
    // Desktop renders media through local temp files (ImageIcon/media players).
    val ext = when {
        mime?.startsWith("image/") == true -> ".png"
        mime?.startsWith("audio/") == true -> ".wav"
        mime?.startsWith("video/") == true -> ".mp4"
        else -> ".bin"
    }
    val f = File.createTempFile("agent-media", ext).apply {
        writeBytes(bytes)
        deleteOnExit()
    }
    f.toURI().toString()
}

actual suspend fun downloadFile(name: String, mime: String?, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val dlg = java.awt.FileDialog(null as java.awt.Frame?, "Save", java.awt.FileDialog.SAVE)
    dlg.file = name
    dlg.isVisible = true
    val target = dlg.file?.let { File(dlg.directory ?: ".", it) } ?: File(System.getProperty("user.home"), name)
    target.writeBytes(bytes)
    target.absolutePath
}

actual suspend fun confirm(title: String, body: String): Boolean = withContext(Dispatchers.IO) {
    javax.swing.JOptionPane.showConfirmDialog(
        null, if (body.isEmpty()) title else "$title\n\n$body",
        title, javax.swing.JOptionPane.YES_NO_OPTION,
    ) == javax.swing.JOptionPane.YES_OPTION
}

actual suspend fun promptText(title: String, initial: String): String? = withContext(Dispatchers.IO) {
    val r = javax.swing.JOptionPane.showInputDialog(null, title, initial)
    r?.takeIf { it.isNotBlank() }
}


actual fun copyToClipboard(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .setContents(java.awt.datatransfer.StringSelection(text), null)
}
