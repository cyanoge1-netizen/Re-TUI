package com.cyanoge1.sampledex

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ohi.andre.consolelauncher.dex.RetuiDexHost
import ohi.andre.consolelauncher.dex.RetuiDexModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("unused")
class CortexDockModule : RetuiDexModule {

    override val id: String = "cortex"
    override val title: String = "CORTEX // AI DOCK"

    companion object {
        const val CORTEX_DIR = "/sdcard/Re-T-UI/dex-modules/cortex"
        const val PATH_REQUEST = "$CORTEX_DIR/request.json"
        const val PATH_RESPONSE = "$CORTEX_DIR/response.json"
        const val PATH_HEARTBEAT = "$CORTEX_DIR/daemon.heartbeat"
        const val PATH_PROPOSALS = "$CORTEX_DIR/proposals.json"
        const val PATH_ALERTS = "$CORTEX_DIR/alerts.json"
        const val PATH_EVENTS = "$CORTEX_DIR/events.json"

        const val HEARTBEAT_TIMEOUT_MS = 15000L
        const val POLL_INTERVAL_MS = 300L
        const val WATCHDOG_INTERVAL_MS = 3000L
        const val TYPEWRITER_SPEED_MS = 25L

        fun dp(ctx: Context, v: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

        fun atomicWriteText(file: File, content: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
            }
            try {
                file.setReadable(true, false)
                file.setWritable(true, false)
            } catch (_: Throwable) {}
        }
    }

    object Theme {
        const val BG = 0xFF0A0A0F.toInt()
        const val CARD = 0xFF14131E.toInt()
        const val CARD_DIM = 0xFF0F0E17.toInt()
        const val BTN = 0xFF1F1E2D.toInt()
        const val BTN_HOVER = 0xFF2A283C.toInt()
        const val DIM = 0xFF8E8CA0.toInt()
        const val TEXT = 0xFFE0DEF4.toInt()
        const val OK = 0xFF50FA7B.toInt()
        const val INFO = 0xFF00E5FF.toInt()
        const val WARN = 0xFFF1FA8C.toInt()
        const val ERR = 0xFFFF5555.toInt()

        fun border(bgColor: Int, borderColor: Int, borderWidthPx: Int = 2, cornerRadiusDp: Float = 0f): GradientDrawable {
            return GradientDrawable().apply {
                setColor(bgColor)
                setStroke(borderWidthPx, borderColor)
                if (cornerRadiusDp > 0) cornerRadius = cornerRadiusDp
            }
        }
    }

    // --- State Variables ---
    private var hostRef: RetuiDexHost? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // TTS Subsystem
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isVoiceEnabled = true
    private var voiceGender = "MALE"
    private var pendingSpeakText: String? = null

    // IPC & Polling State
    private var currentRequestId = ""
    private var lastResponseMtime = 0L
    private var isPollingResponse = false
    private var isStreaming = false
    private var fullResponseText = ""
    private var typewriterCharIndex = 0

    // UI Widgets
    private var rootLayout: LinearLayout? = null
    private var dotStatus: TextView? = null
    private var lblStatus: TextView? = null
    private var lblEngine: TextView? = null
    private var alertBanner: LinearLayout? = null
    private var lblAlertText: TextView? = null
    private var proposalsContainer: LinearLayout? = null
    private var teletypeContent: TextView? = null
    private var btnStop: Button? = null
    private var inputPrompt: EditText? = null
    private var btnVoiceToggle: Button? = null

    // Polling Runnables
    private val responsePoller = object : Runnable {
        override fun run() {
            if (isDestroyed || !isPollingResponse) return
            pollResponseFile()
            if (isPollingResponse) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val watchdogPoller = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            checkDaemonHeartbeat()
            checkAlerts()
            checkPendingProposals()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val typewriterRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (typewriterCharIndex <= fullResponseText.length) {
                val sub = fullResponseText.substring(0, typewriterCharIndex)
                teletypeContent?.text = "> $sub █"
                typewriterCharIndex++
                mainHandler.postDelayed(this, TYPEWRITER_SPEED_MS)
            } else {
                teletypeContent?.text = "> $fullResponseText ▌"
                btnStop?.visibility = View.GONE
            }
        }
    }

