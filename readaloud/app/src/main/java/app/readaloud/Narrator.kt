package app.readaloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * Runs a reading session: a generator thread turns text chunks into audio
 * (staying a few chunks ahead) while a player thread streams it to an
 * AudioTrack, so playback starts after the first sentence instead of after
 * the whole article. In EXPORT mode the audio is written to a WAV file.
 *
 * Lives in a process-wide singleton so playback survives the activity being
 * closed; NarrationService keeps the process in the foreground meanwhile.
 */
object Narrator {
    private const val TAG = "Narrator"
    private const val LOOKAHEAD = 3
    private const val GAP_SECONDS = 0.12f

    enum class Mode { PLAY, EXPORT }
    enum class Phase { IDLE, LOADING, PLAYING, PAUSED, EXPORTING, DONE, ERROR }

    data class Request(
        val chunks: List<String>,
        val voice: Voice,
        val speed: Float,
        val lang: String,
        val mode: Mode,
        val title: String,
    )

    data class Status(
        val phase: Phase,
        val index: Int = 0,
        val total: Int = 0,
        val chunk: String = "",
        val message: String = "",
        val title: String = "",
        val mode: Mode = Mode.PLAY,
        val output: Exporter.Saved? = null,
    ) {
        val active get() = phase == Phase.LOADING || phase == Phase.PLAYING ||
            phase == Phase.PAUSED || phase == Phase.EXPORTING
    }

