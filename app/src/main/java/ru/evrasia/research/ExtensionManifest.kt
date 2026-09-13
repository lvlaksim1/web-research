package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

internal data class ExtensionContentScript(
    val matches: List<String>,
    val excludeMatches: List<String>,
    val js: List<String>,
    val css: List<String>,
    val runAt: String
)

internal data class ExtensionManifest(
    val manifestVersion: Int,
    val name: String,
    val version: String,
    val permissions: Set<String>,
    val hostPermissions: Set<String>,
    val contentScripts: List<ExtensionContentScript>,
    val backgroundServiceWorker: String?,
    val actionPopup: String?
) {
    companion object {
        fun parse(json: JSONObject): ExtensionManifest {
            val version = json.optInt("manifest_version", 0)
            require(version == 3) { "Only Chromium Manifest V3 is supported" }
            val name = json.optString("name").trim()
            val extensionVersion = json.optString("version").trim()
            require(name.isNotEmpty()) { "manifest.json: name is required" }
            require(extensionVersion.isNotEmpty()) { "manifest.json: version is required" }
            return ExtensionManifest(
                manifestVersion = version,
                name = name,
                version = extensionVersion,
                permissions = json.optJSONArray("permissions").strings().toSet(),
                hostPermissions = json.optJSONArray("host_permissions").strings().toSet(),
                contentScripts = parseContentScripts(json.optJSONArray("content_scripts")),
                backgroundServiceWorker = json.optJSONObject("background")?.optString("service_worker")?.takeIf { it.isNotBlank() },
                actionPopup = json.optJSONObject("action")?.optString("default_popup")?.takeIf { it.isNotBlank() }
            )
        }

        private fun parseContentScripts(array: JSONArray?): List<ExtensionContentScript> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ExtensionContentScript(
                    matches = item.optJSONArray("matches").strings(),
                    excludeMatches = item.optJSONArray("exclude_matches").strings(),
                    js = item.optJSONArray("js").strings(),
                    css = item.optJSONArray("css").strings(),
                    runAt = item.optString("run_at", "document_idle")
                )
            }
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
        }
    }
}
