package com.example.aiasistent2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
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

class MainActivity : AppCompatActivity() {

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

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val MIC_PERMISSION_CODE = 101
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

        val savedKey = prefs.getString(KEY_API_KEY, null)
        if (savedKey.isNullOrBlank()) {
            showApiKeyScreen()
        } else {
            showChatScreen(savedKey)
        }
        checkForUpdates()
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.25f
            )
        }
        hudView = hud
        main.addView(hud)

        val logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.9f
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(8)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "MARK XXX"
            setTextColor(JarvisHudView.C_ACC2)
            textSize = 10f
            typeface = monoBold()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "API"
            setTextColor(JarvisHudView.C_PRI)
            textSize = 12f
            typeface = monoBold()
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = chipBg()
            isClickable = true
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("API key")
                    .setMessage("Current API key will be removed. Continue?")
                    .setPositiveButton("Yes") { _, _ ->
                        prefs.edit().remove(KEY_API_KEY).apply()
                        showApiKeyScreen()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })
        logPanel.addView(header)

        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        chatScrollView = sv
        messagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        sv.addView(messagesLayout)
        logPanel.addView(sv)
        main.addView(logPanel)

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.rgb(0, 8, 13))
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
            setPadding(dp(14), dp(11), dp(14), dp(11))
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
        main.addView(inputBar)
        rootLayout.addView(main)

        addMessage("SYS: J.A.R.V.I.S online. MARK XXX mobile systems ready.", false)
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
        isListening = true
        hudView?.statusText = "LISTENING"
        micBtn?.text = "REC"
        micBtn?.startAnimation(AlphaAnimation(0.35f, 1f).apply {
            duration = 450
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        })

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                stopListening()
                if (text.isNotBlank()) {
                    val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty()
                    sendMessage(text, apiKey)
                }
            }

            override fun onError(error: Int) {
                stopListening()
                addMessage("SYS: Voice was not recognised. Try again.", false)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            stopListening()
        }
    }

    private fun stopListening() {
        isListening = false
        hudView?.statusText = if (isTyping) "RESPONDING" else "ONLINE"
        micBtn?.clearAnimation()
        micBtn?.text = "MIC"
        speechRecognizer?.stopListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Opening accessibility settings", Toast.LENGTH_SHORT).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun sendMessage(msg: String, apiKey: String) {
        addMessage("You: $msg", true)
        conversationHistory.add("user" to msg)
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
            } else {
                addMessage("Jarvis: $reply", false)
                conversationHistory.add("model" to reply)
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
                        "You are J.A.R.V.I.S MARK XXX, a concise, direct mobile AI assistant. " +
                            "Answer in the same language as the user. Do not use markdown. " +
                            "You cannot control Windows from Android, but you can help with phone-friendly guidance."
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
        val updater = com.example.aiasistent2.updater.AppUpdateChecker(this)
        lifecycleScope.launch {
            val info = updater.checkForUpdate()
            if (info != null) updater.downloadAndInstall(info)
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
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(7) }
    }

    private fun panelBg() = GradientDrawable().apply {
        setColor(JarvisHudView.C_PANEL)
        setStroke(dp(1), JarvisHudView.C_MID)
        cornerRadius = dp(4).toFloat()
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
        speechRecognizer?.destroy()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        tick += 1f
        val w = width.toFloat()
        val h = height.toFloat()
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

        postInvalidateOnAnimation()
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
        val C_RED: Int = Color.rgb(255, 51, 51)
    }
}
