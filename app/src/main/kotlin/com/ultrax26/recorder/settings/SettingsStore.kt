package com.ultrax26.recorder.settings

import android.content.Context
import com.ultrax26.recorder.util.UxJson
import com.ultrax26.recorder.util.UxLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors

/**
 * Filesystem JSON persistence with an in-memory StateFlow. All writes are debounced onto one
 * background thread; reads are instant. Presets are just other JSON files in `presets/`.
 */
class SettingsStore(context: Context) {
    private val dir: File = File(context.filesDir, "settings").apply { mkdirs() }
    private val file = File(dir, "settings.json")
    private val presetDir = File(dir, "presets").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ux-settings-io") }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        persist(next)
    }

    fun replace(next: AppSettings) {
        _settings.value = next
        persist(next)
    }

    fun resetToDefaults() = replace(AppSettings())

    private fun persist(s: AppSettings) {
        io.execute {
            try {
                val tmp = File(dir, "settings.json.tmp")
                tmp.writeText(UxJson.encodeToString(s))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            } catch (t: Throwable) {
                UxLog.e("Settings", "persist failed", t)
            }
        }
    }

    private fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            UxJson.decodeFromString(AppSettings.serializer(), file.readText())
        } catch (t: Throwable) {
            UxLog.e("Settings", "settings.json unreadable, using defaults", t)
            AppSettings()
        }
    }

    // ---- Presets ---------------------------------------------------------------------------

    fun listPresets(): List<String> =
        presetDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun savePreset(name: String, settings: AppSettings = current) {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").ifEmpty { "preset" }
        File(presetDir, "$safe.json").writeText(UxJson.encodeToString(settings))
    }

    fun loadPreset(name: String): AppSettings? = try {
        UxJson.decodeFromString(AppSettings.serializer(), File(presetDir, "$name.json").readText())
    } catch (t: Throwable) { UxLog.e("Settings", "preset $name unreadable", t); null }

    fun deletePreset(name: String) { File(presetDir, "$name.json").delete() }

    fun exportJson(settings: AppSettings = current): String = UxJson.encodeToString(settings)

    fun importJson(text: String): AppSettings? = try {
        UxJson.decodeFromString(AppSettings.serializer(), text)
    } catch (t: Throwable) { UxLog.e("Settings", "import failed", t); null }
}
