package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class WebResearchV10Activity : AppCompatActivity() {
    internal fun researchWebView(): WebView? = if (::web.isInitialized) web else null
    internal fun researchArchive(): ResearchArchive = archive
    internal fun researchUserAgent(): String = if (::userAgent.isInitialized) userAgent else ""
    internal fun clearResearchSession() {
        archive.clear()
        NetworkDebugStore.clear()
        if (::captureController.isInitialized) captureController.clearPending()
        updateBadge()
    }

    private lateinit var palette: WebUiTheme.Palette
    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var address: EditText
    private lateinit var pageAction: Button
    private lateinit var zipButton: Button
    private lateinit var menuButton: Button
    private lateinit var networkButton: Button
    private lateinit var networkBadge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var navigationController: WebNavigationController
    private lateinit var bookmarkController: WebBookmarkController
    private lateinit var webViewController: WebResearchWebViewController
    private lateinit var exportController: WebResearchExportController
    private lateinit var captureController: WebCaptureController
    private lateinit var menuController: WebResearchMenuController
    private val archive = ResearchArchive()
    private lateinit var userAgent: String
    private val badgeUpdatePending = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var loading = false
    private var editingAddress = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        WebUiTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        title = "web research"
        palette = WebUiTheme.palette(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureSystemBars()

        val root = LinearLayout(this).apply {
            tag = "web-research-root"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        val toolbar = LinearLayout(this).apply {
            tag = "browser-toolbar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setBackgroundColor(palette.background)
            clipChildren = true
            clipToPadding = true
        }

        menuButton = iconButton(TechIconDrawable.Kind.MENU, false) { if (::menuController.isInitialized) menuController.toggleBrowserMenu() }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(42), dp(46)))

        address = EditText(this).apply {
            tag = "browser-address"
            hint = "Адрес сайта"
            setHintTextColor(palette.secondary)
            setTextColor(palette.text)
            setSingleLine(true)
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(palette.address, 22f)
            setPadding(dp(14), 0, dp(14), 0)
            setText("https://evrasia.rest/")
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateFromAddress()
                    true
                } else false
            }
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                editingAddress = hasFocus
                updatePageAction()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (hasFocus()) editingAddress = true
                    updatePageAction()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })

        pageAction = Button(this).apply {
            tag = "browser-page-action"
            text = "→"
            textSize = 21f
            setTextColor(palette.accent)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = rounded(palette.card, 16f, palette.divider)
            setOnClickListener { handlePageAction() }
        }
        toolbar.addView(pageAction, LinearLayout.LayoutParams(dp(42), dp(46)).apply { marginStart = dp(5) })

        zipButton = Button(this).apply {
            tag = "browser-zip"
            text = "ZIP"
            contentDescription = "Экспорт ZIP"
            isAllCaps = false
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            background = rounded(palette.card, 13f, palette.divider)
            setOnClickListener { exportZip() }
        }
        toolbar.addView(zipButton, LinearLayout.LayoutParams(dp(54), dp(46)).apply { marginStart = dp(4) })

        val networkContainer = FrameLayout(this).apply {
            tag = "browser-network"
            clipChildren = true
            clipToPadding = true
        }
        networkButton = iconButton(TechIconDrawable.Kind.NETWORK, true) {
            ensureInstrumentation()
            startActivity(Intent(this, NetworkDebuggerActivity::class.java))
        }
        networkContainer.addView(networkButton, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
        networkBadge = TextView(this).apply {
            tag = "network-badge"
            visibility = View.GONE
            setTextColor(WebUiTheme.contrastText(palette.accent))
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(17)
            maxLines = 1
            setPadding(dp(3), 0, dp(3), 0)
            background = rounded(palette.accent, 9f)
        }
        networkContainer.addView(networkBadge, FrameLayout.LayoutParams(-2, dp(17), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(3)
            marginEnd = dp(3)
        })
        toolbar.addView(networkContainer, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "browser-progress"
            max = 100
            progressTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        web = WebView(this).apply {
            tag = "browser-webview"
            setBackgroundColor(Color.WHITE)
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            tag = "browser-webview-container"
            setColorSchemeColors(palette.accent)
            setProgressBackgroundColorSchemeColor(palette.card)
            setOnChildScrollUpCallback { _, _ -> web.canScrollVertically(-1) }
            setOnRefreshListener {
                web.reload()
                uiHandler.postDelayed({ if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false }, 15000)
            }
            addView(web, ViewGroup.LayoutParams(-1, -1))
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        userAgent = web.settings.userAgentString + " WebResearch/10"
        web.settings.userAgentString = userAgent

        navigationController = WebNavigationController(this, web, address) { addRecord(it) }
        bookmarkController = WebBookmarkController(
            activity = this,
            normalizeUrl = { raw -> navigationController.normalizeUrl(raw) },
            onOpen = { url -> navigationController.navigate(url) }
        )
        captureController = WebCaptureController(
            activity = this,
            web = web,
            archive = archive,
            userAgent = userAgent,
            record = { addRecord(it) },
            onChanged = { scheduleBadgeUpdate() },
            onSnapshot = { scheduleBadgeUpdate() }
        )
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(captureController.bridge, "EvrasiaResearch")
        exportController = WebResearchExportController(
            activity = this,
            archive = archive,
            web = web,
            captureSnapshot = { capturePageSnapshot() }
        )

        webViewController = WebResearchWebViewController(
            activity = this,
            web = web,
            swipeRefresh = swipeRefresh,
            address = address,
            captureController = captureController,
            navigationController = navigationController,
            handler = uiHandler,
            record = { addRecord(it) },
            onLoadingChanged = { isLoading ->
                loading = isLoading
                if (!isLoading) editingAddress = false
                updatePageAction()
                progress.visibility = if (isLoading) View.VISIBLE else View.INVISIBLE
            },
            onProgressChanged = { value ->
                progress.progress = value
                if (value in 1..99) progress.visibility = View.VISIBLE
                if (value >= 100 && !loading) progress.visibility = View.INVISIBLE
            },
            onPageUrlChanged = { url ->
                if (!address.hasFocus()) address.setText(url)
            }
        )
        webViewController.install()
        menuController = WebResearchMenuController(
            activity = this,
            bookmarkController = bookmarkController,
            webViewController = webViewController,
            paletteProvider = { palette },
            currentPageProvider = { currentPage() },
            onAccentColor = { color, persist -> applyAccentColor(color, persist) }
        )
        navigationController.navigate("https://evrasia.rest/")
    }

    private fun configureSystemBars() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
    }

    private fun handlePageAction() {
        when {
            loading -> web.stopLoading()
            editingAddress || address.hasFocus() -> navigateFromAddress()
            else -> web.reload()
        }
    }

    private fun navigateFromAddress() {
        navigationController.navigate(address.text.toString())
        editingAddress = false
        address.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        updatePageAction()
    }

    private fun updatePageAction() {
        if (!::pageAction.isInitialized) return
        pageAction.text = when {
            loading -> "✕"
            editingAddress || address.hasFocus() -> "→"
            else -> "↻"
        }
    }

    private fun iconButton(kind: TechIconDrawable.Kind, strong: Boolean, click: () -> Unit) = Button(this).apply {
        text = ""
        contentDescription = when (kind) {
            TechIconDrawable.Kind.MENU -> "Меню"
            TechIconDrawable.Kind.NETWORK -> "Network / Research"
            TechIconDrawable.Kind.NAVIGATE -> "Перейти"
            TechIconDrawable.Kind.BACK -> "Назад"
        }
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = rounded(if (strong) palette.card else Color.TRANSPARENT, 16f, if (strong) palette.divider else Color.TRANSPARENT)
        foreground = TechIconDrawable(kind, palette.accent)
        setOnClickListener { click() }
    }

    private fun currentPage(): String = web.url ?: address.text?.toString().orEmpty()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =
        if (::captureController.isInitialized) captureController.requestResourceCopy(url, headersJson) else false

    fun ensureInstrumentation() {
        if (::captureController.isInitialized) captureController.ensureInstrumentation()
    }

    private fun capturePageSnapshot() {
        if (::captureController.isInitialized) captureController.capturePageSnapshot()
    }

    private fun addRecord(record: JSONObject) {
        archive.addRecord(record)
        scheduleBadgeUpdate()
    }

    private fun scheduleBadgeUpdate() {
        if (!badgeUpdatePending.compareAndSet(false, true)) return
        uiHandler.postDelayed({
            badgeUpdatePending.set(false)
            if (::networkBadge.isInitialized && !isFinishing) updateBadge()
        }, 250)
    }

    private fun updateBadge() {
        val count = archive.records.length()
        networkBadge.text = if (count > 99) "99+" else count.toString()
        networkBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun exportZip() {
        if (::exportController.isInitialized) exportController.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::exportController.isInitialized) exportController.handleResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (::palette.isInitialized) {
            val savedAccent = WebUiTheme.savedAccentColor(this)
            if (savedAccent != palette.accent) applyAccentColor(savedAccent, persist = false)
        }
    }

    override fun onDestroy() {
        if (::menuController.isInitialized) menuController.dismiss()
        if (::captureController.isInitialized) captureController.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
