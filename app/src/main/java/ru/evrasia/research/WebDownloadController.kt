package ru.evrasia.research

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class WebDownloadController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val userAgent: String,
    private val record: (JSONObject) -> Unit
) {
    companion object {
        private const val BLOB_BRIDGE_NAME = "WebResearchDownloadBridge"
        private const val BLOB_CHUNK_BYTES = 96 * 1024
        private const val MAX_BASE64_CHUNK_CHARS = 192 * 1024
        private const val BLOB_SESSION_TTL_MS = 5 * 60 * 1000L
    }

    private data class BlobSession(
        val token: String,
        val url: String,
        val fileName: String,
        val requestedMimeType: String?,
        val listenerContentLength: Long,
        val createdAt: Long = System.currentTimeMillis(),
        var output: OutputStream? = null,
        var mediaUri: Uri? = null,
        var fallbackFile: File? = null,
        var mimeType: String = "application/octet-stream",
        var bytesWritten: Long = 0L,
        var started: Boolean = false
    )

    private data class BlobDestination(
        val output: OutputStream,
        val mediaUri: Uri? = null,
        val fallbackFile: File? = null
    )

    private val blobSessions = ConcurrentHashMap<String, BlobSession>()
    private val blobBridge = BlobDownloadBridge()

    @SuppressLint("AddJavascriptInterface")
    fun install() {
        web.addJavascriptInterface(blobBridge, BLOB_BRIDGE_NAME)
        web.setDownloadListener { url, suppliedUserAgent, contentDisposition, mimeType, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener

            when {
                url.startsWith("blob:", true) -> {
                    startBlobDownload(url, contentDisposition, mimeType, contentLength)
                }
                url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                    startHttpDownload(url, suppliedUserAgent, contentDisposition, mimeType, contentLength)
                }
                else -> {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Этот тип загрузки пока не поддерживается: ${Uri.parse(url).scheme ?: "unknown"}", Toast.LENGTH_LONG).show()
                    }
                    recordDownload(url, null, mimeType, contentLength, "unsupported-scheme")
                }
            }
        }
    }

    private fun startHttpDownload(
        url: String,
        suppliedUserAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        try {
            val fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("web research")
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                val effectiveUserAgent = suppliedUserAgent?.takeIf { it.isNotBlank() }
                    ?: web.settings.userAgentString?.takeIf { it.isNotBlank() }
                    ?: userAgent
                if (effectiveUserAgent.isNotBlank()) addRequestHeader("User-Agent", effectiveUserAgent)

                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)

                val referer = web.url
                if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer)
            }

            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            recordDownload(url, fileName, mimeType, contentLength, "queued", downloadId)
            activity.runOnUiThread {
                Toast.makeText(activity, "Скачивание началось: $fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            recordDownload(url, null, mimeType, contentLength, "error", error = e.toString())
            activity.runOnUiThread {
                Toast.makeText(activity, "Не удалось начать скачивание", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startBlobDownload(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        cleanupExpiredBlobSessions()

        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = ensureMimeExtension(sanitizeFileName(guessed), mimeType)
        val token = UUID.randomUUID().toString()
        val session = BlobSession(
            token = token,
            url = url,
            fileName = fileName,
            requestedMimeType = mimeType,
            listenerContentLength = contentLength
        )
        blobSessions[token] = session

        recordDownload(url, fileName, mimeType, contentLength, "blob-transfer-starting")
        activity.runOnUiThread {
            Toast.makeText(activity, "Подготовка файла: $fileName", Toast.LENGTH_SHORT).show()
            web.evaluateJavascript(blobTransferScript(url, token, mimeType), null)
        }
    }

    private fun blobTransferScript(url: String, token: String, fallbackMimeType: String?): String {
        val quotedUrl = JSONObject.quote(url)
        val quotedToken = JSONObject.quote(token)
        val quotedMime = JSONObject.quote(fallbackMimeType.orEmpty())
        return """
            (async function(){
              const bridge=window.$BLOB_BRIDGE_NAME;
              const token=$quotedToken;
              try{
                if(!bridge)throw new Error('Native download bridge is unavailable');
                const response=await fetch($quotedUrl);
                const mime=response.headers.get('content-type')||$quotedMime||'application/octet-stream';
                if(!bridge.begin(token,mime))throw new Error('Native download session could not start');

                const send=function(bytes){
                  for(let offset=0;offset<bytes.length;offset+=$BLOB_CHUNK_BYTES){
                    const chunk=bytes.subarray(offset,Math.min(bytes.length,offset+$BLOB_CHUNK_BYTES));
                    let binary='';
                    for(let i=0;i<chunk.length;i+=32768){
                      binary+=String.fromCharCode.apply(null,chunk.subarray(i,Math.min(chunk.length,i+32768)));
                    }
                    if(!bridge.write(token,btoa(binary)))throw new Error('Native download write failed');
                  }
                };

                if(response.body&&response.body.getReader){
                  const reader=response.body.getReader();
                  while(true){
                    const part=await reader.read();
                    if(part.done)break;
                    if(part.value&&part.value.length)send(part.value);
                  }
                }else{
                  send(new Uint8Array(await response.arrayBuffer()));
                }

                if(!bridge.finish(token))throw new Error('Native download finalization failed');
              }catch(error){
                try{bridge&&bridge.fail(token,String(error&&error.message?error.message:error));}catch(_){}
              }
            })();
        """.trimIndent()
    }

    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun begin(token: String, detectedMimeType: String): Boolean {
            val session = blobSessions[token] ?: return false
            synchronized(session) {
                if (session.started) return false
                return try {
                    val effectiveMime = detectedMimeType.takeIf { it.isNotBlank() }
                        ?: session.requestedMimeType?.takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                    val destination = createBlobDestination(session.fileName, effectiveMime)
                    session.output = destination.output
                    session.mediaUri = destination.mediaUri
                    session.fallbackFile = destination.fallbackFile
                    session.mimeType = effectiveMime
                    session.started = true
                    true
                } catch (e: Exception) {
                    blobSessions.remove(token)
                    recordDownload(
                        session.url,
                        session.fileName,
                        session.requestedMimeType,
                        session.listenerContentLength,
                        "blob-open-error",
                        error = e.toString()
                    )
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Не удалось создать файл для загрузки", Toast.LENGTH_LONG).show()
                    }
                    false
                }
            }
        }

        @JavascriptInterface
        fun write(token: String, base64Chunk: String): Boolean {
            val session = blobSessions[token] ?: return false
            if (base64Chunk.length > MAX_BASE64_CHUNK_CHARS) {
                failBlobSession(session, "Blob chunk is too large: ${base64Chunk.length} chars")
                return false
            }

            synchronized(session) {
                if (!session.started) return false
                return try {
                    val bytes = Base64.decode(base64Chunk, Base64.NO_WRAP)
                    session.output?.write(bytes) ?: return false
                    session.bytesWritten += bytes.size
                    true
                } catch (e: Exception) {
                    failBlobSession(session, e.toString())
                    false
                }
            }
        }

        @JavascriptInterface
        fun finish(token: String): Boolean {
            val session = blobSessions[token] ?: return false
            synchronized(session) {
                if (!session.started) return false
                return try {
                    session.output?.flush()
                    session.output?.close()
                    session.output = null
                    publishBlobDestination(session)
                    blobSessions.remove(token)
                    recordDownload(
                        session.url,
                        session.fileName,
                        session.mimeType,
                        session.bytesWritten,
                        "saved"
                    )
                    activity.runOnUiThread {
                        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            "Файл сохранён в Загрузки: ${session.fileName}"
                        } else {
                            "Файл сохранён: ${session.fallbackFile?.absolutePath ?: session.fileName}"
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }
                    true
                } catch (e: Exception) {
                    failBlobSession(session, e.toString())
                    false
                }
            }
        }

        @JavascriptInterface
        fun fail(token: String, error: String) {
            val session = blobSessions[token] ?: return
            failBlobSession(session, error.ifBlank { "Unknown blob download error" })
        }
    }

    private fun createBlobDestination(fileName: String, mimeType: String): BlobDestination {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore could not create a download")
            val output = resolver.openOutputStream(uri, "w")
            if (output == null) {
                resolver.delete(uri, null, null)
                error("MediaStore output stream is unavailable")
            }
            return BlobDestination(output = output, mediaUri = uri)
        }

        val folder = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(activity.filesDir, "downloads")
        folder.mkdirs()
        val file = uniqueFile(folder, fileName)
        return BlobDestination(output = file.outputStream(), fallbackFile = file)
    }

    private fun publishBlobDestination(session: BlobSession) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val uri = session.mediaUri ?: return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        activity.contentResolver.update(uri, values, null, null)
    }

    private fun failBlobSession(session: BlobSession, error: String) {
        synchronized(session) {
            blobSessions.remove(session.token)
            try {
                session.output?.close()
            } catch (_: Exception) {
            }
            session.output = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    session.mediaUri?.let { activity.contentResolver.delete(it, null, null) }
                } else {
                    session.fallbackFile?.delete()
                }
            } catch (_: Exception) {
            }

            recordDownload(
                session.url,
                session.fileName,
                session.mimeType,
                session.bytesWritten,
                "blob-error",
                error = error
            )
            activity.runOnUiThread {
                Toast.makeText(activity, "Не удалось сохранить blob-файл", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cleanupExpiredBlobSessions() {
        val now = System.currentTimeMillis()
        blobSessions.values
            .filter { now - it.createdAt > BLOB_SESSION_TTL_MS }
            .forEach { failBlobSession(it, "Blob download session expired") }
    }

    private fun ensureMimeExtension(fileName: String, mimeType: String?): String {
        if (fileName.substringAfterLast('.', "").isNotBlank()) return fileName
        val extension = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: return fileName
        return sanitizeFileName("$fileName.$extension")
    }

    private fun uniqueFile(folder: File, requestedName: String): File {
        val direct = File(folder, requestedName)
        if (!direct.exists()) return direct
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (index in 1..9999) {
            val candidate = File(folder, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
        }
        return File(folder, "${System.currentTimeMillis()}_$requestedName")
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.takeIf { it.isNotBlank() }?.take(180) ?: "download_${System.currentTimeMillis()}"
    }

    private fun recordDownload(
        url: String,
        fileName: String?,
        mimeType: String?,
        contentLength: Long,
        status: String,
        downloadId: Long? = null,
        error: String? = null
    ) {
        val event = JSONObject()
            .put("source", "download")
            .put("time", System.currentTimeMillis())
            .put("url", url)
            .put("status", status)
            .put("contentLength", contentLength)
        if (!fileName.isNullOrBlank()) event.put("fileName", fileName)
        if (!mimeType.isNullOrBlank()) event.put("mimeType", mimeType)
        if (downloadId != null) event.put("downloadId", downloadId)
        if (!error.isNullOrBlank()) event.put("error", error)
        record(event)
    }
}
