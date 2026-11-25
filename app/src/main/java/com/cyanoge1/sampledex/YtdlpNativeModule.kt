package com.cyanoge1.sampledex

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
 * Features:
 * - Direct URL paste, auto-trim, and clear
 * - Video & Audio presets (1080p, 720p, MP3, M4A, OPUS, BEST)
 * - Playlist Mode toggle (Single Video vs Full Playlist batch download)
 * - Custom Output Name Template (with quick Cyberdeck chips)
 * - Custom Download Directory path (with fast storage preset chips)
 * - Real-time animated progress bar, speed, ETA, and playlist counter (Item X of Y)
 * - Persistent configuration across launcher sessions
 */
class YtdlpNativeModule : RetuiDexModule {
    override val id: String = "ytdlp"
    override val title: String = "YT-DLP // NATIVE"

    private var hostRef: RetuiDexHost? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    // Configurable state
    private var selectedFormat = "1080p"
    private var isPlaylist = false
    private var nameTemplate = "%(title).80s.%(ext)s"
    private var outDir = "/storage/emulated/0/Download/yt-dlp"
    private var isConfigExpanded = false

    // UI references
    private var pbar: ProgressBar? = null
    private var lblStatus: TextView? = null
    private var lblLogs: TextView? = null
    private var inputUrl: EditText? = null
    private var inputTemplate: EditText? = null
    private var inputDir: EditText? = null
    private var panelConfig: LinearLayout? = null
    private var btnConfigToggle: Button? = null
    private var btnPlaylistToggle: Button? = null
    private var scrollLogs: ScrollView? = null
    private val formatButtons = HashMap<String, Button>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            updateFromStateFile()
            handler.postDelayed(this, 500)
        }
    }

    override fun createView(host: RetuiDexHost, container: ViewGroup): View {
        hostRef = host
        val ctx = host.context
        val accent = host.accentTextColor
        val textCol = host.bodyTextColor

        loadConfig()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 14)
        }

        // --- 1. Header Row ---
        val hdr = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val lblTitle = TextView(ctx).apply {
            text = "⚡ YT-DLP // NATIVE DOCK"
            setTextColor(accent)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hdr.addView(lblTitle)

        btnConfigToggle = Button(ctx).apply {
            text = if (isConfigExpanded) "▲ CFG" else "▼ CFG"
            setTextColor(Color.parseColor("#BD93F9"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(10, 4, 10, 4)
            setOnClickListener {
                isConfigExpanded = !isConfigExpanded
                panelConfig?.visibility = if (isConfigExpanded) View.VISIBLE else View.GONE
                text = if (isConfigExpanded) "▲ CFG" else "▼ CFG"
            }
        }
        hdr.addView(btnConfigToggle)

        val btnClose = Button(ctx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF5555"))
            setBackgroundColor(Color.parseColor("#22FF5555"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(10, 4, 10, 4)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(6, 0, 0, 0)
            }
            layoutParams = lp
            setOnClickListener { host.closeModule() }
        }
        hdr.addView(btnClose)
        root.addView(hdr)

        // --- 2. Advanced Config Accordion Panel ---
        panelConfig = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111019"))
            setPadding(12, 10, 12, 10)
            visibility = if (isConfigExpanded) View.VISIBLE else View.GONE
        }

        // Playlist Mode Row
        val plRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 6)
        }
        val lblPl = TextView(ctx).apply {
            text = "PLAYLIST MODE:"
            setTextColor(Color.parseColor("#6272A4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        plRow.addView(lblPl)

        btnPlaylistToggle = Button(ctx).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                isPlaylist = !isPlaylist
                refreshPlaylistButton(accent)
                saveConfig()
            }
        }
        refreshPlaylistButton(accent)
        plRow.addView(btnPlaylistToggle)
        panelConfig?.addView(plRow)

        // Name Template Row
        val tmplLabelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 2)
        }
        tmplLabelRow.addView(TextView(ctx).apply {
            text = "OUTPUT TEMPLATE:"
            setTextColor(Color.parseColor("#6272A4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        panelConfig?.addView(tmplLabelRow)

        // Template Quick Chips
        val chipRow1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        val templates = listOf(
            "DEFAULT" to "%(title).80s.%(ext)s",
            "INDEXED" to "%(playlist_index)02d - %(title).80s.%(ext)s",
            "ARTIST" to "%(uploader)s - %(title).80s.%(ext)s"
        )
        for ((label, tmpl) in templates) {
            val chip = Button(ctx).apply {
                text = label
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#00E5FF"))
                setBackgroundColor(Color.parseColor("#1F1E2D"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    nameTemplate = tmpl
                    inputTemplate?.setText(tmpl)
                    saveConfig()
                }
            }
            chipRow1.addView(chip)
        }
        panelConfig?.addView(chipRow1)

        inputTemplate = EditText(ctx).apply {
            setText(nameTemplate)
            setTextColor(textCol)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#191824"))
            setPadding(10, 8, 10, 8)
        }
        panelConfig?.addView(inputTemplate)

        // Download Directory Row
        val dirLabelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 2)
        }
        dirLabelRow.addView(TextView(ctx).apply {
            text = "DOWNLOAD DIRECTORY:"
            setTextColor(Color.parseColor("#6272A4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        panelConfig?.addView(dirLabelRow)

        // Directory Quick Chips
        val chipRow2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        val dirs = listOf(
            "YT-DLP" to "/storage/emulated/0/Download/yt-dlp",
            "MUSIC" to "/storage/emulated/0/Music",
            "MOVIES" to "/storage/emulated/0/Movies"
        )
        for ((label, path) in dirs) {
            val chip = Button(ctx).apply {
                text = label
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#50FA7B"))
                setBackgroundColor(Color.parseColor("#1F1E2D"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    outDir = path
                    inputDir?.setText(path)
                    saveConfig()
                }
            }
            chipRow2.addView(chip)
        }
        panelConfig?.addView(chipRow2)

        inputDir = EditText(ctx).apply {
            setText(outDir)
            setTextColor(textCol)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#191824"))
            setPadding(10, 8, 10, 8)
        }
        panelConfig?.addView(inputDir)

        root.addView(panelConfig)

        // --- 3. URL Input Row ---
        val urlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 6)
        }

        inputUrl = EditText(ctx).apply {
            hint = "Paste video or playlist URL..."
            setTextColor(textCol)
            setHintTextColor(Color.parseColor("#6272A4"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlRow.addView(inputUrl)

        val btnPaste = Button(ctx).apply {
            text = "PASTE"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(10, 6, 10, 6)
            setOnClickListener {
                try {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cb?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    if (!clip.isNullOrEmpty()) {
                        inputUrl?.setText(clip)
                    }
                } catch (e: Throwable) {}
            }
        }
        urlRow.addView(btnPaste)

        val btnClear = Button(ctx).apply {
            text = "CLR"
            setTextColor(Color.parseColor("#6272A4"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(8, 6, 8, 6)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4, 0, 0, 0)
            }
            layoutParams = lp
            setOnClickListener { inputUrl?.setText("") }
        }
        urlRow.addView(btnClear)
        root.addView(urlRow)

        // --- 4. Quality Selectors Row ---
        val fmtRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 6)
        }

        val formats = listOf(
            "1080p" to "1080p",
            "720p" to "720p",
            "mp3" to "MP3",
            "m4a" to "M4A",
            "opus" to "OPUS",
            "best" to "BEST"
        )
        for ((key, label) in formats) {
            val b = Button(ctx).apply {
                text = label
                textSize = 9f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    selectedFormat = key
                    refreshFormatButtons(accent)
                    saveConfig()
                }
            }
            formatButtons[key] = b
            fmtRow.addView(b)
        }
        refreshFormatButtons(accent)
        root.addView(fmtRow)

        // --- 5. Action Buttons Row ---
        val actRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 6)
        }

        val btnDownload = Button(ctx).apply {
            text = "▶ DOWNLOAD"
            setTextColor(Color.parseColor("#50FA7B"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                setMargins(0, 0, 3, 0)
            }
            setOnClickListener { startDownload(host) }
        }
        actRow.addView(btnDownload)

        val btnCancel = Button(ctx).apply {
            text = "■ STOP"
            setTextColor(Color.parseColor("#FF5555"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                setMargins(3, 0, 3, 0)
            }
            setOnClickListener { cancelDownload(host) }
        }
        actRow.addView(btnCancel)

        val btnFolder = Button(ctx).apply {
            text = "📂"
            setTextColor(Color.parseColor("#F1FA8C"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                setMargins(3, 0, 0, 0)
            }
            setOnClickListener { openFolder(ctx) }
        }
        actRow.addView(btnFolder)
        root.addView(actRow)

        // --- 6. Status & Progress Bar ---
        lblStatus = TextView(ctx).apply {
            text = "STATE: IDLE | Ready"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 2, 0, 4)
        }
        root.addView(lblStatus)

        pbar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 2, 0, 6)
        }
        root.addView(pbar)

        // --- 7. Scrollable Log Output Box ---
        scrollLogs = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0A0A0F"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 110)
            )
            setPadding(10, 8, 10, 8)
        }
        lblLogs = TextView(ctx).apply {
            text = "[READY] Paste video/playlist URL and tap DOWNLOAD."
            setTextColor(textCol)
            textSize = 9.5f
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

    private fun refreshPlaylistButton(accent: Int) {
        if (isPlaylist) {
            btnPlaylistToggle?.text = "PLAYLIST [ON] ⚡"
            btnPlaylistToggle?.setTextColor(Color.parseColor("#0A0A0F"))
            btnPlaylistToggle?.setBackgroundColor(Color.parseColor("#50FA7B"))
        } else {
            btnPlaylistToggle?.text = "SINGLE VIDEO"
            btnPlaylistToggle?.setTextColor(Color.parseColor("#BDB4CA"))
            btnPlaylistToggle?.setBackgroundColor(Color.parseColor("#1F1E2D"))
        }
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

    private fun jobFile(): File =
        File("/sdcard/Re-T-UI/dex-modules/ytdlp/job.json")

    private fun configFile(): File =
        File("/sdcard/Re-T-UI/dex-modules/ytdlp/config.json")

    private fun loadConfig() {
        val f = configFile()
        if (!f.isFile) return
        try {
            val json = JSONObject(f.readText())
            selectedFormat = json.optString("selectedFormat", selectedFormat)
            isPlaylist = json.optBoolean("isPlaylist", isPlaylist)
            nameTemplate = json.optString("nameTemplate", nameTemplate)
            outDir = json.optString("outDir", outDir)
        } catch (e: Throwable) {}
    }

    private fun saveConfig() {
        try {
            configFile().parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("selectedFormat", selectedFormat)
                put("isPlaylist", isPlaylist)
                put("nameTemplate", inputTemplate?.text?.toString()?.trim() ?: nameTemplate)
                put("outDir", inputDir?.text?.toString()?.trim() ?: outDir)
            }
            configFile().writeText(json.toString(2))
        } catch (e: Throwable) {}
    }

    private fun startDownload(host: RetuiDexHost) {
        val url = inputUrl?.text?.toString()?.trim() ?: ""
        if (!url.startsWith("http")) {
            lblStatus?.text = "ERR: Invalid URL!"
            lblLogs?.text = "[ERROR] Enter a valid http(s) URL."
            return
        }

        val currentTmpl = inputTemplate?.text?.toString()?.trim()?.ifEmpty { nameTemplate } ?: nameTemplate
        val currentDir = inputDir?.text?.toString()?.trim()?.ifEmpty { outDir } ?: outDir

        saveConfig()

        // Write job configuration for runner
        try {
            jobFile().parentFile?.mkdirs()
            val jobJson = JSONObject().apply {
                put("url", url)
                put("format", selectedFormat)
                put("is_playlist", isPlaylist)
                put("name_template", currentTmpl)
                put("out_dir", currentDir)
            }
            jobFile().writeText(jobJson.toString(2))

            // Initialize state
            val initJson = JSONObject().apply {
                put("state", "DL")
                put("pct", 0)
                put("speed", "init")
                put("eta", "--:--")
                put("playlist_current", 0)
                put("playlist_total", 0)
                put("log", "Starting yt-dlp...")
            }
            stateFile().writeText(initJson.toString(2))
        } catch (e: Throwable) {}

        lblStatus?.text = "STATE: DL | Initializing..."
        pbar?.progress = 0
        lblLogs?.text = "[START] Fetching: $url\nFormat: $selectedFormat | Playlist: $isPlaylist\nOutput: $currentDir"

        // Execute runner via Termux bridge
        host.executeCommand("termux -run ~/retui/ytdlp_native.sh")

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

    private fun openFolder(ctx: Context) {
        val folderPath = inputDir?.text?.toString()?.trim() ?: outDir
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(folderPath), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Throwable) {
            hostRef?.executeCommand("termux -run am start -a android.intent.action.VIEW -d 'file://$folderPath'")
        }
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
            val plCur = json.optInt("playlist_current", 0)
            val plTot = json.optInt("playlist_total", 0)
            val log = json.optString("log", "")

            pbar?.progress = pct

            val plTag = if (plTot > 0) " | Item $plCur/$plTot" else ""
            lblStatus?.text = "STATE: $state | $pct% | $speed | ETA: $eta$plTag"

            if (log.isNotEmpty()) {
                lblLogs?.text = log
                scrollLogs?.post { scrollLogs?.fullScroll(View.FOCUS_DOWN) }
            }

            if (state == "DONE" || state == "ERR" || state == "CANCELLED") {
                isPolling = false
            }
        } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        isPolling = false
        handler.removeCallbacks(pollRunnable)
        hostRef = null
    }
}
