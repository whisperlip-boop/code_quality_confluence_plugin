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
# 같은 버전을 다시 설치하면 웹 리소스 URL이 그대로여서 브라우저가 이전 빌드의 JS를 계속 쓴다.
# 매크로 브라우저 미리보기 프레임은 페이지 로드 후 스크립트가 만들기 때문에 강제 새로 고침으로도
# 지워지지 않는다. 그래서 배포마다 버전에 빌드 시각을 붙여 URL을 바꾼다.
QUALIFIER=".$(date -u +%Y%m%d%H%M%S)"
/opt/atlassian-plugin-sdk/bin/atlas-mvn -B -q clean package -DskipTests \
    "-Dcq.build.qualifier=$QUALIFIER"

JAR="$(ls -t target/*.jar | grep -v '\-tests' | head -1)"
echo "업로드 대상: $JAR → $BASE"

# 비밀번호를 -u로 넘기면 ps에 그대로 보인다. curl의 --config는 표준입력으로 받으므로
# 커맨드라인에 남지 않는다. -f를 붙여 403/500을 성공과 구분한다.
cq_curl() {
    printf 'user = "%s:%s"\n' "$USER" "$PASS" | curl -s -f --config - "$@"
}

# UPM은 업로드 요청마다 일회성 토큰을 요구한다.
# 실패를 여기서 삼키는 이유: set -e가 아래 안내 문구 전에 스크립트를 죽이면 사용자는
# 아무 설명 없는 종료만 보게 된다. 판정은 TOKEN이 비었는지로 한다.
HEADERS="$(cq_curl -I "$BASE/rest/plugins/1.0/?os_authType=basic" 2>/dev/null || true)"
TOKEN="$(printf '%s' "$HEADERS" \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="upm-token"{print $2}')"

if [ -z "$TOKEN" ]; then
    echo "UPM 토큰을 못 받았다. 계정/비밀번호 또는 $BASE 접근을 확인할 것." >&2
    exit 1
fi

if ! cq_curl \
    -H "Accept: application/json" \
    -H "X-Atlassian-Token: no-check" \
    -F "plugin=@$JAR" \
    "$BASE/rest/plugins/1.0/?token=$TOKEN" > /tmp/cq-upm-upload.json
then
    echo "업로드 요청이 거부됐다. 응답: $(cat /tmp/cq-upm-upload.json)" >&2
    exit 1
fi

echo "업로드 요청 전송 완료. 설치 진행 상태:"
for _ in $(seq 1 40); do
    sleep 3
    STATUS="$(cq_curl "$BASE/rest/plugins/1.0/$KEY-key" 2>/dev/null || true)"
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
