package app.readaloud

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/** One on-device TTS model. Not thread-safe: use from one thread at a time. */
interface SpeechEngine {
    val id: EngineId
    val sampleRate: Int
    fun synthesize(text: String, voice: Voice, speed: Float, lang: String): FloatArray
    fun release()
}

private fun threadCount() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

/** espeak-ng (through piper-phonemize) -> misaki phoneme code points for Kokoro. */
object Phonemizer {
    @Volatile private var ready = false

    fun init(ctx: Context) {
        synchronized(this) {
            if (ready) return
            // Copies espeak-ng-data out of the APK assets on first run (~19 MB).
            val rc = piperPhonemizeInitialize(ctx.applicationContext)
            check(rc >= 0) { "Could not initialise the phonemizer (code $rc)" }
            ready = true
        }
    }

    fun codepoints(text: String, espeakVoice: String, dialect: Dialect): Array<IntArray> {
        val result = piperPhonemizeText(text, espeakVoice)
            ?: throw IllegalStateException("Phonemizer failed for voice $espeakVoice")
        try {
            val out = ArrayList<IntArray>(result.numSentences)
            for (i in 0 until result.numSentences) {
                val cps = result.getPhonemes(i)
                if (cps.isEmpty()) continue
                val mapped = MisakiMap.map(String(cps, 0, cps.size), dialect).trim()
                if (mapped.isNotEmpty()) out.add(mapped.codePoints().toArray())
            }
            return out.toTypedArray()
        } finally {
            result.close()
        }
    }
}

class KokoroEngine(ctx: Context, dir: File) : SpeechEngine {
    override val id = EngineId.KOKORO
    private val tts: OfflineTts

    init {
        Phonemizer.init(ctx)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    // No lexicon: text arrives pre-phonemized (see Phonemizer).
                ),
                numThreads = threadCount(),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        tts = OfflineTts(config = config)
    }

    override val sampleRate: Int get() = tts.sampleRate()

    override fun synthesize(text: String, voice: Voice, speed: Float, lang: String): FloatArray {
        val v = if (lang == "hi" && voice.lang != "hi") Voices.kokoroHindi else voice
        val cps = Phonemizer.codepoints(text, v.espeak, v.dialect)
        if (cps.isEmpty()) return FloatArray(0)
        val gen = GenerationConfig(sid = v.sid, speed = speed, phonemeCodepoints = cps)
        return tts.generateWithConfig("", gen).samples
    }

    override fun release() = tts.free()
}

class SupertonicEngine(dir: File) : SpeechEngine {
    override val id = EngineId.SUPERTONIC
    private val tts: OfflineTts

    init {
        fun f(name: String) = File(dir, name).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = f("duration_predictor.int8.onnx"),
                    textEncoder = f("text_encoder.int8.onnx"),
                    vectorEstimator = f("vector_estimator.int8.onnx"),
                    vocoder = f("vocoder.int8.onnx"),
                    ttsJson = f("tts.json"),
                    unicodeIndexer = f("unicode_indexer.bin"),
                    voiceStyle = f("voice.bin"),
                ),
                numThreads = threadCount(),
                debug = false,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(config = config)
    }

    override val sampleRate: Int get() = tts.sampleRate()

    override fun synthesize(text: String, voice: Voice, speed: Float, lang: String): FloatArray {
        val gen = GenerationConfig(
            sid = voice.sid, speed = speed, numSteps = 5,
            extra = mapOf("lang" to (if (lang == "hi") "hi" else "en")),
        )
        return tts.generateWithConfig(text, gen).samples
    }

    override fun release() = tts.free()
}

object Engines {
    fun create(ctx: Context, id: EngineId): SpeechEngine {
        val spec = Models.spec(id)
        check(Models.isInstalled(ctx, spec)) { "The ${id.title} model is not downloaded yet." }
        val dir = Models.dir(ctx, spec)
        return when (id) {
            EngineId.KOKORO -> KokoroEngine(ctx, dir)
            EngineId.SUPERTONIC -> SupertonicEngine(dir)
        }
    }
}
