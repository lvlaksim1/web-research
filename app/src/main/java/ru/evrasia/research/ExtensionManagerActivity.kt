package ru.evrasia.research

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ExtensionManagerActivity : AppCompatActivity() {
    private lateinit var manager: ExtensionManager
    private lateinit var list: LinearLayout
    private val pickZip = 4107

    override fun onCreate(savedInstanceState: Bundle?) {
        WebUiTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        title = "Расширения"
        manager = ExtensionManager(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(TextView(this).apply { text = "Расширения Chromium Manifest V3"; textSize = 20f })
        root.addView(TextView(this).apply { text = "Установка из ZIP без магазина. Web Research запускает только поддерживаемые стандартные chrome.* API."; textSize = 13f; setPadding(0, dp(8), 0, dp(12)) })
        root.addView(Button(this).apply { text = "Установить расширение из ZIP"; isAllCaps = false; setOnClickListener { chooseZip() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun chooseZip() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip" }, pickZip)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickZip || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { manager.install(uri) }.onSuccess {
            Toast.makeText(this, "Установлено: ${it.manifest.name}", Toast.LENGTH_LONG).show(); render()
        }.onFailure { Toast.makeText(this, "Не установлено: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun render() {
        list.removeAllViews()
        val items = manager.infos()
        if (items.isEmpty()) { list.addView(TextView(this).apply { text = "Установленных расширений нет"; gravity = Gravity.CENTER; setPadding(0, dp(30), 0, dp(20)) }); return }
        items.forEach { info ->
            val report = info.compatibility
            val status = when (report.compatibility) { ExtensionCompatibility.SUPPORTED -> "Поддерживается"; ExtensionCompatibility.PARTIAL -> "Частично поддерживается"; ExtensionCompatibility.UNSUPPORTED -> "Не поддерживается" }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(14)) }
            card.addView(TextView(this).apply { text = "${info.manifest.name}  ${info.manifest.version}"; textSize = 17f })
            card.addView(TextView(this).apply { text = status + if (report.unsupportedFeatures.isEmpty()) "" else "\nНет API: ${report.unsupportedFeatures.joinToString()}"; textSize = 12f })
            card.addView(Button(this).apply { text = if (info.enabled) "Отключить" else "Включить"; isAllCaps = false; setOnClickListener { manager.setEnabled(info.id, !info.enabled); render() } })
            card.addView(Button(this).apply { text = "Удалить"; isAllCaps = false; setOnClickListener { manager.uninstall(info.id); render() } })
            list.addView(card)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
