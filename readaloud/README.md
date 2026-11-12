# ReadAloud

An Android app (.apk) that reads web articles or pasted text aloud with a
neural voice that runs **entirely on the phone**.

* Paste a **link** (article, blog post or PDF) and the **Python backend** fetches
  the page and extracts the readable text.
* Or paste/type **text** in the text area.
* Or use **Share → ReadAloud** from Chrome or any news app.
* Speech is generated offline by **Kokoro-82M** (best quality) or
  **Supertonic 3** (fast), sentence by sentence, so audio starts within a
  second or two. Playback continues with the screen off, and there are
  pause/stop controls in the notification.
* **Save as audio file** writes a WAV to `Download/ReadAloud/`.
* English (US/UK, 24 voices) and Hindi (4 voices). Hindi text is detected
  automatically.

Target: any arm64 Android 8.0+ phone. 6 GB of RAM is plenty (Kokoro peaked at
about 0.5 GB while speaking in testing).

## Which TTS model, and why

| | Kokoro-82M v1.0 (int8) | Supertonic 3 (int8) |
|---|---|---|
| Quality | Best open-weight voice at this size; it has ranked #1 on TTS Arena and stays close to models 10-50× bigger | Good, a bit flatter |
| Speed (2-core x86 test box) | ≈ real time (RTF 1.0) | ≈ 7× real time (RTF 0.15) |
| Download | 132 MB | 129 MB |
| License | Apache-2.0 | OpenRAIL-M (model), MIT (code) |

Kokoro is the default. On slower phones, if you hear pauses between
sentences, switch the engine to Supertonic. Both run through
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ONNX Runtime). The model
is downloaded once inside the app on first use, which keeps the APK small.

**Pronunciation detail:** in sherpa-onnx 2.x, Kokoro v1.0's built-in
lexicon frontend *skips* any word it doesn't know. That drops names, plurals
like "forecasts", and numbers, so it's unusable for articles. ReadAloud
phonemizes with espeak-ng (via piper-phonemize) instead, which handles any
word, then maps espeak's IPA to the "misaki" symbols Kokoro was trained on.
That cut the phoneme mismatch against Kokoro's own lexicon from 31% to 6.6%
(US) and from 21% to 5.0% (UK). Whisper transcribes the output with only
normalisation-level errors.

## Build the APK

### Option A: GitHub Actions (nothing to install)

1. Create a new empty GitHub repository (private is fine) and push this folder:
   ```bash
   cd readaloud
   git init && git add . && git commit -m "ReadAloud"
   git branch -M main
   git remote add origin https://github.com/YOUR-USER/readaloud.git
   git push -u origin main
   ```
2. Open the **Actions** tab. The **Build APK** workflow runs on every push and
   takes about 10 minutes.
3. Open the finished run and download the **ReadAloud-apk** artifact (a zip
   containing `ReadAloud-N.apk`).
4. Optional: push a tag like `v1.0` and the APK is also attached to a
   GitHub Release, which is easy to download straight to your phone.

### Option B: Android Studio

1. Install **Python 3.13** and make sure `python3.13` (Linux/macOS) or
   `py -3.13` (Windows) works. Chaquopy needs the same Python version as the
   app at build time.
2. Open this folder in Android Studio (a recent version that supports AGP 9.5)
   and wait for Gradle sync. The sherpa-onnx and piper-phonemize AARs
   download automatically into `app/libs/`.
3. **Build → Build APK(s)**, or run `./gradlew assembleRelease`. The output is
   `app/build/outputs/apk/release/app-release.apk`.

## Install on your phone

1. Copy the APK to the phone (or download it there) and tap it.
2. Allow **Install unknown apps** for your browser or file manager when asked.
3. Open ReadAloud, tap **Download** (one time, about 130 MB, Wi-Fi
   recommended), and then **Read aloud**.

Every build is signed with the same key (`app/readaloud.jks`), so new builds
install over old ones. That key's password is in this repo, which is fine for
personal sideloading. If you plan to distribute the app, use your own key via
the `READALOUD_KEYSTORE`, `READALOUD_KEYSTORE_PASSWORD`, `READALOUD_KEY_ALIAS`
and `READALOUD_KEY_PASSWORD` environment variables.

