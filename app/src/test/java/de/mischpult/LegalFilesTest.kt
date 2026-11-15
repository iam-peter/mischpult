package de.mischpult

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app ships its licence and the third-party notices in its assets; the
 * repository root holds the same files for people reading the source. These
 * checks keep the shipped copies from drifting, and make sure every licence
 * text the notices refer to is actually shipped.
 */
class LegalFilesTest {
    // Unit tests run in the app module's directory
    private val root = File("..")
    private val assets = File("src/main/assets/licenses")

    @Test fun shippedLicenceMatchesTheRepository() {
        assertEquals(File(root, "LICENSE").readText(), File(assets, "GPL-3.0.txt").readText())
    }

    @Test fun shippedNoticesMatchTheRepository() {
        assertEquals(File(root, "THIRD_PARTY_NOTICES.md").readText(), File(assets, "THIRD_PARTY_NOTICES.md").readText())
    }

    @Test fun everyLicenceTheNoticesNameIsShipped() {
        val named = Regex("`([A-Za-z0-9.-]+\\.txt)`").findAll(File(root, "THIRD_PARTY_NOTICES.md").readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue("notices name no licence files", named.isNotEmpty())
        for (name in named) assertTrue("$name is named in the notices but not shipped", File(assets, name).isFile)
    }
}
