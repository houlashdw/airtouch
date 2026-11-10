package com.airtouch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import com.airtouch.gesture.EngineOutput
import com.airtouch.gesture.GestureEngine
import com.airtouch.gesture.inverted
import com.airtouch.keyboard.Dictionary
import com.airtouch.keyboard.Lang
import com.airtouch.keyboard.ZoneDecoder
import com.airtouch.keyboard.ZoneKeyboardController
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
 * Камера → MediaPipe → GestureEngine (махи + курсор) → AirInputService.
 *
 * Не засыпает, пока экран включён (без руки — экономичные ~7 FPS). Экран погас — камера на паузе,
 * экран включился — продолжает сама, кнопку нажимать не нужно.
 *
 * Foreground service с типом camera: так Android разрешает держать камеру, пока пользователь
 * в других приложениях. Запускать только из видимой activity (MainActivity / ToggleActivity).
 *
 * Потоки: анализ кадров — cameraExecutor; результаты MediaPipe — его внутренний поток
 * (там же живёт GestureEngine); управление жизненным циклом — main.
 */
class GestureCameraService : LifecycleService() {

    private lateinit var settings: AirTouchSettings
    private val engine = GestureEngine()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var tracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    @Volatile private var lastLuma = 128f
    private var exposureBoosted = false

