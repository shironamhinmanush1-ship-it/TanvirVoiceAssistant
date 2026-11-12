package com.tanvir.voiceassistant.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Writes mono 16-bit PCM samples into a standard 44-byte-header WAV file. */
class WavWriter(file: File) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        output.setLength(0)
        output.writeBytes("RIFF")
        writeIntLE(0)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(16000)
        writeIntLE(32000)
        writeShortLE(2)
        writeShortLE(16)
        output.writeBytes("data")
        writeIntLE(0)
    }

    @Synchronized
    fun write(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        for (i in 0 until count) {
            val value = samples[i].toInt()
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }
        dataBytes += count * 2L
    }

    private fun writeShortLE(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeIntLE(value: Long) {
        repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    @Synchronized
    override fun close() {
        output.seek(4)
        writeIntLE(36L + dataBytes)
        output.seek(40)
        writeIntLE(dataBytes)
        output.fd.sync()
        output.close()
    }
}
