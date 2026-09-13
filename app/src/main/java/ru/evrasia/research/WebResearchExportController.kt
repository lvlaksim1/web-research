package ru.evrasia.research

import android.content.Intent
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class WebResearchExportController(
    private val activity: AppCompatActivity,
    private val archive: ResearchArchive,
    private val webProvider: () -> WebView,
    private val captureSnapshot: ((() -> Unit) -> Unit)
) {
    companion object {
        private const val SNAPSHOT_EXPORT_TIMEOUT_MS = 5_000L
    }

    fun exportWindow(startedAt: Long, endedAt: Long) {
        val delivered = AtomicBoolean(false)

        fun deliver() {
            if (!delivered.compareAndSet(false, true)) return
            if (activity.isFinishing || activity.isDestroyed) return
            val selectedArchive = archive.snapshotWindow(startedAt, maxOf(endedAt, System.currentTimeMillis()))
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            ResultDelivery.deliverGeneratedFile(activity, "Экспорт ZIP", "web-research-$stamp.zip", "application/zip") { output ->
                ResearchArchiveExporter(selectedArchive).writeZip(output, webProvider().url ?: "")
            }
        }

        captureSnapshot {
            webProvider().post { deliver() }
        }
        webProvider().postDelayed({ deliver() }, SNAPSHOT_EXPORT_TIMEOUT_MS)
    }

    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        ResultDelivery.handleActivityResult(activity, requestCode, resultCode, data)
}
