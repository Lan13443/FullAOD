# AGENTS.md —— 给 AI 编程助手的交接说明（务必先读）

> 本文优先级**高于**你的通用最佳实践与个人偏好。若你的默认做法与本文冲突，以本文为准；不确定时先提问，不要擅自重构。
> 项目：`com.operit.fullaod` —— 一个**注入 SystemUI 的 LSPosed 模块**，附带一个设置界面。本次任务：**只优化设置界面的外观与交互**。

---

## 0. 一句话边界

> **可以改的只有 UI 层。注入到 SystemUI 的那部分代码是"用真机试错换来的"，改错会导致用户熄屏黑屏/壁纸丢失，请一律视为只读。**

---

## 1. 绝对不能改（NEVER）

### 1.1 身份类（改了就无法覆盖安装或模块不加载）

| 位置 | 内容 | 后果 |
|---|---|---|
| `AndroidManifest.xml` | `package="com.operit.fullaod"` | 变成另一个应用，无法覆盖升级，用户参数丢失 |
| `AndroidManifest.xml` | `android:authorities="com.operit.fullaod.config"` | ContentProvider 冲突或失效 |
| `AndroidManifest.xml` | `meta-data`：`xposedmodule` / `xposeddescription` / `xposedminversion` | LSPosed 不再识别为模块 |
| `assets/xposed_init` | 内容必须是 `com.operit.fullaod.AodModule`（单行） | 模块彻底不加载 |
| `signing/fullaod.jks` | alias=`fullaod`，storepass/keypass=`fullaod` | 换密钥必须卸载重装，`SharedPreferences` 全部丢失 |
| 包名/类名 | `com.operit.fullaod.*` 的包路径与类名 | Xposed 入口按全限定名加载，改包名 = 模块失效 |

### 1.2 配置契约（改了就是"设置没反应"）

| 位置 | 禁止动作 |
|---|---|
| `Prefs.java` | 改任何 `K_*` 常量的**字符串值**；删字段；改 `D_*` 默认值（会影响首启显示和"恢复默认值"）|
| `MainActivity.pushToSettings()` | 改 17 个 JSON 字段名；改类型（bool/int/double）；**丢掉 `/100.0` 换算**；删字段；把 `Settings.Secure.putString` 换成普通存储 |
| `AodController.refreshConfig()` | 改解析逻辑、改读取源、改 3 秒节流 |
| `docs/CONFIG-SCHEMA.md` | 与实际字段不一致而不更新 |

字段清单（必须全量写入，缺一即回落默认值）：
`enabled, mask, bright, shift, night, nightStart, nightEnd, animIn, animOut, scale, shade, shadeMask, aodTimeoutOn, aodTimeoutMin, aodTimeoutMode, nightMode, nightSmart`

### 1.3 注入侧逻辑（一律只读）

- `AodController.java`（1336 行）：scrim 自愈重试、bouncer/PIN 判定、夜间会话状态机、超时退化。**不要"顺手重构"、不要提方法、不要改常量**。里面很多分支是为规避特定 ROM 缺陷而存在（例如系统 `ScrimDrawable` 的绘制矩形是零面积，正因如此才需要自铺暗化层）。
- `AodModule.java`：Xposed 入口与 hook 点。
- `WallpaperAodView.java`：**可以调用它的公开方法**（`setBitmap` / `setMaskAlpha` / `setPixelShift` / `setBlur`），**不要改它的 `onDraw` 语义、alpha clamp(0–0.95)、RenderEffect 逻辑**。
- `ConfigProvider.java`：**它是遗留备用通道**，只返回 12 个字段（缺 `aodTimeout*` / `nightMode` / `nightSmart`）。不要把它改成主通道，也不要把 `refreshConfig()` 改成读 provider —— 否则智能模式会静默失效。

### 1.4 构建方式（本项目没有 Gradle）

```
javac (Java 11)  →  d8 (min-api 34)  →  aapt (manifest+assets[+res])  →  zipalign  →  apksigner
```

因此**禁止**：
- 引入 **AndroidX / Material Components**（`com.google.android.material.*`、`androidx.*`）——没有依赖解析，编不过，或运行时 `NoClassDefFoundError`；
- 改写成 **Kotlin**（没有 kotlinc）；
- 使用 **Compose / ViewBinding / DataBinding / AGP 资源合并**；
- 引入**任何第三方库**或新增权限（尤其 `INTERNET`）；
- 把 `MainActivity extends android.app.Activity` 改成 `AppCompatActivity`（AndroidX 依赖）。

只能使用 **framework API**：`android.widget.*`、`android.app.AlertDialog`、`android.app.TimePickerDialog`、`android.graphics.drawable.GradientDrawable`、`res/drawable` 里的 shape 等。

---

## 2. 可以改（OK）与必须保持的约定

### 2.1 允许

