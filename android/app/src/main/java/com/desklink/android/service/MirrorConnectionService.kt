package com.desklink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.desklink.android.presentation.MainActivity

/**
 * Foreground service that keeps the app's process at foreground priority for the
 * duration of a mirror session.
 *
 * Why it exists: minimizing the app is NOT a disconnect, but Android freezes cached
 * (backgrounded) processes, which stalls the control-channel keep-alive. The Mac
 * then sees PONG silence and drops the client. A foreground service exempts the
 * process from freezing, so the keep-alive keeps running and the connection stays
 * up across a minimize/return — no false disconnect, no reconnect churn.
 *
 * The service holds no connection state itself; its mere presence keeps the process
 * alive. Start it when a session begins and stop it when the session ends.
 */
class MirrorConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (e: Exception) {
            // startForeground can throw (e.g. ForegroundServiceStartNotAllowedException
            // if the start didn't originate from the foreground, or a type prerequisite
            // isn't met). Don't crash the app over keep-alive; log and stop — the
            // session still works, it just won't survive a long background.
            Log.e(TAG, "startForeground failed; connection won't persist in background", e)
            stopSelf()
        }
        // Session-bound: if the system kills us under memory pressure there's nothing
        // useful to resume without the Activity, so don't auto-restart.
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_LOW: persistent, silent status notification (no sound/peek).
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mirror connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the DeskLink connection alive while mirroring."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeskLink")
            .setContentText("Mirroring your Mac")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "DeskLink"
        private const val CHANNEL_ID = "desklink_mirror_connection"
        private const val NOTIFICATION_ID = 1

        /** Starts the keep-alive service. Must be called while the app is foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MirrorConnectionService::class.java),
            )
        }

        /** Stops the keep-alive service when the session ends. */
        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorConnectionService::class.java))
        }
    }
}
