package com.appsalad.recorder.audio

import java.io.File
import java.io.RandomAccessFile

/** 16-bit mono PCM WAV writer; the header sizes are patched in on close(). */
class WavWriter(val file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    var dataBytes = 0L
        private set

    val durationMs: Long get() = dataBytes * 1000 / (sampleRate * 2)

    fun write(buf: ByteArray, len: Int) {
        raf.write(buf, 0, len)
        dataBytes += len
    }

    fun close() {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0); raf.write(h.array()); raf.close()
    }
}