- `MainActivity.java` 的布局结构、控件选型（framework 范围内）、分组、间距、配色、文案；
- 新增 `res/`（`layout/`、`values/`、`drawable/`、`mipmap/`）——**但必须用 `build-portable.sh` 构建**；
- 新增只读展示（如"当前生效配置" JSON 折叠区）、`AlertDialog` 二次确认、控件联动禁用；
- 用 `WallpaperAodView` 做参数实时预览（会自己加载壁纸 Bitmap 时请用 `WallpaperManager`）；
- 新增**前端本地**辅助类（仅 UI 用），放在 `com.operit.fullaod.ui` 之类的新包下，**不要放进注入侧会引用到的类**。

### 2.2 必须保持的行为约定

1. **双写**：每个控件变更都要 `SharedPreferences` + `pushToSettings()` 两步走。只写 Prefs 界面会显示但系统不生效；只写 settings 重开 App 会回退。
2. **立即生效延迟**：`AodController` 每 3 秒轮询一次 settings，所以文案不要写"立即生效"，写"下次熄屏生效"。
3. **不轮询**：若展示"当前生效配置"，只在 `onResume()`/按钮点击时读一次，**不要开 Handler 定时器**（用户会归咎于耗电）。
4. **新增设置项要一次改 4 处**：`Prefs`（key+default+reset）→ `MainActivity`（控件+pushToSettings）→ `AodController.refreshConfig()`（解析+实际生效）→ `docs/CONFIG-SCHEMA.md`。少一处就是无效功能。
5. **默认值谨慎**：`D_*` 影响首启显示与"恢复默认值"，当前设备线上配置是 `nightMode=1`、`nightSmart=true`，而代码默认是 `0`/`false` —— 这个不一致是**已知缺陷**，建议按 `docs/UI-STRUCTURE.md` 第 4 节做"首启从 settings 导入"来修，**而不是**直接把默认值改掉。

---

## 3. 已知陷阱（照抄症状，避免重复踩坑）

| 症状 | 原因 | 处理 |
|---|---|---|
| 拖了滑块，系统行为不变 | `pushToSettings()` 字段名/类型写错，或缺字段 | 对照 `docs/CONFIG-SCHEMA.md` |
| 亮度拖到 200 结果变成 2.0% | 忘了 `/100.0` 换算 | `mask/bright/scale/shadeMask` 都要除 100 |
| 打开 App 时智能模式/夜间方式显示错误 | Prefs 为空，显示的是代码默认值 | 做首启导入（UI-STRUCTURE 第 4 节） |
| 用户没碰开关，功能却突然失效 | 用户碰了任意控件 → 全量覆盖写 JSON | 同上，先修首启导入 |
| `Resources.NotFoundException` | 用了 `res/` 却用 `build.sh` 打包（它不带 `-S res`）| 改用 `build-portable.sh` |
| `NoClassDefFoundError: androidx...` / `com.google.android.material...` | 引入了 AndroidX/Material | 回退到 framework 控件 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 必须用 `signing/fullaod.jks` |
| 熄屏后黑屏 / 二次熄屏没有壁纸 / 通知栏变透明 | 改动了 `AodController` 的 scrim 或状态机 | **立即回滚该文件**，不要试图"修好它" |
| 夜间智能模式不再结束 | 把配置源改成 `ConfigProvider`（只有 12 字段） | 回滚，保持 `Settings.Secure` 为主通道 |
| 时钟又出现在 PIN 键盘上方 | 改了 bouncer 时的时钟隐藏逻辑 | 回滚 `AodController` |

---

## 4. 构建与自测

```bash
# 构建（不依赖 Gradle）
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
export D8_JAR=$ANDROID_HOME/build-tools/34.0.0/lib/d8.jar   # 或 PATH 里有 d8 时留空
bash build-portable.sh          # 产出 build/FullAOD-v1.0.apk
```

自测清单：

- [ ] 构建输出 `BUILD_OK`，APK 大小在 30–60 KB 区间（异常大说明混进了依赖）
- [ ] 覆盖安装成功（签名一致）
- [ ] 打开 App 不闪退；每个控件拖一遍不崩
- [ ] 改完任意参数后：`adb shell settings get secure fullaod_cfg` 输出 **17 个字段齐全**
- [ ] 关掉 App 重开，控件回显与刚才设置一致
- [ ] **只改 UI 时不需要重启 SystemUI**；如果你动了注入侧代码，必须提示用户重启 SystemUI（且不要连续多次 `killall`，本机有救砖模块会因连续异常重启禁用全部 Magisk 模块）

---

## 5. 交付要求

1. 不新增文件类型噪音（不生成 `*.iml`、`local.properties`、Gradle 文件）。
2. 不改 `dist/` 里的历史 APK；新产物放 `build/`。
3. 如果你修改了字段、默认值或新增设置项，**同步更新** `docs/CONFIG-SCHEMA.md` 与 `README.md` 的字段说明。
4. 提交信息请按"UI: xxx"标注，明确"未改动注入侧逻辑"。
5. 遇到本文未覆盖的决策（是否引入依赖、是否新增权限、是否改架构），**先提问**，不要自行决定。