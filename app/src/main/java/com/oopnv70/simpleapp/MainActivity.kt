package com.oopnv70.simpleapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * 主界面。
 *
 * 启动后：
 *  1. 检查悬浮窗权限（SYSTEM_ALERT_WINDOW）
 *  2. 未授权 -> 引导去设置页授权（返回后自动重新检查）
 *  3. 已授权 -> 启动 LicenseOverlayService，弹出独立的卡密验证悬浮窗
 *  4. 验证通过后，主界面露出真正的"已解锁"内容
 *
 * 修复说明（针对闪退）：
 *  - 通知权限（POST_NOTIFICATIONS）在 Android 13+ 动态申请，避免前台服务通知失败崩溃
 *  - 所有服务启动/权限跳转均 try-catch，杜绝未捕获异常闪退
 *  - 悬浮窗权限返回用 onResume 兜底检查，兼容国产 ROM 无回调的问题
 */
class MainActivity : ComponentActivity() {

    private var unlockedState by mutableStateOf(false)

    /** 通知权限申请（Android 13+ 前台服务需要） */
    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 无论用户是否允许，都继续尝试显示悬浮窗（服务内部已做降级）
            if (!granted) {
                toast("未授予通知权限，悬浮窗验证仍会尝试显示")
            }
            startOverlayServiceSafely()
        }

    /** 引导用户去系统设置授权悬浮窗（特殊权限，无 result 回调，用 onResume 兜底） */
    private val overlayPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canDrawOverlays()) {
                startOverlayServiceSafely()
            }
        }

    /** 标记：用户因悬浮窗权限跳转过设置页，用于 onResume 回来时自动继续 */
    private var waitingOverlayPermission = false

    /** 接收悬浮窗验证结果 */
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (LicenseOverlayService.unlocked) {
                unlockedState = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注册验证结果广播（Android 13+ 必须显式指定 RECEIVER_NOT_EXPORTED）
        runCatching {
            val filter = IntentFilter(LicenseOverlayService.EXTRA_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(resultReceiver, filter)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101418)
                ) {
                    AppScreen(
                        unlocked = unlockedState,
                        onRequestLicense = { ensurePermissionsAndShow() }
                    )
                }
            }
        }

        // 若已解锁（例如从后台返回），直接同步状态
        unlockedState = LicenseOverlayService.unlocked
    }

    override fun onResume() {
        super.onResume()
        if (LicenseOverlayService.unlocked) {
            unlockedState = true
        }
        // 从"悬浮窗权限设置页"返回时的兜底：只要有权限就继续启动服务
        if (waitingOverlayPermission && canDrawOverlays()) {
            waitingOverlayPermission = false
            startOverlayServiceSafely()
        }
    }

    /**
     * App 回到前台（可见）——通知服务：可以（重新）显示卡密悬浮窗。
     * 这是"自毁后重现"的入口。
     */
    override fun onStart() {
        super.onStart()
        if (!LicenseOverlayService.unlocked && canDrawOverlays()) {
            sendServiceAction(LicenseOverlayService.ACTION_VISIBLE)
        }
    }

    /**
     * App 退到后台 / 切到其他应用 / 按 Home —— 通知服务：立刻销毁卡密悬浮窗。
     * 只有当本 App 可见时才显示，符合"自毁"要求。
     *
     * 例外：因申请悬浮窗权限而主动跳去系统设置页时，不触发自毁
     * （否则刚授权回来窗口就没了，权限申请流程会打架）。
     */
    override fun onStop() {
        super.onStop()
        if (waitingOverlayPermission) return
        if (!LicenseOverlayService.unlocked) {
            sendServiceAction(LicenseOverlayService.ACTION_INVISIBLE)
        }
    }

    /**
     * Activity 真正销毁（用户彻底退出 App / 系统回收）——通知服务自毁。
     */
    override fun onDestroy() {
        runCatching { unregisterReceiver(resultReceiver) }
        if (!LicenseOverlayService.unlocked) {
            sendServiceAction(LicenseOverlayService.ACTION_DESTROY)
        }
        super.onDestroy()
    }

    /** 向悬浮窗服务发送控制指令（容错，避免服务未启动时抛异常） */
    private fun sendServiceAction(action: String) {
        runCatching {
            val intent = Intent(this, LicenseOverlayService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    // ---------- 业务逻辑 ----------

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        } else {
            true
        }
    }

    /** 统一的权限检查 + 弹窗流程 */
    private fun ensurePermissionsAndShow() {
        // 1) 悬浮窗权限（必须）
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        // 2) 通知权限（Android 13+，前台服务需要，建议但非必须）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 先申请通知权限，回调里继续启动服务
            runCatching {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure {
                // 申请失败也直接启动，服务端已做降级
                startOverlayServiceSafely()
            }
            return
        }
        // 3) 权限齐了，启动悬浮窗服务
        startOverlayServiceSafely()
    }

    private fun requestOverlayPermission() {
        waitingOverlayPermission = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { overlayPermLauncher.launch(intent) }
            .onFailure {
                waitingOverlayPermission = false
                toast("无法打开悬浮窗权限设置页")
            }
    }

    /** 启动悬浮窗服务，全程容错，绝不闪退 */
    private fun startOverlayServiceSafely() {
        runCatching {
            val intent = Intent(this, LicenseOverlayService::class.java)
                .setAction(LicenseOverlayService.ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure { e ->
            toast("启动悬浮窗服务失败: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        runCatching {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun AppScreen(
    unlocked: Boolean,
    onRequestLicense: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (unlocked) {
            UnlockedContent()
        } else {
            LockedContent(onClick = onRequestLicense)
        }
    }
}

@Composable
private fun LockedContent(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "\uD83D\uDD12", fontSize = 48.sp)
        Text(
            text = "应用未激活",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "请通过卡密验证后使用",
            color = Color(0xFF9AA3AD),
            fontSize = 14.sp
        )
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("输入卡密")
        }
    }
}

@Composable
private fun UnlockedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "\u2705", fontSize = 48.sp)
        Text(
            text = "验证通过",
            color = Color(0xFF6EE7A8),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "欢迎使用 · 已解锁全部功能",
            color = Color(0xFF9AA3AD),
            fontSize = 14.sp
        )
        Text(
            text = "Build: ${BuildConfig.VERSION_NAME} (SDK ${Build.VERSION.SDK_INT})",
            color = Color(0xFF5C6670),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
