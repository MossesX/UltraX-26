package com.ultrax26.recorder.camera

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class ThermalState(val status: Int = PowerManager.THERMAL_STATUS_NONE, val headroom: Float = Float.NaN) {
    val label: String get() = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Cool"
        PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "?"
    }
    val severeOrWorse get() = status >= PowerManager.THERMAL_STATUS_SEVERE
    val criticalOrWorse get() = status >= PowerManager.THERMAL_STATUS_CRITICAL
}

/** 8K recording is thermally expensive: watch the platform thermal status + forecast headroom. */
class ThermalMonitor(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ux-thermal") }
    private val _state = MutableStateFlow(ThermalState(pm.currentThermalStatus, safeHeadroom()))
    val state: StateFlow<ThermalState> = _state

    private val listener = PowerManager.OnThermalStatusChangedListener { status -> _state.value = _state.value.copy(status = status) }

    fun start() {
        try { pm.addThermalStatusListener(exec, listener) } catch (_: Throwable) { }
        exec.scheduleWithFixedDelay({ _state.value = _state.value.copy(headroom = safeHeadroom()) }, 5, 5, TimeUnit.SECONDS)
    }

    fun stop() {
        try { pm.removeThermalStatusListener(listener) } catch (_: Throwable) { }
        exec.shutdownNow()
    }

    private fun safeHeadroom(): Float = try { pm.getThermalHeadroom(10) } catch (_: Throwable) { Float.NaN }
}
