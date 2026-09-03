#!/usr/bin/env bash
# WSL2 Docker의 Confluence 7.8.1(UPM)에 jar를 업로드한다. atlas-run은 쓰지 않는다.
#
#   CONFLUENCE_USER=bskim CONFLUENCE_PASS='...' ./deploy.sh
#
set -euo pipefail

cd "$(dirname "$0")"

BASE="${CONFLUENCE_BASE_URL:-http://confluence:18090}"
USER="${CONFLUENCE_USER:-bskim}"
PASS="${CONFLUENCE_PASS:?CONFLUENCE_PASS 환경변수에 관리자 비밀번호를 넣어야 한다}"
KEY="co.bskim.confluence.code-quality"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
/opt/atlassian-plugin-sdk/bin/atlas-mvn -B -q clean package -DskipTests

JAR="$(ls -t target/*.jar | grep -v '\-tests' | head -1)"
echo "업로드 대상: $JAR → $BASE"

# UPM은 업로드 요청마다 일회성 토큰을 요구한다.
TOKEN="$(curl -s -u "$USER:$PASS" -I "$BASE/rest/plugins/1.0/?os_authType=basic" \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="upm-token"{print $2}')"

if [ -z "$TOKEN" ]; then
    echo "UPM 토큰을 못 받았다. 계정/비밀번호 또는 $BASE 접근을 확인할 것." >&2
    exit 1
fi

curl -s -u "$USER:$PASS" \
    -H "Accept: application/json" \
    -H "X-Atlassian-Token: no-check" \
    -F "plugin=@$JAR" \
    "$BASE/rest/plugins/1.0/?token=$TOKEN" > /tmp/cq-upm-upload.json

echo "업로드 요청 전송 완료. 설치 진행 상태:"
for _ in $(seq 1 40); do
    sleep 3
    STATUS="$(curl -s -u "$USER:$PASS" "$BASE/rest/plugins/1.0/$KEY-key" 2>/dev/null || true)"
    if echo "$STATUS" | grep -q '"enabled":true'; then
        echo "설치·활성화 완료"
        echo
        echo "관리 화면 : $BASE/plugins/servlet/code-quality/admin"
        echo "            (Confluence 관리 → 좌측 '설정' 그룹 → '코드 품질 분석')"
        echo "매크로    : 페이지 편집 중 매크로 삽입에서 'code-quality'"
        exit 0
    fi
    # UPM은 실패 사유를 진행 상태 응답에만 담는다.
    if echo "$STATUS" | grep -q '"enabled":false'; then
        echo "설치는 됐지만 활성화되지 않았다. 아래 상태를 확인할 것:" >&2
        echo "$STATUS" >&2
        exit 1
    fi
done
echo "제한 시간 안에 활성화를 확인하지 못했다. UPM 화면에서 상태를 확인할 것." >&2
echo "업로드 응답: $(cat /tmp/cq-upm-upload.json)" >&2
exit 1
