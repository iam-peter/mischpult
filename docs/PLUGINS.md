# Source plugins

Mischpult plays the music files on the device. Online sources are not part of
it: each one is a separate Android app, a *source plugin*, that Mischpult
finds and talks to. A plugin brings its own code, licence and responsibilities;
Mischpult only defines how they talk.

## Finding plugins

A plugin is an app with an exported `ContentProvider` that declares an intent
filter for the action `de.mischpult.action.SOURCE_PLUGIN`:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<provider
    android:name=".SourceProvider"
    android:authorities="org.example.mysource.provider"
    android:exported="true">
    <intent-filter>
        <action android:name="de.mischpult.action.SOURCE_PLUGIN" />
    </intent-filter>
</provider>
```

Mischpult looks for such providers at start and whenever it comes to the front,
so a newly installed plugin shows up without restarting it. The Library's first
tab is named after the plugin and searches it (with several plugins installed,
the first one is used).

## Talking to a plugin

Everything goes through `ContentResolver.call()` on `content://<authority>`,
with a method name and a `Bundle`, plus one `openFile()` for the audio. The
constants are in [`PluginProtocol.kt`](../app/src/main/java/de/mischpult/source/plugin/PluginProtocol.kt);
a plugin keeps its own copy of that file. This is **protocol version 1**.

| Method | Mischpult sends | The plugin answers |
|---|---|---|
| `describe` | – | `protocol` (int, 1), `id` (stable, short), `name` (shown on the tab), `filters` and `filterLabels` (string lists) |
| `search` | `query`, `filter` (a key from `filters`) | `tracks`, `next` (token for more, absent at the end) |
| `more` | `next` | `tracks`, `next` |
| `open` | `link` (what the user pasted) | `tracks`; for a list also `playlist` (its name: Mischpult imports it as a crate) |
| `check` | `uri` | `plays` (boolean); if false, `short` (a few words why) |
| `prepare` | `uri` | `size` (bytes, -1 if unknown), `mime` (optional) |

A **track** is a `Bundle` in the `tracks` list (`putParcelableArrayList`) with
`id`, `uri`, `title`, `artist`, `duration` (seconds, long, -1 unknown) and
`thumbnail` (an image URL, optional).

- `id` names the track in Mischpult's library and cache for good: keep it
  stable and unique, e.g. with a short prefix of your own.
- `uri` is your own address of the item; Mischpult only hands it back to you.
- Clean up names for DJs: an `artist` should be the artist, not a channel or
  account name.

**Filters** run from narrow to broad. When the source refuses a track, Mischpult
offers to look for *other uploads* of the song and searches with the **last**
(broadest) filter, checking the first results with `check`.

## Audio

After `prepare`, Mischpult opens

```
content://<authority>/audio/<Uri.encode(uri)>      mode "r"
```

and reads it to the end: the bytes of an audio file Android's `MediaExtractor`
understands (AAC/M4A, MP3, Opus/WebM, FLAC, …), **in order**. Return a pipe
(`ParcelFileDescriptor.createReliablePipe()`) and write into it from a thread
of your own; download however your source wants it. When Mischpult stops
reading (the load was cancelled), your writes fail: stop then. If you fail,
close your end with `closeWithError(message)`; Mischpult shows the message.

`prepare` and `openFile` come close together; `prepare` is the place to find
the stream and report failures early, `openFile` may reuse what it found.

## Errors

Any call may answer with these keys instead of its result:

| Key | Meaning |
|---|---|
| `error` | A sentence for the person at the decks: what happened and, if possible, what to do |
| `short` | A few words for one line in a result list |
| `refused` | `true` if the source refused this item itself (rights, age, region, …): another upload of the same song may well play. `false` for network trouble, rate limits and the like |

## A minimal provider

```kotlin
class SourceProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = try {
        when (method) {
            "describe" -> Bundle().apply {
                putInt("protocol", 1)
                putString("id", "mysource")
                putString("name", "My Source")
                putStringArrayList("filters", arrayListOf("all"))
                putStringArrayList("filterLabels", arrayListOf("ALL"))
            }
            "search" -> Bundle().apply {
                putParcelableArrayList("tracks", search(extras!!.getString("query")!!))
            }
            "check" -> Bundle().apply { putBoolean("plays", true) }
            "prepare" -> Bundle().apply { putLong("size", -1) }
            else -> Bundle().apply { putString("error", "Not supported"); putString("short", "Not supported") }
        }
    } catch (e: Exception) {
        Bundle().apply { putString("error", e.message); putString("short", "Failed"); putBoolean("refused", false) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val item = uri.lastPathSegment!!
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> download(item, out) }
            } catch (e: Exception) {
                runCatching { write.closeWithError(e.message ?: "Download failed") }
            }
        }
        return read
    }

    // query/insert/update/delete/getType: not used, return null / 0
}
```
