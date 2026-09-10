package ru.evrasia.research

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

class CookieTraceProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private val traceEngine = CookieTraceEngine()

    private val ink = Color.rgb(3, 10, 15)
    private val surface = Color.rgb(7, 18, 25)
    private val surface2 = Color.rgb(10, 25, 34)
    private val line = Color.rgb(21, 57, 69)
    private val cyan = Color.rgb(0, 226, 239)
    private val white = Color.rgb(232, 244, 248)
    private val muted = Color.rgb(113, 139, 151)
    private val accent = Color.rgb(151, 231, 92)

    private val ticker = object : Runnable {
        override fun run() {
            browserRef.get()?.let { traceEngine.sample(it) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
        handler.post(ticker)
        return true
    }

    private fun webOf(activity: WebResearchV10Activity): WebView? = activity.researchWebView()

    private fun showCookieList(activity: NetworkDebuggerActivity) {
        val browser = browserRef.get()
        val page = browser?.let { webOf(it) }?.url.orEmpty()
        val active = if (page.isBlank()) {
            emptyMap()
        } else {
            CookieTraceSupport.parseCookieHeader(CookieManager.getInstance().getCookie(page).orEmpty())
        }
        val host = traceEngine.scopeHost(page)
        val relevant = traceEngine.eventsSnapshot().filter { traceEngine.relevantToHost(it, host) }
        val inactive = relevant
            .map { it.optString("name", "") }
            .filter { it.isNotBlank() && it !in active }
            .distinct()
            .sorted()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 10))
            setBackgroundColor(ink)
        }
        root.addView(TextView(activity).apply {
            text = "Страница: ${page.ifBlank { "—" }}\nАктивных куки: ${active.size}"
            setTextColor(muted)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 8))
        })

        var dialog: AlertDialog? = null
        if (active.isEmpty()) root.addView(sectionText(activity, "Активных куки нет"))
        active.toSortedMap().forEach { (name, value) ->
            val history = relevant.filter { it.optString("name") == name }.sortedBy { it.optLong("time", 0L) }
            val origin = CookieTraceSupport.currentOrigin(history, value)
            root.addView(cookieRow(activity, name, value, CookieTraceSupport.sourceShort(origin), true) {
                dialog?.dismiss()
                showCookieDetails(activity, name, value, host, true)
            })
        }

        if (inactive.isNotEmpty()) {
            root.addView(sectionText(activity, "ИСТОРИЯ / НЕАКТИВНЫЕ"))
            inactive.forEach { name ->
                val history = relevant.filter { it.optString("name") == name }.sortedBy { it.optLong("time", 0L) }
                val latest = history.lastOrNull()
                root.addView(cookieRow(activity, name, latest?.optString("value", "").orEmpty(), CookieTraceSupport.sourceShort(latest), false) {
                    dialog?.dismiss()
                    showCookieDetails(activity, name, null, host, false)
                })
            }
        }

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(ink)
            addView(root)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("Куки")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cyan)
        }
        dialog.show()
    }

    private fun cookieRow(
        activity: Activity,
        name: String,
        value: String,
        source: String,
        active: Boolean,
        click: () -> Unit
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        background = round(activity, if (active) surface else surface2, 10, line)
        setPadding(dp(activity, 11), dp(activity, 9), dp(activity, 11), dp(activity, 9))
        addView(TextView(activity).apply {
            text = name
            setTextColor(if (active) white else muted)
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            val preview = if (value.length > 80) value.take(80) + "…" else value
            text = "${if (active) preview else "неактивна"}\n$source"
            setTextColor(if (active) accent else muted)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(activity, 4), 0, 0)
        })
        setOnClickListener { click() }
    }

    private fun sectionText(activity: Activity, value: String): TextView = TextView(activity).apply {
        text = value
        setTextColor(cyan)
        textSize = 9f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = .08f
        setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), dp(activity, 5))
    }

    private fun showCookieDetails(
        activity: NetworkDebuggerActivity,
        name: String,
        currentValue: String?,
        host: String,
        active: Boolean
    ) {
        val history = traceEngine.eventsSnapshot()
            .filter { it.optString("name") == name && traceEngine.relevantToHost(it, host) }
            .sortedBy { it.optLong("time", 0L) }
        CookieTraceDetailsDialog.show(
            activity = activity,
            name = name,
            currentValue = currentValue,
            active = active,
            history = history,
            options = CookieTraceDetailsDialog.Options(
                verboseActions = true,
                directClipboard = true
            ),
            onBack = { showCookieList(activity) }
        )
    }

    private fun rewireCookieButton(activity: NetworkDebuggerActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? LinearLayout ?: return
        val toolbarScroll = root.getChildAt(0) as? HorizontalScrollView ?: return
        val toolbar = toolbarScroll.getChildAt(0) as? LinearLayout ?: return
        val button = toolbar.findViewWithTag<Button>("debugger-cookies") ?: return
        button.text = "Куки"
        button.setOnClickListener { showCookieList(activity) }
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun round(activity: Activity, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius).toFloat()
        stroke?.let { setStroke(dp(activity, 1), it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            traceEngine.reset()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is WebResearchV10Activity -> {
                browserRef = WeakReference(activity)
                traceEngine.onBrowserResumed(activity)
            }
            is NetworkDebuggerActivity -> {
                handler.postDelayed({ rewireCookieButton(activity) }, 100)
                handler.postDelayed({ rewireCookieButton(activity) }, 500)
                handler.postDelayed({ rewireCookieButton(activity) }, 1200)
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) browserRef.clear()
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
