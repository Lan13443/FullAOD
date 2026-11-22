# 界面结构说明（前端架构文档）

目标入口：`src/com/operit/fullaod/MainActivity.java`
前端辅助包：`src/com/operit/fullaod/ui/`（纯前端 UI 组件，注入侧不引用）
图标资源：`res/drawable/` 与 `res/drawable-v26/`（自适应图标 + Android 13/14 主题图标支持）

## 1. 整体架构

界面采用 **Material You (Monet 动态取色)** + **Miuix / LiquidTab 卡片排版风格**，完全基于 Android 14 (API 34) 原生 Framework API（`RenderNode`、`RenderEffect`、`RuntimeShader` AGSL、`OnBackAnimationCallback`）实现，零第三方依赖：

```text
MainActivity
└── mWindowRoot (FrameLayout, Edge-to-Edge 全屏沉浸)
    ├── mBackdropStage (FrameLayout, 实时模糊与液态玻璃采样源)
    │   ├── mMainPagesHost (FrameLayout)
    │   │   ├── Tab 0: mHomeScroll     「主页」
    │   │   └── Tab 1: mSettingsScroll 「设置」
    │   └── mSubPageOverlay            「二级菜单详情页容器」
    ├── mTopBar (BlurTopBarView)       「顶部实时高斯模糊导航栏（随滚动折叠大标题）」
    └── mBottomBar (LiquidGlassBottomBarView) 「底部 Apple/Miuix 风格液态玻璃悬浮底栏」
```

## 2. 两大主界面与二级菜单

### 2.1 主页（Tab 0，参考图 2 排版）
- **顶部栏**：大标题 `主页` + 右上角 `↻` 状态刷新按钮（滚动时顶部栏实时模糊并淡入紧凑标题）。
- **模块运行状态卡片（`HeroStatusCardView`）**：Monet 动态色调容器卡片，右侧带半透明大尺寸勾选圆环水印，显示 `模块已激活` / `模块已暂停`、版本号 `v1.0-patch31` 与 `FullAOD` 标识（点击可查看 `Settings.Secure` 实时 JSON 配置）。
- **模块总开关卡片**：紧跟在模块状态卡片下方，控制 `Prefs.K_ENABLED`（`enabled`），随时一键开启或暂停全屏 AOD（同步更新顶部状态卡片与 `Settings.Secure`）。
- **设备与系统信息卡片**：垂直堆叠展示 `设备名称`、`设备型号`、`Android ROM 版本`（自动识别 PixelOS 等 ROM 属性）与 `Android 版本`。
- **当前熄屏渲染策略概览卡片**：汇总显示当前暗化、亮度与夜间退化状态，点击直达壁纸预览二级页。

### 2.2 设置详情页（Tab 1，参考图 3 排版）
保留原有全部 17 项配置，优化功能文案说明并拆分为清晰分组卡片与二级菜单：
1. **显示与动效**
   - `壁纸显示与亮度`（二级菜单 `>`）：内含 **`WallpaperAodView` 实时壁纸暗化预览卡片**、`壁纸暗化程度 (mask 0–95%)`、`AOD 亮度倍数 (bright 100–300%)`、`放大倍率 (scale 100–110%)`。
   - `过渡动画时长`（二级菜单 `>`）：`熄屏过渡时长 (animIn 150–1500 ms)`、`亮屏过渡时长 (animOut 100–1000 ms)`。
   - `像素位移防烧屏`（直接开关）：`shift`（每 90 秒 ±3px 周期微移）。
2. **省电与退化策略**
   - `夜间自动退化`（二级菜单 `>`）：`启用夜间退化 (night)` 总开关（联动禁用子项）、`夜间开始 (nightStart 0–23)` 与 `夜间结束 (nightEnd 0–23)`（支持滑块 + `TimePickerDialog` 弹窗 + 同时间预警）、`夜间退化方式 (nightMode 0/1)`、`智能模式 (nightSmart)`。
   - `长期亮屏自动退化`（二级菜单 `>`）：`启用超时退化 (aodTimeoutOn)` 总开关（联动禁用子项）、`等待时间 (aodTimeoutMin 5–180 分钟)`、`退化方式 (aodTimeoutMode 0/1)`。
3. **锁屏与通知栏**
   - `锁屏与通知栏透出壁纸`（二级菜单 `>`）：`锁屏/通知栏透出壁纸 (shade)`、`通知栏遮罩 (shadeMask 0–95%)`。
4. **界面（参考图 3、图 4、图 5）**
   - `主题`（二级菜单 `>`）：
     - 顶部实时手机线框预览卡片（`ThemeMockupView`）
     - `跟随系统` | `浅色` | `深色` 三段切换
     - `启用 Monet 配色`、`主题色`（动态壁纸/紫色/湛蓝/青碧/翠绿/琥珀/珊瑚红/樱花粉）、`色彩风格`（`TonalSpot` / `Vibrant` / `Expressive` / `Neutral` / `Monochrome`）、`色彩规范`（`SPEC_2025` / `SPEC_2021`）
     - `模糊`（顶部及底栏实时模糊）、`悬浮底部栏`、`液态玻璃`（基于 `Kyant0/AndroidLiquidGlass` 的 AGSL 折射与色散着色器）、`底部栏标记`
     - `预测性返回手势`（Android 14 `OnBackAnimationCallback`）、`横移返回手势`、`页面切换手势`（`跨轴拦截` / `自由滑动` / `关闭`）
   - `隐藏桌面图标`：通过 `<activity-alias android:name=".LauncherActivity">` 动态切换桌面图标显隐，隐藏后仍可从 LSPosed 模块列表正常打开 `MainActivity`。
   - `固定快捷图标到桌面`：通过 `ShortcutManager.requestPinShortcut` 主动向桌面添加图标。
5. **调试与数据管理**
   - `当前生效配置与救援说明`、`导出设置`、`导入设置`、`恢复默认值`（带 `AlertDialog` 二次确认）。

## 3. 数据流与首启同步保障

- **首启导入**：`onCreate()` 启动时若 `SharedPreferences("fullaod")` 未初始化，自动从 `Settings.Secure.getString(cr, "fullaod_cfg")` 导入全部 17 个字段，防止覆盖线上 `nightMode=1` / `nightSmart=true` 配置。
- **UI 偏好隔离**：前端主题与手势配置独立存储在 `SharedPreferences("fullaod_ui")`，与 17 项后端配置完全解耦。
- **双写约定**：所有 AOD 控件变更均先写 `SharedPreferences("fullaod")` 再调用 `pushToSettings()` 全量写入 17 个 JSON 字段（含 `mask/bright/scale/shadeMask` 的 `/ 100.0` 换算）。
