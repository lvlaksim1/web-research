package ru.evrasia.research

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

internal class CheckpointController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val archive: ResearchArchive,
    private val record: (JSONObject) -> Unit,
    private val onChanged: () -> Unit
) {
    companion object {
        private const val MAX_CHECKPOINTS = 40
        private const val MAX_SCREENSHOTS = 24
        private const val MAX_STATE_CHARS = 1_500_000
        private const val MIN_INTERVAL_MS = 120L
        private const val MAX_SCREENSHOT_PIXELS = 1_800_000.0
    }

    private var checkpointCount = 0
    private var screenshotCount = 0
    private var lastCheckpointAt = 0L
    private var checkpointLimitWarningSent = false
    private var screenshotLimitWarningSent = false

    fun reset() {
        checkpointCount = 0
        screenshotCount = 0
        lastCheckpointAt = 0L
        checkpointLimitWarningSent = false
        screenshotLimitWarningSent = false
    }

    fun request(reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        web.evaluateJavascript(WebResearchScripts.checkpoint(reason), null)
    }

    fun captureFromBrowser(reason: String, json: String) {
        if (json.length > MAX_STATE_CHARS) {
            record(
                CaptureWarning.create(
                    code = "checkpoint_state_oversize",
                    message = "A checkpoint state exceeded the native bridge limit and was omitted.",
                    stage = "checkpoint",
                    url = web.url ?: "",
                    details = JSONObject()
                        .put("reason", reason.take(80))
                        .put("payloadChars", json.length)
                        .put("limitChars", MAX_STATE_CHARS)
                )
            )
            return
        }

        val state = try {
            JSONObject(json)
        } catch (e: Exception) {
            record(
                CaptureWarning.create(
                    code = "checkpoint_parse_failed",
                    message = "A browser checkpoint state could not be parsed.",
                    stage = "checkpoint",
                    url = web.url ?: "",
                    error = e.toString(),
                    details = JSONObject().put("payloadChars", json.length)
                )
            )
            return
        }

        activity.runOnUiThread {
            captureOnUi(reason, state)
        }
    }

    private fun captureOnUi(reason: String, state: JSONObject) {
        if (activity.isFinishing || activity.isDestroyed) return
        val now = System.currentTimeMillis()

        if (checkpointCount >= MAX_CHECKPOINTS) {
            if (!checkpointLimitWarningSent) {
                checkpointLimitWarningSent = true
                record(
                    CaptureWarning.create(
                        code = "checkpoint_limit_reached",
                        message = "Checkpoint capture reached the configured per-session limit.",
                        stage = "checkpoint",
                        url = web.url ?: "",
                        details = JSONObject().put("limit", MAX_CHECKPOINTS)
                    )
                )
            }
            return
        }

        if (now - lastCheckpointAt < MIN_INTERVAL_MS) return

        val page = state.optString("url", web.url ?: "")
        state.put("nativeCookie", CookieManager.getInstance().getCookie(page).orEmpty())

        val screenshot = if (screenshotCount < MAX_SCREENSHOTS) {
            captureViewportScreenshot()?.also { screenshotCount++ }
        } else {
            if (!screenshotLimitWarningSent) {
                screenshotLimitWarningSent = true
                record(
                    CaptureWarning.create(
                        code = "checkpoint_screenshot_limit_reached",
                        message = "Checkpoint screenshots reached the configured per-session limit; later checkpoint state is still captured.",
                        stage = "checkpoint",
                        url = page,
                        details = JSONObject().put("limit", MAX_SCREENSHOTS)
                    )
                )
            }
            null
        }

        archive.addCheckpoint(reason.take(80), state, screenshot)
        checkpointCount++
        lastCheckpointAt = now
        onChanged()
    }

    private fun captureViewportScreenshot(): ByteArray? {
        val sourceWidth = web.width
        val sourceHeight = web.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val sourcePixels = sourceWidth.toDouble() * sourceHeight.toDouble()
        val scale = if (sourcePixels > MAX_SCREENSHOT_PIXELS) {
            sqrt(MAX_SCREENSHOT_PIXELS / sourcePixels).toFloat()
        } else {
            1f
        }

        val width = maxOf(1, (sourceWidth * scale).toInt())
        val height = maxOf(1, (sourceHeight * scale).toInt())
        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            web.draw(canvas)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
                output.toByteArray()
            }
        } catch (e: Exception) {
            record(
                CaptureWarning.create(
                    code = "checkpoint_screenshot_failed",
                    message = "A checkpoint viewport screenshot could not be captured.",
                    stage = "checkpoint-screenshot",
                    url = web.url ?: "",
                    error = e.toString()
                )
            )
            null
        } finally {
            bitmap?.recycle()
        }
    }
}
