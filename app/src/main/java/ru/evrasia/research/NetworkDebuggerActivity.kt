package ru.evrasia.research

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val listTimeFormat = SimpleDateFormat("HH:mm:ss.SSS",Locale.US)

    private var lastRevision = -1L
    private var mergeMode = false
    private val typeFilters = listOf("ALL","JSON","HTML","JS","CSS","IMG","PDF","TEXT","BIN","OTHER")
    private val methodFilters = listOf("ALL","GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD","WS","SSE","OTHER")
    private val replayController by lazy { NetworkReplayController(this, bg, panel2, line, textColor, muted) }

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
                isRealtimeSession(event) -> showRealtimeSession(event)
                else -> showDetails(event, search.text.toString().trim())
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

    private fun responseKind(event:JSONObject):String = NetworkEventClassifier.responseKind(event)

    private fun eventLocation(event:JSONObject):String = NetworkEventClassifier.eventLocation(event)

    private fun methodOf(event:JSONObject):String = NetworkEventClassifier.methodOf(event)

    private fun responseBodyText(event:JSONObject):String = NetworkEventClassifier.responseBodyText(event)

    private fun isActionEvent(event:JSONObject):Boolean = NetworkEventClassifier.isActionEvent(event)

    private fun hostOf(url:String):String? = NetworkEventClassifier.hostOf(url)

    private fun showRealtimeSession(session:JSONObject){
        val arr=session.optJSONArray("_sessionEvents")?:return
        val dialog=Dialog(this)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        val root=toolDialogRoot(dialog,"Realtime session · ${arr.length()} событий")
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(TextView(this).apply{text="${session.optString("_realtimeProtocol")}  ${session.optString("url")}";setTextColor(cyan);textSize=11f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);setTextIsSelectable(true);setPadding(dp(8),dp(6),dp(8),dp(6))})
        val copyText=StringBuilder()
        for(i in 0 until arr.length()){
            val e=arr.optJSONObject(i)?:continue
            val source=e.optString("source","")
            val direction=when{source.endsWith("-send")->"SEND";source.endsWith("-receive")||source.endsWith("-message")->"RECEIVE";source.endsWith("-open")->"OPEN";else->source.uppercase(Locale.US)}
            val data=e.optString("data",e.optString("message",e.optString("state","")))
            val displayLine="${if(e.has("time"))listTime(e.optLong("time")) else "--:--:--.---"}  $direction${if(data.isNotBlank())"\n$data" else ""}"
            copyText.append(displayLine).append("\n\n")
            body.addView(TextView(this).apply{text=displayLine;setTextColor(if(direction=="SEND")amber else if(direction=="RECEIVE")accent else muted);textSize=10.5f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(9),dp(8),dp(9),dp(8));background=rounded(panel2,9f,line)},LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(3),0,dp(3))})
        }
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(compactButton("REALTIME"){copyText("REALTIME",copyText.toString().trim())},LinearLayout.LayoutParams(-1,dp(42)).apply{setMargins(0,dp(5),0,0)})
        showToolDialog(dialog,root,.96f,.88f)
    }

    private fun toolDialogRoot(dialog:Dialog,title:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(bg,18f,line)
        addView(LinearLayout(this@NetworkDebuggerActivity).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=rounded(panel,12f,line);setPadding(dp(5),dp(4),dp(8),dp(4))
            addView(chromeButton("×","Закрыть"){dialog.dismiss()},LinearLayout.LayoutParams(dp(40),dp(40)))
            addView(TextView(this@NetworkDebuggerActivity).apply{text=title;setTextColor(textColor);textSize=13f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(9),0,0,0);maxLines=2},LinearLayout.LayoutParams(0,dp(40),1f))
        },LinearLayout.LayoutParams(-1,dp(48)).apply{bottomMargin=dp(6)})
    }

    private fun showToolDialog(dialog:Dialog,root:View,widthFraction:Float,heightFraction:Float){
        dialog.setContentView(root)
        dialog.setOnShowListener{
            val dm=resources.displayMetrics
            dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setLayout((dm.widthPixels*widthFraction).toInt(),(dm.heightPixels*heightFraction).toInt());setGravity(Gravity.CENTER)}
        }
        dialog.show()
    }

    private fun showDetails(event:JSONObject,query:String){
        val url=event.optString("url","")
        val requestCookies=if(url.startsWith("http"))CookieManager.getInstance().getCookie(url).orEmpty() else ""
        val responseHeaders=event.optJSONObject("responseHeaders")
        val mime=event.optString("mimeType",NetworkDebuggerText.headerValue(responseHeaders,"Content-Type")).substringBefore(';').trim()
        val responseBody=responseBodyText(event)
        val requestHeadersList=NetworkDebuggerText.requestHeaderPairs(event)
        val responseHeadersList=NetworkDebuggerText.responseHeaderPairs(event)
        val bytes=NetworkRequestActions.responseBytes(this,url)
        val binary=(responseBody=="[binary]"||responseBody=="[non-text response]"||(bytes!=null&&bytes.isNotEmpty()&&NetworkDebuggerText.isBinaryPayload(mime,responseBody,bytes)))
        val imageBitmap=if(binary&&bytes!=null)try{BitmapFactory.decodeByteArray(bytes,0,bytes.size)}catch(_:Exception){null}else null
        val originalTexts=java.util.IdentityHashMap<TextView,CharSequence>()
        var decoded=false
        var dialog:AlertDialog?=null

        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(dp(10),dp(10),dp(10),dp(10))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(panel,14f,line);setPadding(dp(12),dp(10),dp(12),dp(10))}
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val status=event.optInt("status",0)
        titleRow.addView(compactButton("×"){dialog?.dismiss()},LinearLayout.LayoutParams(dp(42),dp(34)).apply{marginEnd=dp(7)})
        titleRow.addView(TextView(this).apply{
            text=buildString{append(methodOf(event));if(status>0)append("  ").append(status);if(event.has("duration"))append("  ").append(NetworkDebuggerText.formatDuration(event.optDouble("duration",0.0)));if(event.has("responseSize"))append("  ").append(NetworkDebuggerText.formatBytes(event.optLong("responseSize")))}
            setTextColor(if(status>=400||event.has("error"))bad else accent);textSize=14f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        },LinearLayout.LayoutParams(0,-2,1f))
        titleRow.addView(chip(responseKind(event),NetworkDebuggerRowPresentation.kindColor(responseKind(event),WebUiTheme.palette(this))))
        header.addView(titleRow)
        header.addView(TextView(this).apply{text=url;setTextColor(textColor);textSize=11f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(0,dp(7),0,0)})
        val flags=NetworkDebuggerRowPresentation.flags(event,changedIds)
        if(flags.isNotBlank())header.addView(TextView(this).apply{text=flags;setTextColor(amber);textSize=9f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);setPadding(0,dp(6),0,0)})
        root.addView(header,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(7))})

        val actionScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        actions.addView(detailButton("cURL"){copyText("cURL",NetworkDebuggerText.buildCurl(event))})
        actions.addView(detailButton("REQUEST"){copyText("REQUEST",NetworkDebuggerText.buildRequestText(event,requestCookies))})
        actions.addView(detailButton("REQ HEADERS"){copyText("REQUEST HEADERS",NetworkDebuggerText.formatHeaders(requestHeadersList))})
        actions.addView(detailButton("RESPONSE"){copyText("RESPONSE",NetworkDebuggerText.buildResponseCopy(event,requestCookies))})
        actions.addView(detailButton("RESP HEADERS"){copyText("RESPONSE HEADERS",NetworkDebuggerText.formatHeaders(responseHeadersList))})
        if(canFetchBody(event))actions.addView(detailButton("GET BODY"){
            val started=NetworkRequestActions.fetchMissingBody(this,event)
            Toast.makeText(this,if(started)"Запрошено содержимое ответа" else "Нельзя повторно получить этот ответ",Toast.LENGTH_SHORT).show()
        })
        actions.addView(detailButton("EDIT / REPLAY"){showReplayEditor(event)})
        if(responseKind(event)=="JSON"&&responseBody.isNotBlank())actions.addView(detailButton("JSON"){copyText("JSON",NetworkDebuggerText.prettyBody(responseBody,mime))})
        val decodeButton=detailButton("URL DECODE"){}
        actions.addView(decodeButton)
        actionScroll.addView(actions)
        root.addView(actionScroll,LinearLayout.LayoutParams(-1,dp(42)).apply{setMargins(0,0,0,dp(7))})

        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(10))}

        val requestPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        requestPanel.addView(codeText(highlightPlain(NetworkDebuggerText.buildRequestSummary(event),query)))
        val queryPairs=NetworkDebuggerText.queryPairs(url)
        if(queryPairs.isNotEmpty()){
            requestPanel.addView(subtitle("QUERY PARAMETERS"))
            requestPanel.addView(plainBlock(NetworkDebuggerText.formatPairs(queryPairs),query))
        }
        requestPanel.addView(subtitle("HEADERS"))
        requestPanel.addView(plainBlock(NetworkDebuggerText.formatHeaders(requestHeadersList),query))
        val formPairs=NetworkDebuggerText.requestFormPairs(event)
        if(formPairs.isNotEmpty()){
            requestPanel.addView(subtitle("FORM PARAMETERS"))
            requestPanel.addView(plainBlock(NetworkDebuggerText.formatPairs(formPairs),query))
        }
        addCollapsible(content,"REQUEST",true,requestPanel)

        val responsePanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        responsePanel.addView(codeText(highlightPlain(NetworkDebuggerText.buildResponseSummary(event),query)))
        responsePanel.addView(subtitle("HEADERS"))
        responsePanel.addView(plainBlock(NetworkDebuggerText.formatHeaders(responseHeadersList),query))
        addCollapsible(content,"RESPONSE",true,responsePanel)

        val bodyPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val requestBody=event.optString("requestBody","")
        if(requestBody.isNotBlank()){
            bodyPanel.addView(subtitle("REQUEST BODY"))
            val requestJson=parseJson(requestBody)
            if(requestJson!=null){
                bodyPanel.addView(jsonTreeView(requestJson,"REQUEST JSON"))
                bodyPanel.addView(subtitle("RAW REQUEST BODY"))
            }
            bodyPanel.addView(codeText(decorateResponseBody(requestBody,event.optString("requestMimeType",""),query)))
        }
        bodyPanel.addView(subtitle("RESPONSE BODY"))
        if(binary){
            val size=bytes?.size?.toLong()?:event.optLong("responseSize",-1L)
            val info=buildString{
                append(if(imageBitmap!=null)"Image payload\n" else "Binary payload\n")
                append("MIME: ").append(mime.ifBlank{"application/octet-stream"}).append('\n')
                if(size>=0)append("Size: ").append(NetworkDebuggerText.formatBytes(size)).append(" (").append(size).append(" bytes)\n")
                append("File: ").append(NetworkDebuggerText.suggestFileName(event,mime))
            }
            bodyPanel.addView(codeText(highlightPlain(info,query)))
            if(imageBitmap!=null)bodyPanel.addView(ImageView(this).apply{setImageBitmap(imageBitmap);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(panel2);contentDescription="Изображение из ответа сервера";setPadding(dp(6),dp(6),dp(6),dp(6))},LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(6),0,0)})
            if(bytes!=null)bodyPanel.addView(compactButton("СОХРАНИТЬ БИНАРНИК"){beginBinarySave(event,bytes,mime)},LinearLayout.LayoutParams(-1,dp(40)).apply{setMargins(0,dp(6),0,0)})
        }else{
            val responseJson=parseJson(responseBody)
            if(responseJson!=null){
                bodyPanel.addView(jsonTreeView(responseJson,"RESPONSE JSON"))
                bodyPanel.addView(subtitle("RAW RESPONSE BODY"))
            }
            bodyPanel.addView(codeText(decorateResponseBody(responseBody.ifBlank{"—"},mime,query)))
        }
        addCollapsible(content,"BODY",true,bodyPanel)

        addCollapsible(content,"TIMING",false,codeText(highlightPlain(NetworkDebuggerText.buildTimingText(event),query)))
        addCollapsible(content,"COOKIES",false,codeText(highlightPlain(requestCookies.ifBlank{"—"},query)))
        addCollapsible(content,"SOURCES",false,codeText(highlightPlain(NetworkDebuggerText.buildSourcesText(event),query)))
        val mergedRaw=event.optJSONArray("_mergedEvents")
        val rawText=if(mergedRaw!=null&&mergedRaw.length()>0)mergedRaw.toString(2) else event.toString(2)
        addCollapsible(content,"RAW",false,codeText(highlightPlain(rawText,query)))

        captureDisplayTexts(content,originalTexts)
        decodeButton.setOnClickListener{
            decoded=!decoded
            applyDecodedMode(content,originalTexts,decoded)
            decodeButton.text=if(decoded)"DECODED ✓" else "URL DECODE"
            decodeButton.setTextColor(if(decoded)accent else textColor)
        }

        val scroll=ScrollView(this).apply{setBackgroundColor(bg);addView(content)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val detailsDialog=AlertDialog.Builder(this).setView(root).create()
        dialog=detailsDialog
        val dm=resources.displayMetrics
        detailsDialog.setOnShowListener{
            detailsDialog.window?.apply{
                setBackgroundDrawable(rounded(bg,18f,line))
                setLayout((dm.widthPixels*0.97).toInt(),(dm.heightPixels*0.92).toInt())
                setGravity(Gravity.CENTER)
            }
        }
        detailsDialog.show()
    }

    private fun canFetchBody(event:JSONObject):Boolean{
        val body=responseBodyText(event)
        val method=methodOf(event)
        val url=event.optString("url","")
        return method=="GET"&&(url.startsWith("http://")||url.startsWith("https://"))&&(body.isBlank()||body=="[unavailable]")
    }

    private fun showReplayEditor(event:JSONObject){
        replayController.show(
            event = event,
            method = methodOf(event),
            headers = NetworkDebuggerText.formatHeaders(NetworkDebuggerText.requestHeaderPairs(event)).takeIf { it != "—" }.orEmpty()
        )
    }

    private fun jsonTreeView(rootValue:Any,title:String):View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(panel2,9f,line);setPadding(dp(6),dp(5),dp(6),dp(5))}
        addJsonNode(root,title,rootValue,0,false)
        return root
    }

    private fun addJsonNode(parent:LinearLayout,label:String,value:Any?,depth:Int,openInitially:Boolean){
        val indent=dp((depth.coerceAtMost(12))*10)
        when(value){
            is JSONObject,is JSONArray->{
                val count=if(value is JSONObject)value.length() else (value as JSONArray).length()
                val node=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                val children=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=if(openInitially)View.VISIBLE else View.GONE}
                var loaded=false
                val button=Button(this).apply{
                    text="${if(openInitially)"▾" else "▸"} $label  ${if(value is JSONObject)"{$count}" else "[$count]"}"
                    setTextColor(cyan);textSize=10f;typeface=Typeface.MONOSPACE;isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL;minHeight=0;minimumHeight=0;setPadding(indent+dp(8),0,dp(8),0);background=rounded(panel,8f,Color.TRANSPARENT)
                }
                fun load(){
                    if(loaded)return
                    loaded=true
                    var shown=0
                    val limit=300
                    if(value is JSONObject){
                        val keys=value.keys()
                        while(keys.hasNext()&&shown<limit){val key=keys.next();addJsonNode(children,key,value.opt(key),depth+1,false);shown++}
                    }else if(value is JSONArray){
                        for(i in 0 until minOf(value.length(),limit)){addJsonNode(children,"[$i]",value.opt(i),depth+1,false);shown++}
                    }
                    if(count>shown)children.addView(TextView(this).apply{text="… ещё ${count-shown} элементов (RAW содержит всё)";setTextColor(muted);textSize=9f;typeface=Typeface.MONOSPACE;setPadding(indent+dp(18),dp(6),dp(6),dp(6))})
                }
                if(openInitially)load()
                button.setOnClickListener{
                    if(children.visibility==View.VISIBLE){children.visibility=View.GONE;button.text="▸ $label  ${if(value is JSONObject)"{$count}" else "[$count]"}"}
                    else{load();children.visibility=View.VISIBLE;button.text="▾ $label  ${if(value is JSONObject)"{$count}" else "[$count]"}"}
                }
                node.addView(button,LinearLayout.LayoutParams(-1,dp(34)))
                node.addView(children)
                parent.addView(node)
            }
            else->{
                val shown=when(value){null,JSONObject.NULL->"null";is String->if(value.length>1200)value.take(1200)+"…" else value;else->value.toString()}
                parent.addView(TextView(this).apply{text="$label: $shown";setTextColor(textColor);textSize=10f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(indent+dp(8),dp(5),dp(8),dp(5))})
            }
        }
    }

    private fun parseJson(raw:String):Any?{
        val text=raw.trim()
        if(text.isBlank())return null
        return try{when{ text.startsWith("{")->JSONObject(text); text.startsWith("[")->JSONArray(text); else->null }}catch(_:Exception){null}
    }

    private fun detailButton(label:String,click:()->Unit)=compactButton(label,click).apply{typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);textSize=9f;layoutParams=LinearLayout.LayoutParams(-2,dp(38)).apply{marginEnd=dp(5)}}

    private fun chip(label:String,color:Int)=TextView(this).apply{text=label;setTextColor(color);textSize=10f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);gravity=Gravity.CENTER;setPadding(dp(8),dp(4),dp(8),dp(4));background=rounded(panel2,8f,line)}


    private fun subtitle(label:String)=TextView(this).apply{text=label;setTextColor(muted);textSize=9f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);letterSpacing=.08f;setPadding(dp(3),dp(6),dp(3),dp(4))}

    private fun plainBlock(raw:String,query:String)=TextView(this).apply{
        text=highlightPlain(raw.ifBlank{"—"},query);setTextColor(textColor);textSize=10.5f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(10),dp(9),dp(10),dp(9));background=rounded(panel2,9f,Color.rgb(40,64,70))
    }

    private fun addCollapsible(root:LinearLayout,title:String,open:Boolean,body:View){
        var expanded=open
        val button=Button(this).apply{text="$title  ${if(expanded)"▴" else "▾"}";setTextColor(cyan);textSize=10f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL;minHeight=0;minimumHeight=0;setPadding(dp(12),0,dp(12),0);background=rounded(panel,10f,line)}
        body.visibility=if(expanded)View.VISIBLE else View.GONE
        button.setOnClickListener{expanded=!expanded;body.visibility=if(expanded)View.VISIBLE else View.GONE;button.text="$title  ${if(expanded)"▴" else "▾"}"}
        root.addView(button,LinearLayout.LayoutParams(-1,dp(38)).apply{setMargins(0,dp(5),0,dp(4))});root.addView(body,LinearLayout.LayoutParams(-1,-2))
    }

    private fun copyText(label:String,value:String){
        ResultDelivery.deliverText(this,label,value)
    }

    private fun captureDisplayTexts(view:View,originals:MutableMap<TextView,CharSequence>){
        when(view){is Button->Unit;is TextView->originals[view]=SpannableString(view.text);is ViewGroup->for(i in 0 until view.childCount)captureDisplayTexts(view.getChildAt(i),originals)}
    }

    private fun applyDecodedMode(view:View,originals:Map<TextView,CharSequence>,decoded:Boolean){
        when(view){is Button->Unit;is TextView->{val original=originals[view]?:view.text;view.text=if(decoded)NetworkDebuggerText.decodePercentText(original.toString()) else original};is ViewGroup->for(i in 0 until view.childCount)applyDecodedMode(view.getChildAt(i),originals,decoded)}
    }

    private fun listTime(ms:Long)=listTimeFormat.format(Date(ms))

    private fun decorateResponseBody(raw:String,mime:String,query:String):CharSequence{
        val pretty=NetworkDebuggerText.prettyBody(raw,mime);val s=SpannableString(pretty)
        if(mime.contains("json",true)||pretty.trim().startsWith("{")||pretty.trim().startsWith("[")){
            colorRegex(s,Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)",RegexOption.DOT_MATCHES_ALL),cyan);colorRegex(s,Regex("(?<=:)\\s*\"(?:\\\\.|[^\"\\\\])*\"",RegexOption.DOT_MATCHES_ALL),accent);colorRegex(s,Regex("\\b(true|false|null)\\b"),violet);colorRegex(s,Regex("(?<![A-Za-z0-9_])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"),amber)
        }else if(mime.contains("html",true)||mime.contains("xml",true)||pretty.trim().startsWith("<")){
            colorRegex(s,Regex("</?[A-Za-z][^>]*>"),cyan);colorRegex(s,Regex("\\b[A-Za-z_:][-A-Za-z0-9_:.]*(?=\\s*=)"),accent);colorRegex(s,Regex("\"[^\"]*\"|'[^']*'"),amber)
        }
        applyQueryHighlight(s,query);return s
    }

    private fun highlightPlain(raw:String,query:String):CharSequence{val s=SpannableString(raw);applyQueryHighlight(s,query);return s}
    private fun colorRegex(s:SpannableString,r:Regex,color:Int){r.findAll(s.toString()).forEach{m->s.setSpan(ForegroundColorSpan(color),m.range.first,m.range.last+1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    private fun applyQueryHighlight(s:SpannableString,query:String){if(query.isBlank())return;var p=s.toString().indexOf(query,0,true);while(p>=0){s.setSpan(BackgroundColorSpan(Color.rgb(90,110,30)),p,p+query.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);p=s.toString().indexOf(query,p+query.length,true)}}

    private fun codeText(value:CharSequence)=TextView(this).apply{text=value;setTextColor(textColor);textSize=10.5f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(10),dp(9),dp(10),dp(9));background=rounded(panel2,9f,Color.rgb(40,64,70))}

    private fun beginBinarySave(event:JSONObject,bytes:ByteArray,mime:String){
        val safeMime=mime.ifBlank{"application/octet-stream"}
        ResultDelivery.deliverBytes(this,"Ответ",bytes,NetworkDebuggerText.suggestFileName(event,safeMime),safeMime)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        ResultDelivery.handleActivityResult(this,requestCode,resultCode,data)
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(fill:Int,radius:Float,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(radius.toInt()).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)}
    private fun compactButton(label:String,click:()->Unit)=Button(this).apply{text=label;setTextColor(textColor);textSize=10f;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(dp(10),0,dp(10),0);background=rounded(panel2,10f,line);setOnClickListener{click()}}


}
