package com.ultrax26.recorder.triggers.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.ultrax26.recorder.triggers.TriggerEvent
import com.ultrax26.recorder.triggers.VolumeKey
import com.ultrax26.recorder.triggers.audio.BurstCounter
import com.ultrax26.recorder.util.Clock
import com.ultrax26.recorder.util.UxLog
import kotlin.math.sqrt

/** Shake (accelerometer) and proximity-wave detection. */
class MotionTriggers(context: Context, private val sink: (TriggerEvent) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastShakeMs = -10000L
    private var proxNear = false
    private val proxBurst = BurstCounter(1500) { count, endMs -> sink(TriggerEvent.ProximityWaves(count, endMs)) }
    var shakeEnabled = false
    var proximityEnabled = false

    fun start(shake: Boolean, proximity: Boolean) {
        shakeEnabled = shake; proximityEnabled = proximity
        stop()
        if (shake) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (proximity) sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val now = Clock.bootMs()
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
                if (g > 1.8f && now - lastShakeMs > 1200) { lastShakeMs = now; sink(TriggerEvent.Shake(g, now)) }
            }
            Sensor.TYPE_PROXIMITY -> {
                val near = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f)) * 0.5f
                if (near && !proxNear) proxBurst.hit(now)
                proxNear = near
                proxBurst.tick(now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
}

/** Volume-key handling for the Activity (call from onKeyDown/onKeyUp). */
class VolumeKeyTriggers(private val sink: (TriggerEvent) -> Unit) {
    private var downAt = HashMap<Int, Long>()
    var enabled = true

    /** @return true if the key was consumed. */
    fun onKeyDown(keyCode: Int, repeat: Int): Boolean {
        if (!enabled) return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (repeat == 0) downAt[keyCode] = Clock.bootMs()
        return true
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (!enabled) return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val start = downAt.remove(keyCode) ?: Clock.bootMs()
        val long = Clock.bootMs() - start >= 600
        sink(TriggerEvent.VolumeKey(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) VolumeKey.UP else VolumeKey.DOWN, long, Clock.bootMs()))
        return true
    }
}

/**
 * Bluetooth remote shutters / headset buttons arrive as media button events. Holding an active
 * MediaSession in the "playing" state routes them to us while the app is in the foreground.
 */
class MediaButtonTriggers(private val context: Context, private val sink: (TriggerEvent) -> Unit) {
    private var session: MediaSession? = null

    fun start() {
        if (session != null) return
        try {
            val s = MediaSession(context, "UltraX26")
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                    val ke: KeyEvent? = mediaButtonIntent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (ke != null && ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                        sink(TriggerEvent.BluetoothKey(ke.keyCode, Clock.bootMs()))
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
                override fun onPlay() { sink(TriggerEvent.BluetoothKey(KeyEvent.KEYCODE_MEDIA_PLAY, Clock.bootMs())) }
                override fun onPause() { sink(TriggerEvent.BluetoothKey(KeyEvent.KEYCODE_MEDIA_PAUSE, Clock.bootMs())) }
            })
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            s.isActive = true
            session = s
        } catch (t: Throwable) { UxLog.w("MediaBtn", "session failed: ${t.message}") }
    }

    fun stop() { try { session?.isActive = false; session?.release() } catch (_: Throwable) { }; session = null }
}
