#!/usr/bin/env bash
# 一次性：解压 JDK/Gradle/Android SDK，安装 SDK 组件，构建 APK
# 可用环境变量覆盖：BUILD_DIR（放 JDK/Gradle 的目录）、ANDROID_SDK_ROOT
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"        # <repo>/android
BUILD="${BUILD_DIR:-$HOME/android-build}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android}"

JDK_DIR="${JAVA_HOME:-}"
if [ -z "$JDK_DIR" ]; then
  JDK_DIR=$(ls -d "$BUILD"/zulu* 2>/dev/null | head -1)
fi
[ -n "$JDK_DIR" ] || { echo "找不到 JDK：设 JAVA_HOME，或把 zulu* 放到 $BUILD"; exit 1; }

echo "==> JDK: $JDK_DIR"
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "==> 解压 Android cmdline-tools"
mkdir -p "$SDK/cmdline-tools"
unzip -q -o "$BUILD/cmdtools.zip" -d "$SDK/cmdline-tools"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

echo "==> 接受许可 + 安装 SDK 组件"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "==> 构建 APK"
cd "$ROOT"
echo "sdk.dir=$SDK" > local.properties
"$BUILD/gradle-8.7/bin/gradle" assembleDebug --no-daemon --stacktrace
echo "==> 产物"
ls -la app/build/outputs/apk/debug/
