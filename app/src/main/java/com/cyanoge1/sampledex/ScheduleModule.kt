package com.cyanoge1.sampledex

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ohi.andre.consolelauncher.dex.RetuiDexHost
import ohi.andre.consolelauncher.dex.RetuiDexModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Native Academic Schedule & WhatsApp Notice Hub for Re:T-UI.
 * Sits inside launcher dock with humane, sober, and serious cyberdeck styling.
 *
 * Features:
 * - Dual feed toggling: Official Notices vs Class Schedule groups
 * - Manual review & approval gate for converting notices into scheduled events
 * - Dynamic form fields tailored per event type (Exam, Class, Lab, Event)
 * - Native Android DatePickerDialog and TimePickerDialog
 * - Direct System Calendar & Alarm Clock integration
 * - Persistent Agenda database in /sdcard/Re-T-UI/dex-modules/schedule/events.json
 */
class ScheduleModule : RetuiDexModule {
    override val id: String = "schedule"
    override val title: String = "NOTICES // SCHEDULE"

    private var hostRef: RetuiDexHost? = null
    private val handler = Handler(Looper.getMainLooper())

    private enum class Tab { OFFICIAL, SCHEDULE, AGENDA }
    private var activeTab = Tab.OFFICIAL

    private enum class EventType { EXAM, CLASS, LAB, EVENT }
    private var selectedType = EventType.EXAM
    private var selectedExamSubtype = "Class Test / TT"

