package ru.evrasia.research

import java.net.URI

internal object ExtensionMatchPattern {
    private val allUrlSchemes = setOf("http", "https", "file")

    fun matches(pattern: String, url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false

        if (pattern == "<all_urls>") {
            return scheme in allUrlSchemes
        }

        val separator = pattern.indexOf("://")
        if (separator <= 0) return false

        val schemePattern = pattern.substring(0, separator).lowercase()
        if (!schemeMatches(schemePattern, scheme)) return false

        val remainder = pattern.substring(separator + 3)
        val pathStart = remainder.indexOf('/')
        if (pathStart < 0) return false

        val authorityPattern = remainder.substring(0, pathStart)
        val pathPattern = remainder.substring(pathStart)

        if (scheme == "file") {
            if (authorityPattern.isNotEmpty()) return false
        } else {
            val authority = parseAuthority(authorityPattern) ?: return false
            val host = uri.host?.lowercase() ?: return false
            if (!hostMatches(authority.first, host)) return false

            val requestedPort = authority.second
            if (requestedPort != null && requestedPort != "*") {
                val expected = requestedPort.toIntOrNull() ?: return false
                val actual = if (uri.port >= 0) uri.port else defaultPort(scheme)
                if (actual != expected) return false
            }
        }

        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return wildcardMatches(pathPattern, path)
    }

    private fun schemeMatches(pattern: String, scheme: String): Boolean = when (pattern) {
        "*" -> scheme == "http" || scheme == "https"
        "http", "https", "file" -> pattern == scheme
        else -> false
    }

    private fun parseAuthority(authority: String): Pair<String, String?>? {
        if (authority.isEmpty()) return null

        val lastColon = authority.lastIndexOf(':')
        if (lastColon > 0 && authority.indexOf(':') == lastColon) {
            return authority.substring(0, lastColon).lowercase() to authority.substring(lastColon + 1)
        }

        return authority.lowercase() to null
    }

    private fun hostMatches(pattern: String, host: String): Boolean {
        if (pattern == "*") return true
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(2)
            return host == suffix || host.endsWith(".$suffix")
        }
        return host == pattern
    }

    private fun wildcardMatches(pattern: String, value: String): Boolean {
        val regex = buildString {
            append('^')
            pattern.split('*').forEachIndexed { index, part ->
                if (index > 0) append(".*")
                append(Regex.escape(part))
            }
            append('$')
        }
        return Regex(regex).matches(value)
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}
