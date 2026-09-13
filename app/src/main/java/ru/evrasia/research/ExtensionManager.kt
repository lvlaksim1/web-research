package ru.evrasia.research

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

internal data class InstalledExtensionInfo(val id: String, val manifest: ExtensionManifest, val compatibility: ExtensionCompatibilityReport, val enabled: Boolean)

internal class ExtensionManager(private val context: Context) {
    private val root = File(context.filesDir, "extensions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("extensions", Context.MODE_PRIVATE)

    fun installed(): List<ExtensionContentScriptController.InstalledExtension> = infos().filter { it.enabled }.map { ExtensionContentScriptController.InstalledExtension(File(root, it.id), it.manifest) }

    fun infos(): List<InstalledExtensionInfo> = root.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { directory ->
        runCatching {
            val manifest = ExtensionManifest.parse(JSONObject(File(directory, "manifest.json").readText(Charsets.UTF_8)))
            InstalledExtensionInfo(directory.name, manifest, ExtensionCompatibilityChecker.check(manifest), prefs.getBoolean("enabled:${directory.name}", true))
        }.getOrNull()
    }

    fun install(uri: Uri): InstalledExtensionInfo {
        val temp = File(context.cacheDir, "extension-import").apply { deleteRecursively(); mkdirs() }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(temp, entry.name).canonicalFile
                    require(target.path.startsWith(temp.canonicalPath + File.separator))
                    if (entry.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        val packageRoot = if (File(temp, "manifest.json").isFile) temp else temp.listFiles().orEmpty().firstOrNull { it.isDirectory && File(it, "manifest.json").isFile } ?: error("manifest.json not found")
        val manifest = ExtensionManifest.parse(JSONObject(File(packageRoot, "manifest.json").readText(Charsets.UTF_8)))
        val report = ExtensionCompatibilityChecker.check(manifest)
        require(report.compatibility != ExtensionCompatibility.UNSUPPORTED) { "Unsupported extension: ${report.unsupportedFeatures.joinToString()}" }
        val id = manifest.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "extension" }
        val destination = File(root, id)
        destination.deleteRecursively(); packageRoot.copyRecursively(destination, overwrite = true); temp.deleteRecursively()
        prefs.edit().putBoolean("enabled:$id", true).apply()
        return InstalledExtensionInfo(id, manifest, report, true)
    }

    fun setEnabled(id: String, enabled: Boolean) { prefs.edit().putBoolean("enabled:$id", enabled).apply() }
    fun uninstall(id: String) { File(root, id).deleteRecursively(); prefs.edit().remove("enabled:$id").apply() }
}
