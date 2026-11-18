package com.stats.baccaratoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class AutoRobotOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var robotView: View
    private lateinit var targetView: View
    private lateinit var robotParams: WindowManager.LayoutParams
    private lateinit var targetParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private val history = mutableListOf<String>()

    private var isAutoScanning = true
    private var isPanelPinned = false
    private var isTargetVisible = true

    // Trạng thái máy quét chống đếm trùng 1 ván
    private var lastCandidateSide: String? = null
    private var consecutiveMatches = 0
    private var cooldownUntilMs = 0L

    private val autoCollapseRunnable = Runnable {
        if (!isPanelPinned && ::robotView.isInitialized) {
            val panel = robotView.findViewById<LinearLayout>(R.id.miniReportPanel)
            val toggleBtn = robotView.findViewById<TextView>(R.id.btnTogglePanel)
            panel.visibility = View.GONE
            toggleBtn.text = "▲"
        }
    }

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (isAutoScanning) {
                performScreenScan()
            }
            handler.postDelayed(this, 1000L) // Quét tự động mỗi 1 giây
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAndroid14ProjectionForeground()
        setupOverlayWindows()
        initMediaProjection(resultCode, resultData)

        handler.removeCallbacks(scanLoopRunnable)
        handler.postDelayed(scanLoopRunnable, 1200L)

        return START_NOT_STICKY
    }

    private fun startAndroid14ProjectionForeground() {
        val channelId = "autorobot_baccarat_channel"
        val channel = NotificationChannel(
            channelId,
            "AutoRobot Baccarat Scanner",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🤖 AutoRobot Baccarat đang hoạt động")
            .setContentText("Đang tự động quét kết quả từ màn hình")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                2002,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(2002, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Bắt buộc đăng ký Callback trước createVirtualDisplay trên Android 14
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "AutoRobotScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupOverlayWindows() {
        if (::windowManager.isInitialized) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1. Tạo Tâm Quét 🎯 nổi trên màn hình
        targetView = LayoutInflater.from(this).inflate(R.layout.scan_target_layout, null)
        targetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels / 2 - 80
            y = resources.displayMetrics.heightPixels / 2 - 80
        }
        windowManager.addView(targetView, targetParams)

        // 2. Tạo Con Robot Mini & Bảng Báo Mini
        robotView = LayoutInflater.from(this).inflate(R.layout.robot_overlay_layout, null)
        robotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }
        windowManager.addView(robotView, robotParams)

        attachDragListener(targetView, targetParams)
        attachDragListener(robotView.findViewById(R.id.robotHeader), robotParams)
        bindControls()
        updateUI(autoPopup = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(dragHandle: View, layoutParams: WindowManager.LayoutParams) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x
                    initY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (event.rawX - touchX).toInt()
                    layoutParams.y = initY + (event.rawY - touchY).toInt()
                    val root = if (dragHandle == targetView) targetView else robotView
                    windowManager.updateViewLayout(root, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun bindControls() {
        val miniPanel = robotView.findViewById<LinearLayout>(R.id.miniReportPanel)
        val btnTogglePanel = robotView.findViewById<TextView>(R.id.btnTogglePanel)
        val btnCloseApp = robotView.findViewById<TextView>(R.id.btnCloseApp)
        val btnPinPanel = robotView.findViewById<TextView>(R.id.btnPinPanel)

        val btnToggleAuto = robotView.findViewById<Button>(R.id.btnToggleAuto)
        val btnToggleTarget = robotView.findViewById<Button>(R.id.btnToggleTarget)
        val btnResetShoe = robotView.findViewById<Button>(R.id.btnResetShoe)

        val btnManualP = robotView.findViewById<Button>(R.id.btnManualP)
        val btnManualB = robotView.findViewById<Button>(R.id.btnManualB)
        val btnManualUndo = robotView.findViewById<Button>(R.id.btnManualUndo)

        btnTogglePanel.setOnClickListener {
            if (miniPanel.visibility == View.VISIBLE) {
                miniPanel.visibility = View.GONE
                btnTogglePanel.text = "▲"
            } else {
                miniPanel.visibility = View.VISIBLE
                btnTogglePanel.text = "▼"
            }
        }

        btnCloseApp.setOnClickListener {
            stopSelf()
        }

        btnPinPanel.setOnClickListener {
            isPanelPinned = !isPanelPinned
            btnPinPanel.text = if (isPanelPinned) "📌 Ghim: BẬT" else "📌 Ghim: TẮT"
            if (isPanelPinned) {
                handler.removeCallbacks(autoCollapseRunnable)
            }
        }

        btnToggleAuto.setOnClickListener {
            isAutoScanning = !isAutoScanning
            val tvStatus = robotView.findViewById<TextView>(R.id.tvRobotStatus)
            if (isAutoScanning) {
                btnToggleAuto.text = "⏸ Tạm dừng"
                tvStatus.text = "AUTO: ĐANG SOI"
            } else {
                btnToggleAuto.text = "▶️ Tiếp tục"
                tvStatus.text = "AUTO: TẠM DỪNG"
            }
        }

        btnToggleTarget.setOnClickListener {
            isTargetVisible = !isTargetVisible
            targetView.visibility = if (isTargetVisible) View.VISIBLE else View.GONE
        }

        btnResetShoe.setOnClickListener {
            history.clear()
            cooldownUntilMs = 0L
            lastCandidateSide = null
            consecutiveMatches = 0
            updateUI(autoPopup = true)
        }

        btnManualP.setOnClickListener {
            history.add("P")
            updateUI(autoPopup = true)
        }

        btnManualB.setOnClickListener {
            history.add("B")
            updateUI(autoPopup = true)
        }

        btnManualUndo.setOnClickListener {
            if (history.isNotEmpty()) {
                history.removeAt(history.lastIndex)
                updateUI(autoPopup = true)
            }
        }
    }

    private fun performScreenScan() {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) return

        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            // Lấy tọa độ chính xác của tâm quét 🎯 trên màn hình
            val loc = IntArray(2)
            targetView.getLocationOnScreen(loc)
            val startX = loc[0].coerceIn(0, image.width - 1)
            val startY = loc[1].coerceIn(0, image.height - 1)
            val boxW = targetView.width.coerceAtLeast(10).coerceAtMost(image.width - startX)
            val boxH = targetView.height.coerceAtLeast(10).coerceAtMost(image.height - startY)

            var blueCount = 0
            var redCount = 0
            var greenCount = 0
            var sampledPixels = 0
            val hsv = FloatArray(3)

            // Quét lưới điểm ảnh bên trong tâm quét (bỏ qua viền 6px để tránh khung xanh của tâm quét)
            val margin = 6
            val xFrom = (startX + margin).coerceAtMost(startX + boxW - 1)
            val xTo = (startX + boxW - margin).coerceAtLeast(xFrom + 1)
            val yFrom = (startY + margin).coerceAtMost(startY + boxH - 1)
            val yTo = (startY + boxH - margin).coerceAtLeast(yFrom + 1)

            for (y in yFrom until yTo step 2) {
                for (x in xFrom until xTo step 2) {
                    val pixel = fullBitmap.getPixel(x, y)
                    Color.colorToHSV(pixel, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]
                    sampledPixels++

                    if (sat >= 0.45f && value >= 0.35f) {
                        when {
                            hue in 195f..245f -> blueCount++
                            hue <= 15f || hue >= 340f -> redCount++
                            hue in 95f..150f -> greenCount++
                        }
                    }
                }
            }

            fullBitmap.recycle()

            if (sampledPixels > 0) {
                val threshold = (sampledPixels * 0.18).toInt().coerceAtLeast(8)
                val detected = when {
                    redCount >= threshold && redCount > blueCount * 1.5 -> "B"
                    blueCount >= threshold && blueCount > redCount * 1.5 -> "P"
                    greenCount >= threshold && greenCount > redCount * 1.5 && greenCount > blueCount * 1.5 -> "T"
                    else -> null
                }

                if (detected != null) {
                    if (detected == lastCandidateSide) {
                        consecutiveMatches++
                    } else {
                        lastCandidateSide = detected
                        consecutiveMatches = 1
                    }

                    // Khớp liên tiếp 2 giây để xác nhận đúng kết quả ván bài
                    if (consecutiveMatches >= 2) {
                        history.add(detected)
                        lastCandidateSide = null
                        consecutiveMatches = 0
                        // Khóa quét 14 giây để không đọc trùng cùng 1 ván
                        cooldownUntilMs = System.currentTimeMillis() + 14_000L

                        triggerSubtleVibration()
                        updateUI(autoPopup = true)
                    }
                } else {
                    lastCandidateSide = null
                    consecutiveMatches = 0
                }
            }
        } finally {
            image.close()
        }
    }

    private fun triggerSubtleVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(90)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateUI(autoPopup: Boolean) {
        val report = BaccaratMathEngine.evaluate(history)

        val tvRobotStatus = robotView.findViewById<TextView>(R.id.tvRobotStatus)
        val tvLastCaptured = robotView.findViewById<TextView>(R.id.tvLastCaptured)
        val tvMainPrediction = robotView.findViewById<TextView>(R.id.tvMainPrediction)
        val tvRealWinRate = robotView.findViewById<TextView>(R.id.tvRealWinRate)
        val tvMarkovProb = robotView.findViewById<TextView>(R.id.tvMarkovProb)
        val tvRecentSequence = robotView.findViewById<TextView>(R.id.tvRecentSequence)

        tvRobotStatus.text = report.robotBadgeText
        tvLastCaptured.text = report.lastCapturedLabel
        tvMainPrediction.text = report.mainRecommendation
        tvRealWinRate.text = report.realDistributionText
        tvMarkovProb.text = report.markovProbabilityText
        tvRecentSequence.text = report.recentSequenceText

        when (report.recommendedSide) {
            "P" -> {
                tvMainPrediction.setTextColor(Color.parseColor("#4FC3F7"))
                tvRobotStatus.setTextColor(Color.parseColor("#4FC3F7"))
            }
            "B" -> {
                tvMainPrediction.setTextColor(Color.parseColor("#FF5252"))
                tvRobotStatus.setTextColor(Color.parseColor("#FF5252"))
            }
            else -> {
                tvMainPrediction.setTextColor(Color.parseColor("#FFD740"))
                tvRobotStatus.setTextColor(Color.parseColor("#FFD700"))
            }
        }

        // Tự động bật bảng mini ra khi Robot vừa bắt được kết quả mới
        if (autoPopup) {
            val miniPanel = robotView.findViewById<LinearLayout>(R.id.miniReportPanel)
            val btnTogglePanel = robotView.findViewById<TextView>(R.id.btnTogglePanel)
            miniPanel.visibility = View.VISIBLE
            btnTogglePanel.text = "▼"

            handler.removeCallbacks(autoCollapseRunnable)
            if (!isPanelPinned) {
                // Tự thu gọn về lại Con Robot Mini sau 12 giây cho gọn màn hình
                handler.postDelayed(autoCollapseRunnable, 12_000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scanLoopRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::robotView.isInitialized) windowManager.removeView(robotView)
        if (::targetView.isInitialized) windowManager.removeView(targetView)
    }
}
