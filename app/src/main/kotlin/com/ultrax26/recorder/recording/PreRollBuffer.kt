package com.ultrax26.recorder.recording

import java.util.ArrayDeque

/**
 * Keeps the last N seconds of encoded video + audio in RAM while the recorder is armed, so a
 * gesture-triggered clip starts *before* the gesture happened.
 */
class PreRollBuffer(private val seconds: Int, private val maxBytes: Long = 400L * 1024 * 1024) {
    private val queue = ArrayDeque<EncodedSample>()
    private var bytes = 0L
    val enabled: Boolean get() = seconds > 0

    @Synchronized
    fun add(sample: EncodedSample) {
        if (!enabled) return
        queue.addLast(sample)
        bytes += sample.size
        val newest = sample.ptsUs
        val keepFromUs = newest - (seconds + 2) * 1_000_000L
        while (queue.isNotEmpty() && (queue.first().ptsUs < keepFromUs || bytes > maxBytes)) bytes -= queue.removeFirst().size
    }

    /** Samples from the last keyframe at or before (latest − seconds), in order. Clears the buffer. */
    @Synchronized
    fun drain(): List<EncodedSample> {
        if (queue.isEmpty()) return emptyList()
        val newest = queue.last().ptsUs
        val target = newest - seconds * 1_000_000L
        var startPts = -1L
        for (s in queue) {
            if (s.track == Track.VIDEO && s.isKeyFrame) {
                if (s.ptsUs <= target || startPts < 0) startPts = s.ptsUs
                if (s.ptsUs > target) break
            }
        }
        val out = if (startPts < 0) emptyList() else queue.filter { it.ptsUs >= startPts }
        queue.clear(); bytes = 0
        return out
    }

    @Synchronized fun clear() { queue.clear(); bytes = 0 }
    @Synchronized fun bufferedSeconds(): Float = if (queue.size < 2) 0f else (queue.last().ptsUs - queue.first().ptsUs) / 1e6f
}
