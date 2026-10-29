package dev.varun.studio.ai

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.varun.studio.MainActivity
import dev.varun.studio.R
import dev.varun.studio.VarunApplication
import dev.varun.studio.core.AppLog

/**
 * Keeps on-device generation alive when the screen is off or the user switches
 * apps.
 *
 * Android kills background work aggressively; a foreground service with the
 * dataSync type is the supported way to continue a long inference. It is
 * started only while generation is actually running and stopped as soon as it
 * finishes, so there is no permanent battery drain.
 *
 * This does NOT keep a wakelock: holding one would drain the battery and heat
 * the device. A long generation with the screen off may be paused by Doze; the
 * UI says so rather than pretending otherwise.
 */
class InferenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.notif_inference_body)
        try {
            val notification = buildNotification(label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException happens when Android
            // refuses a background start. Inference continues in-process; we
            // just lose the "keep alive" guarantee. That is a degradation, not
            // a crash.
            AppLog.w("Could not start the inference foreground service", t)
            stopSelfSafely()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun buildNotification(label: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, InferenceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, VarunApplication.CHANNEL_INFERENCE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_inference_title))
            .setContentText(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4101
        const val ACTION_STOP = "dev.varun.studio.action.STOP_INFERENCE"
        const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, InferenceForegroundService::class.java)
                .putExtra(EXTRA_LABEL, label)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { AppLog.w("startForegroundService refused", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, InferenceForegroundService::class.java).setAction(ACTION_STOP),
                )
            }
            runCatching { context.stopService(Intent(context, InferenceForegroundService::class.java)) }
        }
    }
}
