package me.rerere.rikkahub.service

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ssh.SshDevManager
import me.rerere.rikkahub.ssh.SshDevPhase
import me.rerere.rikkahub.ssh.SshDevState
import org.koin.android.ext.android.inject

/** User-started development server; never sticky, never a boot receiver, not Conversation-owned. */
class SshDevService : Service() {
    private val manager: SshDevManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var seenActive = false
    private var shutdownRequested = false
    private var startOperation: Job? = null
    private var requestEpoch = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val epoch = ++requestEpoch
                shutdownRequested = false
                seenActive = false
                if (BuildConfig.IS_PLAY_STORE) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    val notifications = getSystemService(NotificationManager::class.java)
                    notifications.createNotificationChannel(
                        NotificationChannel(CHANNEL, "SSH 开发连接", NotificationManager.IMPORTANCE_LOW)
                    )
                    check(
                        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
                            notifications.getNotificationChannel(CHANNEL)?.importance !=
                            NotificationManager.IMPORTANCE_NONE
                    ) { "请先允许 SSH 前台通知" }
                    val notification = notification(manager.state.value.copy(phase = SshDevPhase.STARTING))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(ID, notification)
                    }
                    val job = manager.start()
                    startOperation = job
                    job.invokeOnCompletion {
                        scope.launch {
                            if (epoch != requestEpoch) return@launch
                            val state = manager.state.value
                            if (state.phase !in setOf(
                                    SshDevPhase.STARTING,
                                    SshDevPhase.RUNNING,
                                    SshDevPhase.STOPPING
                                ) &&
                                !state.ownsProcess) {
                                finishService()
                            }
                        }
                    }
                    observe()
                } catch (_: Exception) {
                    manager.serviceFailure("前台服务或通知启动被系统拒绝；SSH 未开放，请检查通知权限")
                    finishService()
                }
            }
            ACTION_STOP -> {
                ++requestEpoch
                manager.stop().invokeOnCompletion {
                    scope.launch {
                        if (!manager.state.value.ownsProcess) {
                            finishService()
                        } else {
                            observe()
                        }
                    }
                }
            }
            // A null/redelivered intent must never restore a server after process death.
            else -> finishService()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        if (observing) return
        observing = true
        scope.launch {
            manager.state.collect { state ->
                if (state.phase in
                    setOf(SshDevPhase.STARTING, SshDevPhase.RUNNING, SshDevPhase.STOPPING)) {
                    seenActive = true
                }
                // Ignore the previous terminal state while a new start performs asynchronous preflight.
                // Completion of this specific request handles preflight failures, even without STARTING.
                if (seenActive &&
                    state.phase in setOf(SshDevPhase.STOPPED, SshDevPhase.ERROR) && !state.ownsProcess) {
                    finishService()
                } else if (state.phase != SshDevPhase.STOPPED) {
                    // Updating an existing foreground notification; no network/file content in notifications.
                    getSystemService(NotificationManager::class.java).notify(ID, notification(state))
                }
            }
        }
    }

    private fun notification(state: SshDevState): Notification {
        val open = PendingIntent.getActivity(
            this,
            ID,
            Intent(this, RouteActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            ID + 1,
            Intent(this, SshDevService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("SSH 开发连接 · ${state.phase}")
            .setContentText("${state.workspaceName} · 127.0.0.1:${state.port}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun finishService() {
        shutdownRequested = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        manager.stop("任务已移除，SSH 停止")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        if (!shutdownRequested || startOperation?.isActive == true ||
            manager.state.value.ownsProcess) {
            manager.stop("SSH 服务被系统关闭")
        }
        scope.cancel()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.stop("系统前台服务超时，SSH 停止")
        finishService()
    }

    companion object {
        private const val ID = 2403
        private const val CHANNEL = "ssh_dev"
        private const val ACTION_START = "me.rerere.rikkahub.action.SSH_DEV_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.SSH_DEV_STOP"
        fun start(
            context: Context
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SshDevService::class.java).setAction(ACTION_START)
            )
        }
        fun stop(
            context: Context
        ) {
            context.startService(Intent(context, SshDevService::class.java).setAction(ACTION_STOP))
        }
    }
}