    /** В темноте — максимальная экспокоррекция камеры, при нормальном свете — обратно 0. */
    /**
     * Темно → камера «на максимум»:
     *  - если фронталка даёт ручное управление — выдержка 1/20 с и максимальная чувствительность (ISO);
     *  - если нет — разрешаем автоэкспозиции опускаться до минимального FPS (длиннее выдержка);
     *  - плюс максимальная экспокоррекция и яркость экрана 100% (подсвечивает руку).
     * Светло → всё возвращается как было.
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun updateExposure(dark: Boolean) {
        if (dark == exposureBoosted) return
        val cam = camera ?: return
        exposureBoosted = dark
        try {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
            val control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
            if (dark) {
                val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val manual = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exp = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val b = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                if (manual && iso != null && exp != null) {
                    val t = DARK_EXPOSURE_NS.coerceIn(exp.lower, exp.upper)
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.upper)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, t)
                    Log.i(TAG, "dark: manual exposure ${t / 1_000_000} ms, ISO ${iso.upper}")
                } else {
                    info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.filter { it.upper <= 30 }
                        ?.minWithOrNull(compareBy<Range<Int>>({ it.lower }, { -it.upper }))
                        ?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    val ev = cam.cameraInfo.exposureState
                    if (ev.isExposureCompensationSupported) cam.cameraControl.setExposureCompensationIndex(ev.exposureCompensationRange.upper)
                    Log.i(TAG, "dark: auto exposure with low fps + max EV")
                }
                control.setCaptureRequestOptions(b.build())
            } else {
                control.clearCaptureRequestOptions()
                cam.cameraControl.setExposureCompensationIndex(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "exposure control failed", e)
        }
        screenLight.set(dark && settings.screenLight)
    }

    private val screenLight by lazy { ScreenBrightness(this) }
    private var watchdog: Job? = null

    @Volatile private var mirror = true
    @Volatile private var economy = false
    @Volatile private var handVisible = false
    @Volatile private var lastHandSeenAt = 0L
    @Volatile private var paused = false
    @Volatile private var invertSwipes = false
    /** Уже проверили «вылет» руки за кадр для текущей пропажи. */
    @Volatile private var missingHandled = false
    /** Сброс движка просим у потока MediaPipe — он единственный, кто трогает engine. */
    @Volatile private var resetRequested = false
    private var frameCounter = 0L   // только cameraExecutor
    private var lastTimestamp = 0L  // только cameraExecutor

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        engine.config = settings.engineConfig()
        invertSwipes = settings.invertSwipes
        AirTouchState.update { it.copy(screenLight = settings.screenLight) }
        if (key == AirTouchSettings.KEY_FRONT_CAMERA && !paused) bindCamera()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pauseCamera()
                Intent.ACTION_SCREEN_ON -> resumeCamera()
            }
        }
    }
    private var screenReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        settings = AirTouchSettings(this)
        loadDictionaries()
    }

    // ---------------- Воздушная клавиатура ----------------

    @Volatile private var decoders: Map<Lang, ZoneDecoder> = emptyMap()
    private var keyboard: ZoneKeyboardController? = null // только поток MediaPipe

    /** Словари RU/EN (~1 МБ) грузим в фоне один раз — к первому открытию клавиатуры они готовы. */
    private fun loadDictionaries() {
        Thread {
            try {
                decoders = Lang.values().associateWith { lang ->
                    assets.open(Dictionary.assetName(lang)).bufferedReader().use {
                        ZoneDecoder(lang, Dictionary.parse(it))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "dictionaries failed", e)
            }
        }.apply {
            name = "airtouch-dict"
            isDaemon = true
        }.start()
    }

    private fun keyboardFor(input: AirInputService): ZoneKeyboardController =
        keyboard ?: ZoneKeyboardController(input.textSink, { decoders[it] }).also { keyboard = it }

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

        engine.config = settings.engineConfig()
        invertSwipes = settings.invertSwipes
        resetRequested = true
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true

        lastHandSeenAt = SystemClock.uptimeMillis()
        paused = false
        economy = false
        handVisible = false
        AirTouchState.update { AirTouchState.Status(mode = Mode.ACTIVE, screenLight = settings.screenLight) }

        bindCamera()
        startWatchdog()
    }

    private fun shutdown(mode: Mode, error: String? = null) {
        watchdog?.cancel()
        watchdog = null
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        AirInputService.instance?.cancelTouch()
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        exposureBoosted = false
        screenLight.set(false)
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

    /** Через 3 с без руки — экономичный режим (~7 FPS). Полностью не засыпаем, пока горит экран. */
    private fun startWatchdog() {
        watchdog = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                if (paused) continue
                if (!economy && SystemClock.uptimeMillis() - lastHandSeenAt > Timeouts.NO_HAND_IDLE_MS) {
                    economy = true
                    AirTouchState.update { it.copy(mode = Mode.IDLE, handVisible = false, cursorVisible = false) }
                }
            }
        }
    }

    private fun pauseCamera() {
        if (!isRunning || paused) return
        paused = true
        resetRequested = true
        handVisible = false
        AirInputService.instance?.cancelTouch()
        cameraProvider?.unbindAll()
        AirTouchState.update { it.copy(mode = Mode.PAUSED, handVisible = false, cursorVisible = false) }
    }

    private fun resumeCamera() {
        if (!isRunning || !paused) return
        paused = false
        economy = true // пока руки нет — экономно; появится рука — сразу полная частота
        lastHandSeenAt = SystemClock.uptimeMillis()
        AirTouchState.update { it.copy(mode = Mode.IDLE) }
        bindCamera()
    }

    // ---------------- Камера ----------------

    /**
     * Фиксированные 30 FPS. По умолчанию в помещении камера опускается до 15 FPS с длинной выдержкой —
     * быстрый мах превращается в размазанное пятно, и MediaPipe теряет руку.
     */
    private fun bestFpsRange(front: Boolean): Range<Int>? = try {
        val cm = getSystemService(CameraManager::class.java)
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
        id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
            ?.filter { it.upper <= 30 }
            ?.maxWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))
    } catch (e: Exception) {
        Log.w(TAG, "fps ranges unavailable", e)
        null
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isRunning || paused) return@addListener
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
            val analysisBuilder = ImageAnalysis.Builder()
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
            bestFpsRange(front)?.let { range ->
                Log.i(TAG, "AE target fps range: $range")
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(cameraExecutor, ::analyze)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, analysis)
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

            val raw = FrameUtils.toUprightBitmap(image, mirror)

            if (frameCounter % Timeouts.LUMA_EVERY_N_FRAMES == 0L) {
                lastLuma = FrameUtils.meanLuma(raw)
                // Гистерезис: «на максимуме» кадр сам по себе светлее — выходим, только когда стало заметно светло.
                val dark = if (exposureBoosted) lastLuma < Timeouts.DARK_EXIT_LUMA else lastLuma < Timeouts.BRIGHTEN_BELOW_LUMA
                ContextCompat.getMainExecutor(this).execute { updateExposure(dark) }
                val low = lastLuma < Timeouts.LOW_LIGHT_LUMA
                if (low != AirTouchState.status.value.lowLight) AirTouchState.update { s -> s.copy(lowLight = low) }
            }

            val ts = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = ts
            val gain = if (lastLuma < Timeouts.BRIGHTEN_BELOW_LUMA) {
                (Timeouts.BRIGHTEN_TARGET_LUMA / lastLuma.coerceAtLeast(10f)).coerceIn(1f, 4f)
            } else 1f
            t.detect(FrameUtils.brighten(raw, gain), ts)
        }
    }

    // ---------------- Результаты MediaPipe (его поток) ----------------

    private fun onHand(frame: HandFrame?) {
        if (!isRunning || paused) return
        val input = AirInputService.instance
        if (resetRequested) {
            resetRequested = false
            dispatch(engine.onHandLost(), input)
        }
        val now = SystemClock.uptimeMillis()
        if (frame == null) {
            countFrame(now)
            if (!handVisible) return
            // Первый кадр без руки: возможно, это резкий мах «за край» — засчитываем сразу.
            if (!missingHandled) {
                missingHandled = true
                val out = engine.onHandMissing(now)
                dispatch(out, input)
                if (out.swipe != null) publishSwipe(out, now)
            }
            // MediaPipe может потерять смазанную руку на 1–3 кадра — сбрасываем жесты не сразу.
            if (now - lastHandSeenAt > Timeouts.HAND_LOST_GRACE_MS) {
                handVisible = false
                val lost = engine.onHandLost()
                if (engine.keyboardMode) keyboard?.cancelStroke() else dispatch(lost, input)
                AirTouchState.update {
                    it.copy(handVisible = false, pose = null, cursorVisible = false, pinching = false,
                        holdAction = null, kbCursorVisible = false, keyboard = keyboard?.view(), dockVisible = false)
                }
            }
            return
        }

        countFrame(now)
        lastHandSeenAt = now
        handVisible = true
        missingHandled = false
        economy = false

        // Открыта системная клавиатура → рука печатает на воздушной.
        val kbOn = AirTouchState.status.value.keyboardVisible && input != null
        if (engine.keyboardMode != kbOn) {
            engine.keyboardMode = kbOn
            if (!kbOn) keyboard?.reset()
        }

        engine.suppressedUntil = com.airtouch.core.TouchGuard.quietUntil()
        val out = engine.process(frame)
        val cursor = engine.cursor
        val kb = if (kbOn && input != null) keyboardFor(input) else null
        if (kb != null) {
            cursor?.let { kb.onCursor(it.x, it.y) }
            out.touch?.let { kb.onTouch(it) }
            out.action?.let { input?.system(it) }
        } else {
            dispatch(out, input)
        }

        val palm = engine.palm
        AirTouchState.update { s ->
            s.copy(
                mode = Mode.ACTIVE,
                handVisible = true,
                pose = engine.lastPose,
                palmX = palm?.x ?: s.palmX,
                palmY = palm?.y ?: s.palmY,
                cursorVisible = kb == null && cursor != null,
                kbCursorVisible = kb != null && cursor != null,
                kbCursorX = cursor?.x ?: s.kbCursorX,
                kbCursorY = cursor?.y ?: s.kbCursorY,
                kbPinching = kb != null && cursor?.pinching == true,
                keyboard = kb?.view() ?: s.keyboard,
                cursorX = cursor?.x ?: s.cursorX,
                cursorY = cursor?.y ?: s.cursorY,
                pinching = kb == null && cursor?.pinching == true,
                holdAction = out.hold?.action,
                dockVisible = out.dock,
                holdProgress = out.hold?.progress ?: 0f,
                fps = fps,
                latencyMs = (now - frame.timestampMs).toInt(),
                lastGesture = out.swipe ?: s.lastGesture,
                lastGestureAt = if (out.swipe != null) now else s.lastGestureAt,
                lastAction = out.action ?: s.lastAction,
                lastActionAt = if (out.action != null) now else s.lastActionAt,
            )
        }
    }

    // FPS — сколько кадров в секунду реально проходит через распознавание.
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    @Volatile private var fps = 0

    private fun countFrame(now: Long) {
        fpsFrames++
        if (now - fpsWindowStart >= 1000) {
            fps = (fpsFrames * 1000 / (now - fpsWindowStart).coerceAtLeast(1)).toInt()
            fpsFrames = 0
            fpsWindowStart = now
            if (!handVisible) AirTouchState.update { it.copy(fps = fps) }
        }
    }

    private fun dispatch(out: EngineOutput, input: AirInputService?) {
        if (input == null) return
        out.touch?.let { input.touch(it) }
        out.swipe?.let { input.perform(if (invertSwipes) it.inverted() else it) }
        out.action?.let { input.system(it) }
    }

    private fun publishSwipe(out: EngineOutput, now: Long) {
        AirTouchState.update { it.copy(lastGesture = out.swipe, lastGestureAt = now) }
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
        private const val DARK_EXPOSURE_NS = 50_000_000L

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
