#!/usr/bin/env bash
# FullAOD 可移植构建脚本（支持 res/ 资源目录）
#
# 用法：
#   export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
#   export D8_JAR=$ANDROID_HOME/build-tools/34.0.0/lib/d8.jar    # 可选，PATH 里有 d8 时可留空
#   bash build-portable.sh
#
# 可选环境变量：
#   AAPT / ZIPALIGN / APKSIGNER / JAVAC / JAVA
#   KEYSTORE / KS_ALIAS / KS_PASS / KEY_PASS
#
# 输出：build/FullAOD-v1.0.apk
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
APK_NAME="FullAOD-v1.0.apk"

ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-34/android.jar}"
D8_JAR="${D8_JAR:-}"
AAPT="${AAPT:-aapt}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

KEYSTORE="${KEYSTORE:-$ROOT/signing/fullaod.jks}"
KS_ALIAS="${KS_ALIAS:-fullaod}"
KS_PASS="${KS_PASS:-fullaod}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "错误：找不到 android.jar（$ANDROID_JAR）"
  echo "请执行：export ANDROID_JAR=/path/to/platforms/android-34/android.jar"
  exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "错误：找不到签名文件 $KEYSTORE"
  exit 1
fi

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/stubs" "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk" "$OUT/$APK_NAME"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/stubs"

# 1) 编译 Xposed 编译桩（不打包进 APK）
"$JAVAC" -source 11 -target 11 -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" \
    -d "$OUT/stubs" $(find "$ROOT/stubs" -name '*.java') || exit 10
echo STUBS_OK

# 2) 打包资源与清单（若存在 res/ 则生成 R.java）
RES_ARGS=""
if [ -d "$ROOT/res" ]; then
  mkdir -p "$OUT/gen"
  RES_ARGS="-S $ROOT/res -J $OUT/gen"
  echo "检测到 res/，启用资源编译"
fi
"$AAPT" package -f -M "$ROOT/AndroidManifest.xml" -I "$ANDROID_JAR" \
    -A "$ROOT/assets" $RES_ARGS -F "$OUT/app.unsigned.apk" || exit 13
echo AAPT_OK

# 3) 编译 Java（含自动生成的 R.java）
SRC_LIST="$(find "$ROOT/src" -name '*.java')"
for r in $(find "$OUT/gen" -name 'R.java' 2>/dev/null); do
  SRC_LIST="$SRC_LIST $r"
done
"$JAVAC" -source 11 -target 11 -encoding UTF-8 -nowarn \
    -cp "$ANDROID_JAR:$OUT/stubs" -d "$OUT/classes" $SRC_LIST || exit 11
echo JAVAC_OK

# 4) 生成 dex
CLASSES="$(find "$OUT/classes" -name '*.class')"
if [ -n "$D8_JAR" ]; then
  if [ ! -f "$D8_JAR" ]; then
    echo "错误：D8_JAR 不存在（$D8_JAR）"
    exit 12
  fi
  "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 34 \
      --lib "$ANDROID_JAR" --output "$OUT/dex" $CLASSES || exit 12
else
  d8 --release --min-api 34 --lib "$ANDROID_JAR" --output "$OUT/dex" $CLASSES || exit 12
fi
echo D8_OK

# 5) 把 classes.dex 塞进 APK
( cd "$OUT/dex" && "$AAPT" add ../app.unsigned.apk classes.dex ) || exit 14
echo ADD_DEX_OK

# 6) 对齐 + 签名
"$ZIPALIGN" -f 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk" || exit 15
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT/$APK_NAME" "$OUT/app.aligned.apk" || exit 16

ls -l "$OUT/$APK_NAME"
echo BUILD_OK