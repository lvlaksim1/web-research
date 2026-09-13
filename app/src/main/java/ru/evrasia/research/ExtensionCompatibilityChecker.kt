package ru.evrasia.research

internal enum class ExtensionCompatibility { SUPPORTED, PARTIAL, UNSUPPORTED }

internal data class ExtensionCompatibilityReport(
    val compatibility: ExtensionCompatibility,
    val unsupportedFeatures: List<String>
)

internal object ExtensionCompatibilityChecker {
    private val supportedPermissions = setOf("storage")
    private val deferredPermissions = setOf("downloads", "tabs", "activeTab", "scripting", "cookies", "webRequest")

    fun check(manifest: ExtensionManifest): ExtensionCompatibilityReport {
        val missing = linkedSetOf<String>()
        manifest.permissions.forEach { permission ->
            when {
                permission in supportedPermissions -> Unit
                permission in deferredPermissions -> missing += "chrome.$permission"
                else -> missing += "permission:$permission"
            }
        }
        if (manifest.backgroundServiceWorker != null) missing += "background.service_worker"
        if (manifest.actionPopup != null) missing += "action.default_popup"
        val compatibility = when {
            manifest.manifestVersion != 3 -> ExtensionCompatibility.UNSUPPORTED
            missing.isEmpty() -> ExtensionCompatibility.SUPPORTED
            manifest.contentScripts.isNotEmpty() -> ExtensionCompatibility.PARTIAL
            else -> ExtensionCompatibility.UNSUPPORTED
        }
        return ExtensionCompatibilityReport(compatibility, missing.toList())
    }
}
