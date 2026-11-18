package com.stats.baccaratoverlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AutoRobotOverlayService::class.java).apply {
                putExtra(AutoRobotOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AutoRobotOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true)
        } else {
            Toast.makeText(
                this,
                "Bạn cần cấp quyền quay màn hình để Robot tự đọc kết quả",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        val title = TextView(this).apply {
            text = "🤖 AUTOROBOT BACCARAT V2 (ANDROID 14)\n"
            textSize = 18f
        }

        val info = TextView(this).apply {
            text = "• Tự động bắt kết quả từ màn hình (không cần bấm tay).\n" +
                    "• Thu gọn thành Con Robot Mini nổi trên góc màn hình.\n" +
                    "• Tự bật Bảng Mini báo Con/Cái & tỷ lệ thực từ data bàn.\n" +
                    "• Bộ lọc bỏ qua cầu 50/50 nhiễu, chỉ chốt khi tỷ lệ thực đạt ngưỡng.\n\n" +
                    "Cách dùng:\n" +
                    "1. Bấm nút bên dưới -> Cấp quyền Cửa sổ nổi & Quay màn hình.\n" +
                    "2. Vào bàn chơi, kéo tâm [🎯 QUÉT] đặt đè lên chỗ hiện chữ PLAYER/BANKER thắng.\n" +
                    "3. Robot sẽ tự bắt dữ liệu và tự bật bảng mini báo lệnh."
            textSize = 14f
        }

        val btnStart = Button(this).apply {
            text = "🚀 KHỞI ĐỘNG ROBOT MINI AUTO"
            setOnClickListener { startAutoRobotFlow() }
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(btnStart)
        setContentView(layout)

        requestNotificationPermissionAndroid14()
    }

    private fun requestNotificationPermissionAndroid14() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun startAutoRobotFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Bước 1: Bật quyền 'Hiển thị trên ứng dụng khác'",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }
}
