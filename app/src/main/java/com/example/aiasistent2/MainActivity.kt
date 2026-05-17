package com.example.aiasistent2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootLayout: FrameLayout
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var messagesLayout: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var hudView: JarvisHudView? = null
    private var inputField: EditText? = null
    private var micBtn: TextView? = null
    private var isTyping = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastPartialSpeech = ""
    private var lastSpeechErrorAt = 0L
    private var speechErrorCount = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private var pendingListenAfterSpeak = false
    private var isActivityResumed = false

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val MIC_PERMISSION_CODE = 101
        private const val NOTIFICATION_PERMISSION_CODE = 102
        private const val ASSISTANT_PERMISSION_CODE = 103
        private const val FILE_PICK_CODE = 201
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private val GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        rootLayout = FrameLayout(this).apply { setBackgroundColor(JarvisHudView.C_BG) }
        setContentView(rootLayout)
        supportActionBar?.hide()

        installCrashHandler()

        val prevCrash = prefs.getString("last_crash", null)
        if (prevCrash != null) {
            prefs.edit().remove("last_crash").apply()
            showCrashReport(prevCrash)
            return
        }

        try {
            tts = TextToSpeech(this, this)
            requestNotificationPermission()
            requestAssistantPermissions()

            val savedKey = prefs.getString(KEY_API_KEY, null)
            if (savedKey.isNullOrBlank()) {
                showApiKeyScreen()
            } else {
                showChatScreen(savedKey)
            }
            checkForUpdates()
        } catch (e: Throwable) {
            Log.e("JARVIS", "onCreate crashed", e)
            showCrashReport(e.stackTraceToString())
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString().take(3000)
                prefs.edit().putString("last_crash", trace).commit()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showCrashReport(crashText: String) {
        rootLayout.removeAllViews()
        val sv = ScrollView(this)
        sv.addView(TextView(this).apply {
            text = "JARVIS CRASH REPORT\n\n$crashText\n\n--- Tap below to continue ---"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(48), dp(16), dp(16))
        })
        rootLayout.addView(sv)
        rootLayout.addView(TextView(this).apply {
            text = "RESET API KEY & RESTART"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(0, 212, 255))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setOnClickListener {
                prefs.edit().remove(KEY_API_KEY).remove("last_crash").apply()
                recreate()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        try {
            isActivityResumed = true
            hudView?.onResumeAnimation()
        } catch (e: Throwable) {
            Log.e("JARVIS", "onResume crashed", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            isActivityResumed = false
            hudView?.onPauseAnimation()
            if (isListening) stopListening()
        } catch (e: Throwable) {
            Log.e("JARVIS", "onPause crashed", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showApiKeyScreen() {
        rootLayout.removeAllViews()
        conversationHistory.clear()

        val hud = JarvisHudView(this).apply {
            statusText = "INITIALISING"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        hudView = hud
        rootLayout.addView(hud)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = panelBg()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                bottomMargin = dp(26)
            }
        }

        panel.addView(TextView(this).apply {
            text = "INITIALISATION REQUIRED"
            setTextColor(JarvisHudView.C_PRI)
            textSize = 15f
            typeface = monoBold()
            gravity = Gravity.CENTER
        })
        panel.addView(TextView(this).apply {
            text = "Enter your Gemini API key to boot J.A.R.V.I.S."
            setTextColor(JarvisHudView.C_MID)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = mono()
            setPadding(0, dp(8), 0, dp(14))
        })

        val keyInput = EditText(this).apply {
            hint = "AIzaSy..."
            setHintTextColor(JarvisHudView.C_DIM)
            setTextColor(JarvisHudView.C_TEXT)
            textSize = 14f
            typeface = mono()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = inputBg()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isSingleLine = true
        }
        panel.addView(keyInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val statusText = TextView(this).apply {
            setTextColor(JarvisHudView.C_RED)
            textSize = 12f
            typeface = mono()
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
        }
        panel.addView(statusText)

        val startBtn = commandButton("INITIALISE SYSTEMS")
        startBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty() || !key.startsWith("AIza")) {
                statusText.text = "Wrong format. API key must start with AIza."
                return@setOnClickListener
            }
            startBtn.text = "CHECKING..."
            statusText.text = ""
            lifecycleScope.launch {
                val error = testApiKey(key)
                if (error == null) {
                    prefs.edit().putString(KEY_API_KEY, key).apply()
                    showChatScreen(key)
                } else {
                    startBtn.text = "INITIALISE SYSTEMS"
                    statusText.text = error
                }
            }
        }
        panel.addView(startBtn)

        rootLayout.addView(panel)
    }

    @SuppressLint("SetTextI18n")
    private fun showChatScreen(apiKey: String) {
        try {
            showChatScreenInternal(apiKey)
        } catch (e: Throwable) {
            Log.e("JARVIS", "showChatScreen crashed", e)
            showCrashReport(e.stackTraceToString())
        }
    }

    private fun showChatScreenInternal(apiKey: String) {
        rootLayout.removeAllViews()

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(JarvisHudView.C_BG)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val hud = JarvisHudView(this).apply {
            statusText = "ONLINE"
            isClickable = true
            isFocusable = true
            setOnClickListener { handleMicClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.02f
            )
        }
        hudView = hud
        main.addView(hud)

        val controlDeck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.08f
            )
        }

        val topPanels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val sysPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.58f).apply {
                rightMargin = dp(8)
            }
        }
        sysPanel.addView(panelTitle("* SYS MONITOR"))
        sysPanel.addView(monitorRow("CPU", "58%", JarvisHudView.C_PRI))
        sysPanel.addView(monitorRow("MEM", "93%", Color.rgb(255, 51, 92)))
        sysPanel.addView(monitorRow("NET", "49KB/S", JarvisHudView.C_GREEN))
        sysPanel.addView(monitorRow("GPU", "N/A", JarvisHudView.C_ACC))
        sysPanel.addView(monitorRow("AI CORE", "ACTIVE", JarvisHudView.C_GREEN))
        sysPanel.addView(monitorRow("SEC", "CLEARED", JarvisHudView.C_PRI))
        topPanels.addView(sysPanel)

        val activityPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val activityHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        activityHeader.addView(panelTitle("> ACTIVITY LOG").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        activityHeader.addView(TextView(this).apply {
            text = "API"
            setTextColor(JarvisHudView.C_PRI)
            textSize = 11f
            typeface = monoBold()
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = chipBg()
            isClickable = true
            setOnClickListener { showApiResetDialog() }
        })
        activityPanel.addView(activityHeader)

        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        chatScrollView = sv
        messagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        sv.addView(messagesLayout)
        activityPanel.addView(sv)
        topPanels.addView(activityPanel)
        controlDeck.addView(topPanels)

        val fileUpload = TextView(this).apply {
            text = "^   FILE UPLOAD\nDrop file here or click to browse\nImages . Video . Audio . PDF . Docs . Code . Data"
            setTextColor(JarvisHudView.C_TEXT)
            textSize = 10f
            typeface = mono()
            gravity = Gravity.CENTER
            background = dashedPanelBg()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            }
            setOnClickListener { openFilePicker() }
        }
        controlDeck.addView(fileUpload)
        controlDeck.addView(panelTitle("> COMMAND INPUT"))

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val field = EditText(this).apply {
            hint = "Command..."
            setHintTextColor(JarvisHudView.C_DIM)
            setTextColor(JarvisHudView.C_TEXT)
            textSize = 14f
            typeface = mono()
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = inputBg()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputField = field
        inputBar.addView(field)

        val mic = smallButton("MIC")
        micBtn = mic
        mic.setOnClickListener { handleMicClick() }
        inputBar.addView(mic)

        val a11y = smallButton("A11Y")
        a11y.setOnClickListener { openAccessibilitySettings() }
        inputBar.addView(a11y)

        val send = smallButton(">")
        send.textSize = 18f
        send.setOnClickListener {
            val msg = field.text.toString().trim()
            if (msg.isEmpty() || isTyping) return@setOnClickListener
            field.setText("")
            sendMessage(msg, apiKey)
        }
        inputBar.addView(send)
        controlDeck.addView(inputBar)

        controlDeck.addView(TextView(this).apply {
            text = "  FULLSCREEN  [F11]"
            setTextColor(JarvisHudView.C_TEXT)
            textSize = 9f
            typeface = monoBold()
            gravity = Gravity.CENTER
            background = buttonBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })

        main.addView(controlDeck)
        rootLayout.addView(main)

        addMessage("SYS: J.A.R.V.I.S online. MARK XXX mobile systems ready.", false)
        lifecycleScope.launch {
            try {
                delay(500)
                speak("JARVIS online. Buyruq berishingiz mumkin.")
            } catch (e: Throwable) {
                Log.e("JARVIS", "Startup speak failed", e)
            }
        }
    }

    private fun showApiResetDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("API key")
            .setMessage("Current API key will be removed. Continue?")
            .setPositiveButton("Yes") { _, _ ->
                prefs.edit().remove(KEY_API_KEY).apply()
                showApiKeyScreen()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun handleMicClick() {
        if (isListening) {
            stopListening()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!isActivityResumed) return
        if (isSpeaking) {
            pendingListenAfterSpeak = true
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addMessage("SYS: Ovoz tanish xizmati mavjud emas.", false)
            return
        }

        isListening = true
        lastPartialSpeech = ""
        speechErrorCount = 0
        hudView?.statusText = "LISTENING"
        micBtn?.text = "REC"
        micBtn?.startAnimation(AlphaAnimation(0.35f, 1f).apply {
            duration = 450
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        })

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e("JARVIS", "SpeechRecognizer create failed", e)
            isListening = false
            hudView?.statusText = "ONLINE"
            micBtn?.clearAnimation()
            micBtn?.text = "MIC"
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.ifBlank { lastPartialSpeech }
                    ?: lastPartialSpeech
                stopListening()
                if (text.isNotBlank()) {
                    speechErrorCount = 0
                    val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty()
                    addMessage("You: $text", true)
                    conversationHistory.add("user" to text)
                    trimConversationHistory()
                    isTyping = true
                    hudView?.statusText = "RESPONDING"
                    lifecycleScope.launch {
                        val reply = callGemini(apiKey)
                        isTyping = false
                        hudView?.statusText = "ONLINE"
                        if (reply == null) {
                            addMessage("Jarvis: API yoki internet xatosi.", false)
                            speak("Internet yoki API xatosi.")
                        } else {
                            val agentHandled = handleAgentReply(reply)
                            if (!agentHandled) {
                                addMessage("Jarvis: $reply", false)
                                speak(reply)
                            }
                            conversationHistory.add("model" to reply)
                            trimConversationHistory()
                        }
                    }
                } else {
                    handleSpeechMiss()
                }
            }

            override fun onError(error: Int) {
                val text = lastPartialSpeech.trim()
                stopListening()
                if (text.isNotBlank()) {
                    speechErrorCount = 0
                    val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty()
                    sendMessage(text, apiKey)
                } else {
                    speechErrorCount++
                    if (speechErrorCount <= 2) {
                        handleSpeechMiss()
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() {
                speechErrorCount = 0
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                lastPartialSpeech = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1700L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("JARVIS", "startListening failed", e)
            stopListening()
        }
    }

    private fun trimConversationHistory() {
        if (conversationHistory.size > 40) {
            val excess = conversationHistory.size - 40
            repeat(excess) { conversationHistory.removeAt(0) }
        }
    }

    private fun handleSpeechMiss() {
        val now = System.currentTimeMillis()
        if (now - lastSpeechErrorAt > 5000) {
            addMessage("SYS: Ovoz aniq eshitilmadi. Yana bir marta ayting.", false)
            lastSpeechErrorAt = now
        }
    }

    private fun stopListening() {
        isListening = false
        hudView?.statusText = if (isTyping) "RESPONDING" else "ONLINE"
        micBtn?.clearAnimation()
        micBtn?.text = "MIC"
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("JARVIS", "stopListening failed", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == MIC_PERMISSION_CODE) {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Opening accessibility settings", Toast.LENGTH_SHORT).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, FILE_PICK_CODE)
        } catch (_: Exception) {
            Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_CODE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            addMessage("SYS: File loaded - ${uri.lastPathSegment ?: "selected file"}", false)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
        }
    }

    private fun requestAssistantPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), ASSISTANT_PERMISSION_CODE)
        }
    }

    private fun sendMessage(msg: String, apiKey: String) {
        addMessage("You: $msg", true)
        conversationHistory.add("user" to msg)
        trimConversationHistory()
        isTyping = true
        hudView?.statusText = "RESPONDING"
        val typingView = addMessage("Jarvis: processing...", false)

        lifecycleScope.launch {
            val reply = callGemini(apiKey)
            isTyping = false
            hudView?.statusText = "ONLINE"
            messagesLayout?.removeView(typingView)
            if (reply == null) {
                addMessage("Jarvis: Connection or API key error.", false)
                speak("Internet yoki API kalit xatosi.")
            } else {
                val agentHandled = handleAgentReply(reply)
                if (!agentHandled) {
                    addMessage("Jarvis: $reply", false)
                    speak(reply)
                }
                conversationHistory.add("model" to reply)
                trimConversationHistory()
            }
            scrollToBottom()
        }
    }

    private fun addMessage(text: String, isUser: Boolean): View {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(if (isUser) Color.WHITE else JarvisHudView.C_TEXT)
            textSize = 12f
            typeface = mono()
            setLineSpacing(0f, 1.18f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = if (isUser) userBubbleBg() else null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
        }
        messagesLayout?.addView(view)
        scrollToBottom()
        return view
    }

    private fun scrollToBottom() {
        chatScrollView?.post { chatScrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private suspend fun handleAgentReply(reply: String): Boolean {
        val jsonText = extractJson(reply) ?: return false
        val json = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return false
        }

        val spoken = json.optString("say").ifBlank { json.optString("message") }
        if (spoken.isNotBlank()) addMessage("Jarvis: $spoken", false)
        if (spoken.isNotBlank()) speak(spoken)

        val steps = json.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                executeTool(step.optString("tool"), step.optJSONObject("args") ?: JSONObject())
                delay(step.optLong("delay_ms", 650L))
            }
            return true
        }

        val tool = json.optString("tool")
        if (tool.isNotBlank()) {
            executeTool(tool, json.optJSONObject("args") ?: JSONObject())
            return true
        }
        return spoken.isNotBlank()
    }

    private fun extractJson(raw: String): String? {
        val cleaned = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) return cleaned
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private suspend fun executeTool(tool: String, args: JSONObject) {
        val service = JarvisAccessibilityService.instance
        val result = when (tool.lowercase(Locale.US)) {
            "open_app" -> openApp(args.optString("app_name"))
            "open_settings" -> {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                true
            }
            "open_accessibility_settings" -> {
                openAccessibilitySettings()
                true
            }
            "call_contact" -> callContact(args.optString("contact_name"))
            "dial_number" -> dialNumber(args.optString("phone"))
            "tap_text" -> service?.clickText(args.optString("text")) == true
            "type_text" -> service?.typeIntoFocused(args.optString("text")) == true
            "append_text" -> service?.appendIntoFocused(args.optString("text")) == true
            "press_enter", "send_enter" -> service?.pressImeAction() == true
            "focus_input" -> service?.clickFirstEditable() == true
            "back" -> service?.back() == true
            "home" -> service?.home() == true
            "recents" -> service?.recents() == true
            "scroll_down" -> service?.scrollForward() == true
            "scroll_up" -> service?.scrollBackward() == true
            "read_screen" -> {
                val text = service?.screenText(1600).orEmpty()
                addMessage("SCREEN: ${text.ifBlank { "No readable text." }}", false)
                true
            }
            "set_volume" -> {
                setVolume(args.optInt("level", 70))
                true
            }
            "wait" -> {
                delay(args.optLong("ms", 800L))
                true
            }
            else -> false
        }

        val label = tool.ifBlank { "unknown" }
        addMessage("SYS: $label ${if (result) "done" else "failed"}", false)
    }

    private fun openApp(appName: String): Boolean {
        val query = appName.trim().lowercase(Locale.getDefault())
        if (query.isBlank()) return false
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = packageManager.queryIntentActivities(launcherIntent, 0)
        val match = apps.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).contains(query)
        } ?: apps.firstOrNull {
            it.activityInfo.packageName.lowercase(Locale.getDefault()).contains(query)
        } ?: return false
        val intent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    private fun callContact(contactName: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestAssistantPermissions()
            return false
        }
        val phone = findContactPhone(contactName) ?: return false
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        startActivity(intent)
        return true
    }

    private fun dialNumber(phone: String): Boolean {
        val clean = phone.filter { it.isDigit() || it == '+' }
        if (clean.isBlank()) return false
        val action = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent.ACTION_CALL
        } else {
            Intent.ACTION_DIAL
        }
        startActivity(Intent(action, Uri.parse("tel:$clean")))
        return true
    }

    private fun findContactPhone(contactName: String): String? {
        val query = contactName.trim()
        if (query.isBlank()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                if (name.contains(query, ignoreCase = true)) {
                    return cursor.getString(numberIndex)
                }
            }
        }
        return null
    }

    private fun setVolume(level: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * level.coerceIn(0, 100) / 100f).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val uz = Locale("uz", "UZ")
            val result = tts?.setLanguage(uz)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(0.96f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (pendingListenAfterSpeak) {
                        pendingListenAfterSpeak = false
                        runOnUiThread { startListening() }
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    pendingListenAfterSpeak = false
                }
            })
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text
            .replace(Regex("\\{.*}", RegexOption.DOT_MATCHES_ALL), "")
            .replace("SYS:", "")
            .replace("Jarvis:", "")
            .trim()
            .take(260)
        if (clean.isBlank()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
    }

    private suspend fun callGemini(apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val contentsArray = JSONArray()
            for ((role, content) in conversationHistory.takeLast(18)) {
                contentsArray.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", content)))
                )
            }

            val systemInstruction = JSONObject().put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "text",
                        "You are J.A.R.V.I.S MARK XXX running on the user's Android phone. " +
                            "You are an action agent, not only a chatbot. If the user asks to control the phone, open apps, tap, type, call, change settings, read screen, or send a message, return ONLY valid JSON and no markdown. " +
                            "Use this schema: {\"say\":\"short same-language status\",\"steps\":[{\"tool\":\"tool_name\",\"args\":{}}]}. " +
                            "Available tools: open_app(app_name), open_settings, open_accessibility_settings, call_contact(contact_name), dial_number(phone), tap_text(text), focus_input, type_text(text), append_text(text), press_enter, back, home, recents, scroll_down, scroll_up, read_screen, set_volume(level), wait(ms). " +
                            "For Telegram/WhatsApp/message tasks, make a practical sequence: open_app, wait, tap_text/search if visible, type_text, tap_text contact, focus_input, type_text message, press_enter. " +
                            "For app passcodes, type the passcode if the user explicitly gives it. " +
                            "Never answer that you cannot control the phone when a tool can try. If a command is unsafe or impossible by Android security, briefly say what permission/settings step is needed. " +
                            "For normal questions, answer naturally in the same language without JSON."
                    )
                )
            )

            val body = JSONObject()
                .put("contents", contentsArray)
                .put("system_instruction", systemInstruction)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.7)
                        .put("maxOutputTokens", 2048)
                        .put("topP", 0.95)
                )
            for (model in GEMINI_MODELS) {
                val req = geminiRequest(model, apiKey, body)
                httpClient.newCall(req).execute().use { response ->
                    val resBody = response.body?.string() ?: return@use
                    if (!response.isSuccessful) {
                        if (response.code == 400 || response.code == 404) return@use
                        return@withContext null
                    }
                    return@withContext JSONObject(resBody)
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.trim()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun testApiKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Hi"))))
            )
            val errors = mutableListOf<String>()
            for (model in GEMINI_MODELS) {
                val req = geminiRequest(model, key, body)
                httpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) return@withContext null

                    val rawError = response.body?.string().orEmpty()
                    val message = parseGeminiError(rawError)
                    errors.add("$model: ${response.code}${if (message.isBlank()) "" else " - $message"}")

                    if (response.code == 403 || response.code == 429) {
                        return@withContext "API error: ${response.code}${if (message.isBlank()) "" else " - $message"}"
                    }
                }
            }
            "API model error: ${errors.joinToString(" | ")}"
        } catch (e: Exception) {
            "Connection error: ${e.localizedMessage}"
        }
    }

    private fun geminiRequest(model: String, apiKey: String, body: JSONObject): Request {
        return Request.Builder()
            .url("$GEMINI_API_BASE/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseGeminiError(rawError: String): String {
        return try {
            JSONObject(rawError).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            rawError.take(120)
        }
    }

    private fun checkForUpdates() {
        try {
            val updater = com.example.aiasistent2.updater.AppUpdateChecker(this)
            lifecycleScope.launch {
                try {
                    val info = updater.checkForUpdate()
                    if (info != null) updater.downloadAndInstall(info)
                } catch (e: Exception) {
                    Log.e("JARVIS", "Update check failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "Update init failed", e)
        }
    }

    private fun commandButton(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(JarvisHudView.C_PRI)
        textSize = 13f
        typeface = monoBold()
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = buttonBg()
        isClickable = true
        isFocusable = true
    }

    private fun smallButton(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(JarvisHudView.C_PRI)
        textSize = 10f
        typeface = monoBold()
        gravity = Gravity.CENTER
        background = buttonBg()
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(50), dp(46)).apply { leftMargin = dp(7) }
    }

    private fun panelTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(JarvisHudView.C_PRI)
        textSize = 9f
        typeface = monoBold()
        setPadding(0, 0, 0, dp(4))
    }

    private fun monitorRow(label: String, value: String, valueColor: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = buttonBg()
        setPadding(dp(6), dp(5), dp(6), dp(5))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(5) }

        addView(TextView(this@MainActivity).apply {
            text = label
            setTextColor(JarvisHudView.C_TEXT)
            textSize = 9f
            typeface = mono()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            setTextColor(valueColor)
            textSize = 9f
            typeface = monoBold()
            gravity = Gravity.END
        })
    }

    private fun panelBg() = GradientDrawable().apply {
        setColor(JarvisHudView.C_PANEL)
        setStroke(dp(1), JarvisHudView.C_MID)
        cornerRadius = dp(3).toFloat()
    }

    private fun dashedPanelBg() = GradientDrawable().apply {
        setColor(Color.rgb(0, 13, 18))
        setStroke(dp(1), JarvisHudView.C_DIM, dp(7).toFloat(), dp(4).toFloat())
        cornerRadius = dp(5).toFloat()
    }

    private fun inputBg() = GradientDrawable().apply {
        setColor(Color.rgb(0, 13, 18))
        setStroke(dp(1), JarvisHudView.C_DIM)
        cornerRadius = dp(3).toFloat()
    }

    private fun buttonBg() = GradientDrawable().apply {
        setColor(Color.BLACK)
        setStroke(dp(1), JarvisHudView.C_MID)
        cornerRadius = dp(3).toFloat()
    }

    private fun chipBg() = GradientDrawable().apply {
        setColor(Color.rgb(0, 18, 24))
        setStroke(dp(1), JarvisHudView.C_DIM)
        cornerRadius = dp(3).toFloat()
    }

    private fun userBubbleBg() = GradientDrawable().apply {
        setColor(Color.rgb(0, 32, 42))
        setStroke(dp(1), JarvisHudView.C_DIM)
        cornerRadius = dp(3).toFloat()
    }

    private fun mono(): Typeface = Typeface.create("monospace", Typeface.NORMAL)
    private fun monoBold(): Typeface = Typeface.create("monospace", Typeface.BOLD)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (messagesLayout != null) moveTaskToBack(true) else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}

class JarvisHudView(context: Context) : View(context) {
    var statusText: String = "ONLINE"
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val thinTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var tick = 0f
    private var animating = true
    private val frameHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            try {
                if (animating) {
                    invalidate()
                    frameHandler.postDelayed(this, 33L)
                }
            } catch (_: Throwable) {}
        }
    }

    fun onResumeAnimation() {
        animating = true
        frameHandler.removeCallbacks(frameRunnable)
        frameHandler.post(frameRunnable)
    }

    fun onPauseAnimation() {
        animating = false
        frameHandler.removeCallbacks(frameRunnable)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animating) {
            frameHandler.removeCallbacks(frameRunnable)
            frameHandler.post(frameRunnable)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        frameHandler.removeCallbacks(frameRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            tick += 1f
            if (tick > 100000f) tick = 0f
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val headerH = dp(62).toFloat()
            val footerH = dp(28).toFloat()
            val faceSize = min(w * 0.72f, h * 0.58f)
            val cy = headerH + faceSize * 0.54f
            val speaking = statusText == "RESPONDING" || statusText == "LISTENING"

            canvas.drawColor(C_BG)
            drawGrid(canvas, w, h)
            drawHeader(canvas, w, headerH)
            drawCore(canvas, cx, cy, faceSize, speaking)
            drawStatus(canvas, cx, cy + faceSize * 0.57f, speaking)
            drawFooter(canvas, w, h, footerH)
        } catch (_: Throwable) {
            canvas.drawColor(C_BG)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = C_DIMMER
        var x = 0f
        while (x < w) {
            var y = 0f
            while (y < h) {
                canvas.drawRect(x, y, x + 1f, y + 1f, paint)
                y += dp(34).toFloat()
            }
            x += dp(34).toFloat()
        }
    }

    private fun drawHeader(canvas: Canvas, w: Float, headerH: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 8, 13)
        canvas.drawRect(0f, 0f, w, headerH, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = C_MID
        canvas.drawLine(0f, headerH, w, headerH, paint)

        textPaint.color = C_PRI
        textPaint.textSize = sp(18)
        canvas.drawText("J.A.R.V.I.S", w / 2f, dp(26).toFloat(), textPaint)
        thinTextPaint.color = C_MID
        thinTextPaint.textSize = sp(9)
        canvas.drawText("Just A Rather Very Intelligent System", w / 2f, dp(47).toFloat(), thinTextPaint)

        thinTextPaint.textAlign = Paint.Align.LEFT
        thinTextPaint.color = C_DIM
        canvas.drawText("MARK XXX", dp(14).toFloat(), dp(35).toFloat(), thinTextPaint)
        thinTextPaint.textAlign = Paint.Align.RIGHT
        thinTextPaint.color = C_PRI
        thinTextPaint.textSize = sp(13)
        canvas.drawText(timeFormat.format(Date()), w - dp(14).toFloat(), dp(36).toFloat(), thinTextPaint)
        thinTextPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, size: Float, speaking: Boolean) {
        val pulse = if (speaking) 1.0f else 0.45f
        val halo = size * (0.24f + 0.025f * sin(tick / 18f))

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = withAlpha(C_PRI, 70)
        for (i in 0..4) {
            val r = halo + i * size * 0.065f + (tick % 30f) * pulse
            paint.alpha = (80 - i * 12).coerceAtLeast(15)
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.alpha = 255

        val rings = floatArrayOf(0.49f, 0.40f, 0.31f)
        rings.forEachIndexed { idx, frac ->
            val r = size * frac
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (idx == 0) 3 else 2).toFloat()
            paint.color = withAlpha(C_PRI, 190 - idx * 35)
            val spin = tick * (if (idx == 1) -0.8f else 1.15f + idx)
            var start = spin
            repeat(3 + idx) {
                canvas.drawArc(rect, start, 54f + idx * 12f, false, paint)
                start += 112f
            }
        }

        val scanR = size * 0.51f
        val scanRect = RectF(cx - scanR, cy - scanR, cx + scanR, cy + scanR)
        paint.strokeWidth = dp(3).toFloat()
        paint.color = withAlpha(C_PRI, 230)
        canvas.drawArc(scanRect, tick * 2.2f, if (speaking) 78f else 48f, false, paint)
        paint.strokeWidth = dp(2).toFloat()
        paint.color = withAlpha(C_ACC, 135)
        canvas.drawArc(scanRect, 180f - tick * 1.4f, if (speaking) 70f else 42f, false, paint)

        paint.strokeWidth = 1f
        paint.color = withAlpha(C_PRI, 155)
        val outer = size * 0.50f
        val innerMajor = size * 0.46f
        val innerMinor = size * 0.475f
        for (deg in 0 until 360 step 10) {
            val rad = Math.toRadians(deg.toDouble())
            val inner = if (deg % 30 == 0) innerMajor else innerMinor
            canvas.drawLine(
                cx + outer * cos(rad).toFloat(),
                cy - outer * sin(rad).toFloat(),
                cx + inner * cos(rad).toFloat(),
                cy - inner * sin(rad).toFloat(),
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(Color.rgb(0, 65, 120), 190)
        canvas.drawCircle(cx, cy, size * (0.22f + 0.015f * sin(tick / 9f)), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = C_PRI
        canvas.drawCircle(cx, cy, size * 0.24f, paint)

        textPaint.color = withAlpha(C_PRI, 230)
        textPaint.textSize = sp(13)
        canvas.drawText("JARVIS", cx, cy + sp(4), textPaint)
    }

    private fun drawStatus(canvas: Canvas, cx: Float, y: Float, speaking: Boolean) {
        textPaint.color = if (speaking) C_ACC else C_PRI
        textPaint.textSize = sp(11)
        val dot = if ((tick.toInt() / 30) % 2 == 0) "*" else "."
        canvas.drawText("$dot $statusText", cx, y, textPaint)

        val bars = 28
        val barW = dp(5).toFloat()
        val total = bars * barW
        val start = cx - total / 2f
        paint.style = Paint.Style.FILL
        for (i in 0 until bars) {
            val phase = sin(tick * 0.09f + i * 0.6f)
            val bh = if (speaking) dp(5) + (dp(18) * (phase + 1f) / 2f) else dp(4) + dp(2) * phase
            paint.color = if (speaking && bh > dp(13)) C_PRI else C_DIM
            val x = start + i * barW
            canvas.drawRect(x, y + dp(14), x + barW - 1f, y + dp(14) + bh, paint)
        }
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float, footerH: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 8, 13)
        canvas.drawRect(0f, h - footerH, w, h, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = C_DIM
        canvas.drawLine(0f, h - footerH, w, h - footerH, paint)
        thinTextPaint.color = C_DIM
        thinTextPaint.textSize = sp(8)
        canvas.drawText("FatihMakes Industries  .  CLASSIFIED  .  MARK XXX", w / 2f, h - dp(11).toFloat(), thinTextPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity

    companion object {
        val C_BG: Int = Color.BLACK
        val C_PRI: Int = Color.rgb(0, 212, 255)
        val C_MID: Int = Color.rgb(0, 122, 153)
        val C_DIM: Int = Color.rgb(0, 51, 68)
        val C_DIMMER: Int = Color.rgb(0, 21, 32)
        val C_ACC: Int = Color.rgb(255, 102, 0)
        val C_ACC2: Int = Color.rgb(255, 204, 0)
        val C_TEXT: Int = Color.rgb(143, 252, 255)
        val C_PANEL: Int = Color.rgb(1, 12, 16)
        val C_GREEN: Int = Color.rgb(0, 255, 136)
        val C_RED: Int = Color.rgb(255, 51, 51)
    }
}
