package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
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
import java.util.concurrent.atomic.AtomicInteger

class WebResearchV10Activity : AppCompatActivity() {
    private data class WindowRuntime(
        val windowId: String,
        val mainFrameId: String,
        val web: WebView,
        val captureController: WebCaptureController,
        val webViewController: WebResearchWebViewController
    )

    internal fun researchWebView(): WebView? = if (::web.isInitialized) web else null
    internal fun researchArchive(): ResearchArchive = archive
    internal fun researchUserAgent(): String = if (::userAgent.isInitialized) userAgent else ""

    internal fun clearResearchSession() {
        archive.clear()
        NetworkDebugStore.clear()
        windowRuntimes.values.forEach { it.captureController.clearPending() }
        updateBadge()
    }

    private lateinit var palette: WebUiTheme.Palette
    private lateinit var browserViews: WebResearchBrowserLayout.Views
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
    private lateinit var extensionManager: ExtensionManager
    private lateinit var extensionManagerUi: ExtensionManagerUi
    private lateinit var windowController: BrowserWindowController

    private val archive = ResearchArchive()
    private val windowRuntimes = linkedMapOf<String, WindowRuntime>()
    private lateinit var userAgent: String
    private val badgeUpdatePending = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var loading = false
    private var editingAddress = false
    private var bookmarkBarVisible = false
    private var zipRecordingStartedAt: Long? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        WebUiTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        title = "web research"
        palette = WebUiTheme.palette(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureSystemBars()

        browserViews = WebResearchBrowserLayout.create(
            activity = this,
            palette = palette,
            handler = uiHandler,
            callbacks = WebResearchBrowserLayout.Callbacks(
                onMenu = { if (::menuController.isInitialized) menuController.toggleBrowserMenu() },
                onAddressGo = { navigateFromAddress() },
                onAddressFocusChanged = { hasFocus ->
                    editingAddress = hasFocus
                    setBookmarkBarVisible(hasFocus)
                    updatePageAction()
                },
                onAddressFocusedTap = {
                    setBookmarkBarVisible(!bookmarkBarVisible)
                },
                onAddressToolsDismiss = {
                    if (::address.isInitialized) address.clearFocus()
                    setBookmarkBarVisible(false)
                },
                onAddressChanged = {
                    if (::address.isInitialized && address.hasFocus()) editingAddress = true
                    updatePageAction()
                },
                onPageAction = { handlePageAction() },
                onBookmarkAdd = {
                    if (::bookmarkController.isInitialized && ::address.isInitialized) {
                        bookmarkController.save(address.text.toString())
                    }
                },
                onZip = { handleZipAction() },
                onWindows = { if (::windowController.isInitialized) windowController.showWindowPicker() },
                onNetwork = {
                    ensureInstrumentation()
                    startActivity(Intent(this, NetworkDebuggerActivity::class.java))
                }
            )
        )

        web = browserViews.web
        swipeRefresh = browserViews.swipeRefresh
        address = browserViews.address
        pageAction = browserViews.pageAction
        zipButton = browserViews.zipButton
        menuButton = browserViews.menuButton
        networkButton = browserViews.networkButton
        networkBadge = browserViews.networkBadge
        progress = browserViews.progress
        val root = browserViews.root
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        userAgent = web.settings.userAgentString + " WebResearch/10"
        navigationController = WebNavigationController(
            activity = this,
            webProvider = { web },
            address = address,
            record = { addRecord(it) }
        )
        bookmarkController = WebBookmarkController(
            activity = this,
            normalizeUrl = { raw -> navigationController.normalizeUrl(raw) },
            onOpen = { url ->
                setBookmarkBarVisible(false)
                address.clearFocus()
                navigationController.navigate(url)
            }
        )
        bookmarkController.bind(browserViews.bookmarkSpinner)
        extensionManager = ExtensionManager(this)
        WebView.setWebContentsDebuggingEnabled(true)

        windowController = BrowserWindowController(
            activity = this,
            container = swipeRefresh,
            record = { addRecord(it) },
            configure = { targetWeb, windowId, mainFrameId ->
                configureWindow(targetWeb, windowId, mainFrameId)
            },
            onActivated = { targetWeb, windowId, _ ->
                activateRuntime(targetWeb, windowId)
            },
            onClosed = { windowId, _ ->
                windowRuntimes.remove(windowId)?.captureController?.shutdown()
            },
            onCountChanged = { count ->
                if (::browserViews.isInitialized) {
                    browserViews.windowButton.text = count.toString()
                    browserViews.windowButton.contentDescription = "Окна: $count"
                }
            }
        )
        windowController.registerInitial(web)
        configureSwipeRefresh()

        exportController = WebResearchExportController(
            activity = this,
            archive = archive,
            webProvider = { web },
            captureSnapshot = { onReady -> capturePageSnapshots(onReady) }
        )
        extensionManagerUi = ExtensionManagerUi(
            activity = this,
            manager = extensionManager,
            onChanged = {
                windowRuntimes.values.forEach { it.webViewController.reloadExtensions(reloadPage = false) }
                if (::web.isInitialized && !web.url.isNullOrBlank()) web.reload()
            }
        )
        bindMenuController()
        navigationController.navigate("https://evrasia.rest/")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWindow(targetWeb: WebView, windowId: String, mainFrameId: String) {
        targetWeb.settings.javaScriptEnabled = true
        targetWeb.settings.domStorageEnabled = true
        targetWeb.settings.databaseEnabled = true
        targetWeb.settings.setSupportMultipleWindows(true)
        targetWeb.settings.javaScriptCanOpenWindowsAutomatically = true
        targetWeb.settings.userAgentString = userAgent
        targetWeb.setBackgroundColor(android.graphics.Color.WHITE)
        targetWeb.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && bookmarkBarVisible) {
                setBookmarkBarVisible(false)
                address.clearFocus()
            }
            false
        }

