package me.rerere.search

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BingImageCandidatesTest {
    @Test fun extractsDirectImageAndSourcePageWithoutGuessingAuthor() {
        val html = """<a class="iusc" m='{"murl":"https://cdn.org/photo.jpg","purl":"https://site.org/story","t":"真实来源"}'></a><a class="iusc" m='{"murl":"https://cdn.org/photo.jpg"}'></a><a class="iusc" m='{"murl":"javascript:bad"}'></a><a class="iusc" m='invalid'></a>"""
        val result = BingSearchService.parseImageCandidates(Jsoup.parse(html))
        assertEquals(1, result.size)
        assertEquals("https://cdn.org/photo.jpg", result.single().url)
        assertEquals("https://site.org/story", result.single().sourceUrl)
        assertNull(result.single().author)
    }
    @Test fun captchaOrMissingImageMarkupReturnsNoCandidates() {
        assertTrue(BingSearchService.parseImageCandidates(Jsoup.parse("<html>captcha</html>")).isEmpty())
    }
}
