package ai.opencyclon.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTest {
    @Test fun keepsAShortPhrase() = assertEquals("Picking the photos", Caption.clean("Picking the photos"))

    @Test fun trims() = assertEquals("Opening Photos", Caption.clean("  Opening Photos \t"))

    @Test fun dropsTrailingFullStop() {
        assertEquals("Picking the photos", Caption.clean("Picking the photos."))
        assertEquals("Picking the photos", Caption.clean("Picking the photos..."))
    }

    @Test fun cutsAt40CharactersOnAWordBoundary() {
        val cut = Caption.clean("Comparing the cheapest flights to Lisbon tomorrow")!!
        assertEquals("Comparing the cheapest flights to Lisbon", cut)
        assertTrue(cut.length <= Caption.MAX_LENGTH)
        assertEquals("Supercalifragilisticexpialidociousnessxx", Caption.clean("Supercalifragilisticexpialidociousnessxxyyzz"))
    }

    @Test fun rejectsLineBreaks() {
        assertNull(Caption.clean("Picking\nthe photos"))
        assertNull(Caption.clean("Picking\r\nphotos"))
    }

    @Test fun rejectsUrls() {
        assertNull(Caption.clean("Opening https://evil.example now"))
        assertNull(Caption.clean("Visiting www.example.org"))
        assertNull(Caption.clean("Checking example.com for you"))
    }

    @Test fun rejectsLongDigitRuns() {
        assertNull(Caption.clean("Calling 5551234567"))
        assertEquals("Entering code 123456", Caption.clean("Entering code 123456"))
    }

    @Test fun rejectsMoreThanEightWords() {
        assertNull(Caption.clean("one two three four five six seven eight nine"))
        assertEquals("one two three four five six seven eight", Caption.clean("one two three four five six seven eight"))
    }

    @Test fun blankIsNull() {
        assertNull(Caption.clean(null))
        assertNull(Caption.clean("   "))
        assertNull(Caption.clean("."))
    }
}