    override fun createView(host: RetuiDexHost, container: ViewGroup): View {
        hostRef = host
        isDestroyed = false
        val ctx = host.context

        initTTS(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
        }
        rootLayout = root

        // --- 1. Top Header Bar ---
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, 6))
        }

        dotStatus = TextView(ctx).apply {
            text = "●"
            setTextColor(Theme.OK)
            textSize = 10f
            setPadding(0, 0, dp(ctx, 6), 0)
        }
        header.addView(dotStatus)

        val titleText = TextView(ctx).apply {
            text = "CORTEX // AI DOCK"
            setTextColor(Theme.TEXT)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(titleText)

        lblStatus = TextView(ctx).apply {
            text = "ONLINE"
            setTextColor(Theme.DIM)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(ctx, 6), 0, 0, 0)
        }
        header.addView(lblStatus)

        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        header.addView(spacer)

        btnVoiceToggle = Button(ctx).apply {
            text = if (isVoiceEnabled) "[ 🔊 MALE ]" else "[ 🔇 MUTE ]"
            setTextColor(Theme.INFO)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            background = Theme.border(Theme.BTN, Theme.INFO, 1)
            setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
            setOnClickListener {
                if (!isVoiceEnabled) {
                    isVoiceEnabled = true
                    voiceGender = "MALE"
                    text = "[ 🔊 MALE ]"
                } else if (voiceGender == "MALE") {
                    voiceGender = "FEMALE"
                    text = "[ 🔊 FEMALE ]"
                } else {
                    isVoiceEnabled = false
                    text = "[ 🔇 MUTE ]"
                    stopSpeech()
                }
                applyVoiceSettings()
            }
        }
        header.addView(btnVoiceToggle)

        val btnClose = Button(ctx).apply {
            text = "[ ✕ ]"
            setTextColor(Theme.DIM)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            background = null
            setPadding(dp(ctx, 8), dp(ctx, 2), 0, dp(ctx, 2))
            setOnClickListener { host.closeModule() }
        }
        header.addView(btnClose)
        root.addView(header)

        // --- 2. Proactive Alert Banner ---
        alertBanner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.border(Theme.CARD_DIM, Theme.WARN, 2)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            visibility = View.GONE
        }
        lblAlertText = TextView(ctx).apply {
            text = ""
            setTextColor(Theme.WARN)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        alertBanner?.addView(lblAlertText)
        val btnDismissAlert = Button(ctx).apply {
            text = "[ DISMISS ]"
            setTextColor(Theme.DIM)
            textSize = 7.5f
            typeface = Typeface.MONOSPACE
            background = null
            setOnClickListener { alertBanner?.visibility = View.GONE }
        }
        alertBanner?.addView(btnDismissAlert)
        root.addView(alertBanner)

        // --- 3. Proposals Area ---
        proposalsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        root.addView(proposalsContainer)

        // --- 4. Central Teletype Terminal Area ---
        val terminalCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.border(Theme.CARD, Theme.BTN_HOVER, 2)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }

        val subheadRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, 4))
        }
        lblEngine = TextView(ctx).apply {
            text = "DISPATCH ENGINE // READY"
            setTextColor(Theme.INFO)
            textSize = 8f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        subheadRow.addView(lblEngine)

        btnStop = Button(ctx).apply {
            text = "[ STOP ]"
            setTextColor(Theme.ERR)
            textSize = 8f
            typeface = Typeface.MONOSPACE
            background = Theme.border(Theme.BTN, Theme.ERR, 1)
            setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
            visibility = View.GONE
            setOnClickListener { stopTypewriterAndSpeech() }
        }
        subheadRow.addView(btnStop)
        terminalCard.addView(subheadRow)

        teletypeContent = TextView(ctx).apply {
            text = "> OPERATOR ARMED // ALL MODULES SECURE ▌"
            setTextColor(Theme.TEXT)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
            setOnClickListener {
                // If clicked while typing, instantly reveal full text
                if (typewriterCharIndex < fullResponseText.length) {
                    mainHandler.removeCallbacks(typewriterRunnable)
                    typewriterCharIndex = fullResponseText.length
                    text = "> $fullResponseText ▌"
                    btnStop?.visibility = View.GONE
                }
            }
        }
        terminalCard.addView(teletypeContent)
        root.addView(terminalCard)

        // --- 5. Quick Shortcut Chips ---
        val chipScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val chips = listOf(
            "TODAY" to "What is on today?",
            "TOMORROW" to "What is on tomorrow?",
            "NOTICES" to "Summarize the latest notices",
            "STATUS" to "Status report on battery, storage and schedule",
            "BRIEF" to "Executive brief for today"
        )
        for ((label, query) in chips) {
            val chip = Button(ctx).apply {
                text = label
                setTextColor(Theme.INFO)
                textSize = 8f
                typeface = Typeface.MONOSPACE
                background = Theme.border(Theme.BTN, Theme.DIM, 1)
                setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(ctx, 6), 0) }
                setOnClickListener { sendRequest(ctx, query, if (label == "BRIEF") "brief" else "ask") }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // --- 6. Input Bar ---
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }

        inputPrompt = EditText(ctx).apply {
            hint = "? ask cortex..."
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            background = Theme.border(Theme.CARD, Theme.BTN_HOVER, 2)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(ctx, 6), 0)
            }
        }
        inputRow.addView(inputPrompt)

        val btnSend = Button(ctx).apply {
            text = "SEND ❯"
            setTextColor(Theme.OK)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            background = Theme.border(Theme.BTN, Theme.OK, 2)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            setOnClickListener {
                val q = inputPrompt?.text?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) {
                    inputPrompt?.setText("")
                    sendRequest(ctx, q, "ask")
                }
            }
        }
        inputRow.addView(btnSend)
        root.addView(inputRow)

        // Resume active responses and start polling
        resumePreviousState(ctx)
        mainHandler.post(watchdogPoller)

        return root
    }

    // --- TTS Subsystem ---
    private fun initTTS(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    applyVoiceSettings()
                    val p = pendingSpeakText
                    if (p != null) {
                        pendingSpeakText = null
                        speak(p)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun applyVoiceSettings() {
        val t = tts ?: return
        try {
            t.language = Locale.US
            val voices = t.voices
            if (voices != null) {
                val preferMale = voiceGender.equals("MALE", ignoreCase = true)
                val selectedVoice = voices.firstOrNull { v ->
                    val n = v.name.lowercase(Locale.ROOT)
                    val isEn = v.locale.language.startsWith("en")
                    if (preferMale) {
                        isEn && (n.contains("male") || n.contains("#m") || n.contains("-m-") || n.contains("guy"))
                    } else {
                        isEn && (n.contains("female") || n.contains("#f") || n.contains("-f-"))
                    }
                }
                if (selectedVoice != null) t.voice = selectedVoice
            }
        } catch (_: Throwable) {}

        try {
            val isMale = voiceGender.equals("MALE", ignoreCase = true)
            t.setPitch(if (isMale) 0.82f else 1.0f)
            t.setSpeechRate(1.05f)
        } catch (_: Throwable) {}
    }

    private fun speak(text: String) {
        if (!isVoiceEnabled || text.isEmpty()) return
        if (!isTtsReady || tts == null) {
            pendingSpeakText = text
            return
        }
        try {
            applyVoiceSettings()
            tts?.stop()
            val uId = "cortex_utt_" + System.currentTimeMillis()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uId)
        } catch (_: Throwable) {}
    }

    private fun stopSpeech() {
        try {
            tts?.stop()
        } catch (_: Throwable) {}
    }

    private fun stopTypewriterAndSpeech() {
        mainHandler.removeCallbacks(typewriterRunnable)
        stopSpeech()
        btnStop?.visibility = View.GONE
        val cur = teletypeContent?.text?.toString() ?: ""
        teletypeContent?.text = cur.replace("█", "▌")
    }

    // --- IPC Request Sending ---
    private fun sendRequest(ctx: Context, query: String, type: String = "ask") {
        currentRequestId = "req_" + System.currentTimeMillis()
        val reqObj = JSONObject().apply {
            put("id", currentRequestId)
            put("ts", System.currentTimeMillis())
            put("type", type)
            put("text", query)
            put("approve", JSONObject.NULL)
        }

        teletypeContent?.text = "> Dispatching to Cortex Matrix... █"
        lblEngine?.text = "THINKING..."
        btnStop?.visibility = View.VISIBLE
        fullResponseText = ""
        typewriterCharIndex = 0

        try {
            atomicWriteText(File(PATH_REQUEST), reqObj.toString(2))
            isPollingResponse = true
            mainHandler.removeCallbacks(responsePoller)
            mainHandler.post(responsePoller)
        } catch (e: Throwable) {
            teletypeContent?.text = "> Error dispatching request: ${e.message}"
            lblEngine?.text = "ERROR"
            btnStop?.visibility = View.GONE
        }
    }

    fun sendApproval(proposalId: String, isApproved: Boolean) {
        currentRequestId = "req_" + System.currentTimeMillis()
        val reqObj = JSONObject().apply {
            put("id", currentRequestId)
            put("ts", System.currentTimeMillis())
            put("type", if (isApproved) "approve" else "reject")
            put("text", "")
            put("approve", JSONObject().apply { put("proposal_id", proposalId) })
        }

        teletypeContent?.text = "> Executing Approval Gate [$proposalId]... █"
        btnStop?.visibility = View.VISIBLE

        try {
            atomicWriteText(File(PATH_REQUEST), reqObj.toString(2))
            isPollingResponse = true
            mainHandler.removeCallbacks(responsePoller)
            mainHandler.post(responsePoller)
        } catch (e: Throwable) {
            teletypeContent?.text = "> Error: ${e.message}"
        }
    }

    // --- Response Polling ---
    private fun pollResponseFile() {
        val f = File(PATH_RESPONSE)
        if (!f.isFile) return

        val mtime = f.lastModified()
        if (mtime == lastResponseMtime) return
        lastResponseMtime = mtime

        try {
            val json = JSONObject(f.readText())
            val respId = json.optString("id", "")
            if (respId != currentRequestId && currentRequestId.isNotEmpty()) return

            val status = json.optString("status", "done")
            val text = json.optString("text", "")
            val engine = json.optString("engine", "groq")
            lblEngine?.text = "ENGINE // ${engine.uppercase(Locale.ROOT)}"

            if (status == "streaming" || status == "thinking") {
                if (text != fullResponseText && text.isNotEmpty()) {
                    fullResponseText = text
                    if (typewriterCharIndex == 0) {
                        mainHandler.post(typewriterRunnable)
                    }
                }
            } else if (status == "done") {
                isPollingResponse = false
                fullResponseText = text
                if (typewriterCharIndex == 0) {
                    mainHandler.post(typewriterRunnable)
                } else {
                    mainHandler.removeCallbacks(typewriterRunnable)
                    teletypeContent?.text = "> $fullResponseText ▌"
                    btnStop?.visibility = View.GONE
                }
                speak(fullResponseText)
                checkPendingProposals()
            } else if (status == "cancelled" || status == "error") {
                isPollingResponse = false
                btnStop?.visibility = View.GONE
                teletypeContent?.text = "> ${if (status == "error") "Error: " else ""}$text ▌"
            }
        } catch (_: Throwable) {}
    }

    private fun resumePreviousState(ctx: Context) {
        val f = File(PATH_RESPONSE)
        if (!f.isFile) return
        try {
            val json = JSONObject(f.readText())
            val status = json.optString("status", "")
            val text = json.optString("text", "")
            val engine = json.optString("engine", "cortex")
            lblEngine?.text = "ENGINE // ${engine.uppercase(Locale.ROOT)}"
            if (text.isNotEmpty()) {
                fullResponseText = text
                teletypeContent?.text = "> $text ▌"
            }
            if (status == "streaming" || status == "thinking") {
                currentRequestId = json.optString("id", "")
                isPollingResponse = true
                mainHandler.post(responsePoller)
            }
        } catch (_: Throwable) {}
    }

    // --- Watchdogs & Proactive Displays ---
    private fun checkDaemonHeartbeat() {
        val f = File(PATH_HEARTBEAT)
        val now = System.currentTimeMillis()
        var isAlive = false
        if (f.isFile) {
            try {
                val ts = f.readText().trim().toLong()
                if (now - ts < HEARTBEAT_TIMEOUT_MS) isAlive = true
            } catch (_: Throwable) {}
        }

        if (isAlive) {
            dotStatus?.setTextColor(Theme.OK)
            lblStatus?.text = "ONLINE"
            lblStatus?.setTextColor(Theme.DIM)
        } else {
            dotStatus?.setTextColor(Theme.ERR)
            lblStatus?.text = "OFFLINE"
            lblStatus?.setTextColor(Theme.ERR)
        }
    }

    private fun checkAlerts() {
        val f = File(PATH_ALERTS)
        if (!f.isFile) return
        try {
            val arr = JSONArray(f.readText())
            var unreadAlert: JSONObject? = null
            for (i in arr.length() - 1 downTo 0) {
                val a = arr.getJSONObject(i)
                if (!a.optBoolean("seen", false)) {
                    unreadAlert = a
                    break
                }
            }
            if (unreadAlert != null) {
                val txt = unreadAlert.optString("text", "")
                val lvl = unreadAlert.optString("level", "info")
                alertBanner?.visibility = View.VISIBLE
                lblAlertText?.text = "🚨 $txt"
                lblAlertText?.setTextColor(if (lvl == "warn") Theme.WARN else Theme.INFO)
            } else {
                alertBanner?.visibility = View.GONE
            }
        } catch (_: Throwable) {}
    }

    private fun checkPendingProposals() {
        val container = proposalsContainer ?: return
        val ctx = container.context
        val f = File(PATH_PROPOSALS)
        if (!f.isFile) {
            container.removeAllViews()
            return
        }

        try {
            val arr = JSONArray(f.readText())
            val pendingList = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if (p.optString("status") == "pending") {
                    pendingList.add(p)
                }
            }

            container.removeAllViews()
            if (pendingList.isEmpty()) return

            val header = TextView(ctx).apply {
                text = "ACTION PROPOSALS AWAITING APPROVAL (${pendingList.size})"
                setTextColor(Theme.WARN)
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, 0, 0, dp(ctx, 4))
            }
            container.addView(header)

            for (p in pendingList) {
                val pId = p.optString("id")
                val kind = p.optString("kind")
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.border(Theme.CARD_DIM, Theme.WARN, 2)
                    setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, dp(ctx, 6)) }
                }

                val titleLine = TextView(ctx).apply {
                    val desc = if (kind == "add_event") {
                        val ev = p.optJSONObject("event")
                        val stMs = ev?.optLong("start_ms", 0L) ?: 0L
                        val dateStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(stMs))
                        "Add ${ev?.optString("type")}: '${ev?.optString("title")}' at $dateStr (Room: ${ev?.optString("room","TBD")})"
                    } else if (kind == "queue_download") {
                        val dl = p.optJSONObject("download")
                        "Queue yt-dlp download: ${dl?.optString("url")}"
                    } else {
                        "Action: $kind"
                    }
                    text = desc
                    setTextColor(Theme.TEXT)
                    textSize = 8.5f
                    typeface = Typeface.MONOSPACE
                }
                card.addView(titleLine)

                val btnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 4), 0, 0)
                }

                val btnApprove = Button(ctx).apply {
                    text = "APPROVE ✓"
                    setTextColor(Theme.OK)
                    textSize = 8f
                    typeface = Typeface.MONOSPACE
                    background = Theme.border(Theme.BTN, Theme.OK, 1)
                    setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
                    setOnClickListener {
                        sendApproval(pId, true)
                        card.visibility = View.GONE
                    }
                }
                btnRow.addView(btnApprove)

                val btnReject = Button(ctx).apply {
                    text = "REJECT ✕"
                    setTextColor(Theme.ERR)
                    textSize = 8f
                    typeface = Typeface.MONOSPACE
                    background = Theme.border(Theme.BTN, Theme.ERR, 1)
                    setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(ctx, 8), 0, 0, 0) }
                    setOnClickListener {
                        sendApproval(pId, false)
                        card.visibility = View.GONE
                    }
                }
                btnRow.addView(btnReject)
                card.addView(btnRow)
                container.addView(card)
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        isDestroyed = true
        isPollingResponse = false
        mainHandler.removeCallbacksAndMessages(null)
        stopSpeech()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
        hostRef = null
    }
}
