package ru.evrasia.research

import android.content.Context
import org.json.JSONObject
import java.io.File

internal class ExtensionManager(context: Context) {
    private val root = File(context.filesDir, "extensions").apply { mkdirs() }

    fun installed(): List<ExtensionContentScriptController.InstalledExtension> =
        root.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { directory ->
            val manifestFile = File(directory, "manifest.json")
            if (!manifestFile.isFile) return@mapNotNull null
            runCatching {
                ExtensionContentScriptController.InstalledExtension(directory, ExtensionManifest.parse(JSONObject(manifestFile.readText(Charsets.UTF_8))))
            }.getOrNull()
        }

    fun inspect(directory: File): Pair<ExtensionManifest, ExtensionCompatibilityReport> {
        val rootDirectory = directory.canonicalFile
        val manifestFile = File(rootDirectory, "manifest.json")
        require(manifestFile.isFile) { "manifest.json not found" }
        val manifest = ExtensionManifest.parse(JSONObject(manifestFile.readText(Charsets.UTF_8)))
        return manifest to ExtensionCompatibilityChecker.check(manifest)
    }
}
