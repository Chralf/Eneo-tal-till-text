package se.iteca.eneotaltilltext.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Concatenates AAC-in-M4A segment files into a single M4A via MediaExtractor + MediaMuxer.
 */
object M4aSegmentConcatenator {
    /** One lock per final file so launch recovery and the detail view cannot write the same temp file at once. */
    private val outputLocks = ConcurrentHashMap<String, Any>()

    fun concatenate(parts: List<File>, output: File): Boolean {
        val lock = outputLocks.getOrPut(output.absolutePath) { Any() }
        return synchronized(lock) {
            merge(parts, output)
        }
    }

    private fun merge(parts: List<File>, output: File): Boolean {
        val readable = parts.filter { isReadableAudioPart(it) }
        if (readable.isEmpty()) {
            return output.exists() && output.length() > 1024
        }
        if (readable.size == 1) {
            return copyPartToOutput(readable[0], output)
        }
        val tempOutput = File(output.parentFile, "${output.nameWithoutExtension}_concat_tmp.m4a")
        if (tempOutput.exists()) tempOutput.delete()

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputTrack = -1
        var presentationTimeUs = 0L

        try {
            muxer = MediaMuxer(tempOutput.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            for (part in readable) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.absolutePath)
                    val trackIndex = selectAudioTrack(extractor) ?: continue
                    extractor.selectTrack(trackIndex)
                    val format = extractor.getTrackFormat(trackIndex)
                    if (!muxerStarted) {
                        outputTrack = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }
                    var segmentMaxPts = 0L
                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        info.presentationTimeUs = presentationTimeUs + extractor.sampleTime.coerceAtLeast(0L)
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_SYNC_FRAME
                        } else {
                            0
                        }
                        muxer.writeSampleData(outputTrack, buffer, info)
                        segmentMaxPts = maxOf(segmentMaxPts, extractor.sampleTime.coerceAtLeast(0L))
                        extractor.advance()
                    }
                    // Advance timeline past this segment (add small gap guard for last sample duration).
                    presentationTimeUs += segmentMaxPts + 20_000L
                } catch (_: Exception) {
                    // Skip truncated/corrupt parts from an abrupt process kill.
                    continue
                } finally {
                    extractor.release()
                }
            }

            if (!muxerStarted) {
                tempOutput.delete()
                return false
            }
            muxer.stop()
            muxer.release()
            muxer = null

            if (output.exists()) output.delete()
            if (!tempOutput.renameTo(output)) {
                tempOutput.copyTo(output, overwrite = true)
                tempOutput.delete()
            }
            return output.exists() && output.length() > 1024
        } catch (_: Exception) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            tempOutput.delete()
            return false
        }
    }

    /** True when MediaExtractor can open an audio track (filters kill-truncated m4a parts). */
    fun isReadableAudioPart(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            selectAudioTrack(extractor) != null
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    fun copyPartToOutput(part: File, output: File): Boolean {
        return try {
            if (part.absolutePath == output.absolutePath) {
                part.exists() && part.length() > 1024
            } else {
                part.copyTo(output, overwrite = true)
                output.exists() && output.length() > 1024
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}
