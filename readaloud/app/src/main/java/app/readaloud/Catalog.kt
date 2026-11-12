package app.readaloud

import android.content.Context
import java.io.File

enum class EngineId(val title: String) {
    KOKORO("Kokoro 82M · best quality"),
    SUPERTONIC("Supertonic 3 · fast"),
}

/** A selectable voice. [sid] is the speaker id inside the model's voices file. */
data class Voice(
    val key: String,
    val label: String,
    val engine: EngineId,
    val sid: Int,
    val espeak: String = "en-us",     // espeak-ng voice used to phonemize (Kokoro only)
    val dialect: Dialect = Dialect.US,
    val lang: String = "en",
)

object Voices {
    private fun us(key: String, label: String, sid: Int) =
        Voice(key, label, EngineId.KOKORO, sid, "en-us", Dialect.US, "en")

    private fun gb(key: String, label: String, sid: Int) =
        Voice(key, label, EngineId.KOKORO, sid, "en", Dialect.GB, "en")

    private fun hi(key: String, label: String, sid: Int) =
        Voice(key, label, EngineId.KOKORO, sid, "hi", Dialect.OTHER, "hi")

    // Speaker ids from sherpa-onnx scripts/kokoro/v1.0/generate_voices_bin.py.
    // Ordered roughly by the voice quality grades published with Kokoro.
    val kokoro: List<Voice> = listOf(
        us("af_heart", "Heart · US female ★", 3),
        us("af_bella", "Bella · US female ★", 2),
        us("am_michael", "Michael · US male ★", 16),
        us("am_fenrir", "Fenrir · US male", 14),
        us("am_puck", "Puck · US male", 18),
        us("af_nicole", "Nicole · US female (soft)", 6),
        us("af_aoede", "Aoede · US female", 1),
        us("af_kore", "Kore · US female", 5),
        us("af_sarah", "Sarah · US female", 9),
        us("af_nova", "Nova · US female", 7),
        us("af_alloy", "Alloy · US female", 0),
        us("af_sky", "Sky · US female", 10),
        us("am_echo", "Echo · US male", 12),
        us("am_eric", "Eric · US male", 13),
        us("am_liam", "Liam · US male", 15),
        us("am_onyx", "Onyx · US male", 17),
        gb("bf_emma", "Emma · UK female ★", 21),
        gb("bm_george", "George · UK male", 26),
        gb("bm_fable", "Fable · UK male", 25),
        gb("bf_isabella", "Isabella · UK female", 22),
        gb("bm_lewis", "Lewis · UK male", 27),
        gb("bm_daniel", "Daniel · UK male", 24),
        gb("bf_alice", "Alice · UK female", 20),
        gb("bf_lily", "Lily · UK female", 23),
        hi("hf_alpha", "Alpha · Hindi female", 31),
        hi("hf_beta", "Beta · Hindi female", 32),
        hi("hm_omega", "Omega · Hindi male", 33),
        hi("hm_psi", "Psi · Hindi male", 34),
    )

    // voice.bin is built from the style files sorted by name: F1..F5, M1..M5.
    val supertonic: List<Voice> = (1..5).map {
        Voice("F$it", "Voice F$it · female", EngineId.SUPERTONIC, it - 1)
    } + (1..5).map {
        Voice("M$it", "Voice M$it · male", EngineId.SUPERTONIC, it + 4)
    }

    fun forEngine(engine: EngineId) = if (engine == EngineId.KOKORO) kokoro else supertonic

    fun find(engine: EngineId, key: String?) =
        forEngine(engine).firstOrNull { it.key == key } ?: forEngine(engine).first()

    val kokoroHindi: Voice get() = kokoro.first { it.lang == "hi" }
}

/** A model package from the sherpa-onnx "tts-models" GitHub release. */
data class ModelSpec(
    val engine: EngineId,
    val dirName: String,
    val sizeMb: Int,
    val keep: List<String>,
) {
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$dirName.tar.bz2"
}

object Models {
    val KOKORO = ModelSpec(
        EngineId.KOKORO, "kokoro-int8-multi-lang-v1_0", 132,
        listOf("model.int8.onnx", "voices.bin", "tokens.txt", "LICENSE"),
    )
    val SUPERTONIC = ModelSpec(
        EngineId.SUPERTONIC, "sherpa-onnx-supertonic-3-tts-int8-2026-05-11", 129,
        listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json",
            "unicode_indexer.bin", "voice.bin", "LICENSE",
        ),
    )

    fun spec(engine: EngineId) = if (engine == EngineId.KOKORO) KOKORO else SUPERTONIC

    fun dir(ctx: Context, spec: ModelSpec) = File(ctx.filesDir, "models/${spec.dirName}")

    fun archive(ctx: Context, spec: ModelSpec) = File(ctx.filesDir, "models/${spec.dirName}.tar.bz2")

    fun isInstalled(ctx: Context, spec: ModelSpec): Boolean {
        val d = dir(ctx, spec)
        return spec.keep.all { File(d, it).length() > 0 }
    }

    fun delete(ctx: Context, spec: ModelSpec) {
        dir(ctx, spec).deleteRecursively()
        archive(ctx, spec).delete()
        File(archive(ctx, spec).path + ".part").delete()
    }
}
