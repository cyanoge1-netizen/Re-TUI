package com.cyanoge1.sampledex

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Production ScheduleModule with Cyberdeck monospace design, human manual approval gate,
 * Next-Up 60s countdown ticker, conflict warning, and direct calendar/alarm integration.
 */
class ScheduleModule : RetuiDexModule {
    override val id: String = "schedule"
    override val title: String = "NOTICES // SCHEDULE"

    enum class EventType { EXAM, CLASS, LAB, EVENT }


    /**
     * Design Tokens
     */
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

    fun typeColor(type: EventType): Int = when (type) {
        EventType.EXAM -> ERR
        EventType.LAB -> INFO
        EventType.CLASS -> OK
        EventType.EVENT -> WARN
    }

    fun borderDrawable(bgColor: Int, borderColor: Int, borderWidthPx: Int = 3, cornerRadiusDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            setStroke(borderWidthPx, borderColor)
            if (cornerRadiusDp > 0) cornerRadius = cornerRadiusDp
        }
    }
}

/**
 * Pure Notice Parser with Bangla digit normalization and confidence scoring.
 */
object NoticeParser {
    enum class Confidence { SURE, GUESS, NONE }

    enum class TemporalStatus {
        LIVE,      // Ongoing (within 90 mins of start)
        TODAY,     // Scheduled for later today
        UPCOMING,  // Tomorrow or later
        PAST,      // Date has passed
        NONE       // No date attached
    }

    fun getTemporalStatus(eventMillis: Long?, nowMillis: Long = System.currentTimeMillis()): TemporalStatus {
        if (eventMillis == null) return TemporalStatus.NONE
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val ev = Calendar.getInstance().apply { timeInMillis = eventMillis }

        val isSameDay = ev.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                ev.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)

        if (isSameDay) {
            val diffMins = (eventMillis - nowMillis) / (60 * 1000)
            return when {
                diffMins in -90..15 -> TemporalStatus.LIVE
                eventMillis < nowMillis -> TemporalStatus.PAST
                else -> TemporalStatus.TODAY
            }
        }

