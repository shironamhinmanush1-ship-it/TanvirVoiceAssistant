package com.tanvir.voiceassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.text.InputType
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.tanvir.voiceassistant.data.LocalStore
import com.tanvir.voiceassistant.engine.BengaliAsrEngine
import com.tanvir.voiceassistant.engine.BengaliModelManager
import com.tanvir.voiceassistant.engine.WavWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: LocalStore
    private lateinit var models: BengaliModelManager
    private lateinit var tts: TextToSpeech
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavWriter? = null
    private var asr: BengaliAsrEngine? = null
    @Volatile private var recording = false
    @Volatile private var paused = false
    private var captureThread: Thread? = null
    private var latestAudio: File? = null
    private var translator: Translator? = null
    private var sourceLang = TranslateLanguage.BENGALI
    private var targetLang = TranslateLanguage.ENGLISH
    private var speechLocale = Locale("bn", "BD")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        models = BengaliModelManager(this)
        tts = TextToSpeech(this, this)
        buildShell()
        showVoicePage()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 10)
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        root.addView(TextView(this).apply {
            text = "Tanvir Voice Assistant"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(125, 220, 190))
            text = if (models.isInstalled()) "বাংলা অফলাইন মডেল প্রস্তুত" else "বাংলা অফলাইন মডেল ইনস্টল করা হয়নি"
            setPadding(4, 8, 4, 8)
        }
        root.addView(status)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf("ভয়েস", "অনুবাদ", "রেকর্ড", "হিস্ট্রি", "শব্দ", "মডেল")
        items.forEachIndexed { index, label ->
            nav.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                setOnClickListener {
                    when (index) {
                        0 -> showVoicePage()
                        1 -> showTranslatorPage()
                        2 -> showRecorderPage()
                        3 -> showHistoryPage()
                        4 -> showVocabularyPage()
                        else -> showModelPage()
                    }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        val scroll = ScrollView(this)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun clearPage() = page.removeAllViews()
    private fun heading(value: String) {
        page.addView(TextView(this).apply {
            text = value; textSize = 19f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 8)
        })
    }
    private fun note(value: String) {
        page.addView(TextView(this).apply {
            text = value; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(4, 8, 4, 8)
        })
    }
    private fun button(value: String, action: () -> Unit) {
        page.addView(Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } })
    }
    private fun newEditor(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        textSize = 17f
        minLines = 6
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setPadding(12, 12, 12, 12)
        setBackgroundColor(Color.rgb(35, 42, 50))
    }
    private fun setStatus(value: String) { status.text = value }

    private fun showVoicePage() {
        clearPage()
        heading("ভয়েস থেকে বাংলা লেখা")
        note("অফলাইন ট্রান্সক্রিপশনের জন্য প্রথমে মডেল পেজ থেকে বাংলা মডেল ইনস্টল করো।")
        editor = newEditor("এখানে ট্রান্সক্রিপশন বা নিজের লেখা থাকবে…")
        page.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        button("🎙 Start offline Bengali transcription") { startCapture(true) }
        button("⏸ Pause / Resume") { toggleCapture() }
        button("⏹ Stop & save") { stopCapture() }
        button("Original → Clean punctuation") { editor.setText(cleanText(editor.text.toString())) }
        button("Save draft") { saveText("Draft", "draft") }
        button("Copy text") { copyText(editor.text.toString()) }
        button("Share to ChatGPT / other apps") { shareText(editor.text.toString()) }
        button("Read aloud") { speak(editor.text.toString()) }
    }

    private fun cleanText(value: String): String {
        return value.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,।.!?])"), "$1").trim()
    }

    private fun startCapture(withAsr: Boolean) {
        if (recording) return
        if (withAsr && !models.isInstalled()) {
            setStatus("আগে Model Manager থেকে বাংলা ASR মডেল ইনস্টল করো")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = minBuffer.coerceAtLeast(8192)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            setStatus("মাইক্রোফোন চালু করা যায়নি")
            return
        }
        val directory = File(filesDir, "recordings").apply { mkdirs() }
        latestAudio = File(directory, "voice-" + System.currentTimeMillis() + ".wav")
        val writer = WavWriter(latestAudio!!)
        val engine = if (withAsr) BengaliAsrEngine(models.modelDirectory) else null
        try { engine?.start() } catch (error: Exception) {
            writer.close(); audio.release()
            setStatus("অফলাইন ASR শুরু হয়নি: " + (error.localizedMessage ?: "unknown error"))
            return
        }
        audioRecord = audio
        wavWriter = writer
        asr = engine
        recording = true
        paused = false
        audio.startRecording()
        setStatus(if (withAsr) "অফলাইন বাংলা রেকর্ড ও ট্রান্সক্রিপশন চলছে" else "অডিও রেকর্ডিং চলছে")
        captureThread = thread(name = "TanvirAudioCapture") {
            val pcm = ShortArray(2048)
            while (recording) {
                if (paused) {
                    Thread.sleep(100)
                    continue
                }
                val count = audio.read(pcm, 0, pcm.size)
                if (count > 0) {
                    writer.write(pcm, count)
                    if (engine != null) {
                        val samples = FloatArray(count) { pcm[it] / 32768.0f }
                        val partial = try { engine.accept(samples) } catch (_: Exception) { "" }
                        if (partial.isNotBlank()) runOnUiThread {
                            if (::editor.isInitialized) editor.setText(partial)
                        }
                    }
                }
            }
        }
    }

    private fun toggleCapture() {
        if (!recording) { setStatus("কোনো রেকর্ডিং চলছে না"); return }
        paused = !paused
        try {
            if (paused) {
                audioRecord?.stop()
                setStatus("রেকর্ডিং Pause হয়েছে")
            } else {
                audioRecord?.startRecording()
                setStatus("রেকর্ডিং Resume হয়েছে")
            }
        } catch (error: Exception) {
            setStatus("রেকর্ডিং অবস্থা বদলানো যায়নি: " + (error.localizedMessage ?: ""))
        }
    }

    private fun stopCapture() {
        if (!recording) { setStatus("কোনো রেকর্ডিং চলছে না"); return }
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        captureThread?.join(2000)
        try { wavWriter?.close() } catch (_: Exception) {}
        wavWriter = null
        val finalText = try { asr?.finish().orEmpty() } catch (error: Exception) {
            setStatus("ASR শেষ করা যায়নি: " + (error.localizedMessage ?: ""))
            ""
        }
        asr = null
        audioRecord?.release()
        audioRecord = null
        if (finalText.isNotBlank() && ::editor.isInitialized) editor.setText(finalText)
        if (::editor.isInitialized && editor.text.isNotBlank()) saveText("Voice transcription", "transcription")
        setStatus("রেকর্ডিং লোকালি সেভ হয়েছে")
    }

    private fun showTranslatorPage() {
        clearPage()
        heading("বাংলা ↔ English Translator")
        val input = newEditor("অনুবাদের লেখা লিখো…")
        val output = newEditor("অনুবাদ এখানে আসবে…")
        page.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
        button("বাংলা → English") {
            sourceLang = TranslateLanguage.BENGALI
            targetLang = TranslateLanguage.ENGLISH
            runTranslation(input.text.toString(), output)
        }
        button("English → বাংলা") {
            sourceLang = TranslateLanguage.ENGLISH
            targetLang = TranslateLanguage.BENGALI
            runTranslation(input.text.toString(), output)
        }
        page.addView(output, LinearLayout.LayoutParams(-1, 0, 1f))
        button("Copy translation") { copyText(output.text.toString()) }
        button("Share translation") { shareText(output.text.toString()) }
        note("প্রথমবার সংশ্লিষ্ট ভাষার মডেল ডাউনলোড হবে। এরপর ML Kit on-device translation অফলাইনে কাজ করতে পারে।")
    }

    private fun runTranslation(value: String, output: EditText) {
        if (value.isBlank()) { setStatus("অনুবাদের জন্য লেখা দাও"); return }
        translator?.close()
        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(sourceLang).setTargetLanguage(targetLang).build()
        )
        translator = client
        setStatus("অনুবাদ মডেল প্রস্তুত হচ্ছে…")
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                client.translate(value)
                    .addOnSuccessListener { translated ->
                        output.setText(translated)
                        store.save("Translation", translated, "translation")
                        setStatus("অনুবাদ সম্পন্ন")
                    }
                    .addOnFailureListener { setStatus("অনুবাদ ব্যর্থ: " + (it.localizedMessage ?: "")) }
            }
            .addOnFailureListener { setStatus("ভাষার মডেল ডাউনলোড ব্যর্থ: " + (it.localizedMessage ?: "")) }
    }

    private fun showRecorderPage() {
        clearPage()
        heading("Voice Recorder")
        note("অডিও ফাইল অ্যাপের ব্যক্তিগত স্টোরেজে WAV ফরম্যাটে রাখা হয়।")
        button("Start recording") { startCapture(false) }
        button("Pause / Resume") { toggleCapture() }
        button("Stop & save recording") { stopCapture() }
        button("Import WAV and transcribe offline") {
            if (!models.isInstalled()) { setStatus("আগে বাংলা ASR মডেল ইনস্টল করো"); return@button }
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "audio/wav"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(picker, 77)
        }
        button("Play latest recording") {
            val file = latestAudio
            if (file == null || !file.exists()) setStatus("কোনো রেকর্ডিং পাওয়া যায়নি")
            else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.fromFile(file), "audio/wav")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(intent) } catch (_: Exception) { setStatus("WAV player পাওয়া যায়নি") }
            }
        }
    }

    @Deprecated("Used for Android document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            appScope.launch {
                setStatus("WAV ফাইল ট্রান্সক্রাইব হচ্ছে…")
                try {
                    val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: throw IllegalStateException("ফাইল পড়া যায়নি")
                    if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") throw IllegalArgumentException("শুধু PCM WAV ফাইল সমর্থিত")
                    val sampleRate = littleInt(bytes, 24)
                    val channels = littleShort(bytes, 22)
                    val bits = littleShort(bytes, 34)
                    if (channels != 1 || bits != 16 || sampleRate != 16000) throw IllegalArgumentException("WAV mono 16-bit, 16 kHz হতে হবে")
                    val modelEngine = BengaliAsrEngine(models.modelDirectory)
                    val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
                        modelEngine.start()
                        val dataStart = 44
                        val shorts = ShortArray((bytes.size - dataStart) / 2)
                        for (i in shorts.indices) {
                            val lo = bytes[dataStart + i * 2].toInt() and 255
                            val hi = bytes[dataStart + i * 2 + 1].toInt()
                            shorts[i] = ((hi shl 8) or lo).toShort()
                        }
                        var offset = 0
                        while (offset < shorts.size) {
                            val count = minOf(2048, shorts.size - offset)
                            val floats = FloatArray(count) { shorts[offset + it] / 32768.0f }
                            modelEngine.accept(floats)
                            offset += count
                        }
                        modelEngine.finish()
                    }
                    if (::editor.isInitialized) editor.setText(result)
                    store.save("Imported WAV transcription", result, "transcription")
                    setStatus("WAV ট্রান্সক্রিপশন সম্পন্ন (" + sampleRate + " Hz input)")
                } catch (error: Exception) {
                    setStatus("WAV ট্রান্সক্রিপশন ব্যর্থ: " + (error.localizedMessage ?: ""))
                }
            }
        }
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        littleShort(bytes, offset) or (littleShort(bytes, offset + 2) shl 16)

    private fun showHistoryPage() {
        clearPage()
        heading("Saved History")
        val rows = store.history()
        if (rows.isEmpty()) note("এখনো কোনো লেখা সেভ করা হয়নি।")
        rows.forEach { item ->
            page.addView(TextView(this).apply {
                text = item.title + " • " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.createdAt))
                textSize = 15f; setTextColor(Color.WHITE); setPadding(4, 8, 4, 4)
            })
            page.addView(TextView(this).apply { text = item.body.take(450); textSize = 14f; setTextColor(Color.LTGRAY) })
            button("Open saved text") { showVoicePage(); editor.setText(item.body) }
            button("Delete saved text") { store.deleteSaved(item.id); showHistoryPage() }
        }
    }

    private fun showVocabularyPage() {
        clearPage()
        heading("Personal Vocabulary")
        val term = EditText(this).apply { hint = "নাম, গান, technical term"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        page.addView(term)
        button("Add vocabulary term") {
            if (term.text.isNotBlank()) {
                store.addVocabulary(term.text.toString())
                setStatus("শব্দ সেভ হয়েছে")
                showVocabularyPage()
            }
        }
        store.vocabulary().forEach { word ->
            note(word)
            button("Delete: " + word) { store.deleteVocabulary(word); showVocabularyPage() }
        }
        note("শব্দতালিকা লোকালি সেভ হয়। নির্বাচিত ASR মডেলে সরাসরি hotword injection যাচাই হয়নি; তাই স্বয়ংক্রিয় সংশোধনের দাবি করা হচ্ছে না।")
    }

    private fun showModelPage() {
        clearPage()
        heading("Offline Model Manager")
        note(if (models.isInstalled()) "বাংলা ASR মডেল ইনস্টল আছে। ভয়েস রেকগনিশন ডিভাইসেই চলবে।" else "বাংলা ASR মডেল ইনস্টল নেই। ডাউনলোড করতে ইন্টারনেট লাগবে।")
        button("Download & install Bengali model (~90 MB)") {
            setStatus("বাংলা মডেল ডাউনলোড হচ্ছে…")
            lifecycleScope.launch {
                try {
                    models.install { percent -> runOnUiThread { setStatus("মডেল ডাউনলোড ও ইনস্টল: " + percent + "%") } }
                    setStatus("বাংলা অফলাইন ASR মডেল প্রস্তুত")
                    showModelPage()
                } catch (error: Exception) {
                    setStatus("মডেল ইনস্টল ব্যর্থ: " + (error.localizedMessage ?: ""))
                }
            }
        }
        button("Delete Bengali model") {
            models.delete()
            setStatus("বাংলা ASR মডেল মুছে ফেলা হয়েছে")
            showModelPage()
        }
        note("Engine: sherpa-onnx streaming Zipformer Bengali (Vosk-derived). Bengali model only; Bengali-English mixed recognition needs separate device testing. The model does not promise perfect punctuation or 100% accuracy.")
    }

    private fun saveText(title: String, kind: String) {
        if (!::editor.isInitialized || editor.text.isBlank()) { setStatus("সেভ করার মতো লেখা নেই"); return }
        store.save(title, editor.text.toString(), kind)
        setStatus("লেখা লোকালি সেভ হয়েছে")
    }
    private fun copyText(value: String) {
        if (value.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Tanvir Voice Assistant", value))
        setStatus("কপি হয়েছে")
    }
    private fun shareText(value: String) {
        if (value.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }
        startActivity(Intent.createChooser(send, "Share text"))
    }
    private fun speak(value: String) {
        if (value.isBlank()) return
        val result = tts.setLanguage(speechLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            setStatus("এই ভাষার TTS voice ডিভাইসে ইনস্টল নেই")
            return
        }
        tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "tanvir-voice")
    }
    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts.language = Locale("bn", "BD")
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setStatus("মাইক্রোফোন অনুমতি দেওয়া হয়েছে। আবার Record চাপো।")
        } else if (requestCode == 42) setStatus("মাইক্রোফোন অনুমতি ছাড়া রেকর্ডিং সম্ভব নয়")
    }
    override fun onDestroy() {
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { wavWriter?.close() } catch (_: Exception) {}
        asr?.close()
        audioRecord?.release()
        translator?.close()
        tts.stop()
        tts.shutdown()
        store.close()
        appScope.cancel()
        super.onDestroy()
    }
}
