package com.tanvir.voiceassistant.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class BengaliModelManager(private val context: Context) {
    companion object {
        const val DIRECTORY = "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09"
        const val URL_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2"
    }

    val modelDirectory: File get() = File(context.filesDir, DIRECTORY)

    fun isInstalled(): Boolean =
        listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
            .all { File(modelDirectory, it).isFile }

    suspend fun install(progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, "bengali-asr.tar.bz2")
        val connection = URL(URL_MODEL).openConnection().apply {
            connectTimeout = 20000
            readTimeout = 60000
        }
        val expected = connection.contentLengthLong
        connection.getInputStream().use { input ->
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(65536)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (expected > 0) progress(((copied * 100) / expected).toInt().coerceIn(0, 99))
                }
            }
        }

        modelDirectory.deleteRecursively()
        val root = context.filesDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = File(context.filesDir, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Invalid archive path" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { tar.copyTo(it) }
                }
            }
        }
        archive.delete()
        check(isInstalled()) { "Bengali model archive is incomplete" }
        progress(100)
    }

    fun delete() {
        modelDirectory.deleteRecursively()
    }
}
