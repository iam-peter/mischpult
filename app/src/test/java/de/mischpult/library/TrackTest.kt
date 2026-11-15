package de.mischpult.library

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How tracks are stored in crates and sets. */
class TrackTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun sourceIsStoredByName() {
        val online = Track("x1", TrackSource.ONLINE, "item", "Song", plugin = "src")
        val text = json.encodeToString(Track.serializer(), online)
        assertTrue(text, "\"source\":\"ONLINE\"" in text)
        assertEquals(online, json.decodeFromString(Track.serializer(), text))

        val local = Track("x2", TrackSource.LOCAL_FILE, "content://file", "Song")
        assertEquals(local, json.decodeFromString(Track.serializer(), json.encodeToString(Track.serializer(), local)))
    }

    @Test fun olderValuesReadAsOnline() {
        // Earlier versions stored online tracks under another name, without a plugin
        val old = json.decodeFromString(Track.serializer(), """{"id":"x3","source":"LEGACY","uri":"item","title":"Song"}""")
        assertEquals(TrackSource.ONLINE, old.source)
        assertEquals("", old.plugin)
    }
}
