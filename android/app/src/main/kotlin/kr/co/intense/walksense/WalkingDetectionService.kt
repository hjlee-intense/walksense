package kr.co.intense.walksense

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 화면 수명과 독립적으로 걸음 센서를 구독하는 포그라운드 서비스. */
class WalkingDetectionService : Service() {
    enum class WalkingStatus { UNKNOWN, WALKING, STOPPED, UNAVAILABLE }

    private val sensorManager by lazy {
        getSystemService(SENSOR_SERVICE) as SensorManager
    }
    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    /**
     * Flutter가 번들에 포함해 둔 Material Icons 폰트를 그대로 로드해서,
     * 오버레이의 아이콘이 Icons.directions_walk_rounded와 동일한 모양으로 보이게 한다.
     */
    private val materialIconTypeface: Typeface? by lazy {
        try {
            Typeface.createFromAsset(assets, MATERIAL_ICONS_FONT_ASSET)
        } catch (error: Exception) {
            Log.w(TAG, "Material Icons 폰트를 불러오지 못했습니다.", error)
            null
        }
    }
    private val sensorHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayPulseAnimator: ObjectAnimator? = null
    private var hideOverlayRunnable: Runnable? = null
    private var sensorRegistered = false
    private var lastStepTimestampNanos = 0L
    private val checkStopped = object : Runnable {
        override fun run() {
            if (!sensorRegistered || lastStepTimestampNanos == 0L) return
            val elapsedMillis =
                (SystemClock.elapsedRealtimeNanos() - lastStepTimestampNanos) / 1_000_000
            if (elapsedMillis >= STOP_TIMEOUT_MILLIS) {
                updateWalkingStatus(WalkingStatus.STOPPED)
            } else {
                sensorHandler.postDelayed(this, STOP_TIMEOUT_MILLIS - elapsedMillis)
            }
        }
    }
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!sensorRegistered || event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
            if (event.timestamp <= lastStepTimestampNanos) return
            lastStepTimestampNanos = event.timestamp
            sensorHandler.removeCallbacks(checkStopped)
            // 遅れて届いたイベントで、すでに止まっている人を歩行中に戻さない。
            val elapsedMillis =
                (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
            if (elapsedMillis >= STOP_TIMEOUT_MILLIS) {
                updateWalkingStatus(WalkingStatus.STOPPED)
            } else {
                updateWalkingStatus(WalkingStatus.WALKING)
                sensorHandler.postDelayed(checkStopped, STOP_TIMEOUT_MILLIS - elapsedMillis)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "보행 감지 서비스",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWalkingDetection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // health 서비스 시작에 앞서 신체활동 권한을 확인한다.
        // 권한 요청 UI는 Flutter 화면에서 처리한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "신체활동 권한이 없어 서비스를 종료합니다.")
            stopWalkingDetection()
            isRunning = false
            stateListener?.invoke(false, "신체활동 권한을 허용해 주세요.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            stateListener?.invoke(true, null)
            startWalkingDetection()
            // showOverlay() // TODO: 테스트용 - 무조건 오버레이 표시. 확인 후 제거.
        } catch (error: RuntimeException) {
            Log.e(TAG, "포그라운드 서비스 실행 권한을 확인해 주세요.", error)
            isRunning = false
            stopWalkingDetection()
            stateListener?.invoke(false, "서비스를 시작하지 못했습니다. 권한과 실행 상태를 확인해 주세요.")
            stopSelf()
        }

        // 이 단계에서는 시스템 종료 후 자동 재시작하지 않는다.
        return START_NOT_STICKY
    }

    private fun startWalkingDetection() {
        if (sensorRegistered) return
        lastStepTimestampNanos = 0L
        updateWalkingStatus(WalkingStatus.UNKNOWN)
        try {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (sensor == null) {
                Log.w(TAG, "걸음 감지 센서가 없습니다.")
                updateWalkingStatus(WalkingStatus.UNAVAILABLE)
                return
            }
            sensorRegistered = sensorManager.registerListener(
                stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler,
            )
            if (!sensorRegistered) {
                Log.w(TAG, "걸음 감지 센서를 등록하지 못했습니다.")
                updateWalkingStatus(WalkingStatus.UNAVAILABLE)
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "걸음 감지 센서를 시작하지 못했습니다.", error)
            sensorManager.unregisterListener(stepListener)
            sensorRegistered = false
            updateWalkingStatus(WalkingStatus.UNAVAILABLE)
        }
    }

    private fun stopWalkingDetection() {
        if (sensorRegistered) sensorManager.unregisterListener(stepListener)
        sensorRegistered = false
        sensorHandler.removeCallbacks(checkStopped)
        lastStepTimestampNanos = 0L
        hideOverlay(immediate = true)
        if (walkingStatus != WalkingStatus.UNKNOWN) {
            walkingStatus = WalkingStatus.UNKNOWN
            walkingStatusListener?.invoke(WalkingStatus.UNKNOWN)
        }
    }

    private fun updateWalkingStatus(status: WalkingStatus) {
        if (walkingStatus == status) return
        walkingStatus = status
        Log.d(TAG, "보행 상태: $status")
        walkingStatusListener?.invoke(status)
        when (status) {
            WalkingStatus.WALKING -> showOverlay()
            WalkingStatus.STOPPED -> hideOverlay(immediate = false)
            WalkingStatus.UNKNOWN, WalkingStatus.UNAVAILABLE -> hideOverlay(immediate = true)
        }
        if (isRunning) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification())
        }
    }

    /** 걷는 중일 때 다른 앱 위에도 보이는 경고 오버레이를 띄운다. */
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "오버레이 권한이 없어 경고를 표시할 수 없습니다.")
            return
        }
        hideOverlayRunnable?.let { sensorHandler.removeCallbacks(it) }
        hideOverlayRunnable = null
        if (overlayView != null) return

        val view = createOverlayView()
        try {
            windowManager.addView(view, createOverlayLayoutParams())
            overlayView = view
            Log.d(TAG, "오버레이 표시: true")
        } catch (error: Exception) {
            Log.e(TAG, "경고 오버레이를 표시하지 못했습니다.", error)
        }
    }

    /** 걷기가 멈추면 오버레이를 없앤다. STOPPED는 다시 걷기 시작할 여지를 두고 유예 시간을 둔다. */
    private fun hideOverlay(immediate: Boolean) {
        hideOverlayRunnable?.let { sensorHandler.removeCallbacks(it) }
        hideOverlayRunnable = null

        if (!immediate) {
            val runnable = Runnable { removeOverlayView() }
            hideOverlayRunnable = runnable
            sensorHandler.postDelayed(runnable, HIDE_OVERLAY_DELAY_MILLIS)
            return
        }
        removeOverlayView()
    }

    private fun removeOverlayView() {
        overlayPulseAnimator?.cancel()
        overlayPulseAnimator = null
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
            Log.d(TAG, "오버레이 표시: false")
        } catch (error: Exception) {
            Log.e(TAG, "경고 오버레이를 제거하지 못했습니다.", error)
        }
    }

    private fun createOverlayLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
    }

    /** Flutter의 WalkingWarningScreen과 같은 문구·구성을 네이티브 뷰로 재현한다. */
    private fun createOverlayView(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val badgeSize = dp(160)
        val badge = TextView(this).apply {
            val iconTypeface = materialIconTypeface
            if (iconTypeface != null) {
                typeface = iconTypeface
                text = String(Character.toChars(MATERIAL_ICON_DIRECTIONS_WALK_ROUNDED))
                textSize = 72f
            } else {
                text = "🚶" // 🚶 (폰트 로드 실패 시 대체)
                textSize = 64f
            }
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8C1D18"))
            }
        }
        overlayPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            badge,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.2f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.2f),
        ).apply {
            duration = 1_200
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        val headline = TextView(this).apply {
            text = "걷는 중에는\n잠시 화면을 멀리해 주세요"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, 0)
        }
        val body = TextView(this).apply {
            text = "고개를 들고 주변을 확인해 주세요.\n휴대폰은 안전한 곳에 멈춘 뒤 사용해 주세요."
            setTextColor(Color.parseColor("#E6E1E5"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val footer = TextView(this).apply {
            text = "멈춤이 감지되면 자동으로 돌아갑니다."
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(badge, LinearLayout.LayoutParams(badgeSize, badgeSize))
            addView(headline)
            addView(body)
            addView(footer)
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this, 1,
            Intent(this, WalkingDetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("WalkSense 실행 중")
            .setContentText(
                when (walkingStatus) {
                    WalkingStatus.UNKNOWN -> "보행 상태를 확인하고 있습니다."
                    WalkingStatus.WALKING -> "현재 걷는 중입니다."
                    WalkingStatus.STOPPED -> "현재 멈춰 있습니다."
                    WalkingStatus.UNAVAILABLE -> "보행 센서를 사용할 수 없습니다."
                },
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "중지", stopService).build())
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        stopWalkingDetection()
        stateListener?.invoke(false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var isRunning = false
            private set
        var walkingStatus = WalkingStatus.UNKNOWN
            private set
        var stateListener: ((Boolean, String?) -> Unit)? = null
        var walkingStatusListener: ((WalkingStatus) -> Unit)? = null
        const val ACTION_STOP = "kr.co.intense.walksense.action.STOP_WALKING_DETECTION"
        private const val TAG = "WalkingDetectionService"
        private const val CHANNEL_ID = "walking_detection"
        private const val NOTIFICATION_ID = 1001
        // 센서 전달 지연과 느린 걸음을 고려해 실제 기기에서 조정할 기준값.
        private const val STOP_TIMEOUT_MILLIS = 3_000L
        // 멈춘 직후 다시 걸을 수 있으므로 오버레이 제거를 잠시 유예한다.
        private const val HIDE_OVERLAY_DELAY_MILLIS = 2_000L
        // Flutter 빌드 산출물에 포함된 Material Icons 폰트 에셋 경로.
        private const val MATERIAL_ICONS_FONT_ASSET = "flutter_assets/fonts/MaterialIcons-Regular.otf"
        // Icons.directions_walk_rounded의 코드포인트 (packages/flutter/lib/src/material/icons.dart).
        private const val MATERIAL_ICON_DIRECTIONS_WALK_ROUNDED = 0xf6bd
    }
}
