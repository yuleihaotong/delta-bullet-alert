#!/usr/bin/env bash
# 一次性：解压 JDK/Gradle/Android SDK，安装 SDK 组件，构建 APK
set -e
BUILD=~/android-build
SDK=~/Android
JDK_DIR=$(ls -d $BUILD/zulu* 2>/dev/null | head -1)

echo "==> JDK: $JDK_DIR"
export JAVA_HOME=$JDK_DIR
export PATH=$JAVA_HOME/bin:$PATH
java -version

echo "==> 解压 Android cmdline-tools"
mkdir -p $SDK/cmdline-tools
unzip -q -o $BUILD/cmdtools.zip -d $SDK/cmdline-tools
if [ ! -d $SDK/cmdline-tools/latest ]; then
  mv $SDK/cmdline-tools/cmdline-tools $SDK/cmdline-tools/latest
fi
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK

echo "==> 接受许可 + 安装 SDK 组件"
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK --licenses > /dev/null 2>&1 || true
$SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "==> 构建 APK"
cd /home/leiyu/workspace/delta-force-auto/android
echo "sdk.dir=$SDK" > local.properties
$BUILD/gradle-8.7/bin/gradle assembleDebug --no-daemon --stacktrace
echo "==> 产物"
ls -la app/build/outputs/apk/debug/
