package app.readaloud

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Streams float samples into a 16-bit mono PCM WAV file. */
class WavWriter(private val file: File, private val sampleRate: Int) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L
    private var closed = false

    init {
        out.write(ByteArray(44)) // header is filled in on close()
    }

    fun write(samples: FloatArray) {
        val buf = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[2 * i] = (v and 0xff).toByte()
            buf[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        out.write(buf)
        dataBytes += buf.size
    }

    fun writeSilence(seconds: Float) {
        val n = (sampleRate * seconds).toInt() * 2
        out.write(ByteArray(n))
        dataBytes += n
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(dataBytes))
        }
    }

    private fun header(data: Long): ByteArray {
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.toInt())
        return h.array()
    }
}

object Exporter {
    data class Saved(val uri: Uri, val label: String)

    private fun safeName(title: String): String {
        val base = title.ifBlank { "ReadAloud" }
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ")
            .trim().take(60).ifBlank { "ReadAloud" }
        return "$base.wav"
    }

    /** Copies a finished WAV into Downloads/ReadAloud (or the app's Music folder on Android 8–9). */
    fun save(ctx: Context, wav: File, title: String): Saved {
        val name = safeName(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReadAloud")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { o -> wav.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            return Saved(uri, "Download/ReadAloud/$name")
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
        val out = File(dir, name)
        wav.copyTo(out, overwrite = true)
        return Saved(Uri.fromFile(out), out.absolutePath)
    }
}
