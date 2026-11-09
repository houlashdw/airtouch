package com.airtouch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airtouch.R
import com.airtouch.core.AirTouchState
import com.airtouch.core.AirTouchState.Mode
import com.airtouch.core.Timeouts
import com.airtouch.gesture.GestureDetector
import com.airtouch.gesture.HandFrame
import com.airtouch.settings.AirTouchSettings
import com.airtouch.ui.MainActivity
import com.airtouch.vision.FrameUtils
import com.airtouch.vision.HandTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Камера → MediaPipe → GestureDetector → AirInputService.
 *
 * Foreground service с типом camera: так Android разрешает держать камеру, пока пользователь
 * в других приложениях. Запускать только из видимой activity (MainActivity / ToggleActivity).
 *
 * Потоки: анализ кадров — cameraExecutor; результаты MediaPipe — его внутренний поток
 * (там же живёт GestureDetector); управление жизненным циклом — main.
 */
class GestureCameraService : LifecycleService() {

    private lateinit var settings: AirTouchSettings
    private val detector = GestureDetector()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var tracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var watchdog: Job? = null

    @Volatile private var mirror = true
    @Volatile private var economy = false
    @Volatile private var handVisible = false
    @Volatile private var lastHandSeenAt = 0L
    @Volatile private var lastActivityAt = 0L
    private var frameCounter = 0L   // только cameraExecutor
    private var lastTimestamp = 0L  // только cameraExecutor

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        detector.config = settings.swipeConfig()
        if (key == AirTouchSettings.KEY_FRONT_CAMERA) bindCamera()
    }

    override fun onCreate() {
        super.onCreate()
        settings = AirTouchSettings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) shutdown(Mode.OFF) else start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isRunning) shutdown(Mode.OFF)
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------- Жизненный цикл ----------------

    private fun start() {
        if (isRunning) return
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: Exception) { // SecurityException / ForegroundServiceStartNotAllowedException
            fail("Камеру можно включить только из открытого приложения. Откройте AirTouch и нажмите «Включить».", e)
            return
        }
        isRunning = true

        tracker = try {
            HandTracker(this, ::onHand, ::onTrackerError)
        } catch (e: Exception) {
            fail("Не удалось загрузить модель руки: ${e.message}", e)
            return
        }

        detector.config = settings.swipeConfig()
        detector.onHandLost()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val now = SystemClock.uptimeMillis()
        lastHandSeenAt = now
        lastActivityAt = now
        economy = false
        handVisible = false
        AirTouchState.update { AirTouchState.Status(mode = Mode.ACTIVE) }

        bindCamera()
        startWatchdog()
    }

    private fun shutdown(mode: Mode, error: String? = null) {
        watchdog?.cancel()
        watchdog = null
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        cameraProvider?.unbindAll()
        cameraProvider = null
        tracker?.let { t -> cameraExecutor.execute { t.close() } } // после уже поставленных кадров
        tracker = null
        isRunning = false
        AirTouchState.update { AirTouchState.Status(mode = mode, error = error) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String, e: Throwable) {
        Log.e(TAG, message, e)
        shutdown(Mode.ERROR, message)
    }

    /** Авто-эконом через 3 с без руки, авто-сон через 30 с без руки и жестов. */
    private fun startWatchdog() {
        watchdog = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val now = SystemClock.uptimeMillis()
                if (!economy && now - lastHandSeenAt > Timeouts.NO_HAND_IDLE_MS) {
                    economy = true
                    AirTouchState.update { it.copy(mode = Mode.IDLE, handVisible = false) }
                }
                if (now - maxOf(lastHandSeenAt, lastActivityAt) > Timeouts.AUTO_SLEEP_MS) {
                    shutdown(Mode.SLEEPING)
                    break
                }
            }
        }
    }

    // ---------------- Камера ----------------

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isRunning) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                fail("Камера недоступна: ${e.message}", e)
                return@addListener
            }
            cameraProvider = provider

            val front = settings.useFrontCamera
            mirror = front
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            // 640×480 хватает MediaPipe с запасом и сильно экономит батарею.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor, ::analyze)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                fail("Не удалось открыть ${if (front) "фронтальную" else "основную"} камеру: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val t = tracker ?: return
            frameCounter++
            if (economy && frameCounter % Timeouts.ECONOMY_FRAME_STRIDE != 0L) return

            val bitmap = FrameUtils.toUprightBitmap(image, mirror)

            if (frameCounter % Timeouts.LUMA_EVERY_N_FRAMES == 0L) {
                val low = FrameUtils.meanLuma(bitmap) < Timeouts.LOW_LIGHT_LUMA
                if (low != AirTouchState.status.value.lowLight) AirTouchState.update { s -> s.copy(lowLight = low) }
            }

            val ts = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = ts
            t.detect(bitmap, ts)
        }
    }

    // ---------------- Результаты MediaPipe (его поток) ----------------

    private fun onHand(frame: HandFrame?) {
        if (!isRunning) return
        if (frame == null) {
            // При быстром махе MediaPipe может потерять смазанную руку на 1–3 кадра.
            // Сбрасываем детектор, только если руки нет дольше HAND_LOST_GRACE_MS.
            if (handVisible && SystemClock.uptimeMillis() - lastHandSeenAt > Timeouts.HAND_LOST_GRACE_MS) {
                handVisible = false
                detector.onHandLost()
                AirTouchState.update { it.copy(handVisible = false, pose = null) }
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        lastHandSeenAt = now
        handVisible = true
        economy = false

        val gesture = detector.process(frame)
        if (gesture != null) {
            lastActivityAt = now
            AirInputService.instance?.perform(gesture)
        }
        val palm = detector.lastPalm
        AirTouchState.update { s ->
            s.copy(
                mode = Mode.ACTIVE,
                handVisible = true,
                pose = detector.lastPose,
                palmX = palm?.x ?: s.palmX,
                palmY = palm?.y ?: s.palmY,
                lastGesture = gesture ?: s.lastGesture,
                lastGestureAt = if (gesture != null) now else s.lastGestureAt,
            )
        }
    }

    private fun onTrackerError(e: Throwable) {
        Log.e(TAG, "MediaPipe error", e)
    }

    // ---------------- Уведомление ----------------

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hand)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "AirTouch/Camera"
        const val ACTION_START = "com.airtouch.action.START"
        const val ACTION_STOP = "com.airtouch.action.STOP"
        private const val CHANNEL_ID = "air_mode"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var isRunning = false
            private set

        fun hasCameraPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        /** Вызывать из видимой activity. false — нет разрешения камеры или служба спец. возможностей выключена. */
        fun start(context: Context): Boolean {
            if (!hasCameraPermission(context) || !AirInputService.isConnected) return false
            ContextCompat.startForegroundService(
                context, Intent(context, GestureCameraService::class.java).setAction(ACTION_START),
            )
            return true
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try {
                context.startService(Intent(context, GestureCameraService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() failed", e)
            }
        }
    }
}
