package ru.evrasia.research

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class NetworkDebuggerRealtimeController(
    private val activity: AppCompatActivity
) {
    private val palette get() = WebUiTheme.palette(activity)
    private val bg get() = palette.background
    private val panel get() = palette.card
    private val panel2 get() = palette.address
    private val line get() = palette.divider
    private val accent get() = palette.accent
    private val textColor get() = palette.text
    private val muted get() = palette.secondary
    private val amber get() = palette.orange
    private val listTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun show(session: JSONObject) {
        val events = session.optJSONArray("_sessionEvents") ?: return
        val dialog = Dialog(activity)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)

        val root = dialogRoot(dialog, "Realtime session · ${events.length()} событий")
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(
            TextView(activity).apply {
                text = "${session.optString("_realtimeProtocol")}  ${session.optString("url")}"
                setTextColor(accent)
                textSize = 11f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextIsSelectable(true)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
        )

        val copied = StringBuilder()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val source = event.optString("source", "")
            val direction = when {
                source.endsWith("-send") -> "SEND"
                source.endsWith("-receive") || source.endsWith("-message") -> "RECEIVE"
                source.endsWith("-open") -> "OPEN"
                else -> source.uppercase(Locale.US)
            }
            val data = event.optString(
                "data",
                event.optString("message", event.optString("state", ""))
            )
            val displayLine =
                "${if (event.has("time")) listTime(event.optLong("time")) else "--:--:--.---"}  " +
                    direction +
                    if (data.isNotBlank()) "\n$data" else ""
            copied.append(displayLine).append("\n\n")
            body.addView(
                TextView(activity).apply {
                    text = displayLine
                    setTextColor(
                        when (direction) {
                            "SEND" -> amber
                            "RECEIVE" -> accent
                            else -> muted
                        }
                    )
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dp(9), dp(8), dp(9), dp(8))
                    background = rounded(panel2, 9f, line)
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, dp(3), 0, dp(3))
                }
            )
        }

        root.addView(
            ScrollView(activity).apply { addView(body) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        root.addView(
            compactButton("REALTIME") {
                ResultDelivery.deliverText(
                    activity,
                    "REALTIME",
                    copied.toString().trim()
                )
            },
            LinearLayout.LayoutParams(-1, dp(42)).apply {
                setMargins(0, dp(5), 0, 0)
            }
        )
        showDialog(dialog, root, .96f, .88f)
    }

    private fun dialogRoot(dialog: Dialog, title: String) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(bg, 18f, line)
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(panel, 12f, line)
                    setPadding(dp(5), dp(4), dp(8), dp(4))
                    addView(
                        compactButton("×") { dialog.dismiss() },
                        LinearLayout.LayoutParams(dp(40), dp(40))
                    )
                    addView(
                        TextView(activity).apply {
                            text = title
                            setTextColor(textColor)
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(9), 0, 0, 0)
                            maxLines = 2
                        },
                        LinearLayout.LayoutParams(0, dp(40), 1f)
                    )
                },
                LinearLayout.LayoutParams(-1, dp(48)).apply {
                    bottomMargin = dp(6)
                }
            )
        }

    private fun showDialog(
        dialog: Dialog,
        root: View,
        widthFraction: Float,
        heightFraction: Float
    ) {
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val dm = activity.resources.displayMetrics
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (dm.widthPixels * widthFraction).toInt(),
                    (dm.heightPixels * heightFraction).toInt()
                )
                setGravity(Gravity.CENTER)
            }
        }
        dialog.show()
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

    private fun listTime(ms: Long): String =
        listTimeFormat.format(Date(ms))

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
