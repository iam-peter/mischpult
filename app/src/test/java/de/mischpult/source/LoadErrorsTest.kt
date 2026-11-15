package de.mischpult.source

import de.mischpult.source.plugin.PluginException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** How failures from source plugins and the network read on the deck and in result lists. */
class LoadErrorsTest {
    private val refusal = PluginException(
        "The source won't let other apps play this one. Try another upload of the song",
        "Refused", refused = true,
    )

    @Test fun pluginsSayWhatHappened() {
        assertEquals(refusal.message, describeLoadError(refusal))
        assertEquals("Refused", shortLoadError(refusal))
        assertTrue(isRefusal(refusal))
        // Also when wrapped on its way
        assertTrue(isRefusal(RuntimeException(refusal)))
        assertEquals(refusal.message, describeLoadError(RuntimeException(refusal)))
    }

    @Test fun otherFailuresAreNoRefusal() {
        val missing = PluginException("The X plugin is not installed any more", "Plugin missing", refused = false)
        assertFalse(isRefusal(missing))
        assertFalse(isRefusal(UnknownHostException("example.com")))
        assertEquals("No connection. Check the network", describeLoadError(UnknownHostException("example.com")))
        assertEquals("Won't play", shortLoadError(java.io.IOException("x")))
        assertEquals("disk full", describeLoadError(java.io.IOException("disk full")))
    }

    @Test fun searchTermsDropTheClutter() {
        // Real titles from search results
        assertEquals("Baby Shark Dance Pinkfong",
            searchTermsFor("Baby Shark Dance | #babyshark Most Viewed Video | Animal Songs | PINKFONG Songs for Children", "Pinkfong"))
        assertEquals("Daft Punk - Around The World", searchTermsFor("Daft Punk - Around The World (Official Music Video)", "Daft Punk"))
        assertEquals("Wheels on the Bus CoComelon", searchTermsFor("Wheels on the Bus [Official Video]", "CoComelon"))
        assertEquals("Das Model Kraftwerk", searchTermsFor("Das Model", "Kraftwerk"))
        // The artist is already part of the title
        assertEquals("Kraftwerk Das Model", searchTermsFor("Kraftwerk Das Model", "Kraftwerk"))
    }

    @Test fun songAloneForTheBroaderSearch() {
        assertEquals("Baby Shark Dance", songTermsFor("Baby Shark Dance | #babyshark Most Viewed Video | Animal Songs"))
        assertEquals("Wheels on the Bus", songTermsFor("Wheels on the Bus [Official Video] #nurseryrhymes"))
    }
}
