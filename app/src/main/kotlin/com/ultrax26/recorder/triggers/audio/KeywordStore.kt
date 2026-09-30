package com.ultrax26.recorder.triggers.audio

import android.content.Context
import com.ultrax26.recorder.util.UxJson
import com.ultrax26.recorder.util.UxLog
import kotlinx.serialization.encodeToString
import java.io.File

/** Persists enrolled keyword templates as JSON in app storage. */
class KeywordStore(context: Context) {
    private val file = File(context.filesDir, "keywords.json")

    fun load(): List<KeywordTemplate> = try {
        if (!file.exists()) emptyList() else UxJson.decodeFromString(KeywordTemplates.serializer(), file.readText()).templates
    } catch (t: Throwable) { UxLog.e("Keywords", "load failed", t); emptyList() }

    fun save(templates: List<KeywordTemplate>) {
        try { file.writeText(UxJson.encodeToString(KeywordTemplates(templates))) } catch (t: Throwable) { UxLog.e("Keywords", "save failed", t) }
    }

    fun exportJson(templates: List<KeywordTemplate>): String = UxJson.encodeToString(KeywordTemplates(templates))
    fun importJson(text: String): List<KeywordTemplate>? = try { UxJson.decodeFromString(KeywordTemplates.serializer(), text).templates } catch (_: Throwable) { null }
}
