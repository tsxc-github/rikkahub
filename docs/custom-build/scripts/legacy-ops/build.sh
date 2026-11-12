#!/bin/bash
# RikkaHub 构建入口（coding CT 115）
set -eu
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/mnt/coding/rikkahub-build/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/mnt/coding/rikkahub-build/gradle-home
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
cd /mnt/coding/rikkahub-build/src/rikkahub
exec ./gradlew "${@:-:app:assembleDebug}" --console=plain
