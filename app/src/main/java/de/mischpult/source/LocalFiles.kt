package de.mischpult.source

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import de.mischpult.library.Track
import de.mischpult.library.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Audio files on the device, picked through the Storage Access Framework
 * (no storage permission needed). Access survives reboots because the picked
 * URIs' read grants are persisted.
 */
object LocalFiles {

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "webm", "mp4")

    /** Individually picked files (ACTION_OPEN_DOCUMENT). */
    suspend fun importDocuments(context: Context, uris: List<Uri>): List<Track> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            persist(context, uri)
            runCatching { toTrack(context, uri, displayName(context, uri)) }.getOrNull()
        }
    }

    /** A whole folder (ACTION_OPEN_DOCUMENT_TREE), scanned recursively for audio files. */
    suspend fun importTree(context: Context, tree: Uri): List<Track> = withContext(Dispatchers.IO) {
        persist(context, tree)
        val result = ArrayList<Track>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (mime.startsWith("audio/") || name.substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        runCatching { toTrack(context, uri, name) }.getOrNull()?.let(result::add)
                    }
                }
            }
        }
        result.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
    }

    /** Gives up a persisted grant once no crate refers to the file any more. */
    fun release(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun idOf(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return "local_" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** The file's display name, as the picker shows it. */
    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** Reads tags (title, artist, duration); falls back to the file name. */
    private fun toTrack(context: Context, uri: Uri, fileName: String): Track {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            return Track(
                id = idOf(uri),
                source = TrackSource.LOCAL_FILE,
                uri = uri.toString(),
                title = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
                artist = artist.orEmpty(),
                durationSec = durationMs?.div(1000) ?: -1,
            )
        } finally {
            mmr.release()
        }
    }
}
