package com.cyanoge1.sampledex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ohi.andre.consolelauncher.dex.RetuiDexHost
import ohi.andre.consolelauncher.dex.RetuiDexModule
import org.json.JSONObject
import java.io.File

/**
 * Native Cyberdeck yt-dlp Dock Module for Re:T-UI.
 * Provides direct URL input, 1-tap clipboard paste, quality selectors,
 * real-time animated ProgressBar, and live terminal logs inside the launcher dock.
 */
class YtdlpNativeModule : RetuiDexModule {
    override val id: String = "ytdlp"
    override val title: String = "YT-DLP // NATIVE"

    private var hostRef: RetuiDexHost? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var selectedFormat = "1080p"

    private var pbar: ProgressBar? = null
    private var lblStatus: TextView? = null
    private var lblLogs: TextView? = null
    private var inputUrl: EditText? = null
    private var scrollLogs: ScrollView? = null
    private val formatButtons = HashMap<String, Button>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            updateFromStateFile()
            handler.postDelayed(this, 600)
        }
    }

    override fun createView(host: RetuiDexHost, container: ViewGroup): View {
        hostRef = host
        val ctx = host.context
        val accent = host.accentTextColor
        val textCol = host.bodyTextColor

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 14)
        }

        // Header Row
        val hdr = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val lblTitle = TextView(ctx).apply {
            text = "⚡ YT-DLP // NATIVE DOCK"
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hdr.addView(lblTitle)

        val btnClose = Button(ctx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF5555"))
            setBackgroundColor(Color.parseColor("#22FF5555"))
            textSize = 12f
            setPadding(12, 4, 12, 4)
            setOnClickListener { host.closeModule() }
        }
        hdr.addView(btnClose)
        root.addView(hdr)

        // URL Input Row
        val urlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 8)
        }

        inputUrl = EditText(ctx).apply {
            hint = "Paste video URL..."
            setTextColor(textCol)
            setHintTextColor(Color.parseColor("#6272A4"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlRow.addView(inputUrl)

        val btnPaste = Button(ctx).apply {
            text = "PASTE"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                try {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cb?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    if (!clip.isNullOrEmpty()) {
                        inputUrl?.setText(clip)
                    }
                } catch (_: Throwable) {}
            }
        }
        urlRow.addView(btnPaste)
        root.addView(urlRow)

        // Quality Selectors Row
        val fmtRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        val formats = listOf("1080p" to "1080p", "720p" to "720p", "mp3" to "MP3", "best" to "BEST")
        for ((key, label) to formats) {
            val b = Button(ctx).apply {
                text = label
                textSize = 10f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    selectedFormat = key
                    refreshFormatButtons(accent)
                }
            }
            formatButtons[key] = b
            fmtRow.addView(b)
        }
        refreshFormatButtons(accent)
        root.addView(fmtRow)

        // Action Buttons Row
        val actRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        val btnDownload = Button(ctx).apply {
            text = "▶ DOWNLOAD"
            setTextColor(Color.parseColor("#50FA7B"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                setMargins(0, 0, 4, 0)
            }
            setOnClickListener { startDownload(host) }
        }
        actRow.addView(btnDownload)

        val btnCancel = Button(ctx).apply {
            text = "■ STOP"
            setTextColor(Color.parseColor("#FF5555"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener { cancelDownload(host) }
        }
        actRow.addView(btnCancel)
        root.addView(actRow)

        // Status & Progress Bar
        lblStatus = TextView(ctx).apply {
            text = "STATE: IDLE | Ready"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 4)
        }
        root.addView(lblStatus)

        pbar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 2, 0, 8)
        }
        root.addView(pbar)

        // Scrollable Log Output Box
        scrollLogs = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 110)
            )
            setPadding(10, 8, 10, 8)
        }
        lblLogs = TextView(ctx).apply {
            text = "[READY] Paste link and press DOWNLOAD."
            setTextColor(textCol)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        scrollLogs?.addView(lblLogs)
        root.addView(scrollLogs)

        updateFromStateFile()
        return root
    }

    private fun dp(ctx: Context, v: Int): Int {
        val d = ctx.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    private fun refreshFormatButtons(accent: Int) {
        for ((key, btn) in formatButtons) {
            if (key == selectedFormat) {
                btn.setBackgroundColor(accent)
                btn.setTextColor(Color.parseColor("#0A0A0F"))
            } else {
                btn.setBackgroundColor(Color.parseColor("#1F1E2D"))
                btn.setTextColor(Color.parseColor("#BDB4CA"))
            }
        }
    }

    private fun stateFile(): File =
        File("/sdcard/Re-T-UI/dex-modules/ytdlp/state.json")

    private fun startDownload(host: RetuiDexHost) {
        val url = inputUrl?.text?.toString()?.trim() ?: ""
        if (!url.startsWith("http")) {
            lblStatus?.text = "ERR: Invalid URL!"
            lblLogs?.text = "[ERROR] Enter a valid http(s) URL."
            return
        }

        // Write initial state
        try {
            stateFile().parentFile?.mkdirs()
            val initJson = JSONObject().apply {
                put("state", "DL")
                put("pct", 0)
                put("speed", "init")
                put("eta", "--:--")
                put("log", "Starting yt-dlp...")
            }
            stateFile().writeText(initJson.toString(2))
        } catch (_: Throwable) {}

        lblStatus?.text = "STATE: DL | Initializing..."
        pbar?.progress = 0
        lblLogs?.text = "[START] Fetching: $url\nFormat: $selectedFormat"

        // Execute runner via Termux bridge
        host.executeCommand("termux -run ~/retui/ytdlp_native.sh $url $selectedFormat")

        // Start polling loop
        isPolling = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun cancelDownload(host: RetuiDexHost) {
        host.executeCommand("termux -run ~/retui/ytdlp_native.sh -cancel")
        isPolling = false
        lblStatus?.text = "STATE: CANCELLED"
        lblLogs?.append("\n[CANCELLED] Download aborted.")
    }

    private fun updateFromStateFile() {
        val f = stateFile()
        if (!f.isFile) return
        try {
            val json = JSONObject(f.readText())
            val state = json.optString("state", "IDLE")
            val pct = json.optInt("pct", 0)
            val speed = json.optString("speed", "--")
            val eta = json.optString("eta", "--:--")
            val log = json.optString("log", "")

            pbar?.progress = pct
            lblStatus?.text = "STATE: $state | $pct% | $speed | ETA: $eta"
            if (log.isNotEmpty()) {
                lblLogs?.text = log
                scrollLogs?.post { scrollLogs?.fullScroll(View.FOCUS_DOWN) }
            }

            if (state == "DONE" || state == "ERR" || state == "CANCELLED") {
                isPolling = false
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        isPolling = false
        handler.removeCallbacks(pollRunnable)
        hostRef = null
    }
}
