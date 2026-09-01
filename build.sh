#!/bin/bash
set -e

export JAVA_HOME="${JAVA_HOME:-/Users/swifly/.sdkman/candidates/java/current}"
export ANDROID_HOME="${ANDROID_HOME:-/Users/swifly/Library/Android/sdk}"

echo "=== 清理残留 Gradle 文件 ==="
rm -f ~/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0-all.zip.lck
rm -f ~/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0-all.zip.part 2>/dev/null || true

echo "=== 执行构建 ==="
./gradlew publishToMavenLocal \
  -x test \
  -x lint \
  -x lintVitalRelease \
  --no-build-cache \
  --no-daemon \
  "$@"

echo "=== 构建完成 ==="
