package com.example.vm

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.KernoVmApplication
import com.example.MainActivity
import com.example.R

/**
 * Lightweight Android 14+ compliant `specialUse` ForegroundService that keeps
 * an active RISC-V VM session alive while the user switches apps, offering
 * Pause/Resume and Stop controls right from the notification shade.
 *
 * If notification permissions are denied or ForegroundServiceStartNotAllowedException
 * occurs, [VmSessionManager] automatically falls back to Auto-Pause on ON_STOP.
 */
class VmForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? KernoVmApplication
        val sessionManager = app?.vmSessionManager

        when (intent?.action) {
            ACTION_PAUSE -> {
                sessionManager?.pauseVm(fromBackgroundAutoPause = false)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                sessionManager?.resumeVm()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                sessionManager?.stopVm()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHUTDOWN_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val vmName = intent?.getStringExtra(EXTRA_VM_NAME) ?: "KernoVM"
        val isPaused = intent?.getBooleanExtra(EXTRA_IS_PAUSED, false) ?: false

        ensureNotificationChannel()
        val notification = buildNotification(vmName, isPaused)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            START_NOT_STICKY
        } catch (_: Throwable) {
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.fgs_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.fgs_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(vmName: String, isPaused: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (isPaused) getString(R.string.fgs_action_resume) else getString(R.string.fgs_action_pause)
        val togglePending = PendingIntent.getService(
            this,
            1,
            Intent(this, VmForegroundService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPending = PendingIntent.getService(
            this,
            2,
            Intent(this, VmForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "Dijeda (mtime beku)" else "RV32IMA aktif di latar belakang"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("KernoVM · $vmName")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, toggleLabel, togglePending)
            .addAction(0, getString(R.string.fgs_action_stop), stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kernovm_active_session"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_VM_NAME = "extra_vm_name"
        private const val EXTRA_IS_PAUSED = "extra_is_paused"
        private const val ACTION_PAUSE = "com.example.vm.ACTION_PAUSE"
        private const val ACTION_RESUME = "com.example.vm.ACTION_RESUME"
        private const val ACTION_STOP = "com.example.vm.ACTION_STOP"
        private const val ACTION_SHUTDOWN_SERVICE = "com.example.vm.ACTION_SHUTDOWN_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startOrUpdate(context: Context, vmName: String, isPaused: Boolean) {
            runCatching {
                val intent = Intent(context, VmForegroundService::class.java).apply {
                    putExtra(EXTRA_VM_NAME, vmName)
                    putExtra(EXTRA_IS_PAUSED, isPaused)
                }
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                isRunning = false
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            runCatching {
                context.stopService(Intent(context, VmForegroundService::class.java))
            }
            isRunning = false
        }
    }
}
