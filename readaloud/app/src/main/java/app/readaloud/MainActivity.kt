package app.readaloud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity(), Narrator.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences

    private lateinit var urlInput: EditText
    private lateinit var fetchButton: Button
    private lateinit var fetchStatus: TextView
    private lateinit var textInput: EditText
    private lateinit var charCount: TextView
    private lateinit var engineSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var playStatus: TextView
    private lateinit var playProgress: ProgressBar
    private lateinit var nowReading: TextView
    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var modelCard: View
    private lateinit var modelInfo: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelDownload: Button
    private lateinit var modelCancel: Button

    private var engine = EngineId.KOKORO
    private var title = ""
    private var busy = false            // fetching / preparing text
    private var installing = false
    @Volatile private var cancelInstall = false
    private var lastOutput: Exporter.Saved? = null

    private val speed: Float get() = 0.5f + speedBar.progress * 0.05f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("readaloud", MODE_PRIVATE)
        bindViews()
        applyEdgeToEdgeInsets()

        engine = runCatching { EngineId.valueOf(prefs.getString("engine", null)!!) }
            .getOrDefault(EngineId.KOKORO)
        title = prefs.getString("title", "") ?: ""
        urlInput.setText(prefs.getString("url", ""))
        textInput.setText(prefs.getString("text", ""))
        setupEngineAndVoices()
        setupSpeed()
        setupActions()
        refreshModelCard()
        updateCharCount()

        if (savedInstanceState == null) handleShare(intent)
        worker.execute { runCatching { Backend.warmUp(this) } } // start Python early
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        Narrator.addListener(this)
    }

    override fun onStop() {
        Narrator.removeListener(this)
        savePrefs()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---- setup ---------------------------------------------------------------

    private fun bindViews() {
        urlInput = findViewById(R.id.urlInput)
        fetchButton = findViewById(R.id.fetchButton)
        fetchStatus = findViewById(R.id.fetchStatus)
        textInput = findViewById(R.id.textInput)
        charCount = findViewById(R.id.charCount)
        engineSpinner = findViewById(R.id.engineSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speedBar = findViewById(R.id.speedBar)
        speedLabel = findViewById(R.id.speedLabel)
        playStatus = findViewById(R.id.playStatus)
        playProgress = findViewById(R.id.playProgress)
        nowReading = findViewById(R.id.nowReading)
        playButton = findViewById(R.id.playButton)
        stopButton = findViewById(R.id.stopButton)
        saveButton = findViewById(R.id.saveButton)
        shareButton = findViewById(R.id.shareButton)
        modelCard = findViewById(R.id.modelCard)
        modelInfo = findViewById(R.id.modelInfo)
        modelProgress = findViewById(R.id.modelProgress)
        modelDownload = findViewById(R.id.modelDownload)
        modelCancel = findViewById(R.id.modelCancel)
    }

    /** Android 15+ draws behind the system bars; pad the content so nothing hides under them. */
    private fun applyEdgeToEdgeInsets() {
        if (Build.VERSION.SDK_INT < 35) return
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupEngineAndVoices() {
        engineSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, EngineId.values().map { it.title },
        )
        engineSpinner.setSelection(engine.ordinal, false)
        fillVoices()
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val picked = EngineId.values()[pos]
                if (picked == engine) return
                engine = picked
                prefs.edit().putString("engine", engine.name).apply()
                fillVoices()
                refreshModelCard()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val voice = Voices.forEngine(engine)[pos]
                prefs.edit().putString("voice_${engine.name}", voice.key).apply()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun fillVoices() {
        val voices = Voices.forEngine(engine)
        voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, voices.map { it.label },
        )
        val current = Voices.find(engine, prefs.getString("voice_${engine.name}", null))
        voiceSpinner.setSelection(voices.indexOf(current), false)
    }

    private fun currentVoice(): Voice {
        val voices = Voices.forEngine(engine)
        return voices.getOrElse(voiceSpinner.selectedItemPosition) { voices.first() }
    }

    private fun setupSpeed() {
        speedBar.progress = prefs.getInt("speed", 10) // 10 -> 1.0x
        speedLabel.text = "%.2f×".format(speed)
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                speedLabel.text = "%.2f×".format(speed)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt("speed", speedBar.progress).apply()
            }
        })
    }

    private fun setupActions() {
        fetchButton.setOnClickListener { fetchLink(then = null) }
        urlInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                fetchLink(then = null); true
            } else false
        }
        clearButton().setOnClickListener {
            textInput.setText("")
            urlInput.setText("")
            fetchStatus.visibility = View.GONE
            title = ""
        }
        // Let the long text box scroll by itself inside the page's ScrollView.
        textInput.setOnTouchListener { v, event ->
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.parent.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP &&
                        event.actionMasked != MotionEvent.ACTION_CANCEL
                )
            }
            false
        }
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCharCount()
        })
        playButton.setOnClickListener { onPlayPressed() }
        stopButton.setOnClickListener { Narrator.stop() }
        saveButton.setOnClickListener { begin(Narrator.Mode.EXPORT) }
        shareButton.setOnClickListener { shareOutput() }
        modelDownload.setOnClickListener { installModel() }
        modelCancel.setOnClickListener { cancelInstall = true }
    }

    private fun clearButton(): Button = findViewById(R.id.clearButton)

    private fun updateCharCount() {
        val n = textInput.text.length
        charCount.text = if (n == 0) "" else "%,d chars · ~%d min".format(n, maxOf(1, n / 900))
    }

    // ---- share sheet: "Share → ReadAloud" from Chrome or any app -------------

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        intent.action = null // handle once
        if (shared.isEmpty()) return
        worker.execute {
            val link = runCatching { Backend.linkIn(this, shared) }.getOrNull()
            ui.post {
                if (link != null) {
                    urlInput.setText(link)
                    fetchLink(then = null)
                } else {
                    textInput.setText(shared)
                    title = subject
                    fetchStatus.visibility = View.GONE
                }
            }
        }
    }

    // ---- link fetching (Python backend) ----------------------------------------

    private fun fetchLink(then: Narrator.Mode?) {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            toast("Paste a link first")
            return
        }
        if (busy) return
        hideKeyboard()
        setBusy(true)
        fetchStatus.visibility = View.VISIBLE
        fetchStatus.text = "Fetching the page…"
        worker.execute {
            val article = try {
                Backend.fetch(this, url)
            } catch (t: Throwable) {
                Backend.Article(false, "", "", url, "", t.message ?: "Fetch failed")
            }
            ui.post {
                setBusy(false)
                if (article.ok) {
                    title = article.title
                    textInput.setText(article.text)
                    textInput.setSelection(0)
                    val host = runCatching { Uri.parse(article.url).host }.getOrNull().orEmpty()
                        .removePrefix("www.")
                    val via = when (article.source) {
                        "reader" -> " · via reader service"
                        "pdf" -> " · PDF"
                        else -> ""
                    }
                    val head = if (article.title.isNotBlank()) "✓ ${article.title}\n" else "✓ "
                    fetchStatus.text = "$head${"%,d".format(article.text.length)} characters from $host$via"
                    if (then != null) begin(then)
                } else {
                    fetchStatus.text = "✗ ${article.error}"
                }
            }
        }
    }

    // ---- reading -----------------------------------------------------------------

    private fun onPlayPressed() {
        when (Narrator.status.phase) {
            Narrator.Phase.PLAYING -> Narrator.pause()
            Narrator.Phase.PAUSED -> Narrator.resume()
            Narrator.Phase.LOADING, Narrator.Phase.EXPORTING -> Unit
            else -> begin(Narrator.Mode.PLAY)
        }
    }

    private fun begin(mode: Narrator.Mode) {
        if (busy) return
        if (!Models.isInstalled(this, Models.spec(engine))) {
            refreshModelCard()
            toast("Download the voice model first (one time)")
            findViewById<View>(R.id.root).scrollTo(0, 0)
            return
        }
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) {
            if (urlInput.text.isNotBlank()) fetchLink(then = mode) else toast("Add a link or some text first")
            return
        }
        askForNotificationPermission()
        hideKeyboard()
        val voice = currentVoice()
        val speedNow = speed
        val maxChars = if (engine == EngineId.KOKORO) 280 else 320
        setBusy(true)
        worker.execute {
            try {
                // The text box may itself hold just a link: fetch that first.
                val link = Backend.linkIn(this, text)
                if (link != null) {
                    ui.post {
                        setBusy(false)
                        urlInput.setText(link)
                        fetchLink(then = mode)
                    }
                    return@execute
                }
                val prepared = Backend.prepare(this, text, maxChars)
                ui.post {
                    setBusy(false)
                    if (prepared.chunks.isEmpty()) {
                        toast("Nothing readable in that text")
                        return@post
                    }
                    if (prepared.lang == "hi" && voice.engine == EngineId.KOKORO && voice.lang != "hi") {
                        toast("Hindi text detected: using a Hindi voice")
                    }
                    lastOutput = null
                    val label = title.ifBlank { text.lineSequence().first().take(60) }
                    Narrator.start(
                        this,
                        Narrator.Request(prepared.chunks, voice, speedNow, prepared.lang, mode, label),
                    )
                }
            } catch (t: Throwable) {
                ui.post {
                    setBusy(false)
                    toast("Could not prepare the text: ${t.message}")
                }
            }
        }
    }

    override fun onStatus(status: Narrator.Status) {
        ui.post { render(status) }
    }

    private fun render(s: Narrator.Status) {
        val active = s.active
        playButton.text = when (s.phase) {
            Narrator.Phase.PLAYING -> "Pause"
            Narrator.Phase.PAUSED -> "Resume"
            Narrator.Phase.LOADING -> "Loading…"
            Narrator.Phase.EXPORTING -> "Saving…"
            else -> "Read aloud"
        }
        playButton.isEnabled = !busy && s.phase != Narrator.Phase.LOADING &&
            s.phase != Narrator.Phase.EXPORTING
        setEnabled(stopButton, active)
        setEnabled(saveButton, !active && !busy)
        playProgress.max = maxOf(1, s.total)
        playProgress.progress = if (s.phase == Narrator.Phase.DONE) playProgress.max else s.index
        playStatus.text = when (s.phase) {
            Narrator.Phase.IDLE -> if (s.message.isNotBlank() && s.message != "Stopped") s.message else "Ready"
            Narrator.Phase.LOADING -> s.message.ifBlank { "Loading voice…" }
            Narrator.Phase.PLAYING -> "Reading part ${s.index} of ${s.total}"
            Narrator.Phase.PAUSED -> "Paused at part ${s.index} of ${s.total}"
            Narrator.Phase.EXPORTING -> "Saving audio · part ${s.index} of ${s.total}"
            Narrator.Phase.DONE -> s.message
            Narrator.Phase.ERROR -> "Something went wrong: ${s.message}"
        }
        playStatus.setTextColor(getColor(if (s.phase == Narrator.Phase.ERROR) R.color.danger else R.color.text))
        val showChunk = active && s.chunk.isNotBlank()
        nowReading.visibility = if (showChunk) View.VISIBLE else View.GONE
        if (showChunk) nowReading.text = "“${s.chunk}”"
        if (s.phase == Narrator.Phase.DONE && s.output != null) lastOutput = s.output
        shareButton.visibility =
            if (lastOutput != null && !active && Build.VERSION.SDK_INT >= 29) View.VISIBLE else View.GONE
        window.apply {
            if (active) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun shareOutput() {
        val out = lastOutput ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, out.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share audio"))
    }

    // ---- model download ------------------------------------------------------------

    private fun refreshModelCard() {
        val spec = Models.spec(engine)
        val installed = Models.isInstalled(this, spec)
        modelCard.visibility = if (installed && !installing) View.GONE else View.VISIBLE
        if (!installing) {
            modelInfo.text = "${engine.title}: ${spec.sizeMb} MB, downloaded once from the " +
                "sherpa-onnx GitHub release. After that, speech is generated offline on your phone."
            modelProgress.visibility = View.GONE
            modelDownload.visibility = View.VISIBLE
            modelCancel.visibility = View.GONE
        }
    }

    private fun installModel() {
        if (installing) return
        val spec = Models.spec(engine)
        installing = true
        cancelInstall = false
        modelDownload.visibility = View.GONE
        modelCancel.visibility = View.VISIBLE
        modelProgress.visibility = View.VISIBLE
        modelProgress.isIndeterminate = true
        modelInfo.text = "Starting download…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sink = object : Backend.ProgressSink {
            override fun update(stage: String, percent: Int) {
                ui.post {
                    modelProgress.isIndeterminate = false
                    modelProgress.progress = percent
                    modelInfo.text = if (stage == "download") {
                        "Downloading ${spec.sizeMb} MB… $percent%"
                    } else {
                        "Unpacking… $percent%"
                    }
                }
            }

            override fun isCancelled() = cancelInstall
        }
        Thread({
            val error = try {
                Backend.installModel(this, spec, sink)
            } catch (t: Throwable) {
                t.message ?: "Install failed"
            }
            ui.post {
                installing = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                refreshModelCard()
                when (error) {
                    null -> toast("Voice model ready")
                    "cancelled" -> modelInfo.text = "Download paused. Tap Download to resume."
                    else -> modelInfo.text = "Download failed: $error"
                }
            }
        }, "model-install").start()
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun setBusy(b: Boolean) {
        busy = b
        fetchButton.isEnabled = !b
        setEnabled(fetchButton, !b)
        render(Narrator.status)
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.45f
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun savePrefs() {
        val text = textInput.text.toString()
        prefs.edit()
            .putString("url", urlInput.text.toString())
            .putString("text", if (text.length <= 200_000) text else text.take(200_000))
            .putString("title", title)
            .apply()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
