package ru.evrasia.research

import android.webkit.WebView
import java.io.File
import java.util.regex.Pattern

internal class ExtensionContentScriptController(private val web: WebView) {
    data class InstalledExtension(val root: File, val manifest: ExtensionManifest)

    private val extensions = mutableListOf<InstalledExtension>()

    fun replaceInstalled(items: List<InstalledExtension>) {
        extensions.clear()
        extensions.addAll(items)
    }

    fun inject(url: String, runAt: String) {
        extensions.forEach { extension ->
            extension.manifest.contentScripts.filter { it.runAt == runAt && matches(it, url) }.forEach { script ->
                script.css.forEach { relative ->
                    read(extension.root, relative)?.let { css ->
                        val escaped = JSONObjectQuote.quote(css)
                        web.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=$escaped;(document.head||document.documentElement).appendChild(s);})();", null)
                    }
                }
                script.js.forEach { relative -> read(extension.root, relative)?.let { web.evaluateJavascript(it, null) } }
            }
        }
    }

    private fun read(root: File, relative: String): String? {
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.canonicalFile.path + File.separator) || !target.isFile) return null
        return target.readText(Charsets.UTF_8)
    }

    private fun matches(script: ExtensionContentScript, url: String): Boolean {
        if (script.matches.none { matchPattern(it, url) }) return false
        return script.excludeMatches.none { matchPattern(it, url) }
    }

    private fun matchPattern(pattern: String, url: String): Boolean {
        if (pattern == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
        val regex = Pattern.quote(pattern).replace("\\*", "\\E.*\\Q")
        return Regex("^$regex$").matches(url)
    }
}

private object JSONObjectQuote {
    fun quote(value: String): String = org.json.JSONObject.quote(value)
}
