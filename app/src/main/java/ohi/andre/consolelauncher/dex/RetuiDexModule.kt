package ohi.andre.consolelauncher.dex

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Public contract implemented by dynamically loaded Kotlin/DEX dock modules. */
interface RetuiDexModule {
    val id: String
    val title: String
    fun createView(host: RetuiDexHost, container: ViewGroup): View
    fun onAction(host: RetuiDexHost, action: String) {}
    fun onDestroy() {}
}

/** Host bridge exposed by Re:T-UI to dynamic Kotlin/DEX modules. */
interface RetuiDexHost {
    val context: Context
    val bodyTextColor: Int
    val accentTextColor: Int
    val bodyTextSizeSp: Int
    fun executeCommand(command: String)
    fun closeModule()
    fun refreshModule()
    fun runOnUiThread(action: Runnable)
}