## How it works

```
 ┌───────────── Android app (Kotlin) ──────────────┐
 │ MainActivity ── link / text / voice / speed UI  │
 │      │                                          │
 │      ▼   Chaquopy (CPython 3.13 inside the APK) │
 │ readaloud_backend.py                            │
 │   fetch(url): requests → article extraction     │
 │               (JSON-LD, readability-style       │
 │               scoring, PDFs via pypdf, reader-  │
 │               service fallback for JS pages)    │
 │   prepare(text): normalise ($, %, Dr., U.S.…)   │
 │               + split into sentence chunks      │
 │   install_model(): download (resumable) + unpack│
 │      │                                          │
 │      ▼                                          │
 │ Narrator: generator thread ─► queue ─► AudioTrack│
 │   Kokoro: espeak-ng phonemes → misaki map →     │
 │           sherpa-onnx (ONNX Runtime, 4 threads) │
 │   Supertonic: raw text → sherpa-onnx            │
 │ NarrationService: foreground service + wakelock │
 └─────────────────────────────────────────────────┘
```

| Path | What |
|---|---|
| `app/src/main/python/readaloud_backend.py` | Python backend: link detection, fetching, extraction, text normalisation, chunking, model install |
| `app/src/main/java/app/readaloud/MainActivity.kt` | UI |
| `app/src/main/java/app/readaloud/Narrator.kt` | Streaming synthesis + playback / WAV export |
| `app/src/main/java/app/readaloud/Speech.kt` | Kokoro and Supertonic engines, phonemizer |
| `app/src/main/java/app/readaloud/MisakiMap.kt` | espeak → Kokoro phoneme mapping |
| `app/src/main/java/app/readaloud/Catalog.kt` | Voices and model packages |
| `app/src/main/java/app/readaloud/NarrationService.kt` | Background playback notification |
| `tools/test_backend.py` | Backend tests (runs in CI) |
| `tools/bench_extractor.py` | Extraction benchmark |
| `tools/misaki_map.py` | Python reference for the phoneme mapping |

### Link handling

The backend fetches the URL itself, with a normal mobile-browser user agent.
It prefers the publisher's own `articleBody` (JSON-LD) when present.
Otherwise it removes menus, ads and "related" blocks and picks the densest
block of paragraphs. On the 181-page
[Scrapinghub article-extraction benchmark](https://github.com/scrapinghub/article-extraction-benchmark)
it scores **F1 0.93** (trafilatura scores 0.95 but needs `lxml`, which has no
Android wheels). When a page yields under 500 characters (JavaScript-only
sites, bot walls), it retries through the free `r.jina.ai` reader service.
That is the only third-party service the app contacts. Remove
`_reader_fallback` from the backend if you'd rather it didn't.

Run the tests on a desktop:

```bash
pip install requests==2.34.2 beautifulsoup4==4.15.0 pypdf==6.17.0
python tools/test_backend.py
git clone https://github.com/scrapinghub/article-extraction-benchmark /tmp/aeb
python tools/bench_extractor.py /tmp/aeb
```

## Tuning

* **Gaps between sentences**: the phone is slower than real time with Kokoro.
  Switch to Supertonic, or use *Save as audio file* and play the WAV.
* **Speed**: 0.5× to 2.0×. A change takes effect the next time you press
  Read aloud.
* **Emulator**: add `"x86_64"` to `abiFilters` in `app/build.gradle.kts`.
* **Free storage**: models live in the app's private storage. Clearing the
  app's storage in Android settings removes them.

## Credits and licenses

Kokoro-82M by hexgrad (Apache-2.0) · Supertonic 3 by Supertone (OpenRAIL-M) ·
sherpa-onnx by k2-fsa (Apache-2.0) · piper-phonemize by csukuangfj, including
espeak-ng (GPL-3.0) · Chaquopy (MIT) · requests, Beautiful Soup, pypdf.

piper-phonemize/espeak-ng are GPL-3.0, so if you share the APK with other
people, share this source code with it.
