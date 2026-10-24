package dev.omalauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.omalauncher.linux.LinuxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and with it proot and the Wayland server) in the
 * foreground while Linux is installed or installing; never without Linux.
 * The notification shows the install's progress.
 */
class LinuxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            (application as App).runtime.stopLinux()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux session", NotificationManager.IMPORTANCE_LOW))
        val state = (application as App).runtime.bootstrap.state
        startForeground(1, notification(state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        pending = (pending - 1).coerceAtLeast(0)
        // Stopped before it got here (see [set]): now it may go, unless a later start still has to come here too.
        if (!wanted) {
            if (pending == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) {
            watch =
                scope.launch {
                    state.collect { nm.notify(1, build(it)) }
                }
        }
        // Not restarted after the process dies: Linux only comes back with the launcher (Runtime.start),
        // and Android 12+ may refuse startForeground() in a restart from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: LinuxState) = build(state)

    private fun text(state: LinuxState): String =
        when (state) {
            is LinuxState.Installing -> "Installing Linux" + (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") + " · ${state.stage.label}"
            is LinuxState.Failed -> "Linux setup stopped · open settings › linux to retry"
            LinuxState.Removing -> "Removing Linux"
            else -> "Linux session running"
        }

    private fun build(state: LinuxState): Notification =
        Notification
            .Builder(this, CHANNEL)
            .setContentTitle(text(state))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                // End the session from the notification, like Termux's exit. Only while it runs:
                // there is nothing to stop during an install or after Linux was removed.
                if (state == LinuxState.Installed) {
                    addAction(
                        0,
                        "Stop",
                        PendingIntent.getService(
                            this@LinuxService,
                            0,
                            Intent(this@LinuxService, LinuxService::class.java).setAction(ACTION_STOP),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            .build()

    companion object {
        private const val CHANNEL = "session"

        /** Ends the Linux session (see [Runtime.stopLinux]) without removing it. */
        const val ACTION_STOP = "dev.omalauncher.action.STOP_SESSION"
        // Main thread only, like onStartCommand.
        private var wanted = false

        /** startForegroundService() calls whose onStartCommand hasn't run yet. */
        private var pending = 0

        /**
         * Starts or stops the service. Android kills the app when a service started with startForegroundService()
         * is stopped before it calls startForeground(), as a setup that fails at once did on slow phones; a stop
         * that early is left to onStartCommand.
         */
        fun set(
            context: Context,
            on: Boolean,
        ) {
            wanted = on
            val intent = Intent(context, LinuxService::class.java)
            if (on) {
                context.startForegroundService(intent)
                pending++
            } else if (pending == 0) {
                context.stopService(intent)
            }
        }
    }
}
