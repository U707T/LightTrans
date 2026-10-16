#!/usr/bin/env bash
# 轻译 · 离线构建脚本（不需要 Gradle、不需要联网）
# 依赖：Android SDK 的 aapt2/d8/zipalign/apksigner + JDK 的 javac/keytool
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}}"
PLATFORM="${PLATFORM:-android-36}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
OUT="build"

# 优先用 PATH 里的工具（沙盒里它们是 qemu 包装器），否则用 build-tools 里的
pick() { command -v "$1" 2>/dev/null || echo "$BT/$1"; }
AAPT2=$(pick aapt2); D8=$(pick d8); ZIPALIGN=$(pick zipalign); APKSIGNER=$(pick apksigner)

[ -f "$JAR" ] || { echo "找不到 android.jar: $JAR (可用 ANDROID_SDK / PLATFORM 指定)"; exit 1; }

echo "== 1/6 清理 =="
rm -rf "$OUT" dist
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

echo "== 2/6 aapt2 compile + link =="
"$AAPT2" compile --dir app/res -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$JAR" \
  --manifest app/AndroidManifest.xml --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code 21 --version-name 3.4 "$OUT/res.zip"

echo "== 3/6 javac =="
find app/src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  grep -v "bootstrap class path" "$OUT/javac.log" || true
  echo "javac 失败"; exit 1
fi

echo "== 4/6 d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --lib "$JAR" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 5/6 打包 + 对齐 =="
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT/dex" && zip -q -X ../unaligned.apk classes.dex )
"$ZIPALIGN" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "== 6/6 签名 =="
KS="$OUT/debug.keystore"
# CI 里可用 KEYSTORE_B64 注入固定证书（base64），保证每次发布的签名一致
if [ -n "${KEYSTORE_B64:-}" ]; then
  echo "$KEYSTORE_B64" | base64 -d > "$OUT/release.keystore"
  KS="$OUT/release.keystore"
  echo "(使用 KEYSTORE_B64 注入的证书)"
fi
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=LightTrans, OU=Dev, O=LightTrans, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
PASS="${KEYSTORE_PASS:-android}"
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:${KEY_PASS:-$PASS}" \
  --out "dist/轻译.apk" "$OUT/aligned.apk"

echo
"$AAPT2" dump badging "dist/轻译.apk" | grep -E "^(package|minSdk|targetSdk|uses-permission|application-label|launchable)" || true
echo "构建完成 → dist/轻译.apk ($(stat -c%s dist/轻译.apk) 字节)"