        fun windowRecord(record: JSONObject): JSONObject {
            if (!record.has("windowId")) record.put("windowId", windowId)
            return record
        }

        val capture = WebCaptureController(
            activity = this,
            web = targetWeb,
            archive = archive,
            windowId = windowId,
            mainFrameId = mainFrameId,
            userAgent = userAgent,
            record = { addRecord(windowRecord(it)) },
            onChanged = { scheduleBadgeUpdate() },
            onSnapshot = { scheduleBadgeUpdate() }
        )
        targetWeb.addJavascriptInterface(capture.bridge, "EvrasiaResearch")
        capture.installFrameCapture()

        val controller = WebResearchWebViewController(
            activity = this,
            web = targetWeb,
            swipeRefresh = swipeRefresh,
            address = address,
            captureController = capture,
            navigationController = navigationController,
            extensionManager = extensionManager,
            handler = uiHandler,
            record = { addRecord(windowRecord(it)) },
            onLoadingChanged = { isLoading ->
                if (windowController.active()?.windowId == windowId) {
                    loading = isLoading
                    if (!isLoading) editingAddress = false
                    updatePageAction()
                    progress.visibility = if (isLoading) View.VISIBLE else View.INVISIBLE
                }
            },
            onProgressChanged = { value ->
                if (windowController.active()?.windowId == windowId) {
                    progress.progress = value
                    if (value in 1..99) progress.visibility = View.VISIBLE
                    if (value >= 100 && !loading) progress.visibility = View.INVISIBLE
                }
            },
            onPageUrlChanged = { url ->
                if (windowController.active()?.windowId == windowId && !address.hasFocus()) address.setText(url)
            },
            onCreateWindowRequested = { opener, isDialog, isUserGesture, resultMsg: Message ->
                windowController.createPopup(opener, isDialog, isUserGesture, resultMsg)
            },
            onCloseWindowRequested = { closingWeb ->
                windowController.close(closingWeb)
            }
        )
        controller.install()
        windowRuntimes[windowId] = WindowRuntime(windowId, mainFrameId, targetWeb, capture, controller)

        if (zipRecordingStartedAt != null) {
            capture.resetCheckpointWindow()
            capture.captureCheckpoint("recording-window-created")
        }
    }

    private fun activateRuntime(targetWeb: WebView, windowId: String) {
        val runtime = windowRuntimes[windowId] ?: return
        web = targetWeb
        captureController = runtime.captureController
        webViewController = runtime.webViewController
        loading = web.progress in 1..99
        progress.progress = web.progress
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        if (!address.hasFocus()) address.setText(web.url ?: "about:blank")
        editingAddress = false
        updatePageAction()
        configureSwipeRefresh()
        if (::bookmarkController.isInitialized) bindMenuController()
    }

    private fun configureSwipeRefresh() {
        if (!::swipeRefresh.isInitialized || !::web.isInitialized) return
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> web.canScrollVertically(-1) }
        swipeRefresh.setOnRefreshListener {
            web.reload()
            uiHandler.postDelayed({ swipeRefresh.isRefreshing = false }, 15000)
        }
    }

    private fun bindMenuController() {
        if (!::bookmarkController.isInitialized || !::webViewController.isInitialized) return
        if (::menuController.isInitialized) menuController.dismiss()
        menuController = WebResearchMenuController(
            activity = this,
            bookmarkController = bookmarkController,
            webViewController = webViewController,
            onExtensions = { if (::extensionManagerUi.isInitialized) extensionManagerUi.show() },
            paletteProvider = { palette },
            currentPageProvider = { currentPage() },
            onAccentColor = { color, persist -> applyAccentColor(color, persist) }
        )
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
        setBookmarkBarVisible(false)
        address.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        updatePageAction()
    }

    private fun setBookmarkBarVisible(visible: Boolean) {
        if (!::browserViews.isInitialized || bookmarkBarVisible == visible) return
        bookmarkBarVisible = visible
        WebResearchBrowserLayout.setBookmarkBarVisible(this, browserViews, visible)
    }

    private fun updatePageAction() {
        if (!::pageAction.isInitialized) return
        val (kind, description) = when {
            loading -> TechIconDrawable.Kind.STOP to "Остановить загрузку"
            editingAddress || address.hasFocus() -> TechIconDrawable.Kind.NAVIGATE to "Перейти"
            else -> TechIconDrawable.Kind.RELOAD to "Обновить"
        }
        pageAction.text = ""
        pageAction.contentDescription = description
        pageAction.foreground = TechIconDrawable(kind, palette.accent)
    }

    private fun applyAccentColor(color: Int, persist: Boolean) {
        val opaque = color or 0xFF000000.toInt()
        if (persist) WebUiTheme.saveAccentColor(this, opaque)
        palette = palette.copy(accent = opaque)
        if (::browserViews.isInitialized) WebResearchBrowserLayout.applyAccent(this, browserViews, palette)
        if (::menuController.isInitialized) menuController.updateAccent(opaque)
    }

    private fun currentPage(): String = web.url ?: address.text?.toString().orEmpty()

    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =
        if (::captureController.isInitialized) captureController.requestResourceCopy(url, headersJson) else false

    fun ensureInstrumentation() {
        if (::captureController.isInitialized) captureController.ensureInstrumentation()
    }

    private fun capturePageSnapshots(onReady: () -> Unit) {
        val runtimes = windowRuntimes.values.toList()
        if (runtimes.isEmpty()) {
            onReady()
            return
        }

        val remaining = AtomicInteger(runtimes.size)
        val completeOne: () -> Unit = {
            if (remaining.decrementAndGet() == 0) onReady()
        }

        val activeId = windowController.active()?.windowId
        runtimes.filter { it.windowId != activeId }.forEach {
            it.captureController.capturePageSnapshot(completeOne)
        }
        activeId?.let { id ->
            windowRuntimes[id]?.captureController?.capturePageSnapshot(completeOne)
        }
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

    private fun handleZipAction() {
        if (!::exportController.isInitialized || !::browserViews.isInitialized) return
        val startedAt = zipRecordingStartedAt
        if (startedAt == null) {
            zipRecordingStartedAt = System.currentTimeMillis()
            WebResearchBrowserLayout.setZipRecording(this, browserViews, true)
            zipButton.contentDescription = "Остановить запись ZIP"
            windowRuntimes.values.forEach {
                it.captureController.resetCheckpointWindow()
                it.captureController.captureCheckpoint("recording-start")
            }
        } else {
            val endedAt = System.currentTimeMillis()
            zipRecordingStartedAt = null
            WebResearchBrowserLayout.setZipRecording(this, browserViews, false)
            zipButton.contentDescription = "Начать запись ZIP"
            windowRuntimes.values.forEach { it.captureController.captureCheckpoint("recording-stop") }
            exportController.exportWindow(startedAt, endedAt + 750L)
        }
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
        windowRuntimes.values.forEach { it.captureController.shutdown() }
        if (::windowController.isInitialized) windowController.destroyAll()
        windowRuntimes.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ::web.isInitialized && web.canGoBack() -> web.goBack()
            ::windowController.isInitialized && windowController.all().size > 1 -> windowController.close()
            else -> super.onBackPressed()
        }
    }
}
