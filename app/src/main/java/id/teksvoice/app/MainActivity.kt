package id.teksvoice.app

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import id.teksvoice.app.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class State { IDLE, SPEAKING, PAUSED }

    private lateinit var binding: ActivityMainBinding

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var needsRecheck = false

    // Simpan audio
    private var exporting = false
    private var exporter: AudioExporter? = null
    private var pendingExport: List<AudioExporter.Item>? = null
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
            if (uri != null) startExport(uri) else pendingExport = null
        }

    private var state = State.IDLE
    private var chunks: List<SpeechChunk> = emptyList()
    private var currentIndex = 0
    private var lastIndexInSession = -1

    // Dinaikkan setiap kali pembacaan dimulai/dihentikan, supaya callback lama diabaikan.
    private var session = 0

    private var voices: List<Voice> = emptyList()
    private var appliedVoiceName: String? = null

    private val indonesian: Locale = Locale.Builder().setLanguage("id").setRegion("ID").build()

    private val highlightSpan by lazy {
        BackgroundColorSpan(
            MaterialColors.getColor(
                binding.inputText,
                com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        restoreSettings()
        setupListeners()
        handleIncomingIntent(intent)
        updateUi()
        initTts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Kembali dari pengaturan suara: periksa ulang mesin suara.
        if (needsRecheck) {
            needsRecheck = false
            tts?.shutdown()
            tts = null
            ttsReady = false
            initTts()
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        exporter?.cancel()
        session++
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Pengaturan

    private fun restoreSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        binding.sliderSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f).snap()
        binding.sliderPitch.value = prefs.getFloat(KEY_PITCH, 1.0f).snap()
        binding.checkNormalize.isChecked = prefs.getBoolean(KEY_NORMALIZE, true)
        binding.inputText.setText(prefs.getString(KEY_TEXT, "").orEmpty())
        updateSliderLabels()
        updateCounter()
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_SPEED, binding.sliderSpeed.value)
            .putFloat(KEY_PITCH, binding.sliderPitch.value)
            .putBoolean(KEY_NORMALIZE, binding.checkNormalize.isChecked)
            .putString(KEY_TEXT, binding.inputText.text?.toString().orEmpty())
            .apply()
    }

    private fun Float.snap(): Float = (Math.round(this * 10) / 10f).coerceIn(0.5f, 2.0f)

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                stopSpeaking("Siap")
                binding.inputText.setText(shared)
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun setupListeners() {
        binding.inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCounter()
                // Teks berubah -> posisi sorotan tidak valid lagi.
                if (state != State.IDLE) stopSpeaking("Teks diubah, pembacaan dihentikan")
            }
        })

        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnClear.setOnClickListener { binding.inputText.setText("") }
        binding.btnPlay.setOnClickListener { onPlayClicked() }
        binding.btnStop.setOnClickListener {
            if (exporting) exporter?.cancel() else stopSpeaking("Dihentikan")
        }
        binding.btnSave.setOnClickListener { onSaveClicked() }
        binding.btnSystemSettings.setOnClickListener { openTtsSettings() }

        binding.sliderSpeed.addOnChangeListener { _, _, _ -> updateSliderLabels() }
        binding.sliderPitch.addOnChangeListener { _, _, _ -> updateSliderLabels() }
        val restartOnRelease = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = restartIfSpeaking()
        }
        binding.sliderSpeed.addOnSliderTouchListener(restartOnRelease)
        binding.sliderPitch.addOnSliderTouchListener(restartOnRelease)
    }

    private fun updateSliderLabels() {
        binding.labelSpeed.text = getString(R.string.label_speed, binding.sliderSpeed.value)
        binding.labelPitch.text = getString(R.string.label_pitch, binding.sliderPitch.value)
    }

    private fun updateCounter() {
        binding.textCount.text = getString(R.string.char_count, binding.inputText.length())
    }

    private fun updateUi() {
        binding.btnPlay.isEnabled = ttsReady && !exporting
        binding.btnSave.isEnabled = ttsReady && !exporting
        binding.btnPlay.setText(
            when (state) {
                State.IDLE -> R.string.play
                State.SPEAKING -> R.string.pause
                State.PAUSED -> R.string.resume
            }
        )
        binding.btnStop.isEnabled = state != State.IDLE || exporting
    }

    private fun setStatus(message: String) {
        binding.textStatus.text = message
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else null

        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
            return
        }
        val editable = binding.inputText.text ?: return
        val a = binding.inputText.selectionStart.coerceAtLeast(0)
        val b = binding.inputText.selectionEnd.coerceAtLeast(0)
        editable.replace(minOf(a, b), maxOf(a, b), text)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.inputText.clearFocus()
    }

    // ---------------------------------------------------------------- Mesin suara

    private fun initTts() {
        setStatus("Menyiapkan mesin suara…")
        tts = TextToSpeech(this) { status -> runOnUiThread { onTtsInit(status) } }
    }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            updateUi()
            setStatus("Mesin suara tidak tersedia di perangkat ini")
            showMissingVoiceDialog()
            return
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(progressListener)

        val availability = engine.isLanguageAvailable(indonesian)
        if (availability < 0) {
            ttsReady = false
            updateUi()
            setStatus("Suara Bahasa Indonesia belum terpasang")
            showMissingVoiceDialog()
            return
        }

        engine.setLanguage(indonesian)
        setupVoiceSpinner()
        ttsReady = true
        updateUi()
        setStatus("Siap")
    }

    private fun setupVoiceSpinner() {
        val engine = tts ?: return
        val all: Set<Voice> = try {
            engine.voices
        } catch (e: Exception) {
            null
        }.orEmpty()

        // Suara offline & kualitas tertinggi di urutan pertama.
        voices = all
            .filter { it.locale.language == "id" || it.locale.language == "in" }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )

        if (voices.isEmpty()) {
            binding.voiceGroup.visibility = View.GONE
            return
        }
        binding.voiceGroup.visibility = View.VISIBLE

        val labels = voices.mapIndexed { i, v ->
            val mode = if (v.isNetworkConnectionRequired) "online" else "offline"
            "Suara ${i + 1} · ${qualityLabel(v.quality)} · $mode"
        }
        binding.spinnerVoice.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_VOICE, null)
        val index = voices.indexOfFirst { it.name == saved }.takeIf { it >= 0 } ?: 0
        binding.spinnerVoice.setSelection(index, false)
        applyVoice(index)

        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (voices.getOrNull(position)?.name != appliedVoiceName) {
                    applyVoice(position)
                    restartIfSpeaking()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyVoice(index: Int) {
        val voice = voices.getOrNull(index) ?: return
        tts?.setVoice(voice)
        appliedVoiceName = voice.name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_VOICE, voice.name).apply()
    }

    private fun qualityLabel(quality: Int): String = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "kualitas sangat tinggi"
        quality >= Voice.QUALITY_HIGH -> "kualitas tinggi"
        quality >= Voice.QUALITY_NORMAL -> "kualitas normal"
        else -> "kualitas rendah"
    }

    private fun showMissingVoiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Suara Indonesia belum tersedia")
            .setMessage(
                "Pasang data suara Bahasa Indonesia pada mesin suara perangkat " +
                    "(misalnya Layanan Ucapan oleh Google), lalu kembali ke aplikasi ini."
            )
            .setPositiveButton("Pasang suara") { _, _ -> installVoiceData() }
            .setNeutralButton("Pengaturan") { _, _ -> openTtsSettings() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun installVoiceData() {
        needsRecheck = true
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: ActivityNotFoundException) {
            openTtsSettings()
        }
    }

    private fun openTtsSettings() {
        needsRecheck = true
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (candidate in candidates) {
            try {
                startActivity(candidate)
                return
            } catch (e: ActivityNotFoundException) {
                // coba kandidat berikutnya
            }
        }
        needsRecheck = false
        Toast.makeText(this, "Pengaturan suara tidak bisa dibuka", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- Pembacaan

    private fun onPlayClicked() {
        if (!ttsReady) return
        when (state) {
            State.IDLE -> {
                hideKeyboard()
                chunks = TextChunker.split(binding.inputText.text?.toString().orEmpty())
                if (chunks.isEmpty()) {
                    setStatus("Tulis atau tempel teks dulu")
                    return
                }
                startSpeaking(0)
            }
            State.SPEAKING -> pauseSpeaking()
            State.PAUSED -> startSpeaking(currentIndex)
        }
    }

    private fun startSpeaking(fromIndex: Int) {
        val engine = tts ?: return
        val normalize = binding.checkNormalize.isChecked

        // Pasangan (indeks potongan, teks yang dibaca, jeda sesudahnya)
        val prepared = (fromIndex until chunks.size).mapNotNull { i ->
            val chunk = chunks[i]
            val text = if (normalize) TextNormalizer.normalize(chunk.text) else chunk.text
            if (text.isBlank()) null else Triple(i, text, chunk.pauseAfterMs)
        }
        if (prepared.isEmpty()) {
            finishSpeaking("Tidak ada teks yang bisa dibaca")
            return
        }

        session++
        val sessionId = session
        engine.stop()
        engine.setSpeechRate(binding.sliderSpeed.value)
        engine.setPitch(binding.sliderPitch.value)
        lastIndexInSession = prepared.last().first

        var failed = false
        prepared.forEachIndexed { n, (index, text, pause) ->
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, "u${sessionId}_$index")
            if (result == TextToSpeech.ERROR) failed = true
            if (pause > 0 && n < prepared.lastIndex) {
                engine.playSilentUtterance(pause, TextToSpeech.QUEUE_ADD, "p${sessionId}_$index")
            }
        }
        if (failed) {
            session++
            engine.stop()
            finishSpeaking("Gagal memulai pembacaan")
            return
        }

        currentIndex = prepared.first().first
        state = State.SPEAKING
        updateUi()
        setStatus("Membaca bagian ${currentIndex + 1} dari ${chunks.size}")
    }

    private fun pauseSpeaking() {
        session++
        tts?.stop()
        state = State.PAUSED
        updateUi()
        setStatus("Dijeda di bagian ${currentIndex + 1} dari ${chunks.size}")
    }

    private fun stopSpeaking(message: String) {
        session++
        tts?.stop()
        finishSpeaking(message)
    }

    private fun finishSpeaking(message: String) {
        state = State.IDLE
        highlight(null)
        updateUi()
        setStatus(message)
    }

    private fun restartIfSpeaking() {
        if (state == State.SPEAKING) startSpeaking(currentIndex)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (kind, sessionId, index) = parseId(utteranceId) ?: return
            if (kind != 'u') return
            runOnUiThread {
                if (sessionId == session) {
                    currentIndex = index
                    highlight(index)
                    setStatus("Membaca bagian ${index + 1} dari ${chunks.size}")
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            val (kind, sessionId, index) = parseId(utteranceId) ?: return
            if (kind != 'u') return
            runOnUiThread {
                if (sessionId == session && index == lastIndexInSession) finishSpeaking("Selesai")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = handleError(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = handleError(utteranceId)

        private fun handleError(utteranceId: String?) {
            val (_, sessionId, _) = parseId(utteranceId) ?: return
            runOnUiThread {
                if (sessionId == session) {
                    session++
                    tts?.stop()
                    finishSpeaking("Terjadi kesalahan pada mesin suara")
                }
            }
        }
    }

    /** ID utterance berbentuk "<jenis><sesi>_<indeks>", mis. "u3_12". */
    private fun parseId(id: String?): Triple<Char, Int, Int>? {
        if (id == null || id.length < 4) return null
        val parts = id.substring(1).split('_')
        if (parts.size != 2) return null
        val sessionId = parts[0].toIntOrNull() ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        return Triple(id[0], sessionId, index)
    }

    // ---------------------------------------------------------------- Simpan audio

    private fun onSaveClicked() {
        if (!ttsReady || exporting) return
        hideKeyboard()

        val normalize = binding.checkNormalize.isChecked
        val items = TextChunker.split(binding.inputText.text?.toString().orEmpty())
            .mapNotNull { chunk ->
                val text = if (normalize) TextNormalizer.normalize(chunk.text) else chunk.text
                if (text.isBlank()) null else AudioExporter.Item(text, chunk.pauseAfterMs)
            }
        if (items.isEmpty()) {
            setStatus("Tulis atau tempel teks dulu")
            return
        }

        if (state != State.IDLE) stopSpeaking("Siap")
        pendingExport = items
        createDocument.launch("teks-ke-suara.m4a")
    }

    private fun startExport(uri: Uri) {
        val engine = tts ?: return
        val items = pendingExport ?: return
        pendingExport = null

        engine.setSpeechRate(binding.sliderSpeed.value)
        engine.setPitch(binding.sliderPitch.value)

        val job = AudioExporter(engine, cacheDir, progressListener)
        exporter = job
        exporting = true
        updateUi()
        setStatus("Membuat audio…")

        Thread {
            val temp = File(cacheDir, "teks-ke-suara-export.m4a")
            val message = try {
                job.export(items, temp) { done, total ->
                    runOnUiThread {
                        if (exporter === job) setStatus("Membuat audio… bagian $done dari $total")
                    }
                }
                val out = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Tidak bisa menulis ke lokasi yang dipilih")
                out.use { stream -> temp.inputStream().use { it.copyTo(stream) } }
                "Audio tersimpan"
            } catch (e: Exception) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (ignored: Exception) {
                    // file kosong mungkin tertinggal, tidak masalah
                }
                if (job.cancelled) {
                    "Pembuatan audio dibatalkan"
                } else {
                    "Gagal menyimpan audio: ${e.message ?: "kesalahan tidak diketahui"}"
                }
            } finally {
                temp.delete()
            }
            runOnUiThread {
                if (exporter === job) {
                    exporter = null
                    exporting = false
                    updateUi()
                    setStatus(message)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- Sorotan teks

    private fun highlight(index: Int?) {
        val editable = binding.inputText.text ?: return
        editable.removeSpan(highlightSpan)
        val chunk = index?.let { chunks.getOrNull(it) } ?: return
        val start = chunk.start.coerceIn(0, editable.length)
        val end = chunk.end.coerceIn(start, editable.length)
        if (end > start) {
            editable.setSpan(highlightSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            scrollToOffset(start)
        }
    }

    private fun scrollToOffset(offset: Int) {
        val field = binding.inputText
        val layout = field.layout ?: return
        val line = layout.getLineForOffset(offset.coerceIn(0, field.length()))
        val visibleHeight = field.height - field.totalPaddingTop - field.totalPaddingBottom
        val maxScroll = (layout.height - visibleHeight).coerceAtLeast(0)
        val target = (layout.getLineTop(line) - visibleHeight / 4).coerceIn(0, maxScroll)
        field.scrollTo(0, target)
    }

    private companion object {
        const val PREFS = "teks_ke_suara"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch"
        const val KEY_VOICE = "voice"
        const val KEY_NORMALIZE = "normalize"
        const val KEY_TEXT = "text"
    }
}
