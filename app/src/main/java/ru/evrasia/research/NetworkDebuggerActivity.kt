package ru.evrasia.research

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

class NetworkDebuggerActivity : AppCompatActivity() {
    private val bg get() = WebUiTheme.palette(this).background
    private val panel get() = WebUiTheme.palette(this).card
    private val panel2 get() = WebUiTheme.palette(this).address
    private val line get() = WebUiTheme.palette(this).divider
    private val accent get() = WebUiTheme.palette(this).accent
    private val textColor get() = WebUiTheme.palette(this).text
    private val muted get() = WebUiTheme.palette(this).secondary
    private val bad get() = WebUiTheme.palette(this).red
    private val cyan get() = WebUiTheme.palette(this).accent
    private val amber get() = WebUiTheme.palette(this).orange
    private val violet get() = WebUiTheme.palette(this).blue

    private lateinit var list: ListView
    private lateinit var adapter: NetworkDebuggerEventAdapter
    private lateinit var counter: TextView

    private val dataSource = NetworkDebuggerDataSource()
    private val allItems = mutableListOf<JSONObject>()
    private val items = mutableListOf<JSONObject>()
    private val domains = mutableListOf<String>()
    private val changedIds = hashSetOf<Long>()
    private val handler = Handler(Looper.getMainLooper())

    private var lastRevision = -1L
    private val typeFilters = listOf("ALL","JSON","HTML","JS","CSS","IMG","PDF","TEXT","BIN","OTHER")
    private val methodFilters = listOf("ALL","GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD","WS","SSE","OTHER")
    private val detailsController by lazy { NetworkDebuggerDetailsController(this, changedIds) }
    private val realtimeController by lazy { NetworkDebuggerRealtimeController(this) }
    private lateinit var controlsController: NetworkDebuggerControlsController

    private val refresh = object : Runnable {
        override fun run() {
            refreshIncremental()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WebUiTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !WebUiTheme.palette(this@NetworkDebuggerActivity).dark
            isAppearanceLightNavigationBars = !WebUiTheme.palette(this@NetworkDebuggerActivity).dark
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        list = ListView(this).apply {
            divider = null
            dividerHeight = dp(2)
            setBackgroundColor(bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
        }
        adapter = NetworkDebuggerEventAdapter(this, items, changedIds) { controlsController.filters().mergeMode }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val event = items[position]
            when {
                isActionEvent(event) -> Unit
                isRealtimeSession(event) -> realtimeController.show(event)
                else -> detailsController.show(event, controlsController.filters().query)
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        controlsController = NetworkDebuggerControlsController(
            activity = this,
            typeFilters = typeFilters,
            methodFilters = methodFilters,
            onBack = { finish() },
            onRecordingToggle = {
                NetworkDebugStore.recording = !NetworkDebugStore.recording
                controlsController.updateRecording(NetworkDebugStore.recording)
            },
            onClear = { clearCookies -> clearLogData(clearCookies) },
            onChanged = { applyFilters() }
        )
        root.addView(
            controlsController.createBar(NetworkDebugStore.recording),
            LinearLayout.LayoutParams(-1, dp(55))
        )

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val bars: Insets = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            i
        }
        ViewCompat.requestApplyInsets(root)
        refreshIncremental(force = true)
    }

    private fun clearLogData(clearCookies: Boolean) {
        if (!NetworkRequestActions.clearFullSession(this)) NetworkDebugStore.clear()
        refreshIncremental(force = true)
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                runOnUiThread {
                    Toast.makeText(this, "Журнал, данные ZIP и cookies очищены", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Журнал и данные текущего ZIP очищены", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onResume(){
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause(){
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun refreshIncremental(force:Boolean=false){
        val result = dataSource.refresh(force, lastRevision)
        if(!result.changed)return
        lastRevision=result.revision
        allItems.clear()
        allItems.addAll(result.events)
        rebuildDynamicFilters()
        applyFilters()
    }



    private fun rebuildDynamicFilters() {
        val values = allItems
            .mapNotNull { hostOf(eventLocation(it)) }
            .distinct()
            .sorted()
        controlsController.setDomains(values)
    }

    private fun applyFilters() {
        if (!::controlsController.isInitialized) return
        val filters = controlsController.filters()
        val projection = NetworkDebuggerProjection.build(
            allItems,
            filters.mergeMode,
            filters.domain,
            filters.type,
            filters.method,
            filters.query,
            methodFilters
        )
        changedIds.clear()
        changedIds.addAll(projection.changedIds)
        items.clear()
        items.addAll(projection.rows)
        adapter.notifyDataSetChanged()
        counter.text = projection.counterText
    }

    private fun isRealtimeSession(event:JSONObject) = NetworkEventClassifier.isRealtimeSession(event)

    private fun eventLocation(event:JSONObject):String = NetworkEventClassifier.eventLocation(event)

    private fun isActionEvent(event:JSONObject):Boolean = NetworkEventClassifier.isActionEvent(event)

    private fun hostOf(url:String):String? = NetworkEventClassifier.hostOf(url)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        ResultDelivery.handleActivityResult(this,requestCode,resultCode,data)
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(fill:Int,radius:Float,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(radius.toInt()).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)}


}
