package ru.evrasia.research

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class NetworkDebuggerEventAdapter(
    private val activity: AppCompatActivity,
    private val items: List<JSONObject>,
    private val changedIds: Set<Long>,
    private val mergeMode: () -> Boolean
) : BaseAdapter() {
    private val listTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): JSONObject = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val event = getItem(position)
        val palette = WebUiTheme.palette(activity)
        val row = (convertView as? LinearLayout)
            ?.takeIf { it.findViewWithTag<TextView>("top") != null }
            ?: createRow(palette)

        val top = row.findViewWithTag<TextView>("top")
        val url = row.findViewWithTag<TextView>("url")
        val kind = row.findViewWithTag<TextView>("kind")
        val flagsView = row.findViewWithTag<TextView>("flags")

        if (NetworkEventClassifier.isActionEvent(event)) {
            row.background = rounded(palette.address, 8f, palette.divider)
            kind.visibility = View.GONE
            flagsView.visibility = View.GONE
            val whenText = if (event.has("time")) listTime(event.optLong("time")) else "--:--:--.---"
            top.text = "────  $whenText · ${NetworkDebuggerRowPresentation.actionLabel(event)}  ────"
            top.setTextColor(palette.orange)
            url.text = event.optString("page", "—")
            url.setTextColor(palette.secondary)
            return row
        }

        row.background = rounded(palette.card, 8f, Color.rgb(26, 48, 39))
        kind.visibility = View.VISIBLE

        if (NetworkEventClassifier.isRealtimeSession(event)) {
            val whenText = if (event.has("time")) listTime(event.optLong("time")) else "--:--:--.---"
            val method = NetworkEventClassifier.methodOf(event)
            top.text = "$whenText  $method  · ${event.optInt("_sessionCount", 0)} событий"
            top.setTextColor(palette.accent)
            kind.text = method
            kind.setTextColor(palette.accent)
            url.text = event.optString("url", "—")
            url.setTextColor(palette.secondary)
            flagsView.visibility = View.GONE
            return row
        }

        val status = event.optInt("status", 0)
        val source = NetworkDisplayMerger.sourceSummary(event, mergeMode())
        val method = NetworkEventClassifier.methodOf(event)
        val whenText = if (event.has("time")) listTime(event.optLong("time")) else "--:--:--.---"
        top.text = buildString {
            append(whenText).append("  ")
            append(if (NetworkEventClassifier.isJsEvent(event)) "JS" else method)
            if (status > 0) append("  ").append(status)
            if (event.has("duration")) {
                append("  ").append(NetworkDebuggerText.formatDuration(event.optDouble("duration", 0.0)))
            }
            if (event.has("responseSize")) {
                append("  ").append(NetworkDebuggerText.formatBytes(event.optLong("responseSize")))
            }
            append("  · ").append(source)
        }
        top.setTextColor(
            when {
                status >= 400 || event.has("error") -> palette.red
                NetworkEventClassifier.isJsEvent(event) || status in 200..399 -> palette.accent
                else -> palette.text
            }
        )

        val kindText = NetworkEventClassifier.responseKind(event)
        kind.text = kindText
        kind.setTextColor(NetworkDebuggerRowPresentation.kindColor(kindText, palette))
        url.text = event.optString("url", event.optString("message", "—"))
        url.setTextColor(palette.secondary)

        val flags = NetworkDebuggerRowPresentation.flags(event, changedIds)
        flagsView.text = flags
        flagsView.visibility = if (flags.isBlank()) View.GONE else View.VISIBLE
        flagsView.setTextColor(
            if (flags.contains("CHANGED") || NetworkDebuggerRowPresentation.hasAuth(event) || flags.contains("REPLAY")) {
                palette.orange
            } else {
                palette.secondary
            }
        )
        return row
    }

    private fun createRow(palette: WebUiTheme.Palette): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(activity).apply {
                            tag = "top"
                            textSize = 10.5f
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                            maxLines = 2
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    addView(
                        TextView(activity).apply {
                            tag = "kind"
                            textSize = 9f
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setPadding(dp(7), dp(3), dp(7), dp(3))
                            background = rounded(palette.address, 7f, palette.divider)
                        },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
                    )
                }
            )
            addView(
                TextView(activity).apply {
                    tag = "url"
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                    maxLines = 2
                    setPadding(0, dp(3), 0, 0)
                }
            )
            addView(
                TextView(activity).apply {
                    tag = "flags"
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

    private fun listTime(ms: Long): String = listTimeFormat.format(Date(ms))

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
}
