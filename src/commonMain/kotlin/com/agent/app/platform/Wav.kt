package com.agent.app.platform

/**
 * Float32 [-1,1] mono → 16-bit PCM WAV (44-byte canonical header).
 * Used by the web recorder (Web Audio yields Float32 frames).
 */
internal fun floatToWavPcm16(samples: FloatArray, sampleRate: Int): ByteArray {
    val dataBytes = samples.size * 2
    val pcm = ByteArray(dataBytes)
    for (i in samples.indices) {
        val s = samples[i].coerceIn(-1f, 1f)
        val v = if (s < 0) (s * 0x8000).toInt() else (s * 0x7fff).toInt()
        pcm[i * 2] = (v and 0xff).toByte()
        pcm[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
    }
    return pcm16ToWav(pcm, sampleRate, 16, 1)
}

/**
 * Raw little-endian PCM → a WAV container (pure Kotlin, every KMP target).
 * Android/desktop capture 16-bit PCM directly and call this.
 */
internal fun pcm16ToWav(pcm: ByteArray, rate: Int, bits: Int, channels: Int): ByteArray {
    val byteRate = rate * bits * channels / 8
    val out = ByteArray(44 + pcm.size)
    var o = 0
    fun put(b: ByteArray) { b.copyInto(out, o); o += b.size }
    fun le16(v: Int) {
        out[o++] = (v and 0xff).toByte()
        out[o++] = ((v shr 8) and 0xff).toByte()
    }
    fun le32(v: Int) {
        out[o++] = (v and 0xff).toByte()
        out[o++] = ((v shr 8) and 0xff).toByte()
        out[o++] = ((v shr 16) and 0xff).toByte()
        out[o++] = ((v shr 24) and 0xff).toByte()
    }
    put("RIFF".encodeToByteArray()); le32(36 + pcm.size); put("WAVE".encodeToByteArray())
    put("fmt ".encodeToByteArray()); le32(16); le16(1); le16(channels)
    le32(rate); le32(byteRate); le16(bits * channels / 8); le16(bits)
    put("data".encodeToByteArray()); le32(pcm.size)
    pcm.copyInto(out, o)
    return out
}