    fun interface Listener {
        fun onStatus(status: Status)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var status = Status(Phase.IDLE)
        private set

    private val engineLock = Any()
    private var engine: SpeechEngine? = null

    @Volatile
    private var session: Session? = null

    private class Piece(val index: Int, val samples: FloatArray?)

    private class Session(val req: Request) {
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var track: AudioTrack? = null
        val lock = Object()
        val queue = ArrayBlockingQueue<Piece>(LOOKAHEAD)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onStatus(status)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun publish(s: Status) {
        status = s
        for (l in listeners) {
            try {
                l.onStatus(s)
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }

    // ---- controls ----------------------------------------------------------

    fun start(ctx: Context, req: Request) {
        stop()
        val app = ctx.applicationContext
        val s = Session(req)
        session = s
        publish(Status(Phase.LOADING, 0, req.chunks.size, message = "Loading voice…",
            title = req.title, mode = req.mode))
        NarrationService.start(app, req.mode == Mode.EXPORT)
        Thread({ generate(app, s) }, "tts-generate").start()
    }

    fun pause() {
        val s = session ?: return
        if (s.req.mode != Mode.PLAY || s.paused) return
        s.paused = true
        try { s.track?.pause() } catch (_: IllegalStateException) {}
        publish(status.copy(phase = Phase.PAUSED))
    }

    fun resume() {
        val s = session ?: return
        if (!s.paused) return
        s.paused = false
        try { s.track?.play() } catch (_: IllegalStateException) {}
        synchronized(s.lock) { s.lock.notifyAll() }
        publish(status.copy(phase = Phase.PLAYING))
    }

    fun togglePause() {
        if (session?.paused == true) resume() else pause()
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.cancelled = true
        s.paused = false
        synchronized(s.lock) { s.lock.notifyAll() }
        try {
            s.track?.pause()
            s.track?.flush()
        } catch (_: IllegalStateException) {}
        s.queue.clear()
        publish(Status(Phase.IDLE, message = "Stopped", mode = s.req.mode))
    }

    /** Free the model's memory (it is otherwise kept loaded between sessions). */
    fun releaseEngine() {
        synchronized(engineLock) {
            engine?.release()
            engine = null
        }
    }

    private fun obtainEngine(ctx: Context, id: EngineId): SpeechEngine = synchronized(engineLock) {
        engine?.let { if (it.id == id) return it }
        engine?.release()
        engine = null
        Engines.create(ctx, id).also { engine = it }
    }

    // ---- worker threads ------------------------------------------------------

    private fun generate(ctx: Context, s: Session) {
        val req = s.req
        val total = req.chunks.size
        var wav: WavWriter? = null
        var tmp: File? = null
        var player: Thread? = null
        try {
            val eng = obtainEngine(ctx, req.voice.engine)
            if (s.cancelled) return
            if (req.mode == Mode.PLAY) {
                player = Thread({ play(s, eng.sampleRate) }, "tts-play").also { it.start() }
            } else {
                tmp = File(ctx.cacheDir, "export-${System.currentTimeMillis()}.wav")
                wav = WavWriter(tmp, eng.sampleRate)
                publish(status.copy(phase = Phase.EXPORTING, message = "Creating audio…"))
            }
            for (i in req.chunks.indices) {
                if (s.cancelled) break
                val t0 = System.nanoTime()
                // The native engine is not thread-safe, and a new session may be
                // starting while this (stopped) one finishes its last chunk, so
                // synthesis and engine swaps share one lock.
                val audio = synchronized(engineLock) {
                    if (s.cancelled || engine !== eng) null
                    else eng.synthesize(req.chunks[i], req.voice, req.speed, req.lang)
                } ?: break
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    val sec = audio.size.toFloat() / eng.sampleRate
                    val took = (System.nanoTime() - t0) / 1e9f
                    Log.d(TAG, "chunk $i: ${"%.1f".format(sec)}s audio in ${"%.1f".format(took)}s")
                }
                if (wav != null) {
                    wav.write(audio)
                    wav.writeSilence(GAP_SECONDS)
                    publish(Status(Phase.EXPORTING, i + 1, total, req.chunks[i],
                        "Creating audio…", req.title, req.mode))
                } else {
                    val piece = Piece(i, audio)
                    while (!s.cancelled && !s.queue.offer(piece, 200, TimeUnit.MILLISECONDS)) {
                        // wait for the player to catch up
                    }
                }
            }
            if (wav != null && !s.cancelled) {
                wav.close()
                wav = null
                val saved = Exporter.save(ctx, tmp!!, req.title)
                if (session === s) {
                    session = null
                    publish(Status(Phase.DONE, total, total, message = "Saved to ${saved.label}",
                        title = req.title, mode = req.mode, output = saved))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "generation failed", t)
            if (session === s) {
                session = null
                s.cancelled = true
                publish(Status(Phase.ERROR, message = t.message ?: t.javaClass.simpleName,
                    mode = req.mode))
            }
        } finally {
            try { wav?.close() } catch (_: Throwable) {}
            if (req.mode == Mode.EXPORT) tmp?.delete()
            if (player != null) {
                // End-of-stream marker; the player drains what it already has.
                while (!s.cancelled && !s.queue.offer(Piece(-1, null), 200, TimeUnit.MILLISECONDS)) {
                    // keep trying until there is room or we are stopped
                }
            }
        }
    }

    private fun play(s: Session, sampleRate: Int) {
        val req = s.req
        val total = req.chunks.size
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4 / 2))
            .build()
        s.track = track
        var framesWritten = 0L
        try {
            track.play()
            val block = sampleRate / 10
            val gap = FloatArray((sampleRate * GAP_SECONDS).toInt())
            loop@ while (!s.cancelled) {
                val piece = s.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val samples = piece.samples ?: break
                publish(Status(if (s.paused) Phase.PAUSED else Phase.PLAYING, piece.index + 1,
                    total, req.chunks[piece.index], "", req.title, req.mode))
                var off = 0
                while (off < samples.size) {
                    waitWhilePaused(s)
                    if (s.cancelled) break@loop
                    val n = minOf(block, samples.size - off)
                    val w = track.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) throw IllegalStateException("AudioTrack write failed ($w)")
                    off += w
                    framesWritten += w
                }
                track.write(gap, 0, gap.size, AudioTrack.WRITE_BLOCKING)
                framesWritten += gap.size
            }
            if (!s.cancelled) {
                // Let the buffered tail finish before releasing the track.
                track.stop()
                val deadline = System.currentTimeMillis() + 5000
                while (!s.cancelled && System.currentTimeMillis() < deadline &&
                    (track.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten
                ) {
                    Thread.sleep(50)
                }
                if (session === s) {
                    session = null
                    publish(Status(Phase.DONE, total, total, message = "Finished",
                        title = req.title, mode = req.mode))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback failed", t)
            if (session === s) {
                session = null
                s.cancelled = true
                publish(Status(Phase.ERROR, message = t.message ?: "Playback failed"))
            }
        } finally {
            s.track = null
            try { track.release() } catch (_: Throwable) {}
        }
    }

    private fun waitWhilePaused(s: Session) {
        if (!s.paused) return
        synchronized(s.lock) {
            while (s.paused && !s.cancelled) s.lock.wait(250)
        }
    }
}
