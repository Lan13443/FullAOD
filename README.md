# FullAOD —— 类原生 Android 14+ 全屏熄屏显示 LSPosed 模块

> **当前发布版本：`v1.5-opt`**（2026-11-25）｜[下载最新版](https://github.com/Lan13443/FullAOD/releases/latest)

> 为类原生 Android 14+（PixelOS / LineageOS / Evolution X / crDroid 等 AOSP 系 ROM）补齐**锁屏全屏壁纸 AOD（Always-On Display）**体验：熄屏显示主屏幕壁纸 + 保留原生 Pixel AOD 时钟 + iOS 式景深缩放过渡动画 + 智能姿态感应。

---

## ✨ 核心功能

### 1. 全屏熄屏渲染与防烧屏
- **全屏壁纸 + 原生 AOD 时钟**：在 `SystemUI` 的 `NotificationShadeWindowView` 底层注入自绘壁纸层并压制 Scrim 遮罩，保留原生 Pixel 锁屏时钟与通知图标。
- **壁纸暗化与亮度增益**：支持 `0%–95%` 暗化遮罩调节与 `100%–300% (1.0×–3.0×)` Doze 亮度倍数放大（兼容浮点 `0.0~1.0` 与整数 `1~255` 两种硬件亮度制式）。
- **iOS 风格景深过渡动效**：自定义熄屏淡入时长（`150–1500 ms`）、亮屏唤醒淡出时长（`100–1000 ms`）以及壁纸微距放大倍率（`100%–110%`）。
- **OLED 像素位移防烧屏**：每 90 秒在 `±3px` 范围内周期性微移壁纸，避免静态像素老化。
- **壁纸更换自动同步**：监听系统 `ACTION_WALLPAPER_CHANGED` 广播，更换壁纸后自动重载，无需重启 SystemUI。

### 2. 姿态与智能感应
- **反扣手机自动关闭所有 AOD（`flipOff`）**：当手机屏幕朝下水平反扣放置时，自动关闭全部 AOD（同时关闭全屏壁纸与系统时钟，进入 `DOZE_AOD_PAUSED` 彻底熄屏省电）；再次将手机翻转朝上时自动恢复显示 AOD。
- **拿起手机自动亮屏（`liftWake`）**：在熄屏或 AOD 状态下（带锁屏冷却期与距离传感器防口袋误触），通过加速度三维姿态倾角变化与硬件 `TYPE_PICK_UP_GESTURE` 识别拿起并抬起手机动作，自动唤醒并点亮锁屏。

### 3. 智能夜间模式与超时退化
- **夜间自动退化**：支持设定夜间睡眠时段（如 `23:00 - 07:00`），可选择退化为「仅保留系统时钟」或「完全关闭 AOD」。
- **智能晨间唤醒（`nightSmart`）**：开启后不按固定结束时间退出，而是在**次日 05:00 后第一次亮屏并成功解锁**时自动结束当日夜间退化。
- **长期静置超时退化**：支持设定 `5–180 分钟` 超时阈值（基于 `elapsedRealtime` 休眠计时），超时后自动降级为仅时钟或完全关闭 AOD。

### 4. 现代化 Material You (Monet) + 液态玻璃设置前端
- **双主界面 + 二级菜单架构**：
  - **主页**：模块运行状态大卡片、**模块总开关**、设备信息（设备名称、型号、Android ROM 版本、SDK 版本）及当前熄屏渲染策略概览。
  - **设置详情页**：分类卡片式布局，内置 **`WallpaperAodView` 实时壁纸暗化预览卡片**、时间选择器、JSON 配置导入/导出及恢复默认值二次确认。
- **Monet 动态主题引擎**：支持跟随系统 / 浅色 / 深色模式，读取 Android 14 系统壁纸 Monet 动态取色，内置 8 种主题色、5 种色彩风格（`TonalSpot` / `Vibrant` / `Expressive` / `Neutral` / `Monochrome`）与 `SPEC_2025` / `SPEC_2021` 规范及实时手机线框预览。
- **顶部实时高斯模糊 & 底部液态玻璃悬浮底栏**：基于 Android 14 `RenderNode` + `RenderEffect` + `RuntimeShader` (AGSL)，移植 `Kyant0 / AndroidLiquidGlass` 的圆角 SDF 透镜折射与色散着色器。
- **Android 14 预测性返回手势**：原生接入 `OnBackAnimationCallback`，二级菜单返回与标签切换支持跟手缩放预览及边缘右滑返回。
- **桌面图标显隐控制**：提供 Android 13/14 自适应主题图标，支持在设置内一键隐藏/显示桌面图标或主动固定桌面快捷方式。

---

## 📦 安装与使用

1. 从 [Releases](../../releases) 下载最新 `FullAOD-v1.5-opt.apk`（或使用本地构建产物）并安装到手机。
2. 打开 **LSPosed 管理器** → 启用 **FullAOD** 模块 → 勾选作用域 **`系统界面 (com.android.systemui)`**。
3. 打开一次 **FullAOD** 应用（自动完成 `WRITE_SECURE_SETTINGS` 权限自愈并同步 19 项配置至 `Settings.Secure`）。
4. 重启一次 SystemUI（`su -c "killall com.android.systemui"`）或重启手机即可生效；后续调整任何参数均在**下次熄屏时自动生效**，无需再次重启。

---

## 🛠️ 免 Gradle 极速构建

本项目采用纯 Android Framework API + AGSL 编写，零第三方库依赖，使用 `javac` + `d8` + `aapt` + `zipalign` + `apksigner` 直接构建，产物仅 ~90 KB：

```bash
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
export D8_JAR=$ANDROID_HOME/build-tools/34.0.0/lib/d8.jar   # 若 PATH 中有 d8 可留空
bash build-portable.sh
# 产出：build/FullAOD-v1.0.apk（名称由 build-portable.sh 的 APK_NAME 决定）
```

---

目前仅在Redmi K60+PixelOS 安卓14上验证，尚且还存在某些bug
