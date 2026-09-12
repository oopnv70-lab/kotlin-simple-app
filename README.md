# kotlin-simple-app

> 一个用于 **反编译 / 逆向工程练习** 的 Android 示例应用。
> 本项目是**教学靶子**，仅供合法的学习与安全研究使用。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](./LICENSE)
[![Build APK](https://github.com/oopnv70-lab/kotlin-simple-app/actions/workflows/build.yml/badge.svg)](https://github.com/oopnv70-lab/kotlin-simple-app/actions/workflows/build.yml)

---

## ⚠️ 法律声明

**使用本项目前，请务必阅读 [法律声明（NOTICE.md）](./NOTICE.md)。**

本项目（含其中的卡密、常量、算法与全部"防护"机制）**仅用于合法的
学习、教学与安全研究**。它是一个刻意设计、**欢迎被逆向**的靶场样本，
不用于保护任何真实软件或真实资产。使用者须自行承担全部风险与责任。

---

## 功能

- Kotlin + Jetpack Compose 编写 UI
- 启动后弹出 **系统悬浮窗**（`SYSTEM_ALERT_WINDOW`）形式的卡密验证弹窗
- 卡密校验逻辑**全部下沉到 native 层**（C++ / `.so`）：
  - 盐派生 + 明文拼装 + 10 万次迭代 SHA-256
  - native 反调试 / 反注入哨兵
  - 字符串零明文（敏感串以整数数组加密存储）
  - 放行前的隐藏复检（不一致则静默终止进程）
- Java 层放置**蜜罐诱饵**：看起来像"本地兜底校验"，一旦被改成恒真，
  native 会判定 dex 被篡改
- 验证通过后显示"已解锁"内容，并将授权状态以 **native 签发的
  不透明凭据**持久化到应用私有目录（重启免重输）

> 关于卡密的性质：源码中的卡密素材均为**演示数据**，随源码公开，
> 通过阅读源码或反编译得出正确卡密，正是本项目鼓励的练习方式。

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

云端构建由 **GitHub Actions** 自动完成（见 `.github/workflows/build.yml`），
每次推送到 `main` 即自动出包。

本地构建：

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
- **Ghidra / IDA**（分析 native 部分）

练习目标：

1. 找出正确的卡密；
2. 尝试绕过校验 —— 注意观察"改 Java 返回值"会发生什么；
3. 分析 `.so` 中的校验逻辑（提示：符号已被剥离）。

## 许可

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](./LICENSE)。

> 仅供学习与安全研究使用。