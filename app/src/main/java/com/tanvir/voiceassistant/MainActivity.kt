package com.tanvir.voiceassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.graphics.Color
import android.text.InputType
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var tts: TextToSpeech
    private var language = "bn-BD"
    private val requestSpeech = 41
    private val requestPermission = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 18)
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        val title = TextView(this).apply {
            text = "Tanvir Voice Assistant"
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "বাংলা ভয়েস • লেখা • শেয়ার"
            textSize = 15f; setTextColor(Color.rgb(125, 220, 190)); gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "প্রস্তুত"; textSize = 14f; setTextColor(Color.LTGRAY)
            setPadding(4, 18, 4, 10)
        }
        val languagePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("বাংলা (বাংলাদেশ)", "English"))
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    language = if (position == 0) "bn-BD" else "en-US"
                }
            })
        }
        editor = EditText(this).apply {
            hint = "এখানে কথা থেকে পাওয়া লেখা দেখা যাবে…"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            textSize = 18f; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 9; setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(35, 42, 50))
        }
        root.addView(title, lp(-1, -2))
        root.addView(subtitle, lp(-1, -2))
        root.addView(status, lp(-1, -2))
        root.addView(languagePicker, lp(-1, -2))
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("🎙 কথা লিখুন / Start voice input") { startSpeech() })
        root.addView(button("🔊 লেখা পড়ে শোনান") { speakText() })
        root.addView(button("📋 Copy") { copyText() })
        root.addView(button("↗ Share to ChatGPT / other apps") { shareText() })
        root.addView(button("Clear") { editor.text.clear(); status.text = "লেখা মুছে ফেলা হয়েছে" })
        setContentView(root)
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply { bottomMargin = 8 }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { action() }
        layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun startSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestPermission); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (language == "bn-BD") "বাংলায় কথা বলুন" else "Speak now")
        }
        try { status.text = "মাইক্রোফোন চালু হচ্ছে…"; startActivityForResult(intent, requestSpeech) }
        catch (_: Exception) { status.text = "এই ডিভাইসে speech recognition service পাওয়া যায়নি" }
    }

    @Deprecated("Deprecated by Android API; kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestSpeech) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (resultCode == RESULT_OK && !results.isNullOrEmpty()) {
                val spoken = results[0]
                if (editor.text.isNotEmpty()) editor.append("\n")
                editor.append(spoken)
                status.text = "ট্রান্সক্রিপশন পাওয়া গেছে"
            } else status.text = "কোনো লেখা পাওয়া যায়নি"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermission && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSpeech()
        else status.text = "মাইক্রোফোন অনুমতি ছাড়া ভয়েস ইনপুট চলবে না"
    }

    private fun speakText() {
        val value = editor.text.toString().trim()
        if (value.isEmpty()) { status.text = "আগে কিছু লেখা যোগ করো"; return }
        val locale = if (language == "bn-BD") Locale("bn", "BD") else Locale.US
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = "এই ভাষার TTS voice ডিভাইসে ইনস্টল নেই"; return
        }
        tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "tanvir-voice")
        status.text = "লেখা পড়ে শোনানো হচ্ছে"
    }

    private fun copyText() {
        val value = editor.text.toString()
        if (value.isBlank()) { status.text = "কপি করার মতো লেখা নেই"; return }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Tanvir Voice Assistant", value))
        status.text = "কপি হয়েছে"
    }

    private fun shareText() {
        val value = editor.text.toString()
        if (value.isBlank()) { status.text = "শেয়ার করার মতো লেখা নেই"; return }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }
        startActivity(Intent.createChooser(send, "লেখা শেয়ার করো"))
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts.language = Locale("bn", "BD")
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
