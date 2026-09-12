# kotlin-simple-app

一个用于 **反编译练习** 的 Android 示例应用。

## 功能

- Kotlin + Jetpack Compose 编写 UI
- 启动后弹出 **系统悬浮窗**（SYSTEM_ALERT_WINDOW）形式的卡密验证弹窗
- 卡密以 **Base64 编码**存储，运行时解码 + SHA-256 比对（不含明文）
- 验证通过后显示"已解锁"内容

## 技术栈 / 版本（2026）

| 组件 | 版本 |
|---|---|
| Android | 17 (API 37) |
| AGP | 9.4.0 |
| Gradle | 9.6.0 |
| JDK | 17 |
| Kotlin | 2.2.x |
| Compose BOM | 2026.09.00 |

构建脚本中包含 **构建期环境校验**（JDK 版本、compileSdk、SDK 路径），
不满足时直接 fail，避免工具链错配。

## 构建

```bash
# 需要 JDK 17 + Android SDK (compileSdk 37)
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次点击"输入卡密"会引导授予悬浮窗权限。

## 反编译练习

建议工具：

- **Jadx**（推荐）：`jadx-gui app-debug.apk`
- **apktool**：`apktool d app-debug.apk`
- **Ghidra / IDA**（如有 native 部分）

练习目标：找出正确的卡密，或绕过 `License.verify()` 的返回值。

> 仅供学习与安全研究使用。
