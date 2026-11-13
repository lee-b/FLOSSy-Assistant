package ai.flossyapp.test

import ai.flossyapp.ui.common.speakableText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The speech whitelist: what reaches the TTS engine is letters, digits,
 * whitespace, and the punctuation that carries prosody. Everything else -
 * markdown asterisks, underscores, code symbols, emoji, foreign scripts -
 * is stripped, because a TTS reads each of those out loud where the reader
 * wanted emphasis. The visible text is never touched; this is the speech
 * filter alone.
 */
class SpeakableTextTest {

    @Test
    fun plainSentencesPassThroughUnchanged() {
        val text = "Hey, how's it going? I'm doing well, thanks."
        assertEquals(text, speakableText(text))
    }

    @Test
    fun markdownEmphasisIsStripped() {
        // The model writes emphasis as asterisks; the TTS must not read
        // "star, star" out loud.
        assertEquals("I am very confident", speakableText("**I am *very* confident**"))
        assertEquals("underscored words", speakableText("_underscored_ words"))
    }

    @Test
    fun codeSymbolsAreStripped() {
        // Symbols are replaced by nothing, so the surrounding spaces remain:
        // the TTS hears "x y z", not "xyz".
        assertEquals("x  y  z", speakableText("x = y + #z"))
        // Whitespace (including a tab) survives: it carries pauses.
        assertEquals("a\tb", speakableText("a\tb"))
        // A literal tab is whitespace and survives as itself.
        assertEquals("tab\tseparated", speakableText("tab\tseparated"))
    }

    @Test
    fun apostrophesAndQuotesSurvive() {
        // "it's" must not become "its": the apostrophe carries the sound.
        assertEquals("it's John's \"quote\"", speakableText("it's John's \"quote\""))
    }

    @Test
    fun foreignScriptsAndEmojiAreStripped() {
        // Trailing spaces are trimmed, so "hello " plus stripped scripts is
        // just "hello".
        assertEquals("hello", speakableText("hello 你好"))
        assertEquals("great", speakableText("great 🎉😀"))
        assertEquals("one two", speakableText("one two ελληνικά"))
    }

    @Test
    fun theFilterNeverTruncatesItOnlyDrops() {
        // The recurring suspicion that the filter stops at the first
        // non-speakable character and truncates the rest. `filter` keeps
        // every matching character in order, so the output must always be a
        // subsequence of the input - checked here character by character,
        // and every speakable input character must survive.
        val text = "A **bold** claim, 100% sure: see https://example.com! \ud83c\udf89"
        val result = speakableText(text)
        var at = 0
        for (c in result) {
            val found = text.indexOf(c, at)
            if (found < 0) {
                fail("result character '$c' is not a subsequence of the input - the filter truncated")
            }
            at = found + 1
        }
        for (c in text) {
            val speakable = c.isLetterOrDigit() && c.code < 0x250 ||
                c.isWhitespace() ||
                c in ".,'!?;:-()\"\u2019\u201c\u201d"
            if (speakable) {
                assertTrue("speakable character '$c' was dropped", result.contains(c))
            }
        }
    }

    @Test
    fun numbersAndUnitsSurvive() {
        assertEquals("It is 8:00 PM on 12.11.2026", speakableText("It is 8:00 PM on 12.11.2026"))
    }

    @Test
    fun aReplyOfOnlySymbolsBecomesEmptyOrProsodyOnly() {
        // The engine's "nothing speakable" fallback depends on the filter
        // agreeing with the synthesis: symbols-only text must strip to
        // nothing meaningful (the hyphen survives as prosody punctuation).
        assertEquals("", speakableText("*** ###"))
        assertEquals("---", speakableText("*** --- ###"))
    }
}
