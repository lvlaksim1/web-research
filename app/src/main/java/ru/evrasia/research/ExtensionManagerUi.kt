package ru.evrasia.research

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

internal class ExtensionManagerUi(
    private val activity: AppCompatActivity,
    private val manager: ExtensionManager,
    private val onChanged: () -> Unit
) {
    private var activeDialog: Dialog? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { manager.install(uri) }
                .onSuccess {
                    Toast.makeText(activity, "Установлено: ${it.manifest.name}", Toast.LENGTH_SHORT).show()
                    onChanged()
                    show()
                }
                .onFailure {
                    Toast.makeText(activity, "Не установлено: ${it.message}", Toast.LENGTH_LONG).show()
                    show()
                }
        }
    }

    fun show() {
        activeDialog?.dismiss()

        val palette = WebUiTheme.palette(activity)
        val dialog = Dialog(activity)
        activeDialog = dialog
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = rounded(palette.card, 22f, palette.divider)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = "Расширения"
            setTextColor(palette.text)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(iconButton(
            kind = TechIconDrawable.Kind.CLOSE,
            description = "Закрыть",
            color = palette.accent
        ) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)

        panel.addView(TextView(activity).apply {
            text = "Chromium Manifest V3 · ZIP устанавливается напрямую, без магазина и внешней подписи"
            setTextColor(palette.secondary)
            textSize = 11.5f
            setPadding(dp(8), 0, dp(8), dp(10))
        })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        scroll.addView(list, android.view.ViewGroup.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        renderExtensions(list, dialog, palette)

        panel.addView(primaryButton("Установить ZIP", palette) {
            dialog.dismiss()
            picker.launch(arrayOf("application/zip", "application/octet-stream"))
        }, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(10)
        })

        dialog.setContentView(panel)
        val metrics = activity.resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = metrics.widthPixels
                height = minOf((metrics.heightPixels * 0.74f).toInt(), dp(610))
                dimAmount = 0.48f
            }
        }
        dialog.show()
    }

    private fun renderExtensions(
        list: LinearLayout,
        dialog: Dialog,
        palette: WebUiTheme.Palette
    ) {
        val items = manager.infos()
        if (items.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "Установленных расширений пока нет"
                setTextColor(palette.secondary)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(42), dp(16), dp(42))
            })
            return
        }

        items.forEachIndexed { index, info ->
            if (index > 0) {
                list.addView(View(activity).apply {
                    setBackgroundColor(palette.background)
                }, LinearLayout.LayoutParams(-1, dp(8)))
            }
            list.addView(extensionCard(info, dialog, palette))
        }
    }

    private fun extensionCard(
        info: InstalledExtensionInfo,
        dialog: Dialog,
        palette: WebUiTheme.Palette
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(10))
            background = rounded(palette.address, 16f, palette.divider)
        }

        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(activity).apply {
            text = info.manifest.name
            setTextColor(palette.text)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        labels.addView(TextView(activity).apply {
            text = "Версия ${info.manifest.version}"
            setTextColor(palette.secondary)
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        })
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        top.addView(iconButton(
            kind = TechIconDrawable.Kind.DELETE,
            description = "Удалить расширение",
            color = palette.red
        ) {
            manager.uninstall(info.id)
            Toast.makeText(activity, "Удалено: ${info.manifest.name}", Toast.LENGTH_SHORT).show()
            onChanged()
            dialog.dismiss()
            show()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        card.addView(top)

        val compatibility = when (info.compatibility.compatibility) {
            ExtensionCompatibility.SUPPORTED -> "Поддерживается"
            ExtensionCompatibility.PARTIAL -> "Поддерживается частично"
            ExtensionCompatibility.UNSUPPORTED -> "Не поддерживается"
        }
        val unsupported = info.compatibility.unsupportedFeatures
        card.addView(TextView(activity).apply {
            text = if (unsupported.isEmpty()) compatibility else "$compatibility\nНет API: ${unsupported.joinToString()}"
            setTextColor(
                when (info.compatibility.compatibility) {
                    ExtensionCompatibility.SUPPORTED -> palette.green
                    ExtensionCompatibility.PARTIAL -> palette.orange
                    ExtensionCompatibility.UNSUPPORTED -> palette.red
                }
            )
            textSize = 11.5f
            setPadding(0, dp(8), 0, dp(10))
        })

        card.addView(stateButton(info, dialog, palette), LinearLayout.LayoutParams(-1, dp(42)))
        return card
    }

    private fun stateButton(
        info: InstalledExtensionInfo,
        dialog: Dialog,
        palette: WebUiTheme.Palette
    ): Button = Button(activity).apply {
        text = if (info.enabled) "Включено" else "Отключено"
        isAllCaps = false
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(if (info.enabled) WebUiTheme.contrastText(palette.accent) else palette.text)
        background = rounded(
            if (info.enabled) palette.accent else palette.card,
            13f,
            if (info.enabled) palette.accent else palette.divider
        )
        setOnClickListener {
            manager.setEnabled(info.id, !info.enabled)
            onChanged()
            dialog.dismiss()
            show()
        }
    }

    private fun primaryButton(
        label: String,
        palette: WebUiTheme.Palette,
        click: () -> Unit
    ) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(WebUiTheme.contrastText(palette.accent))
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        background = rounded(palette.accent, 15f, palette.accent)
        setOnClickListener { click() }
    }

    private fun iconButton(
        kind: TechIconDrawable.Kind,
        description: String,
        color: Int,
        click: () -> Unit
    ) = Button(activity).apply {
        text = ""
        contentDescription = description
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = ColorDrawable(Color.TRANSPARENT)
        foreground = TechIconDrawable(kind, color)
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