    // Form state
    private var isFormVisible = false
    private var pendingNoticeText: String = ""
    private var eventCalendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 10)
        set(Calendar.MINUTE, 30)
        set(Calendar.SECOND, 0)
    }
    private var alarmOffsetMinutes = 30

    // UI references
    private var rootLayout: LinearLayout? = null
    private var contentContainer: LinearLayout? = null
    private var formCard: LinearLayout? = null
    private var btnTabOfficial: Button? = null
    private var btnTabSchedule: Button? = null
    private var btnTabAgenda: Button? = null

    // Form inputs
    private var inputTitle: EditText? = null
    private var inputCode: EditText? = null
    private var inputRoom: EditText? = null
    private var btnDate: Button? = null
    private var btnTime: Button? = null
    private var lblNoticeQuote: TextView? = null

    private val typeButtons = HashMap<EventType, Button>()
    private val examSubtypeButtons = HashMap<String, Button>()
    private val alarmButtons = HashMap<Int, Button>()

    override fun createView(host: RetuiDexHost, container: ViewGroup): View {
        hostRef = host
        val ctx = host.context
        val accent = host.accentTextColor
        val textCol = host.bodyTextColor

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 12)
        }
        rootLayout = root

        // --- 1. Top Header Bar ---
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val lblTitle = TextView(ctx).apply {
            text = "NOTICES // AGENDA HUB"
            setTextColor(accent)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(lblTitle)

        val btnNew = Button(ctx).apply {
            text = "+ NEW"
            setTextColor(Color.parseColor("#50FA7B"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(8, 4, 8, 4)
            setOnClickListener {
                openEventForm(EventType.CLASS, "", "New Event", "", "")
            }
        }
        header.addView(btnNew)

        val btnRefresh = Button(ctx).apply {
            text = "REFRESH"
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(8, 4, 8, 4)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4, 0, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                host.executeCommand("termux -run ~/retui/wa_fetch.sh")
                handler.postDelayed({ renderActiveTab() }, 900)
            }
        }
        header.addView(btnRefresh)

        val btnClose = Button(ctx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF5555"))
            setBackgroundColor(Color.parseColor("#22FF5555"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(8, 4, 8, 4)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4, 0, 0, 0)
            }
            layoutParams = lp
            setOnClickListener { host.closeModule() }
        }
        header.addView(btnClose)
        root.addView(header)

        // --- 2. Segmented Navigation Tabs ---
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        btnTabOfficial = Button(ctx).apply {
            text = "NOTICES"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 2, 0)
            }
            setOnClickListener {
                activeTab = Tab.OFFICIAL
                isFormVisible = false
                refreshTabs(accent)
                renderActiveTab()
            }
        }
        tabRow.addView(btnTabOfficial)

        btnTabSchedule = Button(ctx).apply {
            text = "CLASS FEED"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                setMargins(2, 0, 2, 0)
            }
            setOnClickListener {
                activeTab = Tab.SCHEDULE
                isFormVisible = false
                refreshTabs(accent)
                renderActiveTab()
            }
        }
        tabRow.addView(btnTabSchedule)

        btnTabAgenda = Button(ctx).apply {
            text = "AGENDA"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                setMargins(2, 0, 0, 0)
            }
            setOnClickListener {
                activeTab = Tab.AGENDA
                isFormVisible = false
                refreshTabs(accent)
                renderActiveTab()
            }
        }
        tabRow.addView(btnTabAgenda)
        root.addView(tabRow)

        // --- 3. Dynamic Form Container (Review & Approve) ---
        formCard = buildFormView(ctx, accent, textCol)
        root.addView(formCard)

        // --- 4. Main Scrollable Content Container ---
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 210)
            )
            setBackgroundColor(Color.parseColor("#0E0D15"))
            setPadding(8, 8, 8, 8)
        }
        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(contentContainer)
        root.addView(scrollView)

        refreshTabs(accent)
        renderActiveTab()

        return root
    }

    private fun dp(ctx: Context, v: Int): Int {
        val d = ctx.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    private fun refreshTabs(accent: Int) {
        val darkBg = Color.parseColor("#1F1E2D")
        val mutedText = Color.parseColor("#8E8CA0")
        val activeText = Color.parseColor("#0A0A0F")

        fun applyTab(btn: Button?, isActive: Boolean) {
            if (isActive) {
                btn?.setBackgroundColor(accent)
                btn?.setTextColor(activeText)
            } else {
                btn?.setBackgroundColor(darkBg)
                btn?.setTextColor(mutedText)
            }
        }

        applyTab(btnTabOfficial, activeTab == Tab.OFFICIAL)
        applyTab(btnTabSchedule, activeTab == Tab.SCHEDULE)
        applyTab(btnTabAgenda, activeTab == Tab.AGENDA)

        // Update Agenda label with count
        val count = loadEvents().length()
        btnTabAgenda?.text = if (count > 0) "AGENDA ($count)" else "AGENDA"
    }

    private fun renderActiveTab() {
        val ctx = hostRef?.context ?: return
        val accent = hostRef?.accentTextColor ?: Color.CYAN
        val textCol = hostRef?.bodyTextColor ?: Color.WHITE

        formCard?.visibility = if (isFormVisible) View.VISIBLE else View.GONE
        contentContainer?.removeAllViews()

        when (activeTab) {
            Tab.OFFICIAL -> renderNoticeFeed(ctx, accent, textCol, isOfficial = true)
            Tab.SCHEDULE -> renderNoticeFeed(ctx, accent, textCol, isOfficial = false)
            Tab.AGENDA -> renderAgendaList(ctx, accent, textCol)
        }
    }

    // --- NOTICE FEED RENDERING ---
    private fun renderNoticeFeed(ctx: Context, accent: Int, textCol: Int, isOfficial: Boolean) {
        val noticesFile = File("/sdcard/Re-T-UI/dex-modules/schedule/notices.json")
        if (!noticesFile.isFile) {
            val emptyMsg = TextView(ctx).apply {
                text = "No notices synchronized yet.\nTap [REFRESH] to fetch from WhatsApp."
                setTextColor(Color.parseColor("#6272A4"))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(10, 16, 10, 16)
            }
            contentContainer?.addView(emptyMsg)
            return
        }

        try {
            val json = JSONObject(noticesFile.readText())
            val groupKey = if (isOfficial) "official_notices" else "class_schedule"
            val groupObj = json.optJSONObject(groupKey)
            val msgs = groupObj?.optJSONArray("messages") ?: JSONArray()

            if (msgs.length() == 0) {
                val emptyMsg = TextView(ctx).apply {
                    text = "No messages found in this group."
                    setTextColor(Color.parseColor("#6272A4"))
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setPadding(10, 16, 10, 16)
                }
                contentContainer?.addView(emptyMsg)
                return
            }

            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val text = m.optString("text", "")
                val timeStr = m.optString("time_str", "")

                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#151420"))
                    setPadding(10, 8, 10, 8)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 8)
                    }
                    layoutParams = lp
                }

                // Card Header Row
                val cardHdr = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, 4)
                }
                val lblMeta = TextView(ctx).apply {
                    text = timeStr
                    setTextColor(Color.parseColor("#6272A4"))
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                cardHdr.addView(lblMeta)

                val btnConvert = Button(ctx).apply {
                    text = "REVIEW & SCHEDULE"
                    setTextColor(Color.parseColor("#00E5FF"))
                    setBackgroundColor(Color.parseColor("#1E1D2D"))
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    setPadding(8, 2, 8, 2)
                    setOnClickListener {
                        val detectedType = if (isOfficial) {
                            if (text.contains("exam", true) || text.contains("test", true) || text.contains("tt", true)) EventType.EXAM else EventType.EVENT
                        } else {
                            if (text.contains("lab", true)) EventType.LAB else EventType.CLASS
                        }
                        val detectedCode = extractCourseCode(text)
                        openEventForm(detectedType, detectedCode, text.take(45), "", text)
                    }
                }
                cardHdr.addView(btnConvert)
                card.addView(cardHdr)

                // Message Text
                val lblMsg = TextView(ctx).apply {
                    this.text = text
                    setTextColor(textCol)
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                }
                card.addView(lblMsg)

                contentContainer?.addView(card)
            }
        } catch (e: Throwable) {
            val errView = TextView(ctx).apply {
                text = "Error reading notices: ${e.message}"
                setTextColor(Color.parseColor("#FF5555"))
                textSize = 10f
                typeface = Typeface.MONOSPACE
            }
            contentContainer?.addView(errView)
        }
    }

    private fun extractCourseCode(text: String): String {
        val re = Regex("\\b([A-Z]{2,4}\\s*[-_]?\\s*\\d{3})\\b", RegexOption.IGNORE_CASE)
        val match = re.find(text)
        return match?.value?.replace("\\s+".toRegex(), "")?.uppercase() ?: ""
    }

    // --- AGENDA LIST RENDERING ---
    private fun renderAgendaList(ctx: Context, accent: Int, textCol: Int) {
        val events = loadEvents()
        if (events.length() == 0) {
            val emptyMsg = TextView(ctx).apply {
                text = "Agenda is empty.\nConvert a notice or tap [+ NEW] to schedule an event."
                setTextColor(Color.parseColor("#6272A4"))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(10, 16, 10, 16)
            }
            contentContainer?.addView(emptyMsg)
            return
        }

        val sdf = SimpleDateFormat("EEE, MMM dd @ hh:mm a", Locale.getDefault())

        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val typeStr = ev.optString("type", "EVENT")
            val title = ev.optString("title", "Untitled")
            val code = ev.optString("code", "")
            val room = ev.optString("room", "")
            val startMillis = ev.optLong("start_millis", 0)

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#151420"))
                setPadding(10, 8, 10, 8)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 8)
                }
                layoutParams = lp
            }

            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val lblTag = TextView(ctx).apply {
                text = "[$typeStr]"
                setTextColor(when (typeStr) {
                    "EXAM" -> Color.parseColor("#FF5555")
                    "CLASS" -> Color.parseColor("#50FA7B")
                    "LAB" -> Color.parseColor("#F1FA8C")
                    else -> Color.parseColor("#BD93F9")
                })
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(lblTag)

            val btnDel = Button(ctx).apply {
                text = "✕"
                setTextColor(Color.parseColor("#FF5555"))
                setBackgroundColor(Color.TRANSPARENT)
                textSize = 10f
                setPadding(6, 0, 6, 0)
                setOnClickListener {
                    deleteEvent(i)
                    refreshTabs(accent)
                    renderActiveTab()
                }
            }
            row1.addView(btnDel)
            card.addView(row1)

            val lblMain = TextView(ctx).apply {
                val codePrefix = if (code.isNotEmpty()) "$code: " else ""
                text = "$codePrefix$title"
                setTextColor(textCol)
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
            }
            card.addView(lblMain)

            val lblTimeLoc = TextView(ctx).apply {
                val dtStr = if (startMillis > 0) sdf.format(startMillis) else "--"
                val roomStr = if (room.isNotEmpty()) " | Room: $room" else ""
                text = "$dtStr$roomStr"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, 2, 0, 0)
            }
            card.addView(lblTimeLoc)

            contentContainer?.addView(card)
        }
    }

    // --- FORM BUILDER (HUMANE, MONOSPACE & SOBER) ---
    private fun buildFormView(ctx: Context, accent: Int, textCol: Int): LinearLayout {
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111019"))
            setPadding(10, 8, 10, 8)
            visibility = View.GONE
        }

        val hdr = TextView(ctx).apply {
            text = "REVIEW & APPROVE EVENT"
            setTextColor(accent)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 6)
        }
        form.addView(hdr)

        // Type Selector Row
        val typeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 6)
        }
        val types = listOf(EventType.EXAM to "EXAM", EventType.CLASS to "CLASS", EventType.LAB to "LAB", EventType.EVENT to "EVENT")
        for ((t, label) in types) {
            val b = Button(ctx).apply {
                text = label
                textSize = 9f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(2, 0, 2, 0)
                }
                setOnClickListener {
                    selectedType = t
                    refreshTypeButtons(accent)
                }
            }
            typeButtons[t] = b
            typeRow.addView(b)
        }
        form.addView(typeRow)

        // Title Input
        inputTitle = EditText(ctx).apply {
            hint = "Title / Subject..."
            setTextColor(textCol)
            setHintTextColor(Color.parseColor("#6272A4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1A1926"))
            setPadding(8, 6, 8, 6)
        }
        form.addView(inputTitle)

        // Code & Room Row
        val codeRoomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        inputCode = EditText(ctx).apply {
            hint = "Code (e.g. CSE311)"
            setTextColor(textCol)
            setHintTextColor(Color.parseColor("#6272A4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1A1926"))
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 2, 0)
            }
        }
        codeRoomRow.addView(inputCode)

        inputRoom = EditText(ctx).apply {
            hint = "Room / Venue"
            setTextColor(textCol)
            setHintTextColor(Color.parseColor("#6272A4"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#1A1926"))
            setPadding(8, 6, 8, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 0, 0)
            }
        }
        codeRoomRow.addView(inputRoom)
        form.addView(codeRoomRow)

        // Date and Time Pickers Row
        val dtRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 4)
        }
        btnDate = Button(ctx).apply {
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 2, 0)
            }
            setOnClickListener { showDatePicker(ctx) }
        }
        dtRow.addView(btnDate)

        btnTime = Button(ctx).apply {
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 0, 0, 0)
            }
            setOnClickListener { showTimePicker(ctx) }
        }
        dtRow.addView(btnTime)
        form.addView(dtRow)
        updateDateTimeButtons()

        // Alarm Offset Row
        val alarmRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 4)
        }
        val offsets = listOf(0 to "No Alarm", 15 to "15m before", 30 to "30m before", 60 to "1h before")
        for ((mins, label) in offsets) {
            val b = Button(ctx).apply {
                text = label
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(1, 0, 1, 0)
                }
                setOnClickListener {
                    alarmOffsetMinutes = mins
                    refreshAlarmButtons(accent)
                }
            }
            alarmButtons[mins] = b
            alarmRow.addView(b)
        }
        form.addView(alarmRow)
        refreshAlarmButtons(accent)

        // Notice quote preview
        lblNoticeQuote = TextView(ctx).apply {
            text = ""
            setTextColor(Color.parseColor("#6272A4"))
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setPadding(4, 2, 4, 4)
            visibility = View.GONE
        }
        form.addView(lblNoticeQuote)

        // Action Buttons Row (Discard vs Approve)
        val actRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val btnDiscard = Button(ctx).apply {
            text = "DISCARD"
            setTextColor(Color.parseColor("#FF5555"))
            setBackgroundColor(Color.parseColor("#1F1E2D"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 3, 0)
            }
            setOnClickListener {
                isFormVisible = false
                renderActiveTab()
            }
        }
        actRow.addView(btnDiscard)

        val btnApprove = Button(ctx).apply {
            text = "APPROVE & SCHEDULE"
            setTextColor(Color.parseColor("#0A0A0F"))
            setBackgroundColor(Color.parseColor("#50FA7B"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                setMargins(3, 0, 0, 0)
            }
            setOnClickListener { approveAndSave(ctx) }
        }
        actRow.addView(btnApprove)
        form.addView(actRow)

        refreshTypeButtons(accent)
        return form
    }

    private fun openEventForm(type: EventType, code: String, title: String, room: String, noticeText: String) {
        val accent = hostRef?.accentTextColor ?: Color.CYAN
        selectedType = type
        inputCode?.setText(code)
        inputTitle?.setText(title)
        inputRoom?.setText(room)
        pendingNoticeText = noticeText

        if (noticeText.isNotEmpty()) {
            lblNoticeQuote?.text = "Source: ${noticeText.take(90)}..."
            lblNoticeQuote?.visibility = View.VISIBLE
        } else {
            lblNoticeQuote?.visibility = View.GONE
        }

        refreshTypeButtons(accent)
        updateDateTimeButtons()

        isFormVisible = true
        formCard?.visibility = View.VISIBLE
    }

    private fun refreshTypeButtons(accent: Int) {
        val darkBg = Color.parseColor("#1F1E2D")
        for ((t, btn) in typeButtons) {
            if (t == selectedType) {
                btn.setBackgroundColor(accent)
                btn.setTextColor(Color.parseColor("#0A0A0F"))
            } else {
                btn.setBackgroundColor(darkBg)
                btn.setTextColor(Color.parseColor("#8E8CA0"))
            }
        }
    }

    private fun refreshAlarmButtons(accent: Int) {
        val darkBg = Color.parseColor("#1F1E2D")
        for ((mins, btn) in alarmButtons) {
            if (mins == alarmOffsetMinutes) {
                btn.setBackgroundColor(Color.parseColor("#F1FA8C"))
                btn.setTextColor(Color.parseColor("#0A0A0F"))
            } else {
                btn.setBackgroundColor(darkBg)
                btn.setTextColor(Color.parseColor("#8E8CA0"))
            }
        }
    }

    private fun updateDateTimeButtons() {
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
        btnDate?.text = sdfDate.format(eventCalendar.time)
        btnTime?.text = sdfTime.format(eventCalendar.time)
    }

    private fun showDatePicker(ctx: Context) {
        DatePickerDialog(
            ctx,
            { _, year, month, day ->
                eventCalendar.set(Calendar.YEAR, year)
                eventCalendar.set(Calendar.MONTH, month)
                eventCalendar.set(Calendar.DAY_OF_MONTH, day)
                updateDateTimeButtons()
            },
            eventCalendar.get(Calendar.YEAR),
            eventCalendar.get(Calendar.MONTH),
            eventCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker(ctx: Context) {
        TimePickerDialog(
            ctx,
            { _, hour, min ->
                eventCalendar.set(Calendar.HOUR_OF_DAY, hour)
                eventCalendar.set(Calendar.MINUTE, min)
                updateDateTimeButtons()
            },
            eventCalendar.get(Calendar.HOUR_OF_DAY),
            eventCalendar.get(Calendar.MINUTE),
            false
        ).show()
    }

    private fun approveAndSave(ctx: Context) {
        val title = inputTitle?.text?.toString()?.trim()?.ifEmpty { "Event" } ?: "Event"
        val code = inputCode?.text?.toString()?.trim() ?: ""
        val room = inputRoom?.text?.toString()?.trim() ?: ""
        val startMillis = eventCalendar.timeInMillis

        // 1. Save to local Agenda JSON
        val ev = JSONObject().apply {
            put("type", selectedType.name)
            put("title", title)
            put("code", code)
            put("room", room)
            put("start_millis", startMillis)
            put("notes", pendingNoticeText)
            put("alarm_offset", alarmOffsetMinutes)
        }
        saveEvent(ev)

        // 2. Insert into System Calendar
        try {
            val eventTitle = if (code.isNotEmpty()) "[$selectedType] $code: $title" else "[$selectedType] $title"
            val calIntent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, eventTitle)
                putExtra(CalendarContract.Events.EVENT_LOCATION, room)
                putExtra(CalendarContract.Events.DESCRIPTION, pendingNoticeText)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3600000L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(calIntent)
        } catch (e: Throwable) {}

        // 3. Set System Alarm Clock
        if (alarmOffsetMinutes > 0) {
            try {
                val alarmMillis = startMillis - (alarmOffsetMinutes * 60000L)
                val cal = Calendar.getInstance().apply { timeInMillis = alarmMillis }
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    val msg = if (code.isNotEmpty()) "$code: $title" else title
                    putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                    putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                    putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(alarmIntent)
            } catch (e: Throwable) {}
        }

        // Close form and switch to Agenda view
        isFormVisible = false
        activeTab = Tab.AGENDA
        val accent = hostRef?.accentTextColor ?: Color.CYAN
        refreshTabs(accent)
        renderActiveTab()
    }

    // --- AGENDA PERSISTENCE ---
    private fun eventsFile(): File =
        File("/sdcard/Re-T-UI/dex-modules/schedule/events.json")

    private fun loadEvents(): JSONArray {
        val f = eventsFile()
        if (!f.isFile) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Throwable) {
            JSONArray()
        }
    }

    private fun saveEvent(ev: JSONObject) {
        val arr = loadEvents()
        arr.put(ev)
        try {
            eventsFile().parentFile?.mkdirs()
            eventsFile().writeText(arr.toString(2))
        } catch (e: Throwable) {}
    }

    private fun deleteEvent(index: Int) {
        val arr = loadEvents()
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            if (i != index) newArr.put(arr.get(i))
        }
        try {
            eventsFile().writeText(newArr.toString(2))
        } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        hostRef = null
    }
}
