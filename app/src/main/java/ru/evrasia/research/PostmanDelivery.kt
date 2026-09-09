package ru.evrasia.research

import android.app.Activity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object PostmanDelivery {
    fun deliver(activity: Activity, json: String, sourceUrl: String = "", environmentJson: String = "") {
        val host = try { java.net.URL(sourceUrl).host.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "request" } } catch (_: Exception) { "request" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        if (environmentJson.isBlank()) {
            ResultDelivery.deliverText(activity, "POSTMAN JSON", json, "postman-$host-$stamp.json", "application/json")
            return
        }
        ResultDelivery.deliverGeneratedFile(activity, "POSTMAN PACKAGE", "postman-$host-$stamp.zip", "application/zip") { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("postman-$host-$stamp.postman_collection.json"))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("postman-$host-$stamp.postman_environment.json"))
                zip.write(environmentJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
