package org.boluo.panelprobe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import org.boluo.panelprobe.BuildConfig
import org.boluo.panelprobe.MainActivity
import org.boluo.panelprobe.R
import org.boluo.panelprobe.shizuku.ShizukuBridge
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CaptureProbeService : Service() {
    private val finishing = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val panelCommandResponse = AtomicReference<String?>(null)
    private val backupRestoreResponse = AtomicReference<String?>(null)

    private var startedWallTimeMillis = 0L
    private var sessionDirectory: File? = null
    private var analyzer: FrameAnalyzer? = null
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var controllerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            analyzer?.markProjectionStopped()
            if (!finishing.get()) {
                completeRun(
                    wasCancelled = false,
                    error = null,
                    restoreReason = "projection-stopped",
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ShizukuBridge.initialize(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ABORT) {
            if (isRunning.get()) {
                cancelled.set(true)
                completeRun(
                    wasCancelled = true,
                    error = null,
                    restoreReason = "user-abort",
                )
            } else {
                Thread {
                    ShizukuBridge.forcePanelOn("inactive-service-recovery")
                    stopSelf()
                }.start()
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !isRunning.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }

        startedWallTimeMillis = System.currentTimeMillis()
        startAsForeground("正在准备录屏诊断")
        acquireWakeLock()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = projectionDataFrom(intent)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            completeRun(
                wasCancelled = false,
                error = "Missing MediaProjection consent result",
                restoreReason = "missing-projection-consent",
            )
            return START_NOT_STICKY
        }

        try {
            val directory = File(
                cacheDir,
                "probe-${startedWallTimeMillis}-${android.os.Process.myPid()}",
            ).apply { check(mkdirs() || isDirectory) }
            sessionDirectory = directory
            analyzer = FrameAnalyzer(directory)
            startProjection(resultCode, resultData)
            startController()
        } catch (error: Throwable) {
            completeRun(
                wasCancelled = false,
                error = compactError(error),
                restoreReason = "startup-error",
            )
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning.get()) {
            cancelled.set(true)
            completeRun(
                wasCancelled = true,
                error = null,
                restoreReason = "task-removed",
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (isRunning.get() && !finishing.get()) {
            Thread { ShizukuBridge.forcePanelOn("service-destroyed") }.start()
        }
        releaseCaptureResources()
        releaseWakeLock()
        isRunning.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun projectionDataFrom(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    @Suppress("DEPRECATION")
    private fun startProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager returned null")
        projection = mediaProjection

        val thread = HandlerThread("panel-capture-frames").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler
        mediaProjection.registerCallback(projectionCallback, handler)

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WindowManager::class.java)
        windowManager.defaultDisplay.getRealMetrics(metrics)
        check(metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            "Invalid display size ${metrics.widthPixels}x${metrics.heightPixels}"
        }

        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            IMAGE_READER_MAX_IMAGES,
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                analyzer?.onImage(image)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PanelCaptureProbe",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        ) ?: error("MediaProjection could not create a virtual display")
    }

    private fun startController() {
        controllerThread = Thread(controllerLoop@{
            try {
                val countdownSeconds = (BuildConfig.COUNTDOWN_MILLIS / 1_000L).toInt()
                for (remaining in countdownSeconds downTo 1) {
                    updateNotification(
                        "${remaining} 秒后关闭物理面板；请勿锁屏，电源键可应急恢复",
                    )
                    Thread.sleep(1_000L)
                }

                analyzer?.requestSnapshotAndAwait(
                    label = "before_panel_off",
                    timeoutMillis = SNAPSHOT_WAIT_MILLIS,
                )

                val control = ShizukuBridge.awaitControl(SHIZUKU_CONNECT_TIMEOUT_MILLIS)
                if (control == null) {
                    panelCommandResponse.set(
                        """{"accepted":false,"reason":"Shizuku UserService unavailable"}""",
                    )
                    completeRun(
                        wasCancelled = false,
                        error = null,
                        restoreReason = "user-service-unavailable",
                    )
                    return@controllerLoop
                }

                val response =
                    control.startTimedPanelOff(BuildConfig.PANEL_OFF_DURATION_MILLIS.toInt())
                panelCommandResponse.set(response)
                val accepted = runCatching {
                    JSONObject(response).optBoolean("accepted", false)
                }.getOrDefault(false)
                if (!accepted) {
                    completeRun(
                        wasCancelled = false,
                        error = null,
                        restoreReason = "panel-command-rejected",
                    )
                    return@controllerLoop
                }

                analyzer?.markPanelOff()
                updateNotification(
                    "物理面板应已关闭；20 秒自动恢复，电源键可随时应急恢复",
                )
                Thread.sleep(BACKUP_RESTORE_DELAY_MILLIS)

                val restore = ShizukuBridge.forcePanelOn("main-process-backup")
                backupRestoreResponse.set(restore)
                analyzer?.markPanelRestored()
                updateNotification("面板已请求恢复，正在保存最后一帧")
                analyzer?.requestSnapshotAndAwait(
                    label = "after_panel_on",
                    timeoutMillis = SNAPSHOT_WAIT_MILLIS,
                )
                completeRun(
                    wasCancelled = false,
                    error = null,
                    restoreReason = "normal-completion",
                    alreadyRestored = true,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                completeRun(
                    wasCancelled = false,
                    error = compactError(error),
                    restoreReason = "controller-error",
                )
            }
        }, "panel-probe-controller").also { it.start() }
    }

    private fun completeRun(
        wasCancelled: Boolean,
        error: String?,
        restoreReason: String,
        alreadyRestored: Boolean = false,
    ) {
        if (!finishing.compareAndSet(false, true)) return
        cancelled.set(wasCancelled)
        if (Thread.currentThread() !== controllerThread) controllerThread?.interrupt()

        Thread({
            var finalError = error
            try {
                if (!alreadyRestored) {
                    backupRestoreResponse.set(
                        ShizukuBridge.forcePanelOn(restoreReason),
                    )
                    analyzer?.markPanelRestored()
                }
                if (!wasCancelled && !analyzer.orProjectionStopped()) {
                    analyzer?.requestSnapshotAndAwait(
                        label = "after_panel_on",
                        timeoutMillis = SNAPSHOT_WAIT_MILLIS,
                    )
                }

                val finalShellStatus = runCatching {
                    ShizukuBridge.awaitControl(1_500)?.getCycleStatus()
                }.getOrNull()
                releaseCaptureResources()

                val directory = sessionDirectory
                val frameAnalyzer = analyzer
                if (directory != null && frameAnalyzer != null) {
                    updateNotification("正在生成本地诊断 ZIP")
                    val exported = DiagnosticExporter(this).export(
                        sessionDirectory = directory,
                        analyzer = frameAnalyzer,
                        metadata = ProbeRunMetadata(
                            startedWallTimeMillis = startedWallTimeMillis,
                            completedWallTimeMillis = System.currentTimeMillis(),
                            panelCommandResponse = panelCommandResponse.get(),
                            backupRestoreResponse = backupRestoreResponse.get(),
                            finalShellStatus = finalShellStatus,
                            cancelled = wasCancelled,
                            error = finalError,
                        ),
                    )
                    updateNotification("${exported.verdict.title}；ZIP 已保存到 Download")
                    directory.deleteRecursively()
                }
            } catch (exportError: Throwable) {
                finalError = listOfNotNull(finalError, compactError(exportError))
                    .joinToString(" | ")
                updateNotification("诊断结束，但导出失败：$finalError")
            } finally {
                releaseCaptureResources()
                releaseWakeLock()
                isRunning.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "panel-probe-finisher").start()
    }

    private fun FrameAnalyzer?.orProjectionStopped(): Boolean =
        this?.snapshot()?.projectionStopped ?: true

    private fun releaseCaptureResources() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        runCatching { imageReader?.close() }
        imageReader = null
        virtualDisplay = null
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:panel-capture-probe",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val abortIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureProbeService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(isRunning.get())
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "立即恢复并停止",
                    abortIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun compactError(error: Throwable): String {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return "${current.javaClass.simpleName}: ${current.message ?: "no message"}"
            .take(1_000)
    }

    companion object {
        const val ACTION_START = "org.boluo.panelprobe.action.START"
        const val ACTION_ABORT = "org.boluo.panelprobe.action.ABORT"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        const val BACKUP_RESTORE_DELAY_MILLIS = 22_000L
        private const val SHIZUKU_CONNECT_TIMEOUT_MILLIS = 3_000L
        private const val SNAPSHOT_WAIT_MILLIS = 1_500L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
        private const val IMAGE_READER_MAX_IMAGES = 3
        private const val NOTIFICATION_CHANNEL_ID = "panel_capture_probe"
        private const val NOTIFICATION_ID = 4107

        val isRunning = AtomicBoolean(false)

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, CaptureProbeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            context.startForegroundService(intent)
        }
    }
}
