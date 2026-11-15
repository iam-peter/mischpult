package de.mischpult.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the stereo master to an AAC .m4a in Music/Mischpult. The native engine
 * copies the master into a ring buffer on the audio thread; this class drains
 * it on its own thread, encodes and muxes. Nothing here can stall the audio.
 */
class MixRecorder(private val context: Context, private val sampleRate: Int) {

    /** What a finished recording is and where it went. */
    data class Result(val uri: Uri, val displayName: String, val durationMs: Long)

    @Volatile var recording = false
        private set
    /** Recorded audio so far, in milliseconds (the clock of the set list). */
    @Volatile var elapsedMs = 0L
        private set

    private var thread: Thread? = null
    private var result: Result? = null
    private var error: Throwable? = null

    fun start() {
        check(!recording)
        val name = "Mischpult " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date()) + ".m4a"
        val (uri, pfd) = createOutput(name)
        elapsedMs = 0
        result = null
        error = null
        recording = true
        NativeEngine.recordStart()
        thread = Thread({ run(uri, pfd, name) }, "Mischpult-recorder").apply { start() }
    }

    /** Stops, finishes the file and returns it (blocks briefly while the encoder drains). */
    fun stop(): Result? {
        if (!recording) return result
        recording = false
        NativeEngine.recordStop()
        thread?.join(5000)
        thread = null
        error?.let { Log.e(TAG, "recording failed", it) }
        return result
    }

    private fun createOutput(name: String): Pair<Uri, ParcelFileDescriptor> {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Mischpult")
                put(MediaStore.Audio.Media.IS_PENDING, 1) // hidden from other apps until finished
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("Could not create the recording file")
            uri to (resolver.openFileDescriptor(uri, "rw") ?: error("Could not open the recording file"))
        } else {
            // Before Android 10: the app's own music folder, no permission needed
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Mischpult").apply { mkdirs() }
            val file = File(dir, name)
            Uri.fromFile(file) to ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
            )
        }
    }

    private fun run(uri: Uri, pfd: ParcelFileDescriptor, name: String) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val floats = FloatArray(4096 * 2)
        var framesIn = 0L
        var inputDone = false

        fun drain(untilEos: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) muxer.writeSampleData(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            while (!inputDone) {
                val stopping = !recording
                val n = NativeEngine.recordRead(floats)
                if (n == 0 && !stopping) {
                    drain(false)
                    Thread.sleep(10)
                    continue
                }
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx < 0) { drain(false); continue }
                val input = codec.getInputBuffer(inIdx)!!
                input.clear()
                val frames = minOf(n, input.capacity() / 4)
                val shorts = input.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                for (i in 0 until frames * 2) shorts.put((floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                val ptsUs = framesIn * 1_000_000L / sampleRate
                framesIn += frames
                elapsedMs = framesIn * 1000L / sampleRate
                val last = stopping && n == 0
                codec.queueInputBuffer(inIdx, 0, frames * 4, ptsUs, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                if (last) inputDone = true
                drain(false)
            }
            drain(true)
            result = Result(uri, name, elapsedMs)
        } catch (t: Throwable) {
            error = t
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (muxing) muxer.stop() }
            muxer.release()
            pfd.close()
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }, null, null)
            }
            val dropped = NativeEngine.recordDropped()
            if (dropped > 0) Log.w(TAG, "recording dropped $dropped frames")
        }
    }

    private companion object {
        const val TAG = "MixRecorder"
    }
}
