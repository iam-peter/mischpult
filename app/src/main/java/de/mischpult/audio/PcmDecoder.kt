package de.mischpult.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any container/codec Android supports (M4A/AAC, WebM/Opus, MP3, …)
 * into the [PcmTrack] cache format, from online downloads and local files alike.
 *
 * Decoding is progressive: [onStart] hands out the track as soon as the output
 * format is known, and [onProgress] reports every chunk appended to it, so a
 * deck can play (and draw) the beginning while the rest is still decoding.
 */
object PcmDecoder {

    /** Points a [MediaExtractor] at the compressed source (file path, content URI, …). */
    fun interface Source {
        fun open(extractor: MediaExtractor)
    }

    fun fileSource(file: File) = Source { it.setDataSource(file.path) }
    fun uriSource(context: android.content.Context, uri: android.net.Uri) =
        Source { it.setDataSource(context, uri, null) }

    suspend fun decode(
        input: Source,
        output: File,
        onStart: (PcmTrack) -> Unit,
        onProgress: (frames: Int, fraction: Float, done: Boolean) -> Unit,
    ): PcmTrack = withContext(Dispatchers.IO) { // blocking codec waits and file writes
        val started = SystemClock.elapsedRealtime()
        val extractor = MediaExtractor()
        input.open(extractor)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        val tmp = File(output.parentFile, output.name + ".part")
        var writer: PcmWriter? = null
        var finished = false

        // Created once the real output rate is known (HE-AAC reports half of it in the container)
        fun writer(): PcmWriter = writer ?: run {
            val expected = if (durationUs > 0) {
                (durationUs * sampleRate / 1_000_000L).toInt() + sampleRate // +1 s slack
            } else {
                sampleRate * 600
            }
            PcmWriter(tmp, sampleRate, expected).also {
                writer = it
                onStart(it.track)
            }
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var scratch = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var frames = 0

            while (true) {
                ensureActive()
                // Feed every free input buffer without blocking…
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // …then wait briefly for decoded output
                val outIdx = codec.dequeueOutputBuffer(info, 2_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (writer != null && rate != sampleRate) {
                        Log.w(TAG, "Sample rate changed mid-stream ($sampleRate → $rate), ignoring")
                    } else {
                        sampleRate = rate
                    }
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    writer()
                } else if (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.order(ByteOrder.nativeOrder())
                    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val inFrames = info.size / (bytesPerSample * channels)
                    if (inFrames > 0) {
                        if (scratch.capacity() < inFrames * 4) {
                            scratch = ByteBuffer.allocateDirect(inFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
                        }
                        scratch.clear()
                        toStereo16(out, inFrames, channels, encoding, scratch)
                        scratch.flip()
                        writer().append(scratch)
                        frames += inFrames
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    val fraction = if (durationUs > 0) {
                        (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    } else 0f
                    if (inFrames > 0) onProgress(frames, fraction, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }

            val w = writer ?: throw IOException("Decoder produced no audio")
            if (frames == 0) throw IOException("Decoder produced no audio")
            val track = w.finish()
            if (!tmp.renameTo(output)) throw IOException("Could not store decoded audio")
            finished = true
            Log.i(TAG, "$mime → $frames frames @ $sampleRate Hz in ${SystemClock.elapsedRealtime() - started} ms")
            onProgress(frames, 1f, true)
            track
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
            if (!finished) {
                // Keep what was decoded playable, but never cache a partial file
                writer?.close()
                tmp.delete()
            }
        }
    }

    private fun toStereo16(src: ByteBuffer, frames: Int, channels: Int, encoding: Int, dst: ByteBuffer) {
        if (encoding != AudioFormat.ENCODING_PCM_FLOAT && channels == 2 && src.order() == ByteOrder.LITTLE_ENDIAN) {
            dst.put(src) // already our format: the common case
        } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val f = src.asFloatBuffer()
            for (i in 0 until frames) {
                val l = f.get(i * channels)
                val r = if (channels > 1) f.get(i * channels + 1) else l
                dst.putShort(floatTo16(l))
                dst.putShort(floatTo16(r))
            }
        } else {
            val s = src.asShortBuffer()
            for (i in 0 until frames) {
                val l = s.get(i * channels)
                val r = if (channels > 1) s.get(i * channels + 1) else l
                dst.putShort(l)
                dst.putShort(r)
            }
        }
    }

    private fun floatTo16(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private const val TAG = "PcmDecoder"
}