        return if (eventMillis < nowMillis) {
            TemporalStatus.PAST
        } else {
            TemporalStatus.UPCOMING
        }
    }

    data class ParsedNotice(
        val type: EventType,
        val typeConfidence: Confidence,
        val code: String,
        val codeConfidence: Confidence,
        val title: String,
        val titleConfidence: Confidence,
        val room: String,
        val roomConfidence: Confidence,
        val dateMillis: Long?,
        val dateConfidence: Confidence,
        val rawNotice: String
    )

    private val BANGLA_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun normalizeBanglaDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val idx = BANGLA_DIGITS.indexOf(ch)
            if (idx >= 0) sb.append(idx) else sb.append(ch)
        }
        return sb.toString()
    }

    fun parse(raw: String, noticeTs: Long = System.currentTimeMillis()): ParsedNotice {
        val norm = normalizeBanglaDigits(raw)

        // 1. Guess Event Type
        val lower = norm.lowercase(Locale.ROOT)
        var type = EventType.EVENT
        var typeConf = Confidence.NONE

        if (lower.contains("term test") || lower.contains("class test") || lower.contains(Regex("\\btt\\b")) ||
            lower.contains(Regex("\\bct\\b")) || lower.contains("quiz") || lower.contains("mid") ||
            lower.contains("final") || lower.contains("exam") || lower.contains("পরীক্ষা")
        ) {
            type = EventType.EXAM
            typeConf = Confidence.SURE
        } else if (lower.contains("lab") || lower.contains("ল্যাব") || lower.contains("practical")) {
            type = EventType.LAB
            typeConf = Confidence.SURE
        } else if (lower.contains("class") || lower.contains("shifted") || lower.contains("rescheduled") ||
            lower.contains("ক্লাস") || lower.contains("routine") || lower.contains("schedule")
        ) {
            type = EventType.CLASS
            typeConf = Confidence.SURE
        }

        // 2. Extract Course Code
        // Supports formats: "CSE 0541 1143", "MAT 0541 1101", "EEE 0713 1110", "CSE1143", "CSE-311"
        var code = ""
        var codeConf = Confidence.NONE
        val codeRegex = Regex("\\b([A-Z]{2,4})\\s*(?:[-_]?\\s*(\\d{4}|\\d{3}))(?:\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
        val codeMatch = codeRegex.find(norm)
        if (codeMatch != null) {
            val dept = codeMatch.groupValues[1].uppercase(Locale.ROOT)
            val num1 = codeMatch.groupValues[2]
            val num2 = codeMatch.groupValues[3]
            code = if (num2.isNotEmpty()) "$dept $num1 $num2" else "$dept $num1"
            codeConf = Confidence.SURE
        }

        // 3. Extract Room
        var room = ""
        var roomConf = Confidence.NONE
        val roomRegex = Regex("\\b(?:room|r|room\\s*no\\.?|lab)\\s*[:#-]?\\s*([a-z0-9_-]+)\\b", RegexOption.IGNORE_CASE)
        val rMatch = roomRegex.find(norm)
        if (rMatch != null) {
            room = rMatch.groupValues[1].uppercase(Locale.ROOT)
            roomConf = Confidence.SURE
        } else {
            // Secondary guess: 3-digit standalone numbers like "305", "UB402"
            val fallbackRoomRegex = Regex("\\b([A-Z]{0,2}\\d{3})\\b")
            val fbMatch = fallbackRoomRegex.find(norm)
            if (fbMatch != null && fbMatch.value != code) {
                room = fbMatch.value
                roomConf = Confidence.GUESS
            }
        }

        // 4. Extract Date and Time
        val baseCal = Calendar.getInstance().apply { timeInMillis = noticeTs }
        var dateMillis: Long? = null
        var dateConf = Confidence.NONE

        val parsedDt = extractDateTime(norm, baseCal)
        if (parsedDt != null) {
            dateMillis = parsedDt.first
            dateConf = parsedDt.second
        }

        // 5. Title synthesis
        var title = ""
        var titleConf = Confidence.NONE
        if (type == EventType.EXAM) {
            title = when {
                lower.contains("term test") || lower.contains(Regex("\\btt\\b")) -> "Term Test"
                lower.contains("class test") || lower.contains(Regex("\\bct\\b")) -> "Class Test"
                lower.contains("final") -> "Final Exam"
                lower.contains("mid") -> "Midterm Exam"
                lower.contains("quiz") -> "Quiz"
                else -> "Exam"
            }
            titleConf = Confidence.SURE
        } else if (type == EventType.LAB) {
            title = if (lower.contains("report")) "Lab Report Deadline" else "Lab Session"
            titleConf = Confidence.GUESS
        } else if (type == EventType.CLASS) {
            title = if (lower.contains("shifted") || lower.contains("rescheduled")) "Shifted Class" else "Lecture"
            titleConf = Confidence.GUESS
        } else {
            title = "Notice Event"
            titleConf = Confidence.NONE
        }

        return ParsedNotice(
            type = type,
            typeConfidence = typeConf,
            code = code,
            codeConfidence = codeConf,
            title = title,
            titleConfidence = titleConf,
            room = room,
            roomConfidence = roomConf,
            dateMillis = dateMillis,
            dateConfidence = dateConf,
            rawNotice = raw
        )
    }

    private fun extractDateTime(text: String, baseCal: Calendar): Pair<Long, Confidence>? {
        val now = Calendar.getInstance()
        var targetCal: Calendar? = null
        var conf = Confidence.NONE

        val lower = text.lowercase(Locale.ROOT)

        // Relative days
        if (lower.contains("tomorrow") || lower.contains("আগামীকাল")) {
            targetCal = (baseCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            conf = Confidence.SURE
        } else if (lower.contains("today") || lower.contains("আজ")) {
            targetCal = baseCal.clone() as Calendar
            conf = Confidence.SURE
        }

        // Specific dates e.g. "02 Dec", "2 December", "02/12", "02-12-2026"
        if (targetCal == null) {
            val dateMonthRegex = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b", RegexOption.IGNORE_CASE)
            val dmMatch = dateMonthRegex.find(text)
            if (dmMatch != null) {
                val day = dmMatch.groupValues[1].toIntOrNull() ?: 1
                val monthStr = dmMatch.groupValues[2].lowercase(Locale.ROOT)
                val month = when (monthStr) {
                    "jan" -> 0; "feb" -> 1; "mar" -> 2; "apr" -> 3; "may" -> 4; "jun" -> 5
                    "jul" -> 6; "aug" -> 7; "sep" -> 8; "oct" -> 9; "nov" -> 10; "dec" -> 11
                    else -> 0
                }
                val baseYear = baseCal.get(Calendar.YEAR)
                val baseMonth = baseCal.get(Calendar.MONTH)
                var year = baseYear
                if (month < baseMonth - 6) {
                    year = baseYear + 1
                } else if (month > baseMonth + 6) {
                    year = baseYear - 1
                }
                targetCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                conf = Confidence.SURE
            }
        }

        // Numeric dates: "25/11", "02-12-2026"
        if (targetCal == null) {
            val numDateRegex = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b")
            val ndMatch = numDateRegex.find(text)
            if (ndMatch != null) {
                val day = ndMatch.groupValues[1].toIntOrNull() ?: 1
                val month = (ndMatch.groupValues[2].toIntOrNull() ?: 1) - 1
                val yr = ndMatch.groupValues[3].toIntOrNull()
                val baseYear = baseCal.get(Calendar.YEAR)
                val baseMonth = baseCal.get(Calendar.MONTH)
                var year = yr ?: baseYear
                if (yr == null) {
                    if (month < baseMonth - 6) year = baseYear + 1
                    else if (month > baseMonth + 6) year = baseYear - 1
                } else if (year < 100) {
                    year += 2000
                }
                targetCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                conf = Confidence.SURE
            }
        }

        if (targetCal == null) {
            targetCal = (baseCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            conf = Confidence.GUESS
        }

        // Extract Time: "10:30 AM", "10.30am", "5 PM", "14:00"
        val timeRegex = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)
        val tMatch = timeRegex.findAll(text).firstOrNull { m ->
            m.groupValues[3].isNotEmpty() || m.groupValues[2].isNotEmpty()
        }

        if (tMatch != null) {
            var hour = tMatch.groupValues[1].toIntOrNull() ?: 10
            val min = tMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = tMatch.groupValues[3].lowercase(Locale.ROOT)
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            targetCal.set(Calendar.HOUR_OF_DAY, hour)
            targetCal.set(Calendar.MINUTE, min)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
        } else {
            // Default 10:30 AM
            targetCal.set(Calendar.HOUR_OF_DAY, 10)
            targetCal.set(Calendar.MINUTE, 30)
            targetCal.set(Calendar.SECOND, 0)
            conf = Confidence.GUESS
        }

        return Pair(targetCal.timeInMillis, conf)
    }

    /**
     * Self-test function verifying parser resilience against real samples.
     */
    fun runSelfTest(): String {
        val samples = listOf(
            "Term Test of CSE 0541 1143 will be held on 02 Dec at 10:30 AM in Room 305.",
            "EEE lab report submission deadline: 28 Nov, 5 PM.",
            "Physics class shifted to Room 210 on Sunday at 11.00 am.",
            "আগামীকাল MAT 0541 1101 এর ক্লাস টেস্ট সকাল ১০:০০ টায় রুম ৩০৪ এ হবে।",
            "২৫/১২ তারিখে CSE1143 Final Exam অনুষ্ঠিত হবে সকাল ১০ টায়।",
            "Tomorrow 2:00 PM extra class in Lab 2 for CSE 0713 1110.",
            "Notice: Quiz 1 of CSE 311 on Wednesday 10 AM in R-402.",
            "General department meeting today at 4 PM in Auditorium."
        )

        val sb = StringBuilder("--- NoticeParser Self-Test ---\n")
        var passed = 0
        for ((idx, s) in samples.withIndex()) {
            val res = parse(s)
            val ok = res.type != EventType.EVENT || idx == 7
            if (ok) passed++
            sb.append("#$idx [${res.type}] Code='${res.code}' Title='${res.title}' Room='${res.room}' DateConf=${res.dateConfidence}\n")
        }
        sb.append("Result: $passed/${samples.size} passed.\n")
        return sb.toString()
    }
}

    companion object {
        const val PATH_DIR = "/sdcard/Re-T-UI/dex-modules/schedule"
        const val PATH_NOTICES = "$PATH_DIR/notices.json"
        const val PATH_EVENTS = "$PATH_DIR/events.json"
        const val PATH_STATE = "$PATH_DIR/state.json"

        // Configurable Semester Dates for the progress bar
        const val SEMESTER_START = "2026-08-01"
        const val SEMESTER_END = "2026-12-31"

        private const val TICK_INTERVAL_MS = 60_000L
    }

    private enum class Tab { NOTICES, SCHEDULE, AGENDA }

    data class EventItem(
        val id: String = UUID.randomUUID().toString(),
        val sourceNoticeId: Long = 0L,
        val type: EventType,
        val title: String,
        val code: String,
        val room: String,
        val startMillis: Long,
        val alarmOffsetMins: Int = 30,
        val isWeeklyRepeat: Boolean = false,
        val notes: String = ""
    )

    private var hostRef: RetuiDexHost? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // State
    private var activeTab = Tab.NOTICES
    private var lastSeenTs: Long = 0L
    private val ignoredNoticeIds = HashSet<Long>()
    private var searchQuery: String = ""
    private var isArchivedExpanded = false

    // Review Form State
    private var editingEventId: String? = null
    private var isFormOpen = false
    private var formNoticeId: Long = 0L
    private var formType = EventType.EXAM
    private var formExamSubtype = "Class Test / TT"
    private var formCalendar = Calendar.getInstance()
    private var formAlarmOffset = 30
    private var formWeeklyRepeat = false
    private var formSourceText = ""

    // Badges state
    private var titleBadgeText = ""
    private var codeBadgeText = ""
    private var roomBadgeText = ""
    private var dateBadgeText = ""

    // UI Elements
    private var rootLayout: LinearLayout? = null
    private var nextUpContainer: LinearLayout? = null
    private var tabNoticesBtn: Button? = null
    private var tabScheduleBtn: Button? = null
    private var tabAgendaBtn: Button? = null
    private var lblUpdatedHeader: TextView? = null
    private var searchInput: EditText? = null
    private var mainScrollContent: LinearLayout? = null
    private var reviewFormCard: LinearLayout? = null

    // Form inputs
    private var formInputTitle: EditText? = null
    private var formInputCode: EditText? = null
    private var formInputRoom: EditText? = null
    private var formBtnDate: Button? = null
    private var formBtnTime: Button? = null
    private var formLblConflict: TextView? = null
    private var formBtnRepeat: Button? = null
    private var formExamSubtypeRow: LinearLayout? = null

    // Badges labels
    private var lblBadgeTitle: TextView? = null
    private var lblBadgeCode: TextView? = null
    private var lblBadgeRoom: TextView? = null
    private var lblBadgeDate: TextView? = null

    private val typeBtnMap = HashMap<EventType, Button>()
    private val examSubtypeBtnMap = HashMap<String, Button>()
    private val alarmBtnMap = HashMap<Int, Button>()

    // 60-second ticker runnable
    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            updateNextUpStrip()
            if (activeTab == Tab.AGENDA) renderAgendaTab()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun createView(host: RetuiDexHost, container: ViewGroup): View {
        hostRef = host
        isDestroyed = false
        val ctx = host.context

        loadState()
        ensureEventIds()

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
            setPadding(0, 0, 0, dp(ctx, 4))
        }
        val titleText = TextView(ctx).apply {
            text = "NOTICES // HUB"
            setTextColor(host.accentTextColor)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)

        val btnNew = createHeaderButton(ctx, "+NEW", Theme.OK) {
            val emptyNotice = NoticeParser.ParsedNotice(
                type = EventType.CLASS,
                typeConfidence = NoticeParser.Confidence.NONE,
                code = "",
                codeConfidence = NoticeParser.Confidence.NONE,
                title = "",
                titleConfidence = NoticeParser.Confidence.NONE,
                room = "",
                roomConfidence = NoticeParser.Confidence.NONE,
                dateMillis = System.currentTimeMillis() + 86400000L,
                dateConfidence = NoticeParser.Confidence.NONE,
                rawNotice = ""
            )
            openReviewForm(emptyNotice, null)
        }
        header.addView(btnNew)

        val btnRefresh = createHeaderButton(ctx, "REFRESH", Theme.INFO) {
            triggerBackgroundRefresh()
        }
        header.addView(btnRefresh)

        val btnId = createHeaderButton(ctx, "ID", Theme.WARN) {
            showProfileConfigDialog(ctx)
        }
        header.addView(btnId)

        val btnClose = createHeaderButton(ctx, "✕", Theme.ERR) {
            host.closeModule()
        }
        header.addView(btnClose)
        root.addView(header)

        // --- 2. Next Up Strip (Always visible countdown bar) ---
        nextUpContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.CARD)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(ctx, 6))
            }
            layoutParams = lp
        }
        root.addView(nextUpContainer)

        // --- 3. Navigation Tabs & Unread Badge ---
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(ctx, 4))
        }

        tabNoticesBtn = createTabButton(ctx, "NOTICES", 1f) {
            switchTab(Tab.NOTICES)
        }
        tabRow.addView(tabNoticesBtn)

        tabScheduleBtn = createTabButton(ctx, "CLASS FEED", 1.1f) {
            switchTab(Tab.SCHEDULE)
        }
        tabRow.addView(tabScheduleBtn)

        tabAgendaBtn = createTabButton(ctx, "AGENDA", 1f) {
            switchTab(Tab.AGENDA)
        }
        tabRow.addView(tabAgendaBtn)
        root.addView(tabRow)

        // Updated time line
        lblUpdatedHeader = TextView(ctx).apply {
            setTextColor(Theme.DIM)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(dp(ctx, 2), 0, 0, dp(ctx, 4))
        }
        root.addView(lblUpdatedHeader)

        // --- 4. Search Filter Box ---
        searchInput = EditText(ctx).apply {
            hint = "Filter notices / agenda..."
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Theme.CARD)
            setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(ctx, 6))
            }
            layoutParams = lp
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    renderActiveView()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(searchInput)

        // --- 5. Inline Review & Approval Form ---
        reviewFormCard = buildReviewFormView(ctx)
        root.addView(reviewFormCard)

        // --- 6. Content Area (rendered directly in parent scroll view) ---
        mainScrollContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 80))
        }
        root.addView(mainScrollContent)

        // Auto-refresh if data is older than 15 mins
        checkAutoRefresh()

        // Start 60s ticker
        mainHandler.post(tickerRunnable)

        refreshTabsVisual()
        renderActiveView()

        return root
    }

    private fun dp(ctx: Context, v: Int): Int {
        val d = ctx.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    private fun createHeaderButton(ctx: Context, text: String, color: Int, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            setTextColor(color)
            setBackgroundColor(Theme.BTN)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            includeFontPadding = false
            setPadding(dp(ctx, 6), dp(ctx, 3), dp(ctx, 6), dp(ctx, 3))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(ctx, 3), 0, 0, 0)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun createTabButton(ctx: Context, label: String, weight: Float, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = label
            textSize = 9f
            typeface = Typeface.MONOSPACE
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            includeFontPadding = false
            setPadding(dp(ctx, 4), dp(ctx, 5), dp(ctx, 4), dp(ctx, 5))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(dp(ctx, 2), 0, dp(ctx, 2), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun switchTab(tab: Tab) {
        if (activeTab == Tab.NOTICES && tab != Tab.NOTICES) {
            // Leaving NOTICES marks all seen
            markAllNoticesSeen()
        }
        activeTab = tab
        isFormOpen = false
        reviewFormCard?.visibility = View.GONE
        refreshTabsVisual()
        renderActiveView()
    }

    private fun refreshTabsVisual() {
        val unread = computeUnreadCount()
        tabNoticesBtn?.text = if (unread > 0) "NOTICES ($unread)" else "NOTICES"
        tabScheduleBtn?.text = "CLASS FEED"
        tabAgendaBtn?.text = if (activeTab == Tab.AGENDA) "AGENDA*" else "AGENDA"

        fun styleTab(btn: Button?, isActive: Boolean) {
            if (isActive) {
                btn?.setBackgroundColor(Theme.INFO)
                btn?.setTextColor(Theme.BG)
            } else {
                btn?.setBackgroundColor(Theme.BTN)
                btn?.setTextColor(Theme.DIM)
            }
        }

        styleTab(tabNoticesBtn, activeTab == Tab.NOTICES)
        styleTab(tabScheduleBtn, activeTab == Tab.SCHEDULE)
        styleTab(tabAgendaBtn, activeTab == Tab.AGENDA)
    }

    private fun renderActiveView() {
        updateUpdatedHeader()
        updateNextUpStrip()

        reviewFormCard?.visibility = if (isFormOpen) View.VISIBLE else View.GONE
        mainScrollContent?.removeAllViews()

        when (activeTab) {
            Tab.NOTICES -> renderFeedTab(isOfficial = true)
            Tab.SCHEDULE -> renderFeedTab(isOfficial = false)
            Tab.AGENDA -> renderAgendaTab()
        }
    }

    // --- NEXT UP STRIP WITH 60s COUNTDOWN TICKER ---
    private fun updateNextUpStrip() {
        val container = nextUpContainer ?: return
        val ctx = hostRef?.context ?: return
        container.removeAllViews()

        val nextEvent = getNextUpcomingEvent()
        if (nextEvent == null) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        val now = System.currentTimeMillis()
        val evEnd = nextEvent.startMillis + (if (nextEvent.type == EventType.LAB || nextEvent.type == EventType.EXAM) 7200_000L else 3600_000L)
        val isOngoing = now in nextEvent.startMillis until evEnd

        val typeColor = if (isOngoing) Theme.INFO else Theme.typeColor(nextEvent.type)
        container.background = Theme.borderDrawable(Theme.CARD, typeColor, borderWidthPx = 4)

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val lblTag = TextView(ctx).apply {
            text = if (isOngoing) "⚡ LIVE NOW · ${nextEvent.type.name}" else "▌NEXT UP · ${nextEvent.type.name}"
            setTextColor(typeColor)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(lblTag)

        val diff = nextEvent.startMillis - now
        val countdownStr = if (isOngoing) "ONGOING" else formatCountdown(diff)
        val lblCountdown = TextView(ctx).apply {
            text = countdownStr
            setTextColor(if (isOngoing) Theme.INFO else if (nextEvent.type == EventType.EXAM && diff in 1..21_600_000L) Theme.ERR else Theme.DIM)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
        }
        row1.addView(lblCountdown)
        container.addView(row1)

        val lblTitle = TextView(ctx).apply {
            val codePrefix = if (nextEvent.code.isNotEmpty()) "${nextEvent.code} · " else ""
            text = "▌$codePrefix${nextEvent.title}"
            setTextColor(Theme.TEXT)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        container.addView(lblTitle)

        // Progress bar and date/room
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val dtStr = sdf.format(nextEvent.startMillis)
        val roomStr = if (nextEvent.room.isNotEmpty()) " · ${nextEvent.room}" else ""
        val asciiBar = getAsciiBar(diff)

        val lblBar = TextView(ctx).apply {
            text = "▌$asciiBar  $dtStr$roomStr"
            setTextColor(Theme.INFO)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
        }
        container.addView(lblBar)
    }

    private fun formatCountdown(diffMillis: Long): String {
        if (diffMillis <= 0) return "NOW"
        val sec = diffMillis / 1000
        val min = sec / 60
        val hrs = min / 60
        val days = hrs / 24

        return when {
            days > 0 -> "${days}d ${hrs % 24}h"
            hrs > 0 -> "${hrs}h ${min % 60}m"
            else -> "${min}m"
        }
    }

    private fun getAsciiBar(diffMillis: Long): String {
        // Full scale: 3 days (259_200_000 ms)
        val total = 259_200_000.0
        val fraction = (1.0 - (diffMillis / total)).coerceIn(0.0, 1.0)
        val filled = (fraction * 10).toInt()
        return "[" + "█".repeat(filled) + "░".repeat(10 - filled) + "]"
    }

    // --- NOTICES FEED RENDERING ---
    private fun renderFeedTab(isOfficial: Boolean) {
        val ctx = hostRef?.context ?: return
        val noticesData = loadNoticesJson()
        if (noticesData == null) {
            showEmptyMessage("No notices synchronized yet. Tap REFRESH.")
            return
        }

        val groupKey = if (isOfficial) "official_notices" else "class_schedule"
        val group = noticesData.optJSONObject(groupKey)
        val msgs = group?.optJSONArray("messages") ?: JSONArray()

        if (msgs.length() == 0) {
            showEmptyMessage("No messages in this group.")
            return
        }

        val scheduledNoticeIds = getScheduledNoticeIds()
        var renderedCount = 0

        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val nId = m.optLong("id", 0L)
            if (ignoredNoticeIds.contains(nId)) continue

            val rawText = m.optString("text", "").trim()
            val ts = m.optLong("timestamp", 0L)
            val timeStr = m.optString("time_str", "Recent")

            if (searchQuery.isNotEmpty() && !rawText.contains(searchQuery, ignoreCase = true)) {
                continue
            }

            renderedCount++
            val isNew = ts > lastSeenTs
            val isScheduled = scheduledNoticeIds.contains(nId)
            val parsed = NoticeParser.parse(rawText, ts)
            val temporal = NoticeParser.getTemporalStatus(parsed.dateMillis)
            val isPastEvent = temporal == NoticeParser.TemporalStatus.PAST

            val cardBg = if (isScheduled || isPastEvent) Theme.CARD_DIM else Theme.CARD
            val cardBorderColor = when {
                isScheduled || isPastEvent -> Theme.DIM
                temporal == NoticeParser.TemporalStatus.LIVE -> Theme.INFO
                temporal == NoticeParser.TemporalStatus.TODAY -> Theme.WARN
                else -> Theme.typeColor(parsed.type)
            }
            val cardBorderWidth = if (isPastEvent || isScheduled) 2 else 3

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cardBg)
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                background = Theme.borderDrawable(cardBg, cardBorderColor, borderWidthPx = cardBorderWidth)
                if (isPastEvent || isScheduled) alpha = 0.78f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dp(ctx, 8))
                }
                layoutParams = lp

                setOnLongClickListener {
                    showNoticeContextMenu(ctx, nId, rawText, parsed)
                    true
                }
            }

            // Top Status Line: ● NEW / ✓ SCHEDULED + Timestamp
            val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val lblMarker = TextView(ctx).apply {
                textSize = 9f
                typeface = Typeface.MONOSPACE
                when {
                    isScheduled -> {
                        text = "✓ SCHEDULED"
                        setTextColor(Theme.DIM)
                    }
                    temporal == NoticeParser.TemporalStatus.LIVE -> {
                        text = "⚡ LIVE NOW"
                        setTextColor(Theme.INFO)
                    }
                    temporal == NoticeParser.TemporalStatus.TODAY -> {
                        text = "● TODAY"
                        setTextColor(Theme.WARN)
                    }
                    isPastEvent -> {
                        text = "✕ PASSED"
                        setTextColor(Theme.DIM)
                    }
                    isNew -> {
                        text = "● NEW"
                        setTextColor(Theme.OK)
                    }
                    else -> text = ""
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(lblMarker)

            val lblTime = TextView(ctx).apply {
                text = timeStr
                setTextColor(Theme.DIM)
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
            }
            topRow.addView(lblTime)
            card.addView(topRow)

            // Notice Body
            val lblBody = TextView(ctx).apply {
                text = rawText
                setTextColor(if (isScheduled || isPastEvent) Theme.DIM else Theme.TEXT)
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            }
            card.addView(lblBody)

            // Parsed Chips Row: [TYPE] [CODE] [DATE TIME] [ROOM ?]
            val chipRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 4))
            }

            val typeColor = if (isPastEvent) Theme.DIM else Theme.typeColor(parsed.type)
            chipRow.addView(createChipView(ctx, "[${parsed.type.name}]", typeColor))
            if (parsed.code.isNotEmpty()) {
                val codeColor = if (isPastEvent) Theme.DIM else Theme.INFO
                chipRow.addView(createChipView(ctx, "[${parsed.code}]", codeColor))
            }
            if (parsed.dateMillis != null) {
                val sdfTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
                val dtChipStr = when (temporal) {
                    NoticeParser.TemporalStatus.LIVE -> "[LIVE · ${sdfTime.format(parsed.dateMillis)}]"
                    NoticeParser.TemporalStatus.TODAY -> "[TODAY · ${sdfTime.format(parsed.dateMillis)}]"
                    NoticeParser.TemporalStatus.PAST -> "[PAST · ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(parsed.dateMillis)}]"
                    else -> "[${SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault()).format(parsed.dateMillis)}]"
                }
                val dtColor = when (temporal) {
                    NoticeParser.TemporalStatus.LIVE -> Theme.INFO
                    NoticeParser.TemporalStatus.TODAY -> Theme.WARN
                    NoticeParser.TemporalStatus.PAST -> Theme.DIM
                    else -> Theme.TEXT
                }
                chipRow.addView(createChipView(ctx, dtChipStr, dtColor))
            }
            if (parsed.room.isNotEmpty()) {
                val roomLabel = if (parsed.roomConfidence == NoticeParser.Confidence.GUESS) "${parsed.room} ?" else parsed.room
                val roomCol = if (isPastEvent) Theme.DIM else if (parsed.roomConfidence == NoticeParser.Confidence.GUESS) Theme.WARN else Theme.TEXT
                chipRow.addView(createChipView(ctx, "[$roomLabel]", roomCol))
            }
            card.addView(chipRow)

            // Student Record Section (if personal marksheet/row matched from PDF)
            val recordObj = m.optJSONObject("student_record")
            if (recordObj != null) {
                val rSummary = recordObj.optString("summary", "")
                val rKey = recordObj.optString("matched_key", "")
                if (rSummary.isNotEmpty()) {
                    val recordBox = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Theme.CARD_DIM)
                        setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                        background = Theme.borderDrawable(Theme.CARD_DIM, Theme.OK, borderWidthPx = 2)
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, dp(ctx, 3), 0, dp(ctx, 5))
                        }
                        layoutParams = lp
                    }

                    val lblHeader = TextView(ctx).apply {
                        text = "STUDENT RECORD [REG: $rKey]"
                        setTextColor(Theme.OK)
                        textSize = 8.5f
                        typeface = Typeface.MONOSPACE
                        setPadding(0, 0, 0, dp(ctx, 2))
                    }
                    recordBox.addView(lblHeader)

                    val lblSummary = TextView(ctx).apply {
                        text = rSummary
                        setTextColor(Theme.TEXT)
                        textSize = 9.5f
                        typeface = Typeface.MONOSPACE
                    }
                    recordBox.addView(lblSummary)
                    card.addView(recordBox)
                }
            }

            // Attachment Section (if notice has a document or media attachment)
            val attachObj = m.optJSONObject("attachment")
            if (attachObj != null) {
                val aName = attachObj.optString("name", "Document")
                val aPath = attachObj.optString("path", "")
                val aMime = attachObj.optString("mime_type", "application/octet-stream")
                val aSizeStr = attachObj.optString("size_str", "")

                val attachContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Theme.BTN)
                    setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(ctx, 4), 0, dp(ctx, 6))
                    }
                    layoutParams = lp
                }

                val lblAttach = TextView(ctx).apply {
                    val sizeTag = if (aSizeStr.isNotEmpty()) " ($aSizeStr)" else ""
                    val isPdf = aMime.contains("pdf", ignoreCase = true) || aName.endsWith(".pdf", ignoreCase = true)
                    val icon = if (isPdf) "📄 [PDF]" else "📎 [MEDIA]"
                    text = "$icon $aName$sizeTag"
                    setTextColor(Theme.INFO)
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 0, 0, dp(ctx, 4))
                }
                attachContainer.addView(lblAttach)

                val attachBtnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

                val btnOpen = Button(ctx).apply {
                    text = "[ OPEN ]"
                    setTextColor(Theme.OK)
                    setBackgroundColor(Theme.CARD)
                    textSize = 8.5f
                    typeface = Typeface.MONOSPACE
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    includeFontPadding = false
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, dp(ctx, 6), 0)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        openAttachment(ctx, aPath, aMime)
                    }
                }
                attachBtnRow.addView(btnOpen)

                val btnSave = Button(ctx).apply {
                    text = "[ SAVE TO DOWNLOADS ]"
                    setTextColor(Theme.WARN)
                    setBackgroundColor(Theme.CARD)
                    textSize = 8.5f
                    typeface = Typeface.MONOSPACE
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    includeFontPadding = false
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    setOnClickListener {
                        saveAttachmentToDownloads(ctx, aPath, aName)
                    }
                }
                attachBtnRow.addView(btnSave)
                attachContainer.addView(attachBtnRow)
                card.addView(attachContainer)
            }

            // Action: [ REVIEW -> ] (Hidden if already scheduled)
            if (!isScheduled) {
                val btnReview = Button(ctx).apply {
                    text = "[ REVIEW -> ]"
                    setTextColor(Theme.INFO)
                    setBackgroundColor(Theme.BTN)
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 10), dp(ctx, 2))
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.END
                    }
                    layoutParams = lp
                    setOnClickListener {
                        openReviewForm(parsed, nId)
                    }
                }
                card.addView(btnReview)
            }

            mainScrollContent?.addView(card)
        }

        if (renderedCount == 0) {
            showEmptyMessage("No matching notices found.")
        }
    }

    private fun createChipView(ctx: Context, text: String, color: Int): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(ctx, 4), 0)
        }
    }

    private fun showNoticeContextMenu(ctx: Context, noticeId: Long, rawText: String, parsed: NoticeParser.ParsedNotice) {
        val options = arrayOf("Copy Text", "Ignore Notice", "Review & Schedule")
        AlertDialog.Builder(ctx)
            .setTitle("Notice Actions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText("Notice", rawText))
                    }
                    1 -> {
                        ignoredNoticeIds.add(noticeId)
                        saveState()
                        renderActiveView()
                    }
                    2 -> openReviewForm(parsed, noticeId)
                }
            }
            .show()
    }

    // --- AGENDA TAB RENDERING (GROUPED CHRONOLOGICALLY) ---
    private fun renderAgendaTab() {
        val ctx = hostRef?.context ?: return
        val allEvents = loadEventsList().sortedBy { it.startMillis }

        if (allEvents.isEmpty()) {
            showEmptyMessage("Agenda is empty. Convert a notice or tap +NEW.")
            return
        }

        // Semester progress bar
        val semBar = computeSemesterProgress()
        val lblSem = TextView(ctx).apply {
            text = "Semester $semBar"
            setTextColor(Theme.DIM)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        mainScrollContent?.addView(lblSem)

        val now = Calendar.getInstance()
        val todayMid = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }
        val tomorrowMid = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }
        val weekEnd = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }

        val todayList = ArrayList<EventItem>()
        val tomorrowList = ArrayList<EventItem>()
        val weekList = ArrayList<EventItem>()
        val laterList = ArrayList<EventItem>()
        val archiveList = ArrayList<EventItem>()

        for (ev in allEvents) {
            val c = Calendar.getInstance().apply { timeInMillis = ev.startMillis }
            when {
                c.before(now) && !isSameDay(c, now) -> archiveList.add(ev)
                isSameDay(c, now) -> todayList.add(ev)
                isSameDay(c, (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }) -> tomorrowList.add(ev)
                c.before(weekEnd) -> weekList.add(ev)
                else -> laterList.add(ev)
            }
        }

        fun renderGroup(title: String, list: List<EventItem>) {
            if (list.isEmpty()) return
            val secHdr = TextView(ctx).apply {
                text = title
                setTextColor(Theme.DIM)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
            }
            mainScrollContent?.addView(secHdr)

            for (ev in list) {
                renderAgendaCard(ctx, ev)
            }
        }

        renderGroup("TODAY", todayList)
        renderGroup("TOMORROW", tomorrowList)
        renderGroup("THIS WEEK", weekList)
        renderGroup("LATER", laterList)

        // Collapsed Archive Section
        if (archiveList.isNotEmpty()) {
            val btnArchiveToggle = Button(ctx).apply {
                text = if (isArchivedExpanded) "▼ ARCHIVED (${archiveList.size})" else "▶ ARCHIVED (${archiveList.size})"
                setTextColor(Theme.DIM)
                setBackgroundColor(Theme.BTN)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(ctx, 8), 0, dp(ctx, 4))
                }
                layoutParams = lp
                setOnClickListener {
                    isArchivedExpanded = !isArchivedExpanded
                    renderActiveView()
                }
            }
            mainScrollContent?.addView(btnArchiveToggle)

            if (isArchivedExpanded) {
                for (ev in archiveList) {
                    renderAgendaCard(ctx, ev, isPast = true)
                }
            }
        }
    }

    private fun isSameDay(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun renderAgendaCard(ctx: Context, ev: EventItem, isPast: Boolean = false) {
        val nowMillis = System.currentTimeMillis()
        val evEnd = ev.startMillis + (if (ev.type == EventType.LAB || ev.type == EventType.EXAM) 7200_000L else 3600_000L)
        val isExpiredToday = !isPast && (nowMillis >= evEnd)
        val isLiveNow = !isPast && !isExpiredToday && (nowMillis in ev.startMillis until evEnd)
        val isMuted = isPast || isExpiredToday

        val typeColor = when {
            isMuted -> Theme.DIM
            isLiveNow -> Theme.INFO
            else -> Theme.typeColor(ev.type)
        }
        val cardBg = if (isMuted) Theme.CARD_DIM else Theme.CARD

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cardBg)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            background = Theme.borderDrawable(cardBg, typeColor, borderWidthPx = if (isMuted) 2 else 3)
            if (isMuted) alpha = 0.78f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(ctx, 6))
            }
            layoutParams = lp
            setOnClickListener {
                // Edit event in place
                openReviewForm(null, ev.sourceNoticeId, existingEvent = ev)
            }
            setOnLongClickListener {
                showDeleteEventDialog(ctx, ev)
                true
            }
        }

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val sdfTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val lblTimeType = TextView(ctx).apply {
            val repeatTag = if (ev.isWeeklyRepeat) " ↻ weekly" else ""
            val statusPrefix = when {
                isLiveNow -> "⚡ "
                isExpiredToday -> "✓ "
                else -> "▌"
            }
            text = "$statusPrefix${sdfTime.format(ev.startMillis)} · ${ev.type.name}$repeatTag"
            setTextColor(typeColor)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(lblTimeType)

        val diff = ev.startMillis - nowMillis
        val lblDiff = TextView(ctx).apply {
            text = when {
                isPast -> "PAST"
                isExpiredToday -> "DONE"
                isLiveNow -> "LIVE NOW"
                else -> "in " + formatCountdown(diff)
            }
            setTextColor(if (isLiveNow) Theme.INFO else Theme.DIM)
            textSize = 9f
            typeface = Typeface.MONOSPACE
        }
        row1.addView(lblDiff)
        card.addView(row1)

        val lblDetails = TextView(ctx).apply {
            val codeStr = if (ev.code.isNotEmpty()) "${ev.code} · " else ""
            val roomStr = if (ev.room.isNotEmpty()) " · ${ev.room}" else ""
            text = "▌$codeStr${ev.title}$roomStr"
            setTextColor(if (isMuted) Theme.DIM else Theme.TEXT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        card.addView(lblDetails)

        mainScrollContent?.addView(card)
    }

    private fun showDeleteEventDialog(ctx: Context, ev: EventItem) {
        AlertDialog.Builder(ctx)
            .setTitle("Delete Event?")
            .setMessage("Remove '${ev.title}' from agenda?")
            .setPositiveButton("Delete") { _, _ ->
                deleteEventById(ev.id)
                refreshTabsVisual()
                renderActiveView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- REVIEW & APPROVE FORM ---
    private fun buildReviewFormView(ctx: Context): LinearLayout {
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.CARD)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            visibility = View.GONE
        }

        val lblSource = TextView(ctx).apply {
            setTextColor(Theme.DIM)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        form.addView(lblSource)

        // Type row
        val lblType = TextView(ctx).apply {
            text = "TYPE"
            setTextColor(Theme.DIM)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(ctx, 2))
        }
        form.addView(lblType)

        val typeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in EventType.values()) {
            val b = Button(ctx).apply {
                text = t.name
                textSize = 9f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(ctx, 1), 0, dp(ctx, 1), 0)
                }
                setOnClickListener {
                    formType = t
                    refreshFormTypeButtons()
                    checkConflicts()
                }
            }
            typeBtnMap[t] = b
            typeRow.addView(b)
        }
        form.addView(typeRow)

        // Exam Subtype row (visible only if EXAM)
        formExamSubtypeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
        }
        val lblSubtype = TextView(ctx).apply {
            text = "EXAM SUBTYPE"
            setTextColor(Theme.DIM)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
        }
        formExamSubtypeRow?.addView(lblSubtype)

        val subRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val subtypes = listOf("Class Test / TT", "Mid", "Final")
        for (st in subtypes) {
            val b = Button(ctx).apply {
                text = st
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(ctx, 1), 0, dp(ctx, 1), 0)
                }
                setOnClickListener {
                    formExamSubtype = st
                    refreshExamSubtypeButtons()
                }
            }
            examSubtypeBtnMap[st] = b
            subRow.addView(b)
        }
        formExamSubtypeRow?.addView(subRow)
        form.addView(formExamSubtypeRow)

        // Weekly repeat toggle for CLASS
        formBtnRepeat = Button(ctx).apply {
            text = "REPEAT WEEKLY: [OFF]"
            setTextColor(Theme.DIM)
            setBackgroundColor(Theme.BTN)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setOnClickListener {
                formWeeklyRepeat = !formWeeklyRepeat
                text = if (formWeeklyRepeat) "REPEAT WEEKLY: [ON ↻]" else "REPEAT WEEKLY: [OFF]"
                setTextColor(if (formWeeklyRepeat) Theme.OK else Theme.DIM)
            }
        }
        form.addView(formBtnRepeat)

        // Details Section (Auto-filled with badges)
        val lblDetails = TextView(ctx).apply {
            text = "DETAILS"
            setTextColor(Theme.DIM)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
        }
        form.addView(lblDetails)

        val detailsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        }

        // Title row with badge
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        formInputTitle = EditText(ctx).apply {
            hint = "Title / Event name..."
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    lblBadgeTitle?.visibility = View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        titleRow.addView(formInputTitle)
        lblBadgeTitle = createBadgeView(ctx)
        titleRow.addView(lblBadgeTitle)
        detailsCard.addView(titleRow)

        // Code row with badge
        val codeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        formInputCode = EditText(ctx).apply {
            hint = "Code (e.g. CSE 0541 1143)"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    lblBadgeCode?.visibility = View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        codeRow.addView(formInputCode)
        lblBadgeCode = createBadgeView(ctx)
        codeRow.addView(lblBadgeCode)
        detailsCard.addView(codeRow)

        // Room row with badge
        val roomRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        formInputRoom = EditText(ctx).apply {
            hint = "Room / Venue"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    lblBadgeRoom?.visibility = View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        roomRow.addView(formInputRoom)
        lblBadgeRoom = createBadgeView(ctx)
        roomRow.addView(lblBadgeRoom)
        detailsCard.addView(roomRow)

        // Date and Time Row with badge
        val dtRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        formBtnDate = Button(ctx).apply {
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Theme.INFO)
            setBackgroundColor(Theme.BTN)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                setMargins(0, 0, dp(ctx, 2), 0)
            }
            setOnClickListener { showDatePickerDialog(ctx) }
        }
        dtRow.addView(formBtnDate)

        formBtnTime = Button(ctx).apply {
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Theme.INFO)
            setBackgroundColor(Theme.BTN)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(ctx, 2), 0, dp(ctx, 2), 0)
            }
            setOnClickListener { showTimePickerDialog(ctx) }
        }
        dtRow.addView(formBtnTime)

        lblBadgeDate = createBadgeView(ctx)
        dtRow.addView(lblBadgeDate)
        detailsCard.addView(dtRow)

        form.addView(detailsCard)

        // Alarm Row
        val lblAlarm = TextView(ctx).apply {
            text = "ALARM BEFORE"
            setTextColor(Theme.DIM)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
        }
        form.addView(lblAlarm)

        val alarmRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val alarmOptions = listOf(0 to "0", 15 to "15m", 30 to "30m", 60 to "1h", 1440 to "1d")
        for ((mins, label) in alarmOptions) {
            val b = Button(ctx).apply {
                text = label
                textSize = 9f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(ctx, 1), 0, dp(ctx, 1), 0)
                }
                setOnClickListener {
                    formAlarmOffset = mins
                    refreshAlarmButtons()
                }
            }
            alarmBtnMap[mins] = b
            alarmRow.addView(b)
        }
        form.addView(alarmRow)

        // Conflict warning view
        formLblConflict = TextView(ctx).apply {
            setTextColor(Theme.WARN)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
            visibility = View.GONE
        }
        form.addView(formLblConflict)

        // Discard vs Approve Action Buttons
        val actRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        val btnDiscard = Button(ctx).apply {
            text = "[ DISCARD ]"
            setTextColor(Theme.ERR)
            setBackgroundColor(Theme.BTN)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(ctx, 3), 0)
            }
            setOnClickListener {
                isFormOpen = false
                form.visibility = View.GONE
                renderActiveView()
            }
        }
        actRow.addView(btnDiscard)

        val btnApprove = Button(ctx).apply {
            text = "[ APPROVE & SCHEDULE ]"
            setTextColor(Theme.BG)
            setBackgroundColor(Theme.OK)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                setMargins(dp(ctx, 3), 0, 0, 0)
            }
            setOnClickListener {
                saveFormEvent(ctx)
            }
        }
        actRow.addView(btnApprove)
        form.addView(actRow)

        return form
    }

    private fun createBadgeView(ctx: Context): TextView {
        return TextView(ctx).apply {
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), 0, 0, 0)
            visibility = View.GONE
        }
    }

    private fun setBadge(view: TextView?, conf: NoticeParser.Confidence) {
        if (view == null) return
        when (conf) {
            NoticeParser.Confidence.SURE -> {
                view.text = "✓ sure"
                view.setTextColor(Theme.OK)
                view.visibility = View.VISIBLE
            }
            NoticeParser.Confidence.GUESS -> {
                view.text = "~ guess"
                view.setTextColor(Theme.WARN)
                view.visibility = View.VISIBLE
            }
            NoticeParser.Confidence.NONE -> {
                view.visibility = View.GONE
            }
        }
    }

    private fun openReviewForm(parsed: NoticeParser.ParsedNotice?, noticeId: Long?, existingEvent: EventItem? = null) {
        val form = reviewFormCard ?: return
        editingEventId = existingEvent?.id
        formNoticeId = existingEvent?.sourceNoticeId ?: (noticeId ?: 0L)
        formSourceText = existingEvent?.notes ?: (parsed?.rawNotice ?: "")

        val lblSource = form.getChildAt(0) as? TextView
        if (formSourceText.isNotEmpty()) {
            lblSource?.text = "Source: ${formSourceText.take(80)}..."
            lblSource?.visibility = View.VISIBLE
        } else {
            lblSource?.visibility = View.GONE
        }

        if (existingEvent != null) {
            formType = existingEvent.type
            formInputTitle?.setText(existingEvent.title)
            formInputCode?.setText(existingEvent.code)
            formInputRoom?.setText(existingEvent.room)
            formCalendar.timeInMillis = existingEvent.startMillis
            formAlarmOffset = existingEvent.alarmOffsetMins
            formWeeklyRepeat = existingEvent.isWeeklyRepeat

            lblBadgeTitle?.visibility = View.GONE
            lblBadgeCode?.visibility = View.GONE
            lblBadgeRoom?.visibility = View.GONE
            lblBadgeDate?.visibility = View.GONE
        } else if (parsed != null) {
            formType = parsed.type
            formInputTitle?.setText(parsed.title)
            formInputCode?.setText(parsed.code)
            formInputRoom?.setText(parsed.room)
            if (parsed.dateMillis != null) {
                formCalendar.timeInMillis = parsed.dateMillis
            }
            formAlarmOffset = 30
            formWeeklyRepeat = false

            setBadge(lblBadgeTitle, parsed.titleConfidence)
            setBadge(lblBadgeCode, parsed.codeConfidence)
            setBadge(lblBadgeRoom, parsed.roomConfidence)
            setBadge(lblBadgeDate, parsed.dateConfidence)
        }

        refreshFormTypeButtons()
        refreshExamSubtypeButtons()
        refreshAlarmButtons()
        updateFormDateTimeButtons()
        checkConflicts()

        isFormOpen = true
        form.visibility = View.VISIBLE
    }

    private fun refreshFormTypeButtons() {
        for ((t, btn) in typeBtnMap) {
            if (t == formType) {
                btn.setBackgroundColor(Theme.typeColor(t))
                btn.setTextColor(Theme.BG)
                btn.text = "${t.name}*"
            } else {
                btn.setBackgroundColor(Theme.BTN)
                btn.setTextColor(Theme.DIM)
                btn.text = t.name
            }
        }
        formExamSubtypeRow?.visibility = if (formType == EventType.EXAM) View.VISIBLE else View.GONE
        formBtnRepeat?.visibility = if (formType == EventType.CLASS) View.VISIBLE else View.GONE
    }

    private fun refreshExamSubtypeButtons() {
        for ((st, btn) in examSubtypeBtnMap) {
            if (st == formExamSubtype) {
                btn.setBackgroundColor(Theme.ERR)
                btn.setTextColor(Theme.BG)
                btn.text = "$st*"
            } else {
                btn.setBackgroundColor(Theme.BTN)
                btn.setTextColor(Theme.DIM)
                btn.text = st
            }
        }
    }

    private fun refreshAlarmButtons() {
        for ((mins, btn) in alarmBtnMap) {
            val label = when (mins) {
                0 -> "0"; 15 -> "15m"; 30 -> "30m"; 60 -> "1h"; else -> "1d"
            }
            if (mins == formAlarmOffset) {
                btn.setBackgroundColor(Theme.WARN)
                btn.setTextColor(Theme.BG)
                btn.text = "$label*"
            } else {
                btn.setBackgroundColor(Theme.BTN)
                btn.setTextColor(Theme.DIM)
                btn.text = label
            }
        }
    }

    private fun updateFormDateTimeButtons() {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
        formBtnDate?.text = sdfDate.format(formCalendar.time)
        formBtnTime?.text = sdfTime.format(formCalendar.time)
    }

    private fun showDatePickerDialog(ctx: Context) {
        DatePickerDialog(
            ctx,
            { _, year, month, day ->
                formCalendar.set(Calendar.YEAR, year)
                formCalendar.set(Calendar.MONTH, month)
                formCalendar.set(Calendar.DAY_OF_MONTH, day)
                lblBadgeDate?.visibility = View.GONE
                updateFormDateTimeButtons()
                checkConflicts()
            },
            formCalendar.get(Calendar.YEAR),
            formCalendar.get(Calendar.MONTH),
            formCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePickerDialog(ctx: Context) {
        TimePickerDialog(
            ctx,
            { _, hour, min ->
                formCalendar.set(Calendar.HOUR_OF_DAY, hour)
                formCalendar.set(Calendar.MINUTE, min)
                lblBadgeDate?.visibility = View.GONE
                updateFormDateTimeButtons()
                checkConflicts()
            },
            formCalendar.get(Calendar.HOUR_OF_DAY),
            formCalendar.get(Calendar.MINUTE),
            false
        ).show()
    }

    private fun checkConflicts() {
        val chosenStart = formCalendar.timeInMillis
        val events = loadEventsList()
        val conflict = events.firstOrNull { ev ->
            ev.id != editingEventId && Math.abs(ev.startMillis - chosenStart) < 3_600_000L
        }

        if (conflict != null) {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            formLblConflict?.text = "⚠ Conflict: ${conflict.title} (${sdf.format(conflict.startMillis)})"
            formLblConflict?.visibility = View.VISIBLE
        } else {
            formLblConflict?.visibility = View.GONE
        }
    }

    private fun saveFormEvent(ctx: Context) {
        val title = formInputTitle?.text?.toString()?.trim()?.ifEmpty { "Event" } ?: "Event"
        val code = formInputCode?.text?.toString()?.trim() ?: ""
        val room = formInputRoom?.text?.toString()?.trim() ?: ""
        val startMillis = formCalendar.timeInMillis

        val eventItem = EventItem(
            id = editingEventId ?: UUID.randomUUID().toString(),
            sourceNoticeId = formNoticeId,
            type = formType,
            title = if (formType == EventType.EXAM && !title.contains(formExamSubtype)) "$title ($formExamSubtype)" else title,
            code = code,
            room = room,
            startMillis = startMillis,
            alarmOffsetMins = formAlarmOffset,
            isWeeklyRepeat = formWeeklyRepeat,
            notes = formSourceText
        )

        upsertEvent(eventItem)

        // System Calendar Intent
        try {
            val eventTitle = if (code.isNotEmpty()) "[${eventItem.type}] $code: ${eventItem.title}" else "[${eventItem.type}] ${eventItem.title}"
            val calIntent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, eventTitle)
                putExtra(CalendarContract.Events.EVENT_LOCATION, room)
                putExtra(CalendarContract.Events.DESCRIPTION, formSourceText)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3_600_000L)
                if (formWeeklyRepeat) {
                    putExtra(CalendarContract.Events.RRULE, "FREQ=WEEKLY")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(calIntent)
        } catch (_: Throwable) {}

        // System Alarm Clock Intent
        if (formAlarmOffset > 0) {
            try {
                val alarmTime = startMillis - (formAlarmOffset * 60_000L)
                val cal = Calendar.getInstance().apply { timeInMillis = alarmTime }
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    val msg = if (code.isNotEmpty()) "$code: ${eventItem.title}" else eventItem.title
                    putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                    putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                    putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(alarmIntent)
            } catch (_: Throwable) {}
        }

        isFormOpen = false
        reviewFormCard?.visibility = View.GONE
        switchTab(Tab.AGENDA)
    }

    // --- DATA REPOSITORIES (ATOMIC WRITES & BACKWARD COMPATIBILITY) ---
    private fun loadNoticesJson(): JSONObject? {
        val f = File(PATH_NOTICES)
        if (!f.isFile) return null
        return try {
            JSONObject(f.readText())
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadEventsList(): List<EventItem> {
        val f = File(PATH_EVENTS)
        if (!f.isFile) return emptyList()
        val list = ArrayList<EventItem>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "").ifEmpty { UUID.randomUUID().toString() }
                val srcId = o.optLong("source_notice_id", o.optLong("sourceNoticeId", 0L))
                val typeStr = o.optString("type", "EVENT")
                val type = try { EventType.valueOf(typeStr) } catch (_: Throwable) { EventType.EVENT }
                list.add(
                    EventItem(
                        id = id,
                        sourceNoticeId = srcId,
                        type = type,
                        title = o.optString("title", "Untitled"),
                        code = o.optString("code", ""),
                        room = o.optString("room", ""),
                        startMillis = o.optLong("start_millis", o.optLong("startMillis", 0L)),
                        alarmOffsetMins = o.optInt("alarm_offset", o.optInt("alarmOffsetMins", 30)),
                        isWeeklyRepeat = o.optBoolean("is_weekly_repeat", o.optBoolean("isWeeklyRepeat", false)),
                        notes = o.optString("notes", "")
                    )
                )
            }
        } catch (_: Throwable) {}
        return list
    }

    private fun ensureEventIds() {
        val f = File(PATH_EVENTS)
        if (!f.isFile) return
        try {
            val arr = JSONArray(f.readText())
            var modified = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.has("id")) {
                    o.put("id", UUID.randomUUID().toString())
                    modified = true
                }
            }
            if (modified) {
                atomicWriteText(f, arr.toString(2))
            }
        } catch (_: Throwable) {}
    }

    private fun upsertEvent(item: EventItem) {
        val current = loadEventsList().filter { it.id != item.id }.toMutableList()
        current.add(item)
        saveEventsList(current)
    }

    private fun deleteEventById(id: String) {
        val current = loadEventsList().filter { it.id != id }
        saveEventsList(current)
    }

    private fun saveEventsList(items: List<EventItem>) {
        val arr = JSONArray()
        for (ev in items) {
            val o = JSONObject().apply {
                put("id", ev.id)
                put("source_notice_id", ev.sourceNoticeId)
                put("type", ev.type.name)
                put("title", ev.title)
                put("code", ev.code)
                put("room", ev.room)
                put("start_millis", ev.startMillis)
                put("alarm_offset", ev.alarmOffsetMins)
                put("is_weekly_repeat", ev.isWeeklyRepeat)
                put("notes", ev.notes)
            }
            arr.put(o)
        }
        atomicWriteText(File(PATH_EVENTS), arr.toString(2))
    }

    private fun getScheduledNoticeIds(): Set<Long> {
        val set = HashSet<Long>()
        for (ev in loadEventsList()) {
            if (ev.sourceNoticeId > 0L) set.add(ev.sourceNoticeId)
        }
        return set
    }

    private fun getNextUpcomingEvent(): EventItem? {
        val now = System.currentTimeMillis()
        val list = loadEventsList()
        val ongoing = list.firstOrNull { ev ->
            val end = ev.startMillis + (if (ev.type == EventType.LAB || ev.type == EventType.EXAM) 7200_000L else 3600_000L)
            now in ev.startMillis until end
        }
        if (ongoing != null) return ongoing
        return list
            .filter { it.startMillis > now }
            .minByOrNull { it.startMillis }
    }

    private fun loadState() {
        val f = File(PATH_STATE)
        if (!f.isFile) return
        try {
            val json = JSONObject(f.readText())
            lastSeenTs = json.optLong("last_seen_ts", 0L)
            val arr = json.optJSONArray("ignored_notice_ids") ?: JSONArray()
            ignoredNoticeIds.clear()
            for (i in 0 until arr.length()) {
                ignoredNoticeIds.add(arr.getLong(i))
            }
        } catch (_: Throwable) {}
    }

    private fun saveState() {
        try {
            val json = JSONObject().apply {
                put("last_seen_ts", lastSeenTs)
                val arr = JSONArray()
                ignoredNoticeIds.forEach { arr.put(it) }
                put("ignored_notice_ids", arr)
            }
            atomicWriteText(File(PATH_STATE), json.toString(2))
        } catch (_: Throwable) {}
    }

    private fun markAllNoticesSeen() {
        val notices = loadNoticesJson() ?: return
        var maxTs = lastSeenTs
        val off = notices.optJSONObject("official_notices")?.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until off.length()) {
            maxTs = Math.max(maxTs, off.getJSONObject(i).optLong("timestamp", 0L))
        }
        val sch = notices.optJSONObject("class_schedule")?.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until sch.length()) {
            maxTs = Math.max(maxTs, sch.getJSONObject(i).optLong("timestamp", 0L))
        }
        if (maxTs > lastSeenTs) {
            lastSeenTs = maxTs
            saveState()
        }
    }

    private fun computeUnreadCount(): Int {
        val notices = loadNoticesJson() ?: return 0
        var count = 0
        val off = notices.optJSONObject("official_notices")?.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until off.length()) {
            val m = off.getJSONObject(i)
            val nId = m.optLong("id", 0L)
            if (!ignoredNoticeIds.contains(nId) && m.optLong("timestamp", 0L) > lastSeenTs) {
                count++
            }
        }
        return count
    }

    private fun computeSemesterProgress(): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val start = sdf.parse(SEMESTER_START)?.time ?: 0L
            val end = sdf.parse(SEMESTER_END)?.time ?: 0L
            val now = System.currentTimeMillis()
            if (now < start) return "[░░░░░░░░░░] 0%"
            if (now > end) return "[██████████] 100%"
            val pct = (((now - start).toDouble() / (end - start).toDouble()) * 100).toInt().coerceIn(0, 100)
            val filled = pct / 10
            "[" + "█".repeat(filled) + "░".repeat(10 - filled) + "] $pct%"
        } catch (_: Throwable) {
            "[██████░░░░] 60%"
        }
    }

    private fun checkAutoRefresh() {
        val f = File(PATH_NOTICES)
        if (!f.isFile) {
            triggerBackgroundRefresh()
            return
        }
        try {
            val json = JSONObject(f.readText())
            val updated = json.optLong("updated_at", 0L)
            if (System.currentTimeMillis() - updated > 15 * 60_000L) {
                triggerBackgroundRefresh()
            }
        } catch (_: Throwable) {}
    }

    private fun triggerBackgroundRefresh() {
        hostRef?.executeCommand("termux -run ~/retui/wa_fetch.sh")
        mainHandler.postDelayed({
            updateUpdatedHeader()
            renderActiveView()
        }, 1200)
    }

    private fun updateUpdatedHeader() {
        val f = File(PATH_NOTICES)
        if (!f.isFile) {
            lblUpdatedHeader?.text = "not synced"
            return
        }
        try {
            val json = JSONObject(f.readText())
            val updated = json.optLong("updated_at", 0L)
            val diffMins = (System.currentTimeMillis() - updated) / 60_000L
            lblUpdatedHeader?.text = if (diffMins <= 0) "updated just now" else "updated ${diffMins}m ago"
        } catch (_: Throwable) {
            lblUpdatedHeader?.text = ""
        }
    }

    private fun atomicWriteText(target: File, content: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            tmp.renameTo(target)
        } catch (_: Throwable) {}
    }

    private fun showEmptyMessage(msg: String) {
        val ctx = hostRef?.context ?: return
        val tv = TextView(ctx).apply {
            text = msg
            setTextColor(Theme.DIM)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(ctx, 10), dp(ctx, 16), dp(ctx, 10), dp(ctx, 16))
        }
        mainScrollContent?.addView(tv)
    }

    private fun showProfileConfigDialog(ctx: Context) {
        val f = File("/sdcard/Re-T-UI/dex-modules/schedule/profile.json")
        var currentReg = "2025331543"
        try {
            if (f.isFile) currentReg = JSONObject(f.readText()).optString("registration_no", currentReg)
        } catch (_: Throwable) {}

        val input = EditText(ctx).apply {
            hint = "Registration No (e.g. 2025331543)"
            setText(currentReg)
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.DIM)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }

        AlertDialog.Builder(ctx)
            .setTitle("Student ID (Registration No)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newReg = input.text.toString().trim()
                try {
                    val json = JSONObject().apply {
                        put("registration_no", newReg)
                        put("roll_no", "")
                        put("name", "Shuvo")
                        put("batch", "19th")
                    }
                    atomicWriteText(f, json.toString(2))
                    showToast(ctx, "ID saved. Re-scanning notices...")
                    triggerBackgroundRefresh()
                } catch (e: Throwable) {
                    showToast(ctx, "Save failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAttachment(ctx: Context, path: String, mime: String) {
        val f = File(path)
        if (!f.exists()) {
            showToast(ctx, "File not found or deleted from storage.")
            return
        }
        try {
            try {
                val m = android.os.StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                m.invoke(null)
            } catch (_: Throwable) {
                android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(f), mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            val safePath = path.replace("'", "'\\''")
            hostRef?.executeCommand("termux -run ~/retui/open_file.sh '$safePath' '$mime'")
        }
    }

    private fun saveAttachmentToDownloads(ctx: Context, path: String, name: String) {
        val src = File(path)
        if (!src.exists()) {
            showToast(ctx, "Source file not found on storage.")
            return
        }
        try {
            val dlDir = File("/sdcard/Download")
            if (!dlDir.exists()) dlDir.mkdirs()
            val dest = File(dlDir, name)
            src.copyTo(dest, overwrite = true)
            showToast(ctx, "Saved to /sdcard/Download/$name")
            try {
                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest))
                ctx.sendBroadcast(scanIntent)
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            showToast(ctx, "Error saving: ${e.message}")
        }
    }

    private fun showToast(ctx: Context, msg: String) {
        mainHandler.post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        hostRef = null
    }
}
