package com.ultrax26.recorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.ultrax26.recorder.R
import com.ultrax26.recorder.UltraXApp
import com.ultrax26.recorder.triggers.RecAction

/** Foreground service that keeps the process alive (camera + microphone types) while recording. */
class RecordingService : Service() {
    companion object {
        const val CHANNEL = "ultrax_recording"
        const val NOTIF_ID = 2601
        const val ACTION_STOP = "com.ultrax26.recorder.action.STOP"
        const val ACTION_PAUSE = "com.ultrax26.recorder.action.PAUSE"
        const val ACTION_RESUME = "com.ultrax26.recorder.action.RESUME"
        private const val EXTRA_PAUSED = "paused"
        private const val EXTRA_CALL = "call"
        @Volatile private var callActive = false
        @Volatile private var recordingActive = false

        fun startCall(ctx: Context) { callActive = true; try { ctx.startForegroundService(Intent(ctx, RecordingService::class.java).putExtra(EXTRA_CALL, true)) } catch (_: Throwable) { } }
        fun stopCall(ctx: Context) { callActive = false; if (!recordingActive) stop(ctx) else update(ctx, paused = false) }

        fun start(ctx: Context) {
            recordingActive = true
            try { ctx.startForegroundService(Intent(ctx, RecordingService::class.java)) } catch (_: Throwable) { }
        }
        fun update(ctx: Context, paused: Boolean) {
            try { ctx.startService(Intent(ctx, RecordingService::class.java).putExtra(EXTRA_PAUSED, paused)) } catch (_: Throwable) { }
        }
        fun stop(ctx: Context) {
            recordingActive = false
            if (callActive) { update(ctx, paused = false); return }
            try { ctx.stopService(Intent(ctx, RecordingService::class.java)) } catch (_: Throwable) { }
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.notification_channel_recording), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false
        val n = buildNotification(paused, callActive && !recordingActive)
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    private fun action(action: String, label: String, req: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(this, req, Intent(this, ActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun buildNotification(paused: Boolean, callOnly: Boolean = false): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = if (launch != null) PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (callOnly) "UltraX 26 video call" else getString(R.string.notification_recording_title))
            .setContentText(if (callOnly) "In a call — tap to return" else if (paused) "Paused" else "Recording — gestures & voice armed")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (!callOnly) {
            b.addAction(action(ACTION_STOP, getString(R.string.notification_action_stop), 1))
            b.addAction(if (paused) action(ACTION_RESUME, getString(R.string.notification_action_resume), 3) else action(ACTION_PAUSE, getString(R.string.notification_action_pause), 2))
        }
        if (content != null) b.setContentIntent(content)
        return b.build()
    }

    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }

    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val graph = UltraXApp.graphOrNull(context) ?: return
            when (intent.action) {
                ACTION_STOP -> graph.controller.perform(RecAction.STOP, null)
                ACTION_PAUSE -> graph.controller.perform(RecAction.PAUSE, null)
                ACTION_RESUME -> graph.controller.perform(RecAction.RESUME, null)
            }
        }
    }
}
