package de.mischpult.source

import de.mischpult.source.plugin.PluginException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private fun Throwable.chain() = generateSequence(this) { it.cause }.take(5).toList()

private fun Throwable.plugin(): PluginException? = chain().firstNotNullOfOrNull { it as? PluginException }

/**
 * Why a track could not be loaded, in words for the person at the decks.
 * Online sources say it themselves (their plugin knows its service); here
 * only the network and local trouble get words. Short: it shows on a deck
 * card and in a one-line status bar.
 */
fun describeLoadError(e: Throwable): String {
    e.plugin()?.let { return it.message.orEmpty() }
    if (e.chain().any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException }) {
        return "No connection. Check the network"
    }
    return e.message ?: e.javaClass.simpleName
}

/**
 * The source refused this item itself (as its plugin reports), as opposed to
 * the network or a missing plugin: another upload of the same song may play.
 */
fun isRefusal(e: Throwable): Boolean = e.plugin()?.refused == true

/** A few words on why an item won't play, for one line in a result list. */
fun shortLoadError(e: Throwable): String = e.plugin()?.short ?: "Won't play"

/**
 * What to search for to find other uploads of [title] by [artist]: the song
 * and artist without the clutter around them. When the title already reads
 * "Artist - Song", the artist is left out.
 */
fun searchTermsFor(title: String, artist: String): String {
    val song = songTermsFor(title)
    val by = artist.trim()
    return if (by.isEmpty() || " - " in song || song.contains(by, ignoreCase = true)) song else "$song $by"
}

/** The song alone, without "(Official Video)", hashtags and everything after a "|". */
fun songTermsFor(title: String): String = title
    .substringBefore('|')
    .replace(Regex("""[\(\[][^)\]]*[\)\]]"""), " ")
    .replace(Regex("""#\S+"""), " ")
    .replace(Regex("""\s+"""), " ")
    .trim()
