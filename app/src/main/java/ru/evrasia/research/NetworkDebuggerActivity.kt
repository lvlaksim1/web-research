package ru.evrasia.research

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var recordButton: Button
    private lateinit var domainSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var methodSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var menuButton: Button

    private val dataSource = NetworkDebuggerDataSource()
    private val allItems = mutableListOf<JSONObject>()
    private val items = mutableListOf<JSONObject>()
    private val domains = mutableListOf<String>()
    private val changedIds = hashSetOf<Long>()
    private val handler = Handler(Looper.getMainLooper())

    private var lastRevision = -1L
    private var mergeMode = false
    private val typeFilters = listOf("ALL","JSON","HTML","JS","CSS","IMG","PDF","TEXT","BIN","OTHER")
    private val methodFilters = listOf("ALL","GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD","WS","SSE","OTHER")
    private val detailsController by lazy { NetworkDebuggerDetailsController(this, changedIds) }
    private val realtimeController by lazy { NetworkDebuggerRealtimeController(this) }

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

        domainSpinner = Spinner(this, Spinner.MODE_DROPDOWN)
        typeSpinner = Spinner(this, Spinner.MODE_DROPDOWN)
        methodSpinner = Spinner(this, Spinner.MODE_DROPDOWN)
        typeSpinner.adapter = spinnerAdapter(typeFilters)
        methodSpinner.adapter = spinnerAdapter(methodFilters)
        attachFilterListener(typeSpinner)
        attachFilterListener(methodSpinner)

        search = EditText(this).apply {
            visibility = View.GONE
            hint = "Поиск по URL, headers, body..."
            setHintTextColor(muted)
            setTextColor(textColor)
            textSize = 12f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(panel2, 11f, line)
            setPadding(dp(10), 0, dp(10), 0)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { applyFilters(); true } else false
            }
        }

        counter = TextView(this).apply {
            visibility = View.GONE
            setTextColor(muted)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }

        list = ListView(this).apply {
            divider = null
            dividerHeight = dp(2)
            setBackgroundColor(bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
        }
        adapter = NetworkDebuggerEventAdapter(this, items, changedIds) { mergeMode }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val event = items[position]
            when {
                isActionEvent(event) -> Unit
                isRealtimeSession(event) -> realtimeController.show(event)
                else -> detailsController.show(event, search.text.toString().trim())
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(6))
            setBackgroundColor(panel)
        }
        fun addControl(button: Button) {
            controls.addView(button, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(5) })
        }
        addControl(chromeButton("←", "Назад") { finish() })
        addControl(chromeButton("⌄", "Фильтр домена") { showDomainPopup(it) })
        addControl(chromeButton("⌕", "Поиск") { showSearchPopup(it) })
        recordButton = chromeButton(if (NetworkDebugStore.recording) "■" else "●", if (NetworkDebugStore.recording) "Остановить запись" else "Начать запись") {
            NetworkDebugStore.recording = !NetworkDebugStore.recording
            updateRecordButton()
        }
        addControl(recordButton)
        addControl(chromeButton("⌫", "Очистить журнал") {
            showClearOptions(it)
        })
        menuButton = chromeButton("☰", "Меню") { showNetworkMenu(it) }
        controls.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(55)))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val bars: Insets = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            i
        }
        ViewCompat.requestApplyInsets(root)
        refreshIncremental(force = true)
    }

    private fun showClearOptions(anchor: View) {
        var popup: PopupWindow? = null
        val content = popupPanel()
        content.addView(popupHeader("Очистить журнал") { popup?.dismiss() })
        content.addView(popupRow("Журнал и данные ZIP", false) {
            popup?.dismiss()
            clearLogData(clearCookies = false)
        })
        content.addView(popupRow("Журнал, данные ZIP и все cookies", false) {
            popup?.dismiss()
            clearLogData(clearCookies = true)
        })
        popup = buildPopup(content, 320)
        showAboveRight(popup, content, anchor, 320)
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

    private fun chromeButton(symbol: String, description: String, click: (View) -> Unit) = Button(this).apply {
        text = symbol
        contentDescription = description
        setTextColor(accent)
        textSize = 19f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = rounded(panel2, 11f, line)
        setOnClickListener { click(this) }
    }

    private fun showDomainPopup(anchor: View) {
        val values = if (domains.isEmpty()) listOf("Все домены") else domains.toList()
        showSelectionPopup(anchor, "Домен", values, selected(domainSpinner, "Все домены")) { value ->
            val index = domains.indexOf(value)
            if (index >= 0) domainSpinner.setSelection(index)
            applyFilters()
        }
    }

    private fun showSearchPopup(anchor: View) {
        var popup: PopupWindow? = null
        val content = popupPanel()
        content.addView(popupHeader("Поиск") { popup?.dismiss() })
        val input = EditText(this).apply {
            setText(search.text)
            setSelection(text.length)
            hint = "URL, headers, body..."
            setHintTextColor(muted)
            setTextColor(textColor)
            textSize = 12f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(panel2, 11f, line)
            setPadding(dp(10), 0, dp(10), 0)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    search.setText(text)
                    applyFilters()
                    popup?.dismiss()
                    true
                } else false
            }
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(8), dp(5), dp(8), dp(7)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(popupAction("Очистить") {
            search.setText("")
            applyFilters()
            popup?.dismiss()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(popupAction("Найти") {
            search.setText(input.text)
            applyFilters()
            popup?.dismiss()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        content.addView(actions, LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        popup = buildPopup(content, 310)
        showAboveRight(popup, content, anchor, 310)
        input.requestFocus()
    }

    private fun showNetworkMenu(anchor: View) {
        var popup: PopupWindow? = null
        val content = popupPanel()
        content.addView(popupHeader("Фильтры и режимы") { popup?.dismiss() })
        content.addView(popupRow(if (mergeMode) "Объединено ✓" else "Раздельно", mergeMode) {
            mergeMode = !mergeMode
            applyFilters()
            popup?.dismiss()
        })
        content.addView(popupRow("Тип ответа: ${selected(typeSpinner, "ALL")}", selected(typeSpinner, "ALL") != "ALL") {
            popup?.dismiss()
            showSelectionPopup(anchor, "Тип ответа", typeFilters, selected(typeSpinner, "ALL")) { value ->
                typeSpinner.setSelection(typeFilters.indexOf(value).coerceAtLeast(0))
                applyFilters()
            }
        })
        content.addView(popupRow("Метод: ${selected(methodSpinner, "ALL")}", selected(methodSpinner, "ALL") != "ALL") {
            popup?.dismiss()
            showSelectionPopup(anchor, "Метод", methodFilters, selected(methodSpinner, "ALL")) { value ->
                methodSpinner.setSelection(methodFilters.indexOf(value).coerceAtLeast(0))
                applyFilters()
            }
        })
        popup = buildPopup(content, 275)
        showAboveRight(popup, content, anchor, 275)
    }

    private fun showSelectionPopup(anchor: View, title: String, values: List<String>, current: String, onSelect: (String) -> Unit) {
        var popup: PopupWindow? = null
        val content = popupPanel()
        content.addView(popupHeader(title) { popup?.dismiss() })
        val scrollBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        values.forEach { value ->
            scrollBody.addView(popupRow(if (value == current) "$value ✓" else value, value == current) {
                onSelect(value)
                popup?.dismiss()
            })
        }
        val scroll = ScrollView(this).apply { addView(scrollBody) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        popup = buildPopup(content, 285, maxHeight = (resources.displayMetrics.heightPixels * 0.64f).toInt())
        showAboveRight(popup, content, anchor, 285)
    }

    private fun popupPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = rounded(panel, 16f, line)
    }

    private fun popupHeader(title: String, close: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(chromeButton("×", "Закрыть") { close() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(TextView(this@NetworkDebuggerActivity).apply {
            text = title
            setTextColor(textColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
    }

    private fun popupRow(label: String, active: Boolean, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textSize = 12.5f
        setTextColor(if (active) accent else textColor)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(if (active) panel2 else panel, 10f, line)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
    }

    private fun popupAction(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(textColor)
        background = rounded(panel2, 11f, line)
        setOnClickListener { click() }
    }

    private fun buildPopup(content: LinearLayout, widthDp: Int, maxHeight: Int = WindowManager.LayoutParams.WRAP_CONTENT): PopupWindow {
        return PopupWindow(content, dp(widthDp), maxHeight, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
        }
    }

    private fun showAboveRight(popup: PopupWindow, content: View, anchor: View, widthDp: Int) {
        val width = dp(widthDp)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (popup.height == WindowManager.LayoutParams.WRAP_CONTENT) popup.height = content.measuredHeight
        val height = if (popup.height > 0) popup.height else content.measuredHeight
        popup.showAsDropDown(anchor, anchor.width - width, -height - anchor.height)
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


    private fun updateRecordButton(){
        if(::recordButton.isInitialized){
            recordButton.text = if(NetworkDebugStore.recording) "■" else "●"
            recordButton.contentDescription = if(NetworkDebugStore.recording) "Остановить запись" else "Начать запись"
            recordButton.setTextColor(if(NetworkDebugStore.recording) accent else muted)
        }
    }



    private fun selected(spinner:Spinner, fallback:String):String =
        if(spinner.selectedItem!=null) spinner.selectedItem.toString() else fallback

    private fun rebuildDynamicFilters(){
        if(!::domainSpinner.isInitialized)return
        val selectedDomain=selected(domainSpinner,"Все домены")
        domains.clear()
        domains.add("Все домены")
        allItems.mapNotNull{hostOf(eventLocation(it))}.distinct().sorted().forEach{domains.add(it)}
        domainSpinner.adapter=spinnerAdapter(domains)
        domainSpinner.setSelection(domains.indexOf(selectedDomain).takeIf{it>=0}?:0)
        attachFilterListener(domainSpinner)
    }

    private fun spinnerAdapter(values:List<String>)=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values){
        private fun selectedView(position:Int)=TextView(this@NetworkDebuggerActivity).apply{
            text=getItem(position)
            setTextColor(textColor)
            textSize=11f
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
            gravity=Gravity.CENTER_VERTICAL
            maxLines=1
            setPadding(dp(8),0,dp(18),0)
            background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        private fun dropView(position:Int)=TextView(this@NetworkDebuggerActivity).apply{
            text=getItem(position)
            setTextColor(if(position==0)muted else textColor)
            textSize=11f
            typeface=Typeface.MONOSPACE
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(11),dp(14),dp(11))
            background=rounded(panel2,8f,line)
        }
        override fun getView(position:Int,convertView:View?,parent:ViewGroup)=selectedView(position)
        override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup)=dropView(position)
    }

    private fun attachFilterListener(spinner:Spinner){
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){if(::list.isInitialized)applyFilters()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        }
    }

    private fun applyFilters(){
        if(!::search.isInitialized)return
        val domain=if(::domainSpinner.isInitialized)selected(domainSpinner,"Все домены") else "Все домены"
        val type=if(::typeSpinner.isInitialized)selected(typeSpinner,"ALL") else "ALL"
        val method=if(::methodSpinner.isInitialized)selected(methodSpinner,"ALL") else "ALL"
        val q=search.text.toString().trim()

        val projection=NetworkDebuggerProjection.build(allItems,mergeMode,domain,type,method,q,methodFilters)
        changedIds.clear()
        changedIds.addAll(projection.changedIds)
        items.clear()
        items.addAll(projection.rows)
        adapter.notifyDataSetChanged()
        counter.text=projection.counterText
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
