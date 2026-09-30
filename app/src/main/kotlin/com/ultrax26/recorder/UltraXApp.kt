package com.ultrax26.recorder

import android.app.Application
import android.content.Context
import com.ultrax26.recorder.recording.RecordingService

class UltraXApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        RecordingService.ensureChannel(this)
        graph = AppGraph(this)
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as UltraXApp).graph
        fun graphOrNull(context: Context): AppGraph? = (context.applicationContext as? UltraXApp)?.let { if (it::graph.isInitialized) it.graph else null }
    }
}
