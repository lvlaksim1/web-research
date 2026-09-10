package ru.evrasia.research

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

internal class NetworkDebuggerDetailsController(
    private val activity: AppCompatActivity,
    private val changedIds: Set<Long>
) {
    private val palette get() = WebUiTheme.palette(activity)
    private val bg get() = palette.background
    private val panel get() = palette.card
    private val panel2 get() = palette.address
    private val line get() = palette.divider
    private val accent get() = palette.accent
    private val textColor get() = palette.text
    private val muted get() = palette.secondary
    private val bad get() = palette.red
    private val cyan get() = palette.accent
    private val amber get() = palette.orange
    private val violet get() = palette.blue

    private val replayController by lazy {
        NetworkReplayController(activity, bg, panel2, line, textColor, muted)
    }

    fun show(event: JSONObject, query: String) {
        val url = event.optString("url", "")
        val requestCookies =
            if (url.startsWith("http")) CookieManager.getInstance().getCookie(url).orEmpty() else ""
        val responseHeaders = event.optJSONObject("responseHeaders")
        val mime = event.optString(
            "mimeType",
            NetworkDebuggerText.headerValue(responseHeaders, "Content-Type")
        ).substringBefore(';').trim()
        val responseBody = NetworkEventClassifier.responseBodyText(event)
        val requestHeadersList = NetworkDebuggerText.requestHeaderPairs(event)
        val responseHeadersList = NetworkDebuggerText.responseHeaderPairs(event)
        val bytes = NetworkRequestActions.responseBytes(activity, url)
        val binary =
            responseBody == "[binary]" ||
                responseBody == "[non-text response]" ||
                (
                    bytes != null &&
                        bytes.isNotEmpty() &&
                        NetworkDebuggerText.isBinaryPayload(mime, responseBody, bytes)
                    )
        val imageBitmap =
            if (binary && bytes != null) {
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        val originalTexts = IdentityHashMap<TextView, CharSequence>()
        var decoded = false
        var dialog: AlertDialog? = null

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 14f, line)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = event.optInt("status", 0)
        titleRow.addView(
            compactButton("×") { dialog?.dismiss() },
            LinearLayout.LayoutParams(dp(42), dp(34)).apply { marginEnd = dp(7) }
        )
        titleRow.addView(
            TextView(activity).apply {
                text = buildString {
                    append(NetworkEventClassifier.methodOf(event))
                    if (status > 0) append("  ").append(status)
                    if (event.has("duration")) {
                        append("  ").append(
                            NetworkDebuggerText.formatDuration(event.optDouble("duration", 0.0))
                        )
                    }
                    if (event.has("responseSize")) {
                        append("  ").append(
                            NetworkDebuggerText.formatBytes(event.optLong("responseSize"))
                        )
                    }
                }
                setTextColor(if (status >= 400 || event.has("error")) bad else accent)
                textSize = 14f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val responseKind = NetworkEventClassifier.responseKind(event)
        titleRow.addView(
            chip(
                responseKind,
                NetworkDebuggerRowPresentation.kindColor(responseKind, palette)
            )
        )
        header.addView(titleRow)
        header.addView(
            TextView(activity).apply {
                text = url
                setTextColor(textColor)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(7), 0, 0)
            }
        )
        val flags = NetworkDebuggerRowPresentation.flags(event, changedIds)
        if (flags.isNotBlank()) {
            header.addView(
                TextView(activity).apply {
                    text = flags
                    setTextColor(amber)
                    textSize = 9f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
        root.addView(
            header,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(7)) }
        )

        val actionScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            detailButton("cURL") {
                copyText("cURL", NetworkDebuggerText.buildCurl(event))
            }
        )
        actions.addView(
            detailButton("REQUEST") {
                copyText("REQUEST", NetworkDebuggerText.buildRequestText(event, requestCookies))
            }
        )
        actions.addView(
            detailButton("REQ HEADERS") {
                copyText(
                    "REQUEST HEADERS",
                    NetworkDebuggerText.formatHeaders(requestHeadersList)
                )
            }
        )
        actions.addView(
            detailButton("RESPONSE") {
                copyText(
                    "RESPONSE",
                    NetworkDebuggerText.buildResponseCopy(event, requestCookies)
                )
            }
        )
        actions.addView(
            detailButton("RESP HEADERS") {
                copyText(
                    "RESPONSE HEADERS",
                    NetworkDebuggerText.formatHeaders(responseHeadersList)
                )
            }
        )
        if (canFetchBody(event)) {
            actions.addView(
                detailButton("GET BODY") {
                    val started = NetworkRequestActions.fetchMissingBody(activity, event)
                    Toast.makeText(
                        activity,
                        if (started) "Запрошено содержимое ответа"
                        else "Нельзя повторно получить этот ответ",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        actions.addView(
            detailButton("EDIT / REPLAY") {
                showReplayEditor(event)
            }
        )
        if (responseKind == "JSON" && responseBody.isNotBlank()) {
            actions.addView(
                detailButton("JSON") {
                    copyText(
                        "JSON",
                        NetworkDebuggerText.prettyBody(responseBody, mime)
                    )
                }
            )
        }
        val decodeButton = detailButton("URL DECODE") {}
        actions.addView(decodeButton)
        actionScroll.addView(actions)
        root.addView(
            actionScroll,
            LinearLayout.LayoutParams(-1, dp(42)).apply {
                setMargins(0, 0, 0, dp(7))
            }
        )

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val requestPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        requestPanel.addView(
            codeText(
                highlightPlain(
                    NetworkDebuggerText.buildRequestSummary(event),
                    query
                )
            )
        )
        val queryPairs = NetworkDebuggerText.queryPairs(url)
        if (queryPairs.isNotEmpty()) {
            requestPanel.addView(subtitle("QUERY PARAMETERS"))
            requestPanel.addView(
                plainBlock(
                    NetworkDebuggerText.formatPairs(queryPairs),
                    query
                )
            )
        }
        requestPanel.addView(subtitle("HEADERS"))
        requestPanel.addView(
            plainBlock(
                NetworkDebuggerText.formatHeaders(requestHeadersList),
                query
            )
        )
        val formPairs = NetworkDebuggerText.requestFormPairs(event)
        if (formPairs.isNotEmpty()) {
            requestPanel.addView(subtitle("FORM PARAMETERS"))
            requestPanel.addView(
                plainBlock(
                    NetworkDebuggerText.formatPairs(formPairs),
                    query
                )
            )
        }
        addCollapsible(content, "REQUEST", true, requestPanel)

        val responsePanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        responsePanel.addView(
            codeText(
                highlightPlain(
                    NetworkDebuggerText.buildResponseSummary(event),
                    query
                )
            )
        )
        responsePanel.addView(subtitle("HEADERS"))
        responsePanel.addView(
            plainBlock(
                NetworkDebuggerText.formatHeaders(responseHeadersList),
                query
            )
        )
        addCollapsible(content, "RESPONSE", true, responsePanel)

        val bodyPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val requestBody = event.optString("requestBody", "")
        if (requestBody.isNotBlank()) {
            bodyPanel.addView(subtitle("REQUEST BODY"))
            val requestJson = parseJson(requestBody)
            if (requestJson != null) {
                bodyPanel.addView(jsonTreeView(requestJson, "REQUEST JSON"))
                bodyPanel.addView(subtitle("RAW REQUEST BODY"))
            }
            bodyPanel.addView(
                codeText(
                    decorateResponseBody(
                        requestBody,
                        event.optString("requestMimeType", ""),
                        query
                    )
                )
            )
        }

        bodyPanel.addView(subtitle("RESPONSE BODY"))
        if (binary) {
            val size = bytes?.size?.toLong() ?: event.optLong("responseSize", -1L)
            val info = buildString {
                append(if (imageBitmap != null) "Image payload\n" else "Binary payload\n")
                append("MIME: ").append(mime.ifBlank { "application/octet-stream" }).append('\n')
                if (size >= 0) {
                    append("Size: ")
                        .append(NetworkDebuggerText.formatBytes(size))
                        .append(" (")
                        .append(size)
                        .append(" bytes)\n")
                }
                append("File: ").append(NetworkDebuggerText.suggestFileName(event, mime))
            }
            bodyPanel.addView(codeText(highlightPlain(info, query)))
            if (imageBitmap != null) {
                bodyPanel.addView(
                    ImageView(activity).apply {
                        setImageBitmap(imageBitmap)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(panel2)
                        contentDescription = "Изображение из ответа сервера"
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    },
                    LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, dp(6), 0, 0)
                    }
                )
            }
            if (bytes != null) {
                bodyPanel.addView(
                    compactButton("СОХРАНИТЬ БИНАРНИК") {
                        beginBinarySave(event, bytes, mime)
                    },
                    LinearLayout.LayoutParams(-1, dp(40)).apply {
                        setMargins(0, dp(6), 0, 0)
                    }
                )
            }
        } else {
            val responseJson = parseJson(responseBody)
            if (responseJson != null) {
                bodyPanel.addView(jsonTreeView(responseJson, "RESPONSE JSON"))
                bodyPanel.addView(subtitle("RAW RESPONSE BODY"))
            }
            bodyPanel.addView(
                codeText(
                    decorateResponseBody(
                        responseBody.ifBlank { "—" },
                        mime,
                        query
                    )
                )
            )
        }
        addCollapsible(content, "BODY", true, bodyPanel)

        addCollapsible(
            content,
            "TIMING",
            false,
            codeText(
                highlightPlain(
                    NetworkDebuggerText.buildTimingText(event),
                    query
                )
            )
        )
        addCollapsible(
            content,
            "COOKIES",
            false,
            codeText(
                highlightPlain(
                    requestCookies.ifBlank { "—" },
                    query
                )
            )
        )
        addCollapsible(
            content,
            "SOURCES",
            false,
            codeText(
                highlightPlain(
                    NetworkDebuggerText.buildSourcesText(event),
                    query
                )
            )
        )
        val mergedRaw = event.optJSONArray("_mergedEvents")
        val rawText =
            if (mergedRaw != null && mergedRaw.length() > 0) mergedRaw.toString(2)
            else event.toString(2)
        addCollapsible(
            content,
            "RAW",
            false,
            codeText(highlightPlain(rawText, query))
        )

        captureDisplayTexts(content, originalTexts)
        decodeButton.setOnClickListener {
            decoded = !decoded
            applyDecodedMode(content, originalTexts, decoded)
            decodeButton.text = if (decoded) "DECODED ✓" else "URL DECODE"
            decodeButton.setTextColor(if (decoded) accent else textColor)
        }

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(bg)
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val detailsDialog = AlertDialog.Builder(activity).setView(root).create()
        dialog = detailsDialog
        val dm = activity.resources.displayMetrics
        detailsDialog.setOnShowListener {
            detailsDialog.window?.apply {
                setBackgroundDrawable(rounded(bg, 18f, line))
                setLayout(
                    (dm.widthPixels * 0.97).toInt(),
                    (dm.heightPixels * 0.92).toInt()
                )
                setGravity(Gravity.CENTER)
            }
        }
        detailsDialog.show()
    }

    private fun canFetchBody(event: JSONObject): Boolean {
        val body = NetworkEventClassifier.responseBodyText(event)
        val method = NetworkEventClassifier.methodOf(event)
        val url = event.optString("url", "")
        return method == "GET" &&
            (url.startsWith("http://") || url.startsWith("https://")) &&
            (body.isBlank() || body == "[unavailable]")
    }

    private fun showReplayEditor(event: JSONObject) {
        replayController.show(
            event = event,
            method = NetworkEventClassifier.methodOf(event),
            headers = NetworkDebuggerText.formatHeaders(
                NetworkDebuggerText.requestHeaderPairs(event)
            ).takeIf { it != "—" }.orEmpty()
        )
    }

    private fun jsonTreeView(rootValue: Any, title: String): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel2, 9f, line)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        addJsonNode(root, title, rootValue, 0, false)
        return root
    }

    private fun addJsonNode(
        parent: LinearLayout,
        label: String,
        value: Any?,
        depth: Int,
        openInitially: Boolean
    ) {
        val indent = dp(depth.coerceAtMost(12) * 10)
        when (value) {
            is JSONObject, is JSONArray -> {
                val count =
                    if (value is JSONObject) value.length()
                    else (value as JSONArray).length()
                val node = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val children = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (openInitially) View.VISIBLE else View.GONE
                }
                var loaded = false
                val button = Button(activity).apply {
                    text =
                        "${if (openInitially) "▾" else "▸"} $label  " +
                            if (value is JSONObject) "{$count}" else "[$count]"
                    setTextColor(cyan)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(indent + dp(8), 0, dp(8), 0)
                    background = rounded(panel, 8f, Color.TRANSPARENT)
                }

                fun load() {
                    if (loaded) return
                    loaded = true
                    var shown = 0
                    val limit = 300
                    if (value is JSONObject) {
                        val keys = value.keys()
                        while (keys.hasNext() && shown < limit) {
                            val key = keys.next()
                            addJsonNode(
                                children,
                                key,
                                value.opt(key),
                                depth + 1,
                                false
                            )
                            shown++
                        }
                    } else if (value is JSONArray) {
                        for (i in 0 until minOf(value.length(), limit)) {
                            addJsonNode(
                                children,
                                "[$i]",
                                value.opt(i),
                                depth + 1,
                                false
                            )
                            shown++
                        }
                    }
                    if (count > shown) {
                        children.addView(
                            TextView(activity).apply {
                                text = "… ещё ${count - shown} элементов (RAW содержит всё)"
                                setTextColor(muted)
                                textSize = 9f
                                typeface = Typeface.MONOSPACE
                                setPadding(
                                    indent + dp(18),
                                    dp(6),
                                    dp(6),
                                    dp(6)
                                )
                            }
                        )
                    }
                }

                if (openInitially) load()
                button.setOnClickListener {
                    if (children.visibility == View.VISIBLE) {
                        children.visibility = View.GONE
                        button.text =
                            "▸ $label  " +
                                if (value is JSONObject) "{$count}" else "[$count]"
                    } else {
                        load()
                        children.visibility = View.VISIBLE
                        button.text =
                            "▾ $label  " +
                                if (value is JSONObject) "{$count}" else "[$count]"
                    }
                }
                node.addView(button, LinearLayout.LayoutParams(-1, dp(34)))
                node.addView(children)
                parent.addView(node)
            }

            else -> {
                val shown = when (value) {
                    null, JSONObject.NULL -> "null"
                    is String ->
                        if (value.length > 1200) value.take(1200) + "…" else value
                    else -> value.toString()
                }
                parent.addView(
                    TextView(activity).apply {
                        text = "$label: $shown"
                        setTextColor(textColor)
                        textSize = 10f
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        setPadding(
                            indent + dp(8),
                            dp(5),
                            dp(8),
                            dp(5)
                        )
                    }
                )
            }
        }
    }

    private fun parseJson(raw: String): Any? {
        val text = raw.trim()
        if (text.isBlank()) return null
        return try {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun detailButton(label: String, click: () -> Unit) =
        compactButton(label, click).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply {
                marginEnd = dp(5)
            }
        }

    private fun chip(label: String, color: Int) =
        TextView(activity).apply {
            text = label
            setTextColor(color)
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(panel2, 8f, line)
        }

    private fun subtitle(label: String) =
        TextView(activity).apply {
            text = label
            setTextColor(muted)
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .08f
            setPadding(dp(3), dp(6), dp(3), dp(4))
        }

    private fun plainBlock(raw: String, query: String) =
        TextView(activity).apply {
            text = highlightPlain(raw.ifBlank { "—" }, query)
            setTextColor(textColor)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(panel2, 9f, Color.rgb(40, 64, 70))
        }

    private fun addCollapsible(
        root: LinearLayout,
        title: String,
        open: Boolean,
        body: View
    ) {
        var expanded = open
        val button = Button(activity).apply {
            text = "$title  ${if (expanded) "▴" else "▾"}"
            setTextColor(cyan)
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(panel, 10f, line)
        }
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        button.setOnClickListener {
            expanded = !expanded
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            button.text = "$title  ${if (expanded) "▴" else "▾"}"
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(-1, dp(38)).apply {
                setMargins(0, dp(5), 0, dp(4))
            }
        )
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
    }

    private fun copyText(label: String, value: String) {
        ResultDelivery.deliverText(activity, label, value)
    }

    private fun captureDisplayTexts(
        view: View,
        originals: MutableMap<TextView, CharSequence>
    ) {
        when (view) {
            is Button -> Unit
            is TextView -> originals[view] = SpannableString(view.text)
            is ViewGroup ->
                for (index in 0 until view.childCount) {
                    captureDisplayTexts(view.getChildAt(index), originals)
                }
        }
    }

    private fun applyDecodedMode(
        view: View,
        originals: Map<TextView, CharSequence>,
        decoded: Boolean
    ) {
        when (view) {
            is Button -> Unit
            is TextView -> {
                val original = originals[view] ?: view.text
                view.text =
                    if (decoded) {
                        NetworkDebuggerText.decodePercentText(original.toString())
                    } else {
                        original
                    }
            }

            is ViewGroup ->
                for (index in 0 until view.childCount) {
                    applyDecodedMode(view.getChildAt(index), originals, decoded)
                }
        }
    }

    private fun decorateResponseBody(
        raw: String,
        mime: String,
        query: String
    ): CharSequence {
        val pretty = NetworkDebuggerText.prettyBody(raw, mime)
        val spannable = SpannableString(pretty)
        if (
            mime.contains("json", true) ||
            pretty.trim().startsWith("{") ||
            pretty.trim().startsWith("[")
        ) {
            colorRegex(
                spannable,
                Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)", RegexOption.DOT_MATCHES_ALL),
                cyan
            )
            colorRegex(
                spannable,
                Regex("(?<=:)\\s*\"(?:\\\\.|[^\"\\\\])*\"", RegexOption.DOT_MATCHES_ALL),
                accent
            )
            colorRegex(
                spannable,
                Regex("\\b(true|false|null)\\b"),
                violet
            )
            colorRegex(
                spannable,
                Regex("(?<![A-Za-z0-9_])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"),
                amber
            )
        } else if (
            mime.contains("html", true) ||
            mime.contains("xml", true) ||
            pretty.trim().startsWith("<")
        ) {
            colorRegex(
                spannable,
                Regex("</?[A-Za-z][^>]*>"),
                cyan
            )
            colorRegex(
                spannable,
                Regex("\\b[A-Za-z_:][-A-Za-z0-9_:.]*(?=\\s*=)"),
                accent
            )
            colorRegex(
                spannable,
                Regex("\"[^\"]*\"|'[^']*'"),
                amber
            )
        }
        applyQueryHighlight(spannable, query)
        return spannable
    }

    private fun highlightPlain(raw: String, query: String): CharSequence {
        val spannable = SpannableString(raw)
        applyQueryHighlight(spannable, query)
        return spannable
    }

    private fun colorRegex(
        spannable: SpannableString,
        regex: Regex,
        color: Int
    ) {
        regex.findAll(spannable.toString()).forEach { match ->
            spannable.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyQueryHighlight(
        spannable: SpannableString,
        query: String
    ) {
        if (query.isBlank()) return
        var position = spannable.toString().indexOf(query, 0, true)
        while (position >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(Color.rgb(90, 110, 30)),
                position,
                position + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            position = spannable.toString().indexOf(
                query,
                position + query.length,
                true
            )
        }
    }

    private fun codeText(value: CharSequence) =
        TextView(activity).apply {
            text = value
            setTextColor(textColor)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(panel2, 9f, Color.rgb(40, 64, 70))
        }

    private fun beginBinarySave(
        event: JSONObject,
        bytes: ByteArray,
        mime: String
    ) {
        val safeMime = mime.ifBlank { "application/octet-stream" }
        ResultDelivery.deliverBytes(
            activity,
            "Ответ",
            bytes,
            NetworkDebuggerText.suggestFileName(event, safeMime),
            safeMime
        )
    }

    private fun compactButton(label: String, click: () -> Unit) =
        Button(activity).apply {
            text = label
            setTextColor(textColor)
            textSize = 10f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(panel2, 10f, line)
            setOnClickListener { click() }
        }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun rounded(
        fill: Int,
        radius: Float,
        stroke: Int = Color.TRANSPARENT
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != Color.TRANSPARENT) {
                setStroke(dp(1), stroke)
            }
        }
}
