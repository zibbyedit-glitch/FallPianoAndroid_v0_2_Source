package com.example.fallpiano

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device, dependency-free analysis:
 * - decodes audio to PCM through Android MediaCodec
 * - finds energy onsets from short RMS frames
 * - estimates tempo from median onset intervals
 * - maps rough spectral/zero-crossing character to lanes
 *
 * This is rhythm/onset analysis, not polyphonic music transcription.
 */
object AudioAnalyzer {
    fun analyze(resolver: ContentResolver, uri: Uri): SongAnalysis? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var afd: android.content.res.AssetFileDescriptor? = null
        try {
            afd = resolver.openAssetFileDescriptor(uri, "r") ?: return null
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val info = MediaCodec.BufferInfo()
            val energies = ArrayList<Double>()
            val crossings = ArrayList<Double>()
            val pcm = PcmSamples(sampleRate * max(1, channels) * 2)
            var inputDone = false
            var outputDone = false
            val maxSamples = sampleRate * max(1, channels) * 8 * 60
            while (!outputDone && pcm.size < maxSamples) { // cap analysis at 8 minutes
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    val output = codec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset); output.limit(info.offset + info.size)
                        val bytes = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                        while (bytes.remaining() >= 2 && pcm.size < maxSamples) pcm.add(bytes.short)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Most Android decoders output 16-bit PCM; channel count is read from the original track.
                }
            }
            codec.stop()
            codec.release()
            codec = null
            extractor.release()
            if (pcm.size < sampleRate / 2) return null

            val frameSize = max(256, sampleRate / 50) * max(1, channels) // ~20 ms
            val frameCount = pcm.size / frameSize
            if (frameCount < 10) return null
            val rms = DoubleArray(frameCount)
            val zcr = DoubleArray(frameCount)
            for (f in 0 until frameCount) {
                val start = f * frameSize
                var sum = 0.0
                var cross = 0
                var prev = pcm[start].toInt()
                for (j in 0 until frameSize) {
                    val value = pcm[start + j].toInt()
                    sum += value.toDouble() * value
                    if ((value >= 0 && prev < 0) || (value < 0 && prev >= 0)) cross++
                    prev = value
                }
                rms[f] = sqrt(sum / frameSize)
                zcr[f] = cross.toDouble() / frameSize
            }
            val smooth = DoubleArray(frameCount) { i ->
                val lo = max(0, i-2); val hi = min(frameCount-1, i+2)
                (lo..hi).map { rms[it] }.average()
            }
            val peaks = ArrayList<Int>()
            val threshold = max(250.0, smooth.average() * 0.72)
            for (i in 2 until frameCount-2) {
                val rise = smooth[i] - smooth[i-2]
                if (smooth[i] > threshold && rise > max(100.0, smooth.average()*0.12) &&
                    smooth[i] >= smooth[i-1] && smooth[i] >= smooth[i+1] &&
                    (peaks.isEmpty() || i - peaks.last() >= 8)) peaks.add(i)
            }
            if (peaks.size < 4) {
                for (i in 0 until frameCount step 25) if (smooth[i] > threshold) peaks.add(i)
            }
            val intervals = peaks.zipWithNext { a, b -> (b-a) * 20.0 }.filter { it in 250.0..1500.0 }
            val median = if (intervals.isNotEmpty()) intervals.sorted()[intervals.size/2] else 500.0
            var bpm = (60000.0 / median).toInt().coerceIn(50, 200)
            if (bpm < 75) bpm = (bpm * 2).coerceAtMost(200)
            if (bpm > 155) bpm /= 2
            val events = peaks.distinct().sorted().map { frame ->
                val t = frame * 20L
                // Zero-crossing rate is only a rough timbre proxy, not pitch recognition.
                val lane = ((zcr[frame] * 180.0).toInt() % 4)
                NoteEvent(t, lane, (smooth[frame] / max(1.0, smooth.maxOrNull() ?: 1.0)).toFloat())
            }.filter { it.timeMs < pcm.size.toLong() * 1000L / (sampleRate * max(1, channels)) }
            val density = events.size / max(1.0, (pcm.size.toDouble() / (sampleRate * max(1, channels))))
            val difficulty = when { density > 3.0 -> "Hard"; density > 1.5 -> "Medium"; else -> "Easy" }
            return SongAnalysis(bpm, events, difficulty)
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { afd?.close() } catch (_: Exception) {}
        }
    }

    private class PcmSamples(initialCapacity: Int) {
        private var data = ShortArray(max(1024, initialCapacity))
        var size: Int = 0
            private set
        operator fun get(index: Int): Short = data[index]
        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }
    }
}
