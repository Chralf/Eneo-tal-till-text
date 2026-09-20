package se.iteca.eneotaltilltext.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import android.Manifest
import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Records mono AAC/M4A via AudioRecord so we can apply a mild digital gain
 * without OEM noise suppression from MediaRecorder.MIC.
 */
internal class GainedAacSegmentRecorder(
    private val outputFile: File,
    private val audioSource: Int,
    private val context: Context,
    private val sampleRateHz: Int = 48_000,
    private val bitRate: Int = 128_000,
    private val gain: Float = INPUT_GAIN,
) {
    companion object {
        /** ~+7.5 dB — lifts quiet OEM mics closer to typical iPhone levels. */
        const val INPUT_GAIN = 2.4f
    }

    private val running = AtomicBoolean(false)
    private val peakAmplitude = AtomicInteger(0)

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var error: Exception? = null

    val maxAmplitude: Int
        get() = peakAmplitude.getAndSet(0)

    fun start() {
        check(!running.get()) { "Recorder already started" }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Mikrofonbehörighet saknas"
        }
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuf > 0) { "Ogiltig AudioRecord-buffert" }
        val bufferBytes = minBuf * 2

        val record = AudioRecord(
            audioSource,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord kunde inte initieras")
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferBytes)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        audioRecord = record
        codec = encoder
        muxer = mediaMuxer
        error = null
        peakAmplitude.set(0)
        running.set(true)
        record.startRecording()

        worker = thread(name = "eneo-aac-record", isDaemon = true) {
            try {
                captureLoop(record, encoder, mediaMuxer, bufferBytes)
            } catch (e: Exception) {
                error = e
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        worker?.join(5_000L)
        worker = null
        releaseAll()
        error?.let { throw it }
    }

    fun cancel() {
        running.set(false)
        worker?.join(2_000L)
        worker = null
        releaseAll()
        error = null
        runCatching { if (outputFile.exists()) outputFile.delete() }
    }

    private fun captureLoop(
        record: AudioRecord,
        encoder: MediaCodec,
        mediaMuxer: MediaMuxer,
        bufferBytes: Int,
    ) {
        val pcm = ShortArray(bufferBytes / 2)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var trackIndex = -1
        var presentationTimeUs = 0L

        fun drain(endOfStream: Boolean) {
            val result = drainEncoder(encoder, mediaMuxer, info, muxerStarted, trackIndex, endOfStream)
            muxerStarted = result.first
            trackIndex = result.second
        }

        while (running.get()) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) {
                drain(endOfStream = false)
                continue
            }

            var peak = 0
            for (i in 0 until read) {
                val amplified = (pcm[i] * gain).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[i] = amplified.toShort()
                peak = maxOf(peak, abs(amplified))
            }
            peakAmplitude.updateAndGet { maxOf(it, peak.coerceAtMost(32767)) }

            var remaining = read
            var offset = 0
            while (remaining > 0) {
                val inputIndex = encoder.dequeueInputBuffer(10_000L)
                if (inputIndex < 0) break
                val input = encoder.getInputBuffer(inputIndex) ?: break
                input.clear()
                val samplesToWrite = minOf(remaining, input.capacity() / 2)
                for (i in 0 until samplesToWrite) {
                    input.putShort(pcm[offset + i])
                }
                val bytes = samplesToWrite * 2
                encoder.queueInputBuffer(inputIndex, 0, bytes, presentationTimeUs, 0)
                presentationTimeUs += (1_000_000L * samplesToWrite) / sampleRateHz
                offset += samplesToWrite
                remaining -= samplesToWrite
            }
            drain(endOfStream = false)
        }

        val inputIndex = encoder.dequeueInputBuffer(50_000L)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
        drain(endOfStream = true)
        if (muxerStarted) {
            runCatching { mediaMuxer.stop() }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        mediaMuxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        trackIndex: Int,
        endOfStream: Boolean,
    ): Pair<Boolean, Int> {
        var started = muxerStarted
        var track = trackIndex
        var idleTries = 0
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return started to track
                    idleTries++
                    if (idleTries > 50) return started to track
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "Encoder format changed twice" }
                    track = mediaMuxer.addTrack(encoder.outputFormat)
                    mediaMuxer.start()
                    started = true
                    idleTries = 0
                }
                outIndex >= 0 -> {
                    idleTries = 0
                    val encoded = encoder.getOutputBuffer(outIndex)
                    if (encoded != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && started) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        mediaMuxer.writeSampleData(track, encoded, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (eos) return started to track
                }
            }
        }
    }

    private fun releaseAll() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { muxer?.release() }
        muxer = null
    }
}
