# 构建 / 签名 / 安装 / 调试

## 1. 依赖

| 组件 | 说明 |
|---|---|
| JDK 11+ | 编译参数是 `-source 11 -target 11`。**JDK 17 下不要加 `-bootclasspath`**，会报 `option --boot-class-path not allowed with target 11` |
| `android.jar` | API 34 平台包（Android SDK `platforms/android-34/android.jar`）|
| `aapt` / `zipalign` / `apksigner` | Android SDK build-tools |
| `d8` 或 `r8.jar` | 生成 dex。用 r8.jar 时命令为 `java -cp r8.jar com.android.tools.r8.D8` |

本机（开发机 Ubuntu 容器）的工具链放在 `/root/fullaod/sdk/`：`android.jar`（26 MB）、`r8.jar`（16 MB），未随包分发。

## 2. 两种构建方式

### A. 原始脚本 `build.sh`（路径写死，仅开发机可用）

```bash
bash build.sh
```
它固定使用 `/root/fullaod/sdk/android.jar`、`/root/fullaod/sdk/r8.jar`，输出 `build/FullAOD-v1.0.apk`。

### B. 可移植脚本 `build-portable.sh`（推荐，支持 `res/`）

```bash
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
export D8_JAR=$ANDROID_HOME/build-tools/34.0.0/lib/d8.jar   # 或留空改用 PATH 里的 d8 命令
bash build-portable.sh
```

可选环境变量：`AAPT`、`ZIPALIGN`、`APKSIGNER`、`JAVAC`、`JAVA`、`KEYSTORE`、`KS_ALIAS`、`KS_PASS`。

脚本行为：
1. 编译 `stubs/`（Xposed API 编译桩，**不打包**）；
2. 若存在 `res/` 目录，自动 `aapt ... -S res -J build/gen` 生成 `R.java` 并一起编译；不存在则跳过（保持与旧版一致）；
3. `d8 --release --min-api 34` 生成 `classes.dex`；
4. `aapt` 打包 manifest + assets（+ res），再 `aapt add classes.dex`；
5. `zipalign` + `apksigner` 签名，输出 `build/FullAOD-v1.0.apk`。

## 3. 签名

```
keystore : signing/fullaod.jks
alias    : fullaod
storepass: fullaod
keypass  : fullaod
```

**必须沿用这把密钥**：Android 不允许签名不同的 APK 覆盖安装，换密钥就得先 `pm uninstall com.operit.fullaod`，而卸载会清掉 SharedPreferences（用户参数全丢）。

## 4. 安装

```bash
adb push build/FullAOD-v1.0.apk /data/local/tmp/fullaod.apk
adb shell su -c "pm install -r /data/local/tmp/fullaod.apk"
```

首次安装后需在 **LSPosed 管理器**里给模块勾选作用域 `com.android.systemui`（模块是"仅作用域"模式，default 不生效），然后重启 SystemUI 或重启手机。

## 5. 生效规则（很重要）

| 改动内容 | 生效方式 |
|---|---|
| 只改界面（布局、文案、控件样式） | 重装 APK → 重新打开 App 即可，**不用重启** |
| 改了 `AodController` / `AodModule` / `WallpaperAodView` 等注入侧代码 | 必须重启 SystemUI（`adb shell su -c "killall com.android.systemui"`）或重启手机 |
| 只改参数值 | 3 秒内被 `refreshConfig()` 读到，**下次熄屏生效** |

⚠️ **不要连续多次 killall SystemUI**：本机装有 `Automatic_brick_rescue`（连续 3 次异常重启或卡开机 2 分钟会自动禁用全部 Magisk 模块），且 LSPosed 会弹"崩溃保护"。验证时至少间隔数分钟，或者干脆让用户自己重启手机。

## 6. 调试

```bash
# 模块自身日志（SystemUI 有权限写）
adb shell su -c "tail -f /data/user/0/com.android.systemui/files/fullaod.log"

# LSPosed 框架日志（含模块异常栈）
adb shell su -c "ls -t /data/adb/lspd/log/modules_*.log | head -1"

# 当前生效配置
adb shell settings get secure fullaod_cfg

# 确认模块是否注入成功
adb shell su -c "ps -A -o PID,NAME | grep systemui"
adb shell su -c "dumpsys package com.operit.fullaod | grep lastUpdateTime"   # 与 SystemUI 启动时间对比
```

判读技巧：**APK 的 `lastUpdateTime` 晚于 SystemUI 进程启动时间，说明跑的还是旧代码**，必须重启 SystemUI。

## 7. 回滚 / 救援

| 手段 | 命令 |
|---|---|
| 卸载模块（最彻底） | `adb shell su -c "pm uninstall com.operit.fullaod"` |
| 关闭作用域 | LSPosed 管理器 → 模块 → 取消勾选 SystemUI → 重启 SystemUI |
| 强制重启 | 长按电源 10–15 秒 |
| 模块内救援 | 开机动画阶段三击音量− 可禁用 Xposed 模块 |

模块只注入 SystemUI，最坏结果是 SystemUI 反复重启（表现为"锁屏黑屏/状态栏消失"），**不会 bootloop**，adb 仍然可用。
