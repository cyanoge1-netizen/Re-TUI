package com.cyanoge1.sampledex

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import ohi.andre.consolelauncher.dex.RetuiDexHost
import ohi.andre.consolelauncher.dex.RetuiDexModule

/**
 * Sample dynamic Kotlin/DEX dock module.
 * Demonstrates real native Android Views running inside the Re:T-UI module dock.
 */
class CyberSampleModule : RetuiDexModule {
    override val id: String = "cybersample"
    override val title: String = "CYBER NATIVE"

    private var hostRef: RetuiDexHost? = null

    override fun createView(host: RetuiDexHost, container: ViewGroup): View {
        hostRef = host
        val ctx = host.context

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
        }

        // Title row with Accent Color
        val titleView = TextView(ctx).apply {
            text = "⚡ NATIVE KOTLIN DEX LOADED ⚡"
            setTextColor(host.accentTextColor)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(titleView)

        // Status description
        val descView = TextView(ctx).apply {
            text = "Real Android Views inside Re:T-UI Dock!\nTest interactive controls below:"
            setTextColor(host.bodyTextColor)
            textSize = 12f
            setPadding(0, 8, 0, 12)
        }
        root.addView(descView)

        // Native EditText
        val input = EditText(ctx).apply {
            hint = "Type a command to run..."
            setTextColor(host.bodyTextColor)
            setHintTextColor(Color.DKGRAY)
            textSize = 13f
            setBackgroundColor(0x22FFFFFF)
            setPadding(16, 12, 16, 12)
        }
        root.addView(input)

        // Native ProgressBar
        val pbar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 75
            setPadding(0, 12, 0, 12)
        }
        root.addView(pbar)

        // Button action row
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }

        val btnRun = Button(ctx).apply {
            text = "▶ EXECUTE"
            setTextColor(host.accentTextColor)
            setBackgroundColor(0x3300E5FF)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val cmd = input.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    host.executeCommand(cmd)
                }
            }
        }
        btnRow.addView(btnRun)

        val btnClose = Button(ctx).apply {
            text = "✕ CLOSE"
            setTextColor(Color.RED)
            setBackgroundColor(0x33FF5555)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                host.closeModule()
            }
        }
        btnRow.addView(btnClose)

        root.addView(btnRow)
        return root
    }

    override fun onDestroy() {
        hostRef = null
    }
}
