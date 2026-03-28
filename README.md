# 触控助手

一个基于 Android 无障碍服务的自动化触控工具，支持多点触控目标管理、方向性滑动、自然随机化操作，适用于自动化测试与辅助操作场景。

## 项目简介

本项目使用 Kotlin 开发，核心能力基于 `AccessibilityService.dispatchGesture()` 实现。支持在屏幕指定位置执行自动触控，并提供可视化悬浮控制面板。

使用流程：

1. 打开应用
2. 授予悬浮窗权限
3. 启用无障碍服务
4. 点击「启动服务」显示悬浮控制面板
5. 点击「+」添加触控目标，拖动到目标位置
6. 点击目标上的设置按钮，配置触控模式和参数
7. 点击播放按钮开始自动触控
8. 点击停止按钮停止当前任务
9. 点击退出按钮关闭服务（需确认）

---

## 功能特性

- 支持最多 **5 个独立触控目标**，每个目标可独立配置
- 支持悬浮控制面板，可拖拽移动
- 支持触控目标可视化拖拽定位
- 支持两种触控模式：**单次触控**（可配置间隔）、**持续触控**（可配置滑动方向和距离）
- 持续触控支持 **4 方向滑动**（上 / 下 / 左 / 右）
- 内置**自然随机化**：坐标抖动 ±3px、时序抖动 ±10%、按下时长随机变化
- 支持编辑模式，批量管理目标
- 支持播放 / 暂停切换
- 支持停止和退出分离操作
- 退出前提供确认弹窗防误触
- 使用 `SharedPreferences` 持久化保存配置
- GitHub Actions 自动构建 Release APK

---

## 技术规格

- 开发语言：Kotlin
- 构建工具：Gradle
- 最低 Android 版本：API 26（Android 8.0）
- 目标 SDK：34
- 编译 SDK：34
- 应用 ID：`com.jons.touchassist`

主要依赖：

- AndroidX Core KTX 1.10.1
- AndroidX AppCompat 1.6.1
- Material Components 1.9.0
- kotlinx-coroutines-android 1.7.1

---

## 核心架构

### 1. MainActivity
文件：`app/src/main/java/com/jons/touchassist/MainActivity.kt`

职责：
- 检查悬浮窗权限
- 检查无障碍服务是否启用
- 引导用户完成权限设置
- 在权限满足后启动服务

### 2. AutoClickService
文件：`app/src/main/java/com/jons/touchassist/core/AutoClickService.kt`

职责：
- 作为无障碍服务执行触控手势
- 管理多目标触控任务生命周期
- 支持单次触控（协程调度）和持续触控（Handler 循环）
- 内置坐标/时序/时长随机抖动
- 停止时发送空手势中断正在执行的长按

关键数据结构：
```kotlin
data class ClickTargetInfo(
    val id: String,
    val x: Float,
    val y: Float,
    val clickType: ClickType,   // SINGLE | LONG_PRESS
    val interval: Long,          // 单次触控间隔(ms)
    val swipeDistance: Int,      // 滑动距离(px)
    val swipeAngle: Int          // 滑动方向角度(0=右,90=下,180=左,270=上)
)
```

### 3. FloatingManager
文件：`app/src/main/java/com/jons/touchassist/core/FloatingManager.kt`

职责：
- 管理悬浮控制面板和多个触控目标视图
- 处理拖拽、编辑模式、设置弹窗
- 持久化保存目标配置
- 与 AutoClickService 同步目标数据

---

## 触控模式说明

### 单次触控
每隔指定间隔执行一次点按，duration 40~90ms 随机，坐标 ±3px 随机偏移。

### 持续触控
循环执行按下+滑动手势（400~500ms/次），支持配置：
- **滑动距离**：像素值
- **滑动方向**：上 / 下 / 左 / 右

滑动起终点均有随机抖动，每次轨迹略有不同。

---

## 构建与发布

### 本地构建

```bash
./gradlew assembleDebug
```

### 自动发布

推送 tag 后 GitHub Actions 自动构建签名 Release APK：

```bash
git tag v1.0.0
git push origin v1.0.0
```

APK 会自动附加到 GitHub Release 页面，文件名格式：`touchassist-v1.0.0.apk`

#### 所需 GitHub Secrets

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | keystore 文件的 base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |

生成 KEYSTORE_BASE64：
```bash
# Linux/macOS
base64 -w 0 touchassist.jks.jks

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("touchassist.jks"))
```

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 显示悬浮控制面板和触控目标 |
| `BIND_ACCESSIBILITY_SERVICE` | 执行触控手势 |

---

## 调试

```bash
adb logcat | grep TouchService
```

---

## 常见问题

### 1. 「启动服务」按钮不可用
请确认已授予悬浮窗权限并启用无障碍服务。

### 2. 清除后台后服务似乎仍在运行
Android 无障碍服务生命周期由系统管理，清除后台不等于关闭无障碍服务。请使用应用内「退出」按钮结束运行态，或在系统设置中关闭无障碍服务。

### 3. 持续触控停止后屏幕仍有残留输入
这是因为正在执行的手势 duration 较长。应用已通过发送空手势来中断，极端情况下可能有短暂延迟（<500ms）。

### 4. 构建报找不到 Java
请安装 JDK 17+ 并配置 `JAVA_HOME`。

---

## 注意事项

- 本项目依赖无障碍服务，请仅在合法、合规的场景下使用。
- 不同厂商 ROM 对悬浮窗、无障碍服务的处理存在差异，建议在目标设备上测试。
- Release 版本已启用 ProGuard 代码混淆。
