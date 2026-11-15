package de.mischpult.audio

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * A decoded track: interleaved stereo int16 PCM, memory-mapped from the cache.
 * Mapping keeps multi-minute tracks off the Java heap and makes reloading a
 * cached track instant. Random access is what scratching and seeking need.
 *
 * A track can still be growing while it is played: [PcmWriter] appends decoded
 * audio and publishes it through [available]. Readers must not touch frames at
 * or beyond [available].
 */
class PcmTrack internal constructor(
    /** The cache-format file this track maps (the native engine maps it too). */
    private val file: File,
    val sampleRate: Int,
    data: ShortBuffer,
    expectedFrames: Int,
    available: Int,
    complete: Boolean,
    /** Frames the file currently has room for. */
    private var capacityFrames: Int,
) {
    // Plain field: only replaced before a write to the volatile [available],
    // so any reader that read [available] first sees a buffer covering it.
    private var data: ShortBuffer = data

    /** Frames decoded so far and safe to read. */
    @Volatile var available: Int = available
        private set

    /** True once decoding finished; [available] is then the track length. */
    @Volatile var complete: Boolean = complete
        private set

    /** Best guess at the final length; exact once [complete]. Use it for scaling. */
    @Volatile var expectedFrames: Int = expectedFrames
        private set

    /** Sample of [channel] (0/1) at integer [frame]. No bounds check: hot path. */
    fun sample(frame: Int, channel: Int): Short = data.get(frame * 2 + channel)

    /** Bulk copy, used for waveform analysis. [fromFrame] + [frameCount] ≤ [available]. */
    fun read(fromFrame: Int, dst: ShortArray, frameCount: Int) {
        val dup = data.duplicate()
        dup.position(fromFrame * 2)
        dup.get(dst, 0, frameCount * 2)
    }

    internal fun replaceData(newData: ShortBuffer, newCapacity: Int) {
        data = newData
        synchronized(this) {
            capacityFrames = newCapacity
            if (nativeHandle != 0L) NativeEngine.trackRemap(nativeHandle, newCapacity.toLong())
        }
    }

    internal fun publish(frames: Int) {
        if (frames > expectedFrames) expectedFrames = frames
        available = frames
        publishNative()
    }

    internal fun finish(frames: Int) {
        expectedFrames = frames
        available = frames
        complete = true
        publishNative()
    }

    // --- Native engine view -----------------------------------------------------
    // The audio engine maps the same file. One native handle per track, shared by
    // every deck/pad slot it is on, released when the last slot lets go.

    private var nativeHandle = 0L
    private var attachments = 0

    private fun publishNative() = synchronized(this) {
        if (nativeHandle != 0L) NativeEngine.trackPublish(nativeHandle, available, complete)
    }

    /** Handle for a deck/pad slot (0 if the file cannot be mapped). Pair with [detachNative]. */
    @Synchronized
    internal fun attachNative(): Long {
        if (nativeHandle == 0L) {
            val fd = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
            }.getOrNull() ?: return 0L
            nativeHandle = NativeEngine.trackCreate(fd, sampleRate, capacityFrames.toLong(), available, complete)
            if (nativeHandle == 0L) return 0L
        }
        attachments++
        return nativeHandle
    }

    @Synchronized
    internal fun detachNative() {
        if (attachments == 0) return
        if (--attachments == 0 && nativeHandle != 0L) {
            NativeEngine.trackRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    /**
     * Copies frames [from, to) into a new finished cache-format file at [dest],
     * e.g. to turn a deck's loop into a sampler pad.
     */
    fun copyRange(from: Int, to: Int, dest: File): PcmTrack {
        val start = from.coerceIn(0, available)
        val end = to.coerceIn(start, available)
        val tmp = File(dest.parentFile, dest.name + ".part")
        val writer = PcmWriter(tmp, sampleRate, end - start)
        try {
            val chunk = 1 shl 15
            val shorts = ShortArray(chunk * 2)
            val bytes = ByteBuffer.allocate(chunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            var at = start
            while (at < end) {
                val n = minOf(chunk, end - at)
                read(at, shorts, n)
                bytes.clear()
                bytes.asShortBuffer().put(shorts, 0, n * 2)
                bytes.limit(n * 4)
                writer.append(bytes)
                at += n
            }
            writer.finish()
        } finally {
            writer.close()
        }
        if (!tmp.renameTo(dest)) throw java.io.IOException("Could not store sample")
        return open(dest) ?: throw java.io.IOException("Sample unreadable")
    }

    companion object {
        const val MAGIC = 0x50444A31 // "PDJ1"
        const val HEADER_BYTES = 16

        /** Opens a finished cache file, or returns null if it is missing or incomplete. */
        fun open(file: File): PcmTrack? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < HEADER_BYTES) return null
                val ch = raf.channel
                val header = ch.map(FileChannel.MapMode.READ_ONLY, 0, HEADER_BYTES.toLong())
                    .order(ByteOrder.LITTLE_ENDIAN)
                if (header.getInt(0) != MAGIC) return null
                val rate = header.getInt(4)
                val frames = header.getLong(8)
                val bytes = frames * 4
                if (frames <= 0 || HEADER_BYTES + bytes > raf.length() || bytes > Int.MAX_VALUE) return null
                val body = ch.map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES.toLong(), bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val n = frames.toInt()
                PcmTrack(file, rate, body.asShortBuffer(), n, n, complete = true, capacityFrames = n)
            }
        }.getOrNull()
    }
}

/**
 * Writes a [PcmTrack] cache file while the track is already playable. The file
 * is pre-sized from the expected length and grown if the estimate was short.
 * The magic number is written last, so an interrupted file is never mistaken
 * for a finished one.
 */
class PcmWriter(private val file: File, sampleRate: Int, expectedFrames: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var capacity = expectedFrames.coerceAtLeast(sampleRate)
    private var body: MappedByteBuffer = map(capacity)
    private var frames = 0
    private var closed = false

    val track = PcmTrack(file, sampleRate, body.asShortBuffer(), expectedFrames, 0, complete = false, capacityFrames = capacity)

    private fun map(frameCapacity: Int): MappedByteBuffer {
        val bytes = frameCapacity.toLong() * 4
        raf.setLength(PcmTrack.HEADER_BYTES + bytes)
        return raf.channel.map(FileChannel.MapMode.READ_WRITE, PcmTrack.HEADER_BYTES.toLong(), bytes).also {
            it.order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /** Appends raw interleaved stereo int16 little-endian frames from [src]. */
    fun append(src: ByteBuffer) {
        val n = src.remaining() / 4
        if (n == 0) return
        if (frames + n > capacity) {
            capacity = maxOf(frames + n, (capacity * 1.5).toInt())
            body = map(capacity)
            track.replaceData(body.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), capacity)
        }
        val dst = body.duplicate()
        dst.position(frames * 4)
        dst.put(src)
        frames += n
        track.publish(frames)
    }

    /** Writes the header and marks the track complete. */
    fun finish(): PcmTrack {
        val header = ByteBuffer.allocate(PcmTrack.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(PcmTrack.MAGIC).putInt(track.sampleRate).putLong(frames.toLong()).flip()
        raf.channel.write(header, 0)
        track.finish(frames)
        close()
        return track
    }

    /** Stops writing; whatever was decoded stays playable. */
    override fun close() {
        if (closed) return
        closed = true
        if (!track.complete) track.finish(frames)
        // The mapping outlives the descriptor, so the track stays readable
        raf.close()
    }
}
