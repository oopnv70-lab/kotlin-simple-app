package com.oopnv70.simpleapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主界面。
 *
 * 启动后：
 *  1. 检查悬浮窗权限（SYSTEM_ALERT_WINDOW）
 *  2. 未授权 -> 引导去设置页授权
 *  3. 已授权 -> 启动 LicenseOverlayService，弹出独立的卡密验证悬浮窗
 *  4. 验证通过后，主界面露出真正的"已解锁"内容
 */
class MainActivity : ComponentActivity() {

    private var unlockedState by mutableStateOf(false)

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 用户从设置页返回，重新检查
            if (canDrawOverlays()) {
                startOverlayService()
            }
        }

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

        // 注册验证结果广播
        val filter = IntentFilter(LicenseOverlayService.EXTRA_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101418)
                ) {
                    AppScreen(
                        unlocked = unlockedState,
                        onRequestLicense = { ensurePermissionAndShow() }
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
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(resultReceiver) }
        super.onDestroy()
    }

    // ---------- 业务逻辑 ----------

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun ensurePermissionAndShow() {
        if (canDrawOverlays()) {
            startOverlayService()
        } else {
            // 跳转系统设置申请悬浮窗权限
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, LicenseOverlayService::class.java)
            .setAction(LicenseOverlayService.ACTION_SHOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
private fun AppScreen(
    unlocked: Boolean,
    onRequestLicense: () -> Unit
) {
    var showLicense by remember { mutableStateOf(false) }

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
            LockedContent(onClick = {
                showLicense = true
                onRequestLicense()
            })
        }
    }
}

@Composable
private fun LockedContent(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🔒",
            fontSize = 48.sp
        )
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
        Text(
            text = "✅",
            fontSize = 48.sp
        )
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