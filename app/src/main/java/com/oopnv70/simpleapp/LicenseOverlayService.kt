package com.oopnv70.simpleapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 卡密验证悬浮窗服务。
 *
 * 卡密弹窗以「系统悬浮窗」形式呈现，浮在应用自身 UI 之上，
 * 与 MainActivity 的界面相互独立。
 */
class LicenseOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var verified = false

    companion object {
        const val ACTION_SHOW = "com.oopnv70.simpleapp.action.SHOW"
        const val EXTRA_RESULT = "result"
        private const val CHANNEL_ID = "license_overlay_channel"
        private const val NOTIF_ID = 1001

        /** 全局验证状态（供 Activity 查询） */
        @Volatile
        var unlocked: Boolean = false
            private set

        internal fun markUnlocked() {
            unlocked = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (verified) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay()
        return START_NOT_STICKY
    }

    /** 构建并显示悬浮窗 */
    private fun showOverlay() {
        if (overlayView != null) return

        val ctx = this
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 根容器（半透明遮罩）
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // 卡片
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF1B1F24.toInt())
                setStroke(dp(1), 0xFF2E353D.toInt())
            }
        }

        val title = TextView(ctx).apply {
            text = getString(R.string.license_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }

        val hint = TextView(ctx).apply {
            text = getString(R.string.license_hint)
            setTextColor(0xFF9AA3AD.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }

        val input = EditText(ctx).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6B747E.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF0E1116.toInt())
                setStroke(dp(1), 0xFF3A424B.toInt())
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }

        val cancelBtn = Button(ctx).apply {
            text = getString(R.string.license_cancel)
            setOnClickListener { hideOverlay() }
        }

        val confirmBtn = Button(ctx).apply {
            text = getString(R.string.license_confirm)
            setOnClickListener {
                val value = input.text?.toString().orEmpty()
                if (License.verify(value)) {
                    verified = true
                    markUnlocked()
                    Toast.makeText(ctx, getString(R.string.license_ok), Toast.LENGTH_SHORT).show()
                    hideOverlay()
                    // 通知 Activity 刷新
                    sendBroadcast(Intent(EXTRA_RESULT).setPackage(packageName))
                    stopSelf()
                } else {
                    Toast.makeText(ctx, getString(R.string.license_fail), Toast.LENGTH_SHORT).show()
                    input.setText("")
                }
            }
        }

        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })
        btnRow.addView(confirmBtn)

        card.addView(title)
        card.addView(hint)
        card.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(btnRow)

        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = root
        runCatching { windowManager.addView(root, lp) }
            .onFailure { e ->
                Toast.makeText(ctx, "悬浮窗添加失败: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
    }

    private fun hideOverlay() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
            overlayView = null
        }
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "License Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.license_title))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }
}