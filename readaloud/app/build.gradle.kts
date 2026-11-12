import java.net.URI

plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// ---------------------------------------------------------------------------
// Native libraries, taken as prebuilt AARs from their GitHub releases:
//  * sherpa-onnx: ONNX Runtime + the Kokoro / Supertonic TTS engines
//  * piper-phonemize: espeak-ng, turns any text into phonemes for Kokoro
// They are downloaded once into app/libs/ (git-ignored).
// ---------------------------------------------------------------------------
val sherpaOnnxVersion = "2.0.1"
val piperPhonemizeVersion = "1.5.9"

fun githubAar(fileName: String, url: String): File {
    val dir = file("libs")
    val target = File(dir, fileName)
    if (!target.isFile || target.length() < 100_000) {
        dir.mkdirs()
        logger.lifecycle("Downloading $fileName")
        val part = File(dir, "$fileName.part")
        URI(url).toURL().openStream().use { input ->
            part.outputStream().use { input.copyTo(it) }
        }
        if (!part.renameTo(target)) throw GradleException("Could not write $target")
    }
    return target
}

android {
    namespace = "app.readaloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.readaloud"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Phones. Add "x86_64" to also run on a PC emulator (bigger APK).
            abiFilters += listOf("arm64-v8a")
        }
    }

    // A fixed key, so every build installs as an update over the previous
    // one. Override with your own keystore through these environment variables.
    signingConfigs {
        create("sideload") {
            storeFile = file(System.getenv("READALOUD_KEYSTORE") ?: "readaloud.jks")
            storePassword = System.getenv("READALOUD_KEYSTORE_PASSWORD") ?: "readaloud"
            keyAlias = System.getenv("READALOUD_KEY_ALIAS") ?: "readaloud"
            keyPassword = System.getenv("READALOUD_KEY_PASSWORD") ?: "readaloud"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // Personal sideload app: don't let Play-Store lint rules block the build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

chaquopy {
    defaultConfig {
        // 3.13+ is needed for Android 15's 16 KB memory pages.
        version = "3.13"
        pip {
            // Pure-Python only, so nothing depends on Android-specific wheels.
            install("requests==2.34.2")
            install("beautifulsoup4==4.15.0")
            install("pypdf==6.17.0")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation(
        files(
            githubAar(
                "sherpa-onnx-$sherpaOnnxVersion.aar",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                    "v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar",
            )
        )
    )
    implementation(
        files(
            githubAar(
                "piper-phonemize-$piperPhonemizeVersion.aar",
                "https://github.com/csukuangfj/piper-phonemize/releases/download/" +
                    "v$piperPhonemizeVersion/piper-phonemize-$piperPhonemizeVersion.aar",
            )
        )
    )
}
