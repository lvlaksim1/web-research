package ru.evrasia.research

import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

internal class ExtensionManagerUi(
    private val activity: AppCompatActivity,
    private val manager: ExtensionManager,
    private val onChanged: () -> Unit
) {
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { manager.install(uri) }
            .onSuccess { Toast.makeText(activity, "Установлено: ${it.manifest.name}", Toast.LENGTH_LONG).show(); onChanged(); show() }
            .onFailure { Toast.makeText(activity, "Не установлено: ${it.message}", Toast.LENGTH_LONG).show(); show() }
    }

    fun show() {
        val items = manager.infos()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 20, 36, 12) }
        body.addView(TextView(activity).apply { text = "Chromium Manifest V3. ZIP устанавливается напрямую, без магазина и внешней подписи."; textSize = 13f })
        if (items.isEmpty()) body.addView(TextView(activity).apply { text = "\nУстановленных расширений нет."; textSize = 14f })
        items.forEach { info ->
            val missing = info.compatibility.unsupportedFeatures
            body.addView(TextView(activity).apply {
                text = "\n${info.manifest.name} ${info.manifest.version}\n${info.compatibility.compatibility}" + if (missing.isEmpty()) "" else "\nНет API: ${missing.joinToString()}"
                textSize = 14f
                setOnClickListener { showExtension(info) }
            })
        }
        AlertDialog.Builder(activity).setTitle("Расширения").setView(body).setPositiveButton("Установить ZIP") { _, _ -> picker.launch(arrayOf("application/zip", "application/octet-stream")) }.setNegativeButton("Закрыть", null).show()
    }

    private fun showExtension(info: InstalledExtensionInfo) {
        AlertDialog.Builder(activity)
            .setTitle(info.manifest.name)
            .setMessage("Версия ${info.manifest.version}\n${info.compatibility.compatibility}\n\n${if (info.enabled) "Расширение включено" else "Расширение отключено"}")
            .setPositiveButton(if (info.enabled) "Отключить" else "Включить") { _, _ -> manager.setEnabled(info.id, !info.enabled); onChanged(); show() }
            .setNeutralButton("Удалить") { _, _ -> manager.uninstall(info.id); onChanged(); show() }
            .setNegativeButton("Назад") { _, _ -> show() }
            .show()
    }
}
