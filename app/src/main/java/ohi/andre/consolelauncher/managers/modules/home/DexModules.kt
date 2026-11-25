package ohi.andre.consolelauncher.managers.modules.home

import android.content.Context
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import ohi.andre.consolelauncher.R
import ohi.andre.consolelauncher.dex.RetuiDexHost
import ohi.andre.consolelauncher.managers.dex.DexModuleManager
import ohi.andre.consolelauncher.managers.modules.ModuleManager
import ohi.andre.consolelauncher.presentation.ModulePanelPresentation

/** Renders dynamically loaded Kotlin/DEX modules inside the Re:T-UI module dock panel. */
internal class DexModules(
    private val host: HomeModuleHost
) {
    fun showDexModuleIfSource(id: String?): Boolean {
        val source = ModuleManager.getModuleSource(host.context, id)
        if (!ModuleManager.isDexSource(source)) {
            return false
        }
        val dexId = ModuleManager.dexModuleId(source)
        val container = host.moduleContainer ?: return true

        val moduleView = ModulePanelPresentation.inflate(
            host.context,
            container,
            ModulePanelPresentation.Kind.TEXT
        )
        container.addView(moduleView)

        val label = moduleView.findViewById<TextView?>(R.id.module_text_label)
        val close = moduleView.findViewById<TextView?>(R.id.module_text_close)
        val scroll = moduleView.findViewById<ScrollView?>(R.id.module_text_border)
        val body = moduleView.findViewById<TextView?>(R.id.module_text_body)

        close?.let(host::bindCloseButton)

        val dexHost = object : RetuiDexHost {
            override val context: Context get() = host.context
            override val bodyTextColor: Int get() = host.bodyTextColor()
            override val accentTextColor: Int get() = host.accentTextColor()
            override val bodyTextSizeSp: Int get() = host.bodyTextSize()
            override fun executeCommand(command: String) = host.executeCommand(command)
            override fun closeModule() = host.closeModule()
            override fun refreshModule() = host.presentModule(id ?: dexId)
            override fun runOnUiThread(action: Runnable) {
                host.handler?.post(action)
            }
        }

        val loaded = DexModuleManager.load(host.context, dexId)
        loaded.onSuccess { mod ->
            label?.text = mod.title.ifBlank { ModuleManager.displayTitle(host.context, id) }
            label?.setOnClickListener {
                DexModuleManager.load(host.context, dexId, forceReload = true)
                host.presentModule(id ?: dexId)
            }
            if (scroll != null) {
                try {
                    val customView = mod.createView(dexHost, scroll)
                    scroll.removeAllViews()
                    scroll.addView(
                        customView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                } catch (t: Throwable) {
                    body?.apply {
                        setTextColor(host.bodyTextColor())
                        text = "DEX UI error: ${t.javaClass.simpleName}: ${t.message}"
                    }
                }
            }
        }.onFailure { err ->
            label?.text = DexModuleManager.getTitle(dexId) ?: dexId.uppercase()
            body?.apply {
                setTextColor(host.bodyTextColor())
                text = "DEX load error:\n${err.javaClass.simpleName}: ${err.message}"
            }
        }
        return true
    }
}
