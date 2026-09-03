#!/usr/bin/env bash
# 빌드 후 jar를 Windows Downloads 폴더로 복사한다(사용자가 UPM에 직접 업로드할 수 있게).
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
DOWNLOADS="/mnt/c/Users/vuno/Downloads"

/opt/atlassian-plugin-sdk/bin/atlas-mvn -B clean package "$@"

JAR="$(ls -t target/*.jar | head -1)"
cp "$JAR" "$DOWNLOADS/"
echo
echo "빌드 완료: $JAR"
echo "복사 완료: $DOWNLOADS/$(basename "$JAR")"
