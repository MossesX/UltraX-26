package com.ultrax26.recorder.recording

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.Maths
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioCaptureConfig(
    val source: Int = MediaRecorder.AudioSource.CAMCORDER,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val preferredDevice: AudioDeviceInfo? = null,
    val privacySensitive: Boolean? = null,
    val gainDb: Float = 0f,
    val highPassHz: Int = 0,
    val limiter: Boolean = true,
    val mute: Boolean = false,
)

/**
 * One AudioRecord for everything: the AAC encoder, the clap/whistle/keyword detectors and the level
 * meter all consume the same PCM stream, so the microphone is never contended while recording.
 * Timestamps are CLOCK_BOOTTIME (same base as Camera2 sensor timestamps on REALTIME devices).
 */
class AudioCapture(val config: AudioCaptureConfig) {
    fun interface Listener { fun onPcm(chunk: PcmChunk) }

    private val tag = "Audio"
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    val levelDbfs = MutableStateFlow(-120f)
    val peakDbfs = MutableStateFlow(-120f)
    @Volatile var muted: Boolean = config.mute

    // High-pass biquad state per channel
    private val hpX1 = FloatArray(2); private val hpX2 = FloatArray(2); private val hpY1 = FloatArray(2); private val hpY2 = FloatArray(2)
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var limiterGain = 1f

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    val frameSizeBytes get() = 2 * config.channels

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val channelMask = if (config.channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(config.sampleRate).setChannelMask(channelMask).build()
        val minBuf = AudioRecord.getMinBufferSize(config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { UxLog.e(tag, "unsupported audio format $config"); return false }
        val builder = AudioRecord.Builder().setAudioSource(config.source).setAudioFormat(fmt).setBufferSizeInBytes(maxOf(minBuf * 4, config.sampleRate * frameSizeBytes / 2))
        if (Build.VERSION.SDK_INT >= 30 && config.privacySensitive != null) builder.setPrivacySensitive(config.privacySensitive)
        val rec = try { builder.build() } catch (t: Throwable) { UxLog.e(tag, "AudioRecord build failed", t); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { UxLog.e(tag, "AudioRecord not initialized"); rec.release(); return false }
        config.preferredDevice?.let { if (!rec.setPreferredDevice(it)) UxLog.w(tag, "setPreferredDevice(${it.productName}) refused") }
        setupHighPass()
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "ux-audio").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        try { record?.stop() } catch (_: Throwable) { }
        try { record?.release() } catch (_: Throwable) { }
        record = null
    }

    private fun setupHighPass() {
        val fc = config.highPassHz
        if (fc <= 0) { b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f; return }
        val w0 = 2.0 * Math.PI * fc / config.sampleRate
        val cosw = Math.cos(w0); val sinw = Math.sin(w0)
        val q = 0.7071
        val alpha = sinw / (2 * q)
        val a0 = 1 + alpha
        b0 = ((1 + cosw) / 2 / a0).toFloat(); b1 = (-(1 + cosw) / a0).toFloat(); b2 = ((1 + cosw) / 2 / a0).toFloat()
        a1 = (-2 * cosw / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try { rec.startRecording() } catch (t: Throwable) { UxLog.e(tag, "startRecording failed", t); running = false; return }
        val framesPerChunk = config.sampleRate / 50 // 20 ms
        val buf = ShortArray(framesPerChunk * config.channels)
        var framesRead = 0L
        val ts = AudioTimestamp()
        var anchorFrame = -1L; var anchorNs = 0L; var lastTsRefresh = 0L
        val gain = Maths.dbToGain(config.gainDb)
        while (running) {
            val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) { if (n < 0) { UxLog.w(tag, "read error $n"); break }; continue }
            val frames = n / config.channels
            val now = Clock.bootNs()
            if (now - lastTsRefresh > 1_000_000_000L) {
                lastTsRefresh = now
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) { anchorFrame = ts.framePosition; anchorNs = ts.nanoTime }
            }
            val chunkPtsNs = if (anchorFrame >= 0) anchorNs + (framesRead - anchorFrame) * 1_000_000_000L / config.sampleRate
                             else now - frames * 1_000_000_000L / config.sampleRate
            framesRead += frames
            process(buf, n, gain)
            var peak = 0f; var sumSq = 0.0
            for (i in 0 until n) { val v = abs(buf[i].toInt()); if (v > peak) peak = v.toFloat(); sumSq += buf[i].toDouble() * buf[i] }
            val rms = sqrt(sumSq / n) / 32768.0
            levelDbfs.value = Maths.dbfs(rms).toFloat()
            peakDbfs.value = Maths.dbfs(peak / 32768.0).toFloat()
            val chunk = PcmChunk(buf.copyOf(n), frames, config.channels, config.sampleRate, chunkPtsNs, peak / 32768f)
            for (l in listeners) { try { l.onPcm(chunk) } catch (t: Throwable) { UxLog.w(tag, "listener failed: ${t.message}") } }
        }
        running = false
    }

    private fun process(buf: ShortArray, n: Int, gain: Float) {
        val ch = config.channels
        val hp = config.highPassHz > 0
        val useGain = gain != 1f
        if (!hp && !useGain && !config.limiter && !muted) return
        if (muted) { java.util.Arrays.fill(buf, 0, n, 0.toShort()); return }
        var i = 0
        while (i < n) {
            for (c in 0 until ch) {
                var x = buf[i + c].toFloat()
                if (hp) {
                    val y = b0 * x + b1 * hpX1[c] + b2 * hpX2[c] - a1 * hpY1[c] - a2 * hpY2[c]
                    hpX2[c] = hpX1[c]; hpX1[c] = x; hpY2[c] = hpY1[c]; hpY1[c] = y
                    x = y
                }
                if (useGain) x *= gain
                if (config.limiter) {
                    val a = abs(x)
                    if (a * limiterGain > 30000f) limiterGain = 30000f / a
                    else limiterGain += (1f - limiterGain) * 0.0005f
                    x *= limiterGain
                }
                buf[i + c] = x.coerceIn(-32768f, 32767f).toInt().toShort()
            }
            i += ch
        }
    }
}
