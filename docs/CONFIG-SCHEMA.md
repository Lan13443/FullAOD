# 配置字段契约（改前端必须遵守）

设置界面写入 `Settings.Secure` 的键名是 **`fullaod_cfg`**，值是一个 JSON 字符串。
SystemUI 内的 `AodController.refreshConfig()` 每 **3 秒**读一次，解析失败会保留上一次的值。

⚠️ 这是**前后端唯一契约**：字段名、类型、单位变更都会让后端静默回落默认值（表现为"改了没反应"）。

## 1. 字段表（共 22 个，缺一不可）

| JSON 字段 | 类型 | 取值 | 默认 | 含义 / 备注 |
|---|---|---|---|---|
| `enabled` | bool | true/false | `true` | 全屏 AOD 总开关。false 时完全不干预系统 AOD |
| `mask` | double | 0 – 0.95 | `0.65` | 壁纸暗化程度（界面显示 65%） |
| `bright` | double | 1.0 – 3.0 | `2.0` | AOD 亮度倍数，界面显示 200% |
| `shift` | bool | true/false | `true` | 像素位移防烧屏（每 90 秒 ±3px） |
| `night` | bool | true/false | `true` | 夜间退化总开关 |
| `nightStart` | int | 0 – 23 | `23` | 夜间开始整点。**当 `nightStart == nightEnd` 时代码视为不启用夜间** |
| `nightEnd` | int | 0 – 23 | `7` | 夜间结束整点（支持跨零点） |
| `animIn` | int | 150 – 1500 | `550` | 熄屏淡入+放大时长 ms |
| `animOut` | int | 100 – 1000 | `350` | 亮屏淡出时长 ms |
| `scale` | double | 1.00 – 1.10 | `1.03` | AOD 壁纸相对倍率（`AodController` 会叠加系统 `config_wallpaperMaxScale`，例如 K60 为 `1.1`，故 `scale=1.0` 时与锁屏静态 `1.1x` 对齐；`WallpaperAodView` 水平裁切按 LTR `0` / RTL `bw - cropW` 与桌面/锁屏起始框选对齐） |
| `shade` | bool | true/false | `false` | 锁屏/通知栏透出壁纸（**当前线上永久关闭，勿在 UI 上默认打开**） |
| `shadeMask` | double | 0 – 0.95 | `0.80` | 通知栏遮罩浓度，仅 `shade=true` 生效 |
| `aodTimeoutOn` | bool | true/false | `false` | 长期亮屏自动退化总开关 |
| `aodTimeoutMin` | int | 5 – 180 | `30` | AOD 持续多少分钟后开始退化 |
| `aodTimeoutMode` | int | 0 / 1 | `0` | 0=退化到仅时钟；1=完全关闭 AOD（走 `DozeMachine.requestState(DOZE_AOD_PAUSED)`，失败自动回落仅时钟） |
| `nightMode` | int | 0 / 1 | `0` | 夜间退化方式：0=仅时钟；1=完全关闭 AOD。**设备线上值为 1** |
| `nightSmart` | bool | true/false | `false` | 智能模式：夜间退化不按 `nightEnd` 结束，而是"次日 05:00 之后第一次亮屏并解锁"时结束。**设备线上值为 true** |
| `flipOff` | bool | true/false | `true` | 反扣熄屏：手机屏幕朝下水平放置时自动关闭所有 AOD（含时钟与全屏壁纸），翻转朝上时自动恢复显示 |
| `liftWake` | bool | true/false | `true` | 拿起自动亮屏：熄屏或 AOD 状态下通过姿态传感器识别拿起并抬起手机动作，自动唤醒并点亮锁屏 |
| `autoBright` | bool | true/false | `true` | 环境光自动亮度：开启后按光线传感器（`TYPE_LIGHT`）动态映射硬件 Doze 亮度并联动壁纸遮罩；仅全屏壁纸 AOD 期间生效，置灰手动 `bright` |
| `autoBrightMin` | int | 1 – 50 | `4` | 自动亮度最低硬件下限（百分比 `1%–50%`，对应约 `3–128 / 255`） |
| `autoBrightMax` | int | 20 – 100 | `75` | 自动亮度最高硬件上限（百分比 `20%–100%`，自动保证不低于 `autoBrightMin`） |

## 2. 单位换算（前端最容易出错的地方）

Prefs 里存的是**整数**，JSON 里存**小数**，比例是 100：

| Prefs key | Prefs 值示例 | JSON 字段 | JSON 值 |
|---|---|---|---|
| `mask` | 65 | `mask` | 0.65 |
| `bright` | 200 | `bright` | 2.0 |
| `scale` | 103 | `scale` | 1.03 |
| `shadeMask` | 80 | `shadeMask` | 0.8 |

其余字段（`nightStart` / `animIn` / `aodTimeoutMin` / `autoBrightMin` / `autoBrightMax` 等）整数直接写入，不做换算。

## 3. 一份真实可用的线上配置

```
{"enabled":true,"mask":0.65,"bright":2,"shift":true,"night":true,"nightStart":23,"nightEnd":7,
 "animIn":550,"animOut":350,"scale":1.03,"shade":false,"shadeMask":0.8,
 "aodTimeoutOn":false,"aodTimeoutMin":30,"aodTimeoutMode":0,"nightMode":1,"nightSmart":true,
 "flipOff":true,"liftWake":true,"autoBright":true,"autoBrightMin":4,"autoBrightMax":75}
```

命令行复核方式：

```bash
adb shell settings get secure fullaod_cfg
adb shell settings put secure fullaod_cfg '<上面的整行 JSON>'   # 手工回滚用
```

## 4. 智能模式的语义（前端文案别写错）

开启 `nightSmart` 后，夜间退化的结束条件从"到达 `nightEnd`"改为：

> **次日 05:00 之后，第一次「屏幕亮起且已解锁」时结束当日退化。**

- 05:00 之前解锁**不会**结束退化；
- 结束后**当天不再重新进入**（内部用"夜间会话编号"保证，23:00 之后算新会话，会重新开启）；
- 若用户在夜间中途关掉智能模式，退化会立刻恢复按固定时间窗工作。

代码位置：`AodController.updateNightActive()`，日志关键字 `night: active` / `night: ended by first unlock`。
