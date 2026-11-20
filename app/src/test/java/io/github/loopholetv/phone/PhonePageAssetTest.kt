package io.github.loopholetv.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks on the phone page asset itself: it loads nothing from outside the TV and stays small. */
class PhonePageAssetTest {
    private val file: File = listOf("src/main/assets/phone/index.html", "app/src/main/assets/phone/index.html")
        .map(::File)
        .firstOrNull { it.isFile }
        ?: error("phone/index.html not found from ${File("").absolutePath}")

    private val page: String = file.readText()

    @Test
    fun noExternalUrls() {
        val external = Regex("""(?:\bsrc\s*=|\bhref\s*=|\burl\(|\bfetch\()\s*["'`]?\s*(?:https?:)?//""", RegexOption.IGNORE_CASE)
        val hits = external.findAll(page).map { it.value }.toList()
        assertTrue("external URLs: $hits", hits.isEmpty())
        assertFalse(page.contains("http://", ignoreCase = true))
        assertFalse(page.contains("https://", ignoreCase = true))
    }

    @Test
    fun onlyTheHelperFetches() {
        assertEquals(1, Regex("""\bfetch\(""").findAll(page).count())
        val helper = functionBody("api")
        assertTrue("fetch( is outside api()", helper.contains("fetch("))
        assertTrue(helper.contains("\"X-Loophole\": \"1\""))
        assertTrue(helper.contains("credentials: \"same-origin\""))
    }

    @Test
    fun underSizeBudget() {
        assertTrue("${file.length()} bytes", file.length() < 120 * 1024)
    }

    @Test
    fun phonePageHintNamesCompanion() {
        assertTrue(page.contains("Open Settings → Companion on your TV to see the code"))
        assertFalse(page.contains("Phone page"))
    }

    @Test
    fun hasAllTabs() {
        for (tab in listOf("Playlists", "Channels", "Groups", "More")) {
            assertTrue("tab $tab", page.contains(">$tab</button>"))
        }
    }

    @Test
    fun neverParsesHtmlFromStrings() {
        for (sink in listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write")) {
            assertFalse(sink, page.contains(sink))
        }
    }

    /** The text of `function <name>(…) { … }`, braces matched. */
    private fun functionBody(name: String): String {
        val start = page.indexOf("function $name(")
        assertTrue("function $name not found", start >= 0)
        val open = page.indexOf('{', start)
        var depth = 0
        for (i in open until page.length) {
            when (page[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return page.substring(start, i + 1)
            }
        }
        error("function $name is not closed")
    }
}
