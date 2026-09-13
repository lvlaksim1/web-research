package ru.evrasia.research

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.util.Locale

internal class BrowserWindowController(
    private val activity: AppCompatActivity,
    private val container: SwipeRefreshLayout,
    private val record: (JSONObject) -> Unit,
    private val configure: (WebView, String, String) -> Unit,
    private val onActivated: (WebView, String, String) -> Unit,
    private val onClosed: (String, WebView) -> Unit,
    private val onCountChanged: (Int) -> Unit
) {
    data class Entry(
        val windowId: String,
        val mainFrameId: String,
        val web: WebView,
        val openerWindowId: String,
        val creationReason: String
    )

    private val entries = linkedMapOf<String, Entry>()
    private var sequence = 0
    private var activeWindowId = ""

    fun registerInitial(web: WebView): Entry {
        val entry = register(web, "", "initial", true)
        activate(entry.windowId, emitEvent = false)
        return entry
    }

    fun active(): Entry? = entries[activeWindowId]

    fun all(): List<Entry> = entries.values.toList()

    fun find(web: WebView): Entry? = entries.values.firstOrNull { it.web === web }

    fun createPopup(
        opener: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val openerEntry = find(opener) ?: active()
        val child = WebView(activity)
        val entry = register(
            child,
            openerEntry?.windowId.orEmpty(),
            if (isDialog) "web-dialog" else "web-new-window",
            isUserGesture
        )
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
            close(entry.windowId)
            return false
        }
        transport.webView = child
        resultMsg.sendToTarget()
        activate(entry.windowId)
        return true
    }

    fun createManual(url: String? = null, opener: WebView? = active()?.web): Entry {
        val child = WebView(activity)
        val openerEntry = opener?.let { find(it) } ?: active()
        val entry = register(child, openerEntry?.windowId.orEmpty(), "manual-new-window", true)
        activate(entry.windowId)
        if (!url.isNullOrBlank()) child.loadUrl(url) else child.loadUrl("about:blank")
        return entry
    }

    fun close(web: WebView) {
        find(web)?.let { close(it.windowId) }
    }

    fun close(windowId: String = activeWindowId) {
        val entry = entries[windowId] ?: return
        if (entries.size == 1) {
            entry.web.loadUrl("about:blank")
            return
        }

        val wasActive = activeWindowId == windowId
        entries.remove(windowId)
        record(
            JSONObject()
                .put("source", "window-closed")
                .put("time", System.currentTimeMillis())
                .put("windowId", entry.windowId)
                .put("frameId", entry.mainFrameId)
                .put("openerWindowId", entry.openerWindowId)
                .put("url", entry.web.url.orEmpty())
        )

        onClosed(entry.windowId, entry.web)
        if (entry.web.parent != null) (entry.web.parent as? ViewGroup)?.removeView(entry.web)
        entry.web.stopLoading()
        entry.web.removeAllViews()
        entry.web.destroy()

        if (wasActive) {
            entries.values.lastOrNull()?.let { activate(it.windowId) }
        }
        onCountChanged(entries.size)
    }

    fun showWindowPicker() {
        val list = entries.values.toList()
        val labels = list.mapIndexed { index, entry ->
            val title = entry.web.title?.takeIf { it.isNotBlank() }
                ?: entry.web.url?.takeIf { it.isNotBlank() }
                ?: "Пустое окно"
            "${index + 1}. ${title.take(70)}"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Окна (${entries.size})")
            .setItems(labels) { _, which ->
                list.getOrNull(which)?.let { activate(it.windowId) }
            }
            .setPositiveButton("Новое окно") { _, _ -> createManual() }
            .setNegativeButton("Закрыть текущее") { _, _ -> close() }
            .setNeutralButton("Отмена", null)
            .show()
    }

    fun destroyAll() {
        entries.values.toList().forEach { entry ->
            try {
                if (entry.web.parent != null) (entry.web.parent as? ViewGroup)?.removeView(entry.web)
                entry.web.stopLoading()
                entry.web.removeAllViews()
                entry.web.destroy()
            } catch (_: Exception) {
            }
        }
        entries.clear()
        activeWindowId = ""
    }

    private fun register(
        web: WebView,
        openerWindowId: String,
        reason: String,
        isUserGesture: Boolean
    ): Entry {
        sequence++
        val suffix = String.format(Locale.US, "%04d", sequence)
        val windowId = "window-$suffix"
        val mainFrameId = "frame-$suffix-main"
        val entry = Entry(windowId, mainFrameId, web, openerWindowId, reason)
        entries[windowId] = entry

        record(
            JSONObject()
                .put("source", "window-created")
                .put("time", System.currentTimeMillis())
                .put("windowId", windowId)
                .put("frameId", mainFrameId)
                .put("openerWindowId", openerWindowId)
                .put("creationReason", reason)
                .put("isUserGesture", isUserGesture)
        )

        configure(web, windowId, mainFrameId)
        installLinkMenu(web)
        onCountChanged(entries.size)
        return entry
    }

    private fun activate(windowId: String, emitEvent: Boolean = true) {
        val entry = entries[windowId] ?: return
        activeWindowId = windowId

        if (entry.web.parent !== container) {
            (entry.web.parent as? ViewGroup)?.removeView(entry.web)
            container.removeAllViews()
            container.addView(entry.web, ViewGroup.LayoutParams(-1, -1))
        }

        onActivated(entry.web, entry.windowId, entry.mainFrameId)
        if (emitEvent) {
            record(
                JSONObject()
                    .put("source", "window-activated")
                    .put("time", System.currentTimeMillis())
                    .put("windowId", entry.windowId)
                    .put("frameId", entry.mainFrameId)
                    .put("url", entry.web.url.orEmpty())
            )
        }
    }

    private fun installLinkMenu(web: WebView) {
        web.setOnLongClickListener {
            val result = web.hitTestResult ?: return@setOnLongClickListener false
            val url = result.extra?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            } ?: return@setOnLongClickListener false

            AlertDialog.Builder(activity)
                .setTitle("Ссылка")
                .setItems(arrayOf("Открыть", "Открыть в новом окне", "Копировать ссылку")) { _, which ->
                    when (which) {
                        0 -> {
                            activate(find(web)?.windowId.orEmpty())
                            web.loadUrl(url)
                        }
                        1 -> createManual(url, web)
                        2 -> {
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                            Toast.makeText(activity, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
            true
        }
    }
}
