package app.readaloud

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/**
 * Bridge to the Python backend (app/src/main/python/readaloud_backend.py),
 * which Chaquopy bundles inside the APK. Every call blocks, so call from a
 * background thread.
 */
object Backend {

    data class Article(
        val ok: Boolean,
        val title: String,
        val text: String,
        val url: String,
        val source: String,
        val error: String,
    )

    data class Prepared(val lang: String, val chunks: List<String>)

    /** Implemented in Kotlin, called from Python during model installation. */
    interface ProgressSink {
        fun update(stage: String, percent: Int)
        fun isCancelled(): Boolean
    }

    private fun module(ctx: Context): PyObject {
        synchronized(this) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        }
        return Python.getInstance().getModule("readaloud_backend")
    }

    /** Warm up the interpreter so the first real call is quick. */
    fun warmUp(ctx: Context) {
        module(ctx)
    }

    fun linkIn(ctx: Context, text: String): String? =
        module(ctx).callAttr("looks_like_link", text).toString().ifBlank { null }

    fun fetch(ctx: Context, url: String): Article {
        val j = JSONObject(module(ctx).callAttr("fetch", url).toString())
        return Article(
            ok = j.optBoolean("ok"),
            title = j.optString("title"),
            text = j.optString("text"),
            url = j.optString("url", url),
            source = j.optString("source"),
            error = j.optString("error"),
        )
    }

    fun prepare(ctx: Context, text: String, maxChars: Int): Prepared {
        val j = JSONObject(module(ctx).callAttr("prepare", text, maxChars).toString())
        val arr = j.getJSONArray("chunks")
        return Prepared(j.optString("lang", "en"), List(arr.length()) { arr.getString(it) })
    }

    /** Downloads and unpacks a model. Returns null on success, else an error message. */
    fun installModel(ctx: Context, spec: ModelSpec, sink: ProgressSink): String? {
        val dest = Models.dir(ctx, spec)
        val archive = Models.archive(ctx, spec)
        archive.parentFile?.mkdirs()
        val keep = org.json.JSONArray(spec.keep).toString()
        val j = JSONObject(
            module(ctx).callAttr(
                "install_model", spec.url, archive.absolutePath, dest.absolutePath, keep, sink,
            ).toString()
        )
        return if (j.optBoolean("ok")) null else j.optString("error", "Install failed")
    }
}
