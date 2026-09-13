package ru.evrasia.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionMatchPatternTest {
    @Test
    fun chatGptContentScriptPatternMatchesConversationAndRoot() {
        assertTrue(ExtensionMatchPattern.matches("https://chatgpt.com/*", "https://chatgpt.com/"))
        assertTrue(ExtensionMatchPattern.matches("https://chatgpt.com/*", "https://chatgpt.com/c/123"))
        assertFalse(ExtensionMatchPattern.matches("https://chatgpt.com/*", "https://example.com/c/123"))
    }

    @Test
    fun wildcardSchemeMatchesOnlyHttpAndHttps() {
        assertTrue(ExtensionMatchPattern.matches("*://mail.google.com/*", "http://mail.google.com/inbox"))
        assertTrue(ExtensionMatchPattern.matches("*://mail.google.com/*", "https://mail.google.com/inbox"))
        assertFalse(ExtensionMatchPattern.matches("*://mail.google.com/*", "file:///mail.google.com/inbox"))
    }

    @Test
    fun wildcardHostMatchesBaseDomainAndSubdomains() {
        assertTrue(ExtensionMatchPattern.matches("https://*.google.com/foo*bar", "https://google.com/foobar"))
        assertTrue(ExtensionMatchPattern.matches("https://*.google.com/foo*bar", "https://docs.google.com/foo/baz/bar"))
        assertFalse(ExtensionMatchPattern.matches("https://*.google.com/foo*bar", "https://google.net/foobar"))
    }

    @Test
    fun allUrlsSupportsPermittedSchemes() {
        assertTrue(ExtensionMatchPattern.matches("<all_urls>", "https://example.com/a"))
        assertTrue(ExtensionMatchPattern.matches("<all_urls>", "http://example.com/a"))
        assertTrue(ExtensionMatchPattern.matches("<all_urls>", "file:///tmp/a.html"))
        assertFalse(ExtensionMatchPattern.matches("<all_urls>", "data:text/plain,hello"))
    }

    @Test
    fun explicitPortIsRespected() {
        assertTrue(ExtensionMatchPattern.matches("https://example.com:8443/*", "https://example.com:8443/a"))
        assertFalse(ExtensionMatchPattern.matches("https://example.com:8443/*", "https://example.com/a"))
    }
}
