package kr.co.intense.walksense

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.Keyframe
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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

    private val sensorHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayPulseAnimator: Animator? = null
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
            // 뒤늦게 도착한 이벤트로 인해, 이미 멈춘 사람을 다시 걷는 중으로 되돌리지 않는다.
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
            // showOverlay() // TODO: 테스트용 - 무조건 오버레이 표시
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
        // 파동이 화면 가로 폭의 80%까지 퍼지도록 최대 배율을 계산한다.
        val maxPulseScale = (resources.displayMetrics.widthPixels * 0.8f) / badgeSize
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.stop)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D9D9D9")) // 조금 더 어두운 회색
            }
            clipToOutline = true
        }
        // 여러 원이 시차를 두고 퍼지며 겹치는 느낌을 주기 위한 파동 개수.
        val pulseRingCount = 2
        // 퍼지고 사라지는 데 걸리는 시간과, 다음 파동이 시작되기 전 쉬는 시간.
        val pulseActiveDuration = 2_600L
        val pulsePauseDuration = 1_400L
        val pulseCycleDuration = pulseActiveDuration + pulsePauseDuration
        val pulseActiveFraction = pulseActiveDuration.toFloat() / pulseCycleDuration
        val badgeContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            repeat(pulseRingCount) {
                addView(
                    View(this@WalkingDetectionService).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#D9D9D9")) // 배지 배경색과 동일한 회색
                        }
                    },
                    FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER),
                )
            }
            addView(badge, FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER))
        }
        // 이미지는 고정된 채, 원형 배경 여러 개가 시차를 두고 밖으로 퍼지며 옅어지는 파동(ripple) 효과.
        // 한 주기 안에서 퍼지는 구간(pulseActiveFraction)이 끝나면 쉬는 구간 동안 투명하게 머문다.
        val pulseRingAnimators = (0 until pulseRingCount).map { index ->
            val ring = badgeContainer.getChildAt(index)
            val scaleKeyframes = PropertyValuesHolder.ofKeyframe(
                View.SCALE_X,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(pulseActiveFraction, maxPulseScale),
                Keyframe.ofFloat(1f, maxPulseScale),
            )
            val scaleYKeyframes = PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(pulseActiveFraction, maxPulseScale),
                Keyframe.ofFloat(1f, maxPulseScale),
            )
            // 알파는 퍼지는 구간의 절반까지 불투명함을 유지하다가 서서히 사라지고,
            // 쉬는 구간 동안은 계속 투명한 상태로 머문다.
            val alphaKeyframes = PropertyValuesHolder.ofKeyframe(
                View.ALPHA,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(pulseActiveFraction * 0.5f, 1f),
                Keyframe.ofFloat(pulseActiveFraction, 0f),
                Keyframe.ofFloat(1f, 0f),
            )
            ObjectAnimator.ofPropertyValuesHolder(
                ring,
                scaleKeyframes,
                scaleYKeyframes,
                alphaKeyframes,
            ).apply {
                duration = pulseCycleDuration
                startDelay = index * (pulseCycleDuration / pulseRingCount)
                repeatMode = ObjectAnimator.RESTART
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        overlayPulseAnimator = AnimatorSet().apply {
            playTogether(*pulseRingAnimators.toTypedArray())
            start()
        }

        // 파동이 badgeContainer의 레이아웃 크기를 넘어 아래로 퍼지므로,
        // 겹치지 않도록 headline 위쪽에 그 넘친 만큼 여백을 더해준다.
        val pulseOverflow = ((badgeSize * (maxPulseScale - 1f)) / 2f).toInt()
        val headline = TextView(this).apply {
            text = "보행 중 스마트폰\n사용 주의"
            setTextColor(Color.WHITE)
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(36) + pulseOverflow, 0, 0)
        }
        val body = TextView(this).apply {
            text = "잠시 걸음을 멈추고\n확인해주세요."
            setTextColor(Color.parseColor("#E6E1E5"))
            textSize = 25f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val footer = TextView(this).apply {
            text = "멈추면 자동으로 이전 화면으로 돌아갑니다."
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(32))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(badgeContainer, LinearLayout.LayoutParams(badgeSize, badgeSize))
            addView(
                headline,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F21C1B1F")) // 약 95% 불투명 (5% 정도 비쳐 보임)
            clipChildren = false
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            addView(
                footer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
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
    }
}
