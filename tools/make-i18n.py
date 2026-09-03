#!/usr/bin/env python3
"""src/main/resources/code-quality*.properties 생성기.

Confluence(Java 8)는 플러그인 i18n .properties를 ISO-8859-1로 읽는다. 한글을 그대로 넣으면
관리 화면과 리포트에서 전부 깨지므로 \\uXXXX로 이스케이프해야 하는데, 손으로 관리하면
읽을 수가 없다. 그래서 원문은 이 스크립트가 들고 있고 .properties는 생성물이다.

    python3 tools/make-i18n.py
"""

import os

# (key, english, korean)
MESSAGES = [
    # --- macro browser -------------------------------------------------------
    # Confluence looks the macro browser title up as "<plugin-key>.<macro-name>.label", with no
    # descriptor element to override it. Without these keys the browser shows the raw key as
    # the macro's title, so searching for it finds nothing and it is unrecognisable under
    # "Other macros". Parameter labels work the same way.
    ("co.bskim.confluence.code-quality.code-quality.label",
     "Code Quality Insights", "코드 품질 분석"),
    ("co.bskim.confluence.code-quality.code-quality.desc",
     "Lists the registered GitHub repositories and links to their quality reports.",
     "등록된 깃헙 레포지터리 목록과 품질 리포트 링크를 표시한다."),
    ("co.bskim.confluence.code-quality.code-quality.param.repository.label",
     "Repository", "레포지터리"),
    ("co.bskim.confluence.code-quality.code-quality.param.repository.desc",
     "Show only this repository, by display name or id. Empty shows all of them.",
     "이 레포지터리만 표시(표시 이름 또는 id). 비우면 전체."),
    ("co.bskim.confluence.code-quality.code-quality.param.title.label",
     "Heading", "제목"),
    ("co.bskim.confluence.code-quality.code-quality.param.title.desc",
     "Heading shown above the table. Empty uses the default.",
     "표 위에 표시할 제목. 비우면 기본값."),

    # --- plugin descriptor ----------------------------------------------------
    ("cq.macro.description",
     "Registers GitHub repositories and reports AI-era code quality metrics.",
     "깃헙 레포지터리를 등록하고 AI 시대 코드 품질 지표를 리포트한다."),
    ("cq.macro.param.repository",
     "Show only this repository (name or id); empty shows all.",
     "이 레포지터리만 표시(이름 또는 id). 비우면 전체."),
    ("cq.macro.param.title", "Heading shown above the table.", "표 위에 표시할 제목."),
    ("cq.admin.section.label", "Code Quality Insights", "코드 품질 분석"),
    ("cq.admin.title", "Code Quality Insights - Repositories", "코드 품질 분석 - 레포지터리"),
    ("cq.admin.note",
     "Registered repositories are global: one clone and one analysis serves every page that "
     "references them.",
     "등록한 레포지터리는 전역이다. 클론과 분석은 한 번만 돌고, 이를 참조하는 모든 페이지가 "
     "같은 결과를 본다."),
    ("cq.admin.back", "Back to Confluence", "Confluence로 돌아가기"),

    # --- repository table UI --------------------------------------------------
    ("cq.ui.repositories", "Repositories", "Repositories"),
    ("cq.ui.header.name", "Name", "이름"),
    ("cq.ui.header.url", "URL", "URL"),
    ("cq.ui.header.lastSync", "Last synchronized", "마지막 분석"),
    ("cq.ui.header.status", "Status", "상태"),
    ("cq.ui.header.actions", "Actions", "Actions"),
    ("cq.ui.new", "New Repository", "레포지터리 추가"),
    ("cq.ui.edit", "Edit", "수정"),
    ("cq.ui.delete", "Delete", "삭제"),
    ("cq.ui.analyze", "Analyze", "분석"),
    ("cq.ui.report", "Report", "리포트"),
    ("cq.ui.cancel", "Cancel", "취소"),
    ("cq.ui.save", "Save", "저장"),
    ("cq.ui.test", "Test connection", "연결 확인"),
    ("cq.ui.never", "Never", "없음"),
    ("cq.ui.empty", "No repositories registered yet.", "등록된 레포지터리가 없다."),
    ("cq.ui.emptyAdmin",
     "No repositories yet. Add one to get a report.",
     "등록된 레포지터리가 없다. 추가하면 리포트를 뽑을 수 있다."),
    ("cq.ui.confirmDelete",
     "Delete {0}? The clone and every cached metric for it are removed.",
     "{0}을(를) 삭제한다. 클론과 캐시된 지표가 모두 지워진다."),
    ("cq.ui.noReport", "Run an analysis first.", "먼저 분석을 실행해야 한다."),
    ("cq.ui.adminOnly",
     "Only Confluence administrators can register or analyze repositories.",
     "레포지터리 등록과 분석은 Confluence 관리자만 할 수 있다."),
    ("cq.ui.probeOk", "Reachable - {0} branches found.", "연결 성공 - 브랜치 {0}개."),
    ("cq.ui.probePublic",
     "Reachable without credentials - {0} branches. The repository is public, so no token is "
     "needed.",
     "자격증명 없이 연결됨 - 브랜치 {0}개. 공개 레포지터리이므로 토큰이 필요 없다."),
    ("cq.ui.probePublicToken",
     "Reachable without credentials - {0} branches. The repository is public, so the token was "
     "neither used nor checked - any value would pass here.",
     "자격증명 없이 연결됨 - 브랜치 {0}개. 공개 레포지터리라 토큰은 쓰이지도 검증되지도 "
     "않았다. 아무 값이나 통과한다."),
    ("cq.ui.probeAuthed",
     "Authenticated - {0} branches. The repository is private and the token works.",
     "인증 성공 - 브랜치 {0}개. 비공개 레포지터리이고 토큰이 유효하다."),
    ("cq.ui.probeFail", "Cannot reach the repository: {0}", "레포지터리에 연결할 수 없다: {0}"),
    ("cq.ui.probeNetwork", "Could not reach Confluence. Try again.",
     "Confluence에 연결하지 못했다. 다시 시도할 것."),
    # A category, not the remote's own text: that distinguished an open port from a closed one.
    ("cq.ui.probeError.notAuthorized",
     "Not authorized. A private repository needs a valid access token.",
     "권한이 없다. 비공개 레포지터리에는 유효한 액세스 토큰이 필요하다."),
    ("cq.ui.probeError.notFound",
     "No repository there. Check the URL, or the token if it is private.",
     "그 위치에 레포지터리가 없다. URL을 확인하거나, 비공개라면 토큰을 확인할 것."),
    ("cq.ui.probeError.notGitRepository",
     "That location is not a git repository.",
     "그 위치는 git 저장소가 아니다."),
    ("cq.ui.probeError.timeout",
     "The remote did not answer in time.",
     "원격이 제한 시간 안에 응답하지 않았다."),
    ("cq.ui.probeError.tokenUnreadable",
     "The stored access token could not be decrypted. Enter it again.",
     "저장된 액세스 토큰을 복호화할 수 없다. 다시 입력할 것."),
    ("cq.ui.probeError.unreachable",
     "Could not reach the remote. Check the URL and that Confluence can reach that host.",
     "원격에 연결할 수 없다. URL과 Confluence의 해당 호스트 접근 가능 여부를 확인할 것."),
    ("cq.ui.urlCredentialsMoved",
     "The credentials in the URL were moved into the encrypted token field and removed from "
     "the URL.",
     "URL에 포함된 자격증명은 암호화된 토큰 필드로 옮기고 URL에서 제거했다."),
    ("cq.ui.urlError.scheme",
     "Only https:// and ssh:// are accepted. A file:// or bare path would read the server's "
     "own disk.",
     "https:// 와 ssh:// 만 허용한다. file:// 이나 맨 경로는 서버 자신의 디스크를 읽는다."),
    ("cq.ui.urlError.localHost",
     "That host is not a valid repository location.",
     "그 호스트는 유효한 레포지터리 위치가 아니다."),
    ("cq.ui.urlError.malformed", "That is not a valid URL.", "유효한 URL이 아니다."),
    ("cq.ui.urlError.host", "The URL has no host.", "URL에 호스트가 없다."),
    ("cq.ui.urlError.control", "The URL contains an invalid character.",
     "URL에 사용할 수 없는 문자가 있다."),
    ("cq.ui.urlError.tooLong", "That URL is too long.", "URL이 너무 길다."),
    ("cq.ui.urlError.empty", "Enter the repository clone URL.",
     "레포지터리 클론 URL을 입력해야 한다."),
    ("cq.ui.probing", "Checking...", "확인 중..."),
    ("cq.ui.deterministic",
     "Analysis is deterministic and runs entirely inside Confluence - no external service.",
     "분석은 결정론적이고 전부 Confluence 안에서 돈다. 외부 서비스를 쓰지 않는다."),
    ("cq.ui.error", "Something went wrong: {0}", "오류가 발생했다: {0}"),

    ("cq.ui.form.required", "* Required. Everything else is optional.",
     "* 필수 입력. 나머지는 모두 선택이다."),
    ("cq.ui.form.urlRequired", "Enter the repository clone URL.",
     "레포지터리 클론 URL을 입력해야 한다."),
    ("cq.ui.form.title.new", "New repository", "레포지터리 추가"),
    ("cq.ui.form.title.edit", "Edit repository", "레포지터리 수정"),
    ("cq.ui.form.name", "Display name", "표시 이름"),
    ("cq.ui.form.nameHint",
     "Empty derives it from the URL as owner/repo. Only the label on this table and on the "
     "report - it has nothing to do with authentication.",
     "비우면 URL에서 owner/repo 형태로 자동 생성한다. 이 표와 리포트에 쓰는 라벨일 뿐이고 "
     "인증과는 무관하다."),
    ("cq.ui.form.url", "Repository URL", "레포지터리 URL"),
    ("cq.ui.form.urlHint",
     "HTTPS clone URL, for example https://github.com/owner/repo.git",
     "HTTPS 클론 URL. 예: https://github.com/owner/repo.git"),
    ("cq.ui.form.branch", "Branch", "브랜치"),
    ("cq.ui.form.branchHint", "Empty uses the remote default branch.", "비우면 원격 기본 브랜치를 쓴다."),
    ("cq.ui.form.authUser", "User name", "사용자명"),
    ("cq.ui.form.authUserHint",
     "Leave empty for GitHub, which ignores it. Bitbucket app passwords need the real account "
     "name; GitLab may need oauth2. Without a token it does nothing.",
     "GitHub은 이 값을 무시하므로 비워둘 것. Bitbucket app password는 실제 계정명이 필요하고 "
     "GitLab은 oauth2를 쓰는 경우가 있다. 토큰이 없으면 아무 역할도 하지 않는다."),
    ("cq.ui.form.token", "Access token", "액세스 토큰"),
    ("cq.ui.form.tokenHint",
     "Needed for private repositories. Stored encrypted and never returned to the browser.",
     "비공개 레포지터리에 필요하다. 암호화해 저장하고 브라우저로 되돌려주지 않는다."),
    ("cq.ui.form.tokenKeep", "A token is stored. Leave empty to keep it.",
     "저장된 토큰이 있다. 비워두면 그대로 유지한다."),
    ("cq.ui.form.spaces", "Visible in these spaces", "이 스페이스에서 보임"),
    ("cq.ui.form.spacesHint",
     "Whoever can view one of these spaces can see this repository and its report. Reuses "
     "Confluence's own permissions - a personal repository belongs in a personal space, a "
     "team's in the team's.",
     "선택한 스페이스를 볼 수 있는 사람이 이 레포지터리와 리포트를 볼 수 있다. Confluence "
     "기존 권한을 그대로 쓴다 — 개인 레포는 개인 스페이스에, 팀 레포는 팀 스페이스에."),
    ("cq.ui.form.spacesLoading", "Loading spaces...", "스페이스 불러오는 중..."),
    ("cq.ui.form.spacesNone",
     "No spaces available. The repository will be visible to administrators only.",
     "선택할 스페이스가 없다. 이 레포지터리는 관리자만 볼 수 있다."),
    ("cq.ui.form.spacesEmptyConfirm",
     "No space selected. Only Confluence administrators will see this repository and its "
     "report. Save anyway?",
     "스페이스를 선택하지 않았다. 이 레포지터리와 리포트를 Confluence 관리자만 볼 수 있게 "
     "된다. 그대로 저장하겠는가?"),
    ("cq.ui.spacesAdminOnly", "admins only", "관리자만"),
    ("cq.ui.urlError.unknownSpaces",
     "One of those space keys does not exist.",
     "존재하지 않는 스페이스 키가 있다."),
    ("cq.ui.form.excludes", "Extra exclude patterns", "추가 제외 패턴"),
    ("cq.ui.form.excludesHint",
     "One glob per line, on top of the built-in list (node_modules, dist, target, generated "
     "code and minified bundles are already excluded).",
     "한 줄에 glob 하나. 내장 목록에 더해진다(node_modules, dist, target, 생성 코드, "
     "minified 번들은 이미 제외된다)."),
    ("cq.ui.form.advanced", "Advanced - display name, user name, exclusions",
     "고급 — 표시 이름, 사용자명, 제외 패턴"),

    ("cq.ui.status.NEW", "Not analyzed", "미분석"),
    ("cq.ui.status.QUEUED", "Queued", "대기 중"),
    ("cq.ui.status.RUNNING", "Running", "분석 중"),
    ("cq.ui.status.OK", "Up to date", "완료"),
    ("cq.ui.status.FAILED", "Failed", "실패"),
    ("cq.ui.phase.queued", "waiting for a slot", "순서 대기"),
    ("cq.ui.phase.fetch", "cloning or fetching", "클론/페치"),
    ("cq.ui.phase.materialise", "loading cached tree", "캐시된 트리 로드"),
    ("cq.ui.phase.commits", "walking commits", "커밋 분석"),
    ("cq.ui.phase.head", "scanning HEAD", "HEAD 스캔"),
    ("cq.ui.phase.report", "building report", "리포트 생성"),
    ("cq.ui.phase.store", "storing results", "결과 저장"),
    ("cq.ui.phase.done", "done", "완료"),

    # --- report chrome --------------------------------------------------------
    ("cq.report.lang", "en", "ko"),
    ("cq.report.title", "Code Quality Report", "코드 품질 리포트"),
    ("cq.report.subtitle",
     "Metrics that do not have lines of code in the denominator, so growing the codebase "
     "cannot improve the score on its own.",
     "분모가 LOC가 아닌 지표들. 코드가 늘어난다고 점수가 좋아지지 않는다."),
    ("cq.report.verdict", "Summary", "요약"),
    ("cq.report.analysedAt", "Analyzed", "분석 시각"),
    ("cq.report.head", "HEAD", "HEAD"),
    ("cq.report.branch", "Branch", "브랜치"),
    ("cq.report.back", "Back", "돌아가기"),
    ("cq.report.deterministic",
     "Deterministic: no model and no network call takes part in the numbers, so the same "
     "commit always yields the same value.",
     "결정론적: 숫자 계산에 모델도 네트워크 호출도 개입하지 않는다. 같은 커밋은 언제 돌려도 "
     "같은 값이 나온다."),
    ("cq.report.cached", "{0} commits reused from cache", "캐시에서 재사용한 커밋 {0}개"),
    ("cq.report.algo", "algorithm v{0}", "알고리즘 v{0}"),
    ("cq.report.empty.title", "No report yet", "리포트가 없다"),
    ("cq.report.empty.body",
     "Run an analysis for this repository first.",
     "이 레포지터리를 먼저 분석해야 한다."),

    ("cq.fact.commits", "Commits", "커밋"),
    ("cq.fact.span", "Span", "기간"),
    ("cq.fact.loc", "LOC", "LOC"),
    ("cq.fact.files", "Files", "파일"),
    ("cq.fact.authors", "Authors", "작성자"),
    ("cq.fact.identities", "{0} identities", "identity {0}개"),
    ("cq.fact.days", "{0} days", "{0}일"),

    ("cq.section.legacy", "Legacy metrics - what this replaces", "레거시 지표 — 이것을 대체한다"),
    ("cq.legacy.note",
     "All four divide by lines of code or by function count. Read them against the metrics "
     "below: when these stay flat or improve while those move, that is the gap this exists to "
     "close. Deliberately ungraded.",
     "네 지표 모두 분모가 LOC 또는 함수 수다. 아래 지표와 나란히 읽을 것 — 위가 평평하거나 "
     "좋아지는데 아래가 움직인다면 그 간극이 이 플러그인의 존재 이유다. 등급은 일부러 매기지 "
     "않는다."),
    ("cq.legacy.duplicates", "Duplicates", "중복률"),
    ("cq.legacy.duplicates.miss",
     "A ratio: it hides growth whenever the codebase grows as fast as the duplication does.",
     "비율이라, 코드가 중복과 같은 속도로 늘면 증가를 감춘다."),
    ("cq.legacy.complexity", "Cyclomatic complexity", "순환 복잡도"),
    ("cq.legacy.complexity.miss",
     "Per function: blind to the shape where every function is simple but the same logic sits "
     "in several of them.",
     "함수 단위라, 함수 하나하나는 단순한데 같은 로직이 여러 군데 복제된 형태를 못 잡는다."),
    ("cq.legacy.commentDensity", "Comment density", "주석 밀도"),
    ("cq.legacy.commentDensity.miss",
     "Generated code comments heavily, so this rises while maintainability falls.",
     "생성 코드는 주석을 많이 달기 때문에, 유지보수성이 떨어지는 동안 이 값은 오른다."),
    ("cq.legacy.functionLength", "Average function length", "평균 함수 길이"),
    ("cq.legacy.functionLength.miss",
     "More, shorter functions scores better and means nothing if they are not wired together.",
     "함수가 짧고 많아지면 점수는 좋아지지만, 서로 엮이지 않으면 의미가 없다."),

    ("cq.unit.perFunction", "/function", "/함수"),
    ("cq.dir.improving", "improving", "개선 중"),
    ("cq.dir.worsening", "worsening", "악화 중"),
    ("cq.dir.flat", "no change", "변화 없음"),
    ("cq.dir.unknown", "unknown", "알 수 없음"),
    ("cq.label.level", "Level", "수준"),
    ("cq.label.direction", "Direction", "방향"),
    ("cq.label.noBasis", "No baseline set", "기준 미설정"),
    ("cq.label.levelBasis",
     "Warn at {0}%: the 75th percentile of {2} public {3} repositories. Act at {1}%: the 90th "
     "percentile of all {4} repositories measured, pooled across languages because a "
     "per-language 90th percentile moves by up to 3 points when one repository is dropped, "
     "and the pooled one by 0.3. Same detector and same exclusions throughout.",
     "주의 {0}% — 공개 {3} 레포지터리 {2}개 분포의 p75. 경고 {1}% — 측정한 전체 "
     "{4}개 레포지터리를 언어 구분 없이 합친 분포의 p90이다. 언어별 p90은 레포 하나가 "
     "빠지면 최대 3%p 움직이고 합친 값은 0.3%p 움직여서 그렇게 했다. 탐지기와 제외 "
     "규칙은 전 구간 동일하다."),
    ("cq.label.noBasisNote",
     "This repository is mostly {0}, and no cohort has been measured for it. A threshold "
     "borrowed from another language would be a guess. The number stands on its own; the "
     "direction below does not need a threshold.",
     "이 레포지터리는 주로 {0}이고, 해당 언어의 코호트를 측정한 바가 없다. 다른 언어의 "
     "임계값을 빌려오면 그건 측정이 아니라 추측이다. 수치만 두었고, 아래 방향 판정에는 "
     "임계값이 필요하지 않다."),
    ("cq.label.floorNote",
     "Changes under {0} lines are reported as no change: a percentage over a small base "
     "explodes, and {0} lines is about four clones at this detector's minimum size.",
     "{0}줄 미만의 변화는 변화 없음으로 본다. 작은 기저에서 백분율은 폭발하고, {0}줄은 이 "
     "탐지기의 최소 클론 크기로 약 4건에 해당한다."),
    ("cq.axis.copyPaste", "copy-paste", "복사·붙여넣기"),
    ("cq.axis.level", "level", "수준"),
    ("cq.axis.direction", "trend", "추세"),
    ("cq.axis.errorSwallow", "error handling", "에러 처리"),
    ("cq.axis.connectivity", "connectivity", "연결도"),
    ("cq.axis.busFactor", "ownership", "소유권"),
    ("cq.axis.churn", "churn", "churn"),

    ("cq.section.kpi", "Metrics", "지표 현황"),
    ("cq.section.duplication", "Duplication trend", "중복 추세"),
    ("cq.section.mix", "New code composition", "신규 코드 구성"),
    ("cq.section.churn", "Two-week churn", "2주 churn"),
    ("cq.section.findings", "What to look at", "확인 필요"),
    ("cq.section.clones", "Duplicated blocks", "중복 블록"),
    ("cq.section.authors", "Ownership", "소유권"),
    ("cq.section.caveats", "How to read this", "읽을 때 주의할 것"),

    ("cq.grade.duplication", "Duplication", "중복 관리"),
    ("cq.grade.maintainability", "Maintainability", "유지보수성"),
    ("cq.grade.changeSafety", "Change safety", "변경 안전성"),
    ("cq.state.good", "Healthy", "양호"),
    ("cq.state.warn", "Watch", "주의"),
    ("cq.state.crit", "Act", "경고"),
    ("cq.state.unknown", "No data", "데이터 없음"),

    ("cq.kpi.copyPaste", "Copy-paste ratio", "복사·붙여넣기 비율"),
    ("cq.kpi.copyPaste.note",
     "Share of added lines that already existed elsewhere as a block of three or more lines. "
     "Judged per block, not per line: a line-level rule flags every language idiom.",
     "추가된 라인 중 3줄 이상 블록으로 이미 다른 곳에 있던 비율. 라인 단위로 판정하면 언어 "
     "관용구가 전부 걸리기 때문에 블록 단위로 센다."),
    ("cq.kpi.refactor", "Refactoring ratio", "리팩터링 비율"),
    ("cq.kpi.refactor.note",
     "Moved / (moved + copied). Code that left its old home is refactoring; code that now "
     "exists twice is not.",
     "이동 / (이동 + 복사). 원래 자리에서 사라졌으면 리팩터링, 두 곳에 남았으면 아니다."),
    ("cq.kpi.churn", "Two-week churn", "2주 churn"),
    ("cq.kpi.churn.note",
     "Share of added lines rewritten within 14 days. Commits newer than 14 days are excluded, "
     "because their window has not closed.",
     "추가된 라인 중 14일 안에 다시 고쳐진 비율. 14일이 지나지 않은 커밋은 관측 창이 닫히지 "
     "않았으므로 제외한다."),
    ("cq.kpi.duplication", "Duplicated lines", "중복 라인"),
    ("cq.kpi.duplication.note",
     "Absolute count first, ratio second. The ratio hides growth whenever the codebase grows "
     "at the same rate as the duplication.",
     "절대량이 먼저, 비율은 보조. 코드가 중복과 같은 속도로 늘면 비율은 증가를 감춘다."),
    ("cq.kpi.errorSwallow", "Error-swallowing density", "에러 은폐 밀도"),
    ("cq.kpi.errorSwallow.note",
     "Bare handlers, over-broad handlers, empty bodies and log-and-continue, per KLOC.",
     "bare 핸들러, 광범위 핸들러, 빈 본문, 로그만 찍고 넘기는 처리를 KLOC당으로 센다."),
    ("cq.kpi.connectivity", "Function connectivity", "함수 연결도"),
    ("cq.kpi.connectivity.note",
     "Call-shaped tokens per KLOC. An approximation, not a call graph: falling connectivity "
     "suggests new code handling things itself instead of calling what exists.",
     "KLOC당 호출 형태 토큰 수. 콜그래프가 아니라 근사다. 떨어지면 새 코드가 기존 함수를 "
     "부르지 않고 자체 처리하는 쪽으로 기울고 있다는 신호다."),

    ("cq.unit.percent", "%", "%"),
    ("cq.label.importExcluded", "bulk imports excluded", "일괄 반입 제외"),
    ("cq.label.tableView", "Show the numbers", "숫자로 보기"),
    ("cq.label.clones", "{0} clone pairs", "클론 {0}쌍"),
    # The table is capped, so it names both numbers rather than repeating the total over a
    # shorter list.
    ("cq.label.clonesShown", "{0} of {1} clone pairs", "클론 {1}쌍 중 {0}쌍"),
    ("cq.label.why", "Why", "설명"),
    ("cq.label.language", "Language", "언어"),
    # Each language names itself, so the switcher reads correctly whichever one is active.
    ("cq.lang.ko", "한국어", "한국어"),
    ("cq.lang.en", "English", "English"),
    ("cq.label.hideWhy", "Hide", "접기"),
    ("cq.label.findings", "{0} of {1} shown", "{1}건 중 {0}건 표시"),
    ("cq.unit.lines", "lines", "줄"),
    ("cq.unit.perKloc", "/KLOC", "/KLOC"),

    ("cq.chart.dupAbsolute", "Duplicated lines (absolute)", "중복 라인 수 (절대량)"),
    ("cq.chart.dupRatio", "Duplication ratio (%)", "중복 비율 (%)"),
    ("cq.chart.novel", "New", "신규"),
    ("cq.chart.copied", "Copy-paste", "복사·붙여넣기"),
    ("cq.chart.moved", "Moved (refactoring)", "이동 (리팩터링)"),
    ("cq.chart.churnPct", "Churn (%)", "churn (%)"),
    ("cq.chart.censored", "Window still open", "관측 창 미종료"),
    ("cq.chart.commitAxis", "commits, oldest first", "커밋 (오래된 순)"),

    ("cq.clones.header.a", "Block", "블록"),
    ("cq.clones.header.b", "Duplicate of", "중복 대상"),
    ("cq.clones.header.lines", "Lines", "줄"),
    ("cq.clones.empty", "No duplicated blocks found.", "중복 블록이 없다."),
    ("cq.authors.header.name", "Author", "작성자"),
    ("cq.authors.header.commits", "Commits", "커밋"),
    ("cq.authors.header.added", "Added lines", "추가 라인"),
    ("cq.authors.header.identities", "Identities merged", "병합된 identity"),

    ("cq.label.approximate", "approximate", "근사"),
    ("cq.label.censoredNote",
     "Greyed points are still inside the 14-day window.",
     "회색 구간은 아직 14일 관측 창 안이다."),
    ("cq.label.delta", "vs {0}d ago", "{0}일 전 대비"),
    # Shown where a change would be, when there is nothing to compare against. A "0.0%" there
    # reads as "measured, and it did not move".
    ("cq.label.noBaseline", "No comparable baseline", "비교 기준선 없음"),
    # C-5: the tile is graded on the ratio, so the ratio has to be on the tile.
    ("cq.label.dupShare", "{0}% of code at HEAD", "HEAD 코드의 {0}%"),
    # C-10: churn excludes commits whose window has not closed. Saying how many keeps the
    # denominator visible.
    ("cq.label.churnCensored", "{0} commit(s) still inside the window",
     "관측 창 안의 커밋 {0}개 제외"),
    # C-9: a bucket where only part of the commits could be counted.
    ("cq.label.bucketPartial", "{0} of {1} commits counted", "커밋 {1}개 중 {0}개 집계"),
    ("cq.label.window", "{0}-day window", "{0}일 창"),
    ("cq.label.showAll", "Show all", "전체 보기"),
    ("cq.label.showLess", "Show less", "접기"),
    ("cq.label.noData", "Not enough history yet.", "히스토리가 아직 부족하다."),

    # --- findings -------------------------------------------------------------
    ("cq.finding.crossFileClone.title",
     "The same block lives in {0} and {2}",
     "같은 블록이 {0}와 {2}에 함께 있다"),
    ("cq.finding.crossFileClone.body",
     "{5} cross-file clone group(s); the largest is {4} lines, at {0}:{1} and {2}:{3}. Fixing "
     "one side leaves the other behind - this is the shape that produces "
     "\"we already fixed that\" bugs.",
     "파일 간 클론 {5}건. 가장 큰 것은 {4}줄로 {0}:{1}과 {2}:{3}이다. 한쪽만 고치면 다른 "
     "쪽이 남는다. \"그거 이미 고쳤는데\" 류의 버그가 여기서 나온다."),
    ("cq.finding.cloneConcentration.title",
     "{2}% of all duplication sits in {0}",
     "전체 중복의 {2}%가 {0} 한 파일에 있다"),
    ("cq.finding.cloneConcentration.body",
     "{1} of {3} duplicated lines are in this one file. Concentrated duplication is usually "
     "one construct copied per variant, which is cheaper to fix than scattered duplication.",
     "중복 {3}줄 중 {1}줄이 이 파일 하나에 있다. 한 파일에 몰린 중복은 보통 한 구조를 "
     "변형마다 복제한 형태여서, 흩어진 중복보다 고치기 쉽다."),
    ("cq.finding.errorHandling.title",
     "Error handling: {4} suppressing handlers per KLOC",
     "에러 처리: KLOC당 은폐 핸들러 {4}건"),
    ("cq.finding.errorHandling.body",
     "Bare handlers {0}, over-broad {1}, empty or log-only bodies {2}, explicit suppression "
     "{3}. This is the axis that usually degrades in generated code, so the number matters "
     "more as a direction than as a level.",
     "bare {0}건, 광범위 {1}건, 빈 본문 또는 로그만 {2}건, 명시적 suppress {3}건. 생성 "
     "코드에서 흔히 나빠지는 축이라 절대값보다 방향이 중요하다."),
    ("cq.finding.connectivityDrift.title",
     "Function connectivity moved {2}% over {3} days",
     "함수 연결도가 {3}일간 {2}% 변했다"),
    ("cq.finding.connectivityDrift.body",
     "{0} to {1} calls per KLOC. Falling connectivity means new code is handling things "
     "itself rather than calling what already exists. This is an approximation over "
     "call-shaped tokens, not resolved symbols, so treat a small move as noise.",
     "KLOC당 호출 {0} → {1}. 떨어지면 새 코드가 기존 함수를 부르지 않고 자체 처리한다는 "
     "뜻이다. 심볼 해석이 아니라 호출 형태 토큰 기반 근사이므로 작은 변동은 노이즈로 볼 것."),
    ("cq.finding.busFactor.title",
     "Bus factor {0}, across {1} author(s)",
     "bus factor {0}, 작성자 {1}명"),
    ("cq.finding.busFactor.body",
     "{3} accounts for {4} commits. Git reported {2} raw identities for {1} people, so "
     "identity merging is already doing work here - without it ownership and bus factor would "
     "both be wrong.",
     "{3}이(가) {4}개 커밋을 썼다. git은 {1}명을 identity {2}개로 보고했다. identity 병합이 "
     "이미 개입하고 있다는 뜻이고, 병합이 없으면 소유권과 bus factor가 둘 다 틀린다."),
    # Emitted when git reported exactly as many identities as authors: the merged-identity
    # sentence would be a claim about work that did not happen.
    ("cq.finding.busFactorClean.title",
     "Bus factor {0}, across {1} author(s)",
     "bus factor {0}, 작성자 {1}명"),
    ("cq.finding.busFactorClean.body",
     "{3} accounts for {4} commits. Every author resolved to a single git identity, so this "
     "count is what the history says without any merging applied.",
     "{3}이(가) {4}개 커밋을 썼다. 작성자마다 git identity가 하나씩이라 병합 없이 나온 "
     "수치 그대로다."),
    ("cq.finding.churnSpike.title",
     "{0} was {2}% rewritten within two weeks",
     "{0}이(가) 2주 안에 {2}% 다시 쓰였다"),
    ("cq.finding.churnSpike.body",
     "\"{1}\" - {3} of {4} added lines were rewritten, against a repository average of {5}%. "
     "Shipping and immediately reworking is the pattern churn is built to catch, and it "
     "catches it without reading the commit message.",
     "\"{1}\" - 추가한 {4}줄 중 {3}줄이 다시 쓰였다. 레포 평균은 {5}%다. 내보내자마자 손보는 "
     "전형적인 패턴이고, churn 지표는 커밋 메시지를 읽지 않고 이걸 짚어낸다."),
    ("cq.finding.identitySuspects.title",
     "{0} and {1} may be the same person",
     "{0}과 {1}이 같은 사람일 수 있다"),
    ("cq.finding.identitySuspects.body",
     "{2} pair(s) of git identities share a name or an email prefix but were counted "
     "separately, because a prefix rule that merges them also merges two genuinely different "
     "people - and a wrongly merged author corrupts ownership exactly as badly as a wrongly "
     "split one. Add a .mailmap to the repository and the next run will fold them; until then "
     "ownership and bus factor are counted per identity.",
     "이름이나 이메일 접두사가 겹치는 git identity가 {2}쌍 있지만 별개로 셌다. 이들을 자동 "
     "병합하는 접두사 규칙은 실제로 다른 두 사람도 병합해버리고, 잘못 병합된 작성자는 잘못 "
     "분리된 작성자만큼 소유권을 망친다. 레포에 .mailmap을 추가하면 다음 분석에서 합쳐진다. "
     "그때까지 소유권과 bus factor는 identity 단위로 센다."),
    ("cq.finding.copyPasteHigh.title",
     "Copy-paste ratio is {0}%",
     "복사·붙여넣기 비율이 {0}%다"),
    ("cq.finding.copyPasteHigh.body",
     "{1} of {2} added lines arrived as blocks that already existed. The refactoring ratio is "
     "{3}%, so the code is being duplicated rather than moved.",
     "추가된 {2}줄 중 {1}줄이 이미 존재하던 블록이다. 리팩터링 비율은 {3}%로, 코드가 "
     "이동하는 게 아니라 복제되고 있다."),

    # --- caveats --------------------------------------------------------------
    ("cq.caveat.mirrorTrees.title",
     "{0} mirrored subtree(s) left out of the duplication measurement",
     "중복 측정에서 제외한 미러 서브트리 {0}개"),
    ("cq.caveat.mirrorTrees.body",
     "{2} - {1} lines held at the same relative paths as another subtree, and matching after "
     "normalisation. Measured whether the repository keeps a second copy of a library for "
     "another platform, vendors a dependency, or clones a package per build target: the ratio "
     "would otherwise be a statement about the layout rather than the code. The ratio is over "
     "the remaining {3} lines, and the copy-paste ratio still counts a line added to both "
     "copies, because that is work done twice.",
     "{2} — 다른 서브트리와 같은 상대경로에 있고 정규화 후 내용이 일치하는 {1}줄이다. 다른 "
     "플랫폼용 사본, 벤더링된 의존성, 빌드 타깃별 복제 중 무엇이든 같은 형태로 잡힌다. "
     "그대로 재면 비율이 코드가 아니라 디렉터리 구조에 대한 진술이 된다. 비율은 남은 "
     "{3}줄 기준이고, 양쪽에 같이 추가한 라인은 복사·붙여넣기 비율에 그대로 남는다 — "
     "두 번 한 일이기 때문이다."),
    ("cq.caveat.historyTruncated.title",
     "Only the newest {0} commits were analysed",
     "최근 커밋 {0}개만 분석했다"),
    ("cq.caveat.historyTruncated.body",
     "This history is longer than one run will walk, so every figure above - the span, the "
     "commit count, the authors, the trend - describes that window and not the whole "
     "repository. The limit bounds the walk, the memory one run holds and the rows one "
     "transaction writes; the numbers inside the window are computed the same way as always.",
     "이 히스토리는 한 번의 분석이 훑는 길이보다 길다. 따라서 위의 모든 수치 — 기간, 커밋 "
     "수, 작성자, 추세 — 는 레포지터리 전체가 아니라 그 구간에 대한 것이다. 이 상한은 "
     "탐색 범위와 한 번의 실행이 점유하는 메모리, 한 트랜잭션이 쓰는 행 수를 묶는다. "
     "구간 안의 수치 계산 방식은 평소와 같다."),
    ("cq.caveat.rightCensoring.title", "Right-censoring", "오른쪽 절단(right-censoring)"),
    ("cq.caveat.rightCensoring.body",
     "{0} commit(s) are newer than {1} days, so their churn cannot be measured to the end. "
     "Those points are greyed out and excluded from the average: 0% there means \"not known "
     "yet\", not \"clean\". Without this, the newest commits would always look healthiest.",
     "{0}개 커밋이 {1}일보다 최근이라 churn을 끝까지 측정할 수 없다. 해당 구간은 회색으로 "
     "표시하고 평균에서 제외했다. 여기서 0%는 \"좋다\"가 아니라 \"아직 모른다\"다. 이 처리를 "
     "빼면 최신 커밋이 항상 가장 건강해 보인다."),
    ("cq.caveat.importExcluded.title", "Bulk imports excluded", "일괄 반입 커밋 제외"),
    ("cq.caveat.importExcluded.body",
     "{0} commit(s) totalling {1} lines were detected as bulk imports and left out of every "
     "ratio. Including a single commit that lands a whole codebase makes every ratio "
     "meaningless.",
     "일괄 반입으로 판정된 커밋 {0}개, {1}줄을 모든 비율에서 제외했다. 코드베이스가 한 번에 "
     "들어온 커밋을 포함하면 모든 비율이 무의미해진다."),
    ("cq.caveat.sampleSize.title", "Sample size", "표본 크기"),
    ("cq.caveat.sampleSize.body",
     "{0} commits over {1} days by {2} author(s). Read the direction, not the slope - and "
     "compare against your own repositories rather than an industry figure.",
     "{2}명이 {1}일간 {0}개 커밋. 기울기가 아니라 방향으로 읽을 것. 업계 수치보다 사내 다른 "
     "레포와 비교하는 게 맞다."),
    ("cq.caveat.approximation.title", "What is approximate", "근사인 항목"),
    ("cq.caveat.approximation.body",
     "Error-swallowing and connectivity come from a line scanner with block tracking, not a "
     "parser: a Confluence plugin runs in the JVM and cannot load a native grammar. "
     "Duplication uses normalised line-window hashing rather than a token-level clone "
     "detector. Copy-paste, refactoring and churn are exact given the normalisation rules.",
     "에러 은폐와 연결도는 파서가 아니라 블록 추적이 붙은 라인 스캐너로 뽑는다. Confluence "
     "플러그인은 JVM 안에서 돌아 네이티브 문법을 못 싣는다. 중복은 토큰 단위 클론 탐지기가 "
     "아니라 정규화 라인 윈도우 해싱을 쓴다. 복사·붙여넣기, 리팩터링, churn은 정규화 규칙 "
     "안에서 정확하다."),
    ("cq.caveat.aiAttribution.title", "AI contribution is not measured",
     "AI 기여도는 측정하지 않는다"),
    ("cq.caveat.aiAttribution.body",
     "There is no reliable way to tell from a diff which lines a model wrote. Commit-size and "
     "typing-speed heuristics are not trustworthy. This only becomes measurable through a "
     "commit trailer convention, which is a process decision rather than a tooling one - and "
     "the share of AI-written code is not a quality metric in either direction.",
     "diff만 보고 어느 라인을 모델이 썼는지 가릴 방법은 없다. 커밋 크기나 입력 속도 "
     "휴리스틱은 신뢰할 수 없다. 커밋 트레일러 규약으로만 측정 가능해지는데 그건 도구가 아니라 "
     "프로세스 문제다. 그리고 AI 작성 비율 자체는 높든 낮든 품질 지표가 아니다."),
    ("cq.caveat.firstParent.title", "History is read along first parents",
     "히스토리는 first-parent로 읽는다"),
    ("cq.caveat.firstParent.body",
     "A merge commit is analyzed as one change against its branch point, so in a "
     "pull-request workflow a whole PR is attributed to its merge. This keeps the tree state "
     "linear, which is what makes incremental analysis possible.",
     "머지 커밋은 분기점 대비 한 번의 변경으로 분석한다. PR 워크플로에서는 PR 전체가 머지 "
     "커밋에 귀속된다. 트리 상태를 선형으로 유지해야 증분 분석이 가능하기 때문이다."),
]


def emit(path, index, header):
    lines = [header, ""]
    for entry in MESSAGES:
        value = entry[index]
        lines.append("{0}={1}".format(entry[0], escape(value)))
    with open(path, "w", encoding="ascii") as handle:
        handle.write("\n".join(lines) + "\n")
    print("wrote {0} ({1} keys)".format(path, len(MESSAGES)))


def escape(value):
    out = []
    for char in value:
        if ord(char) < 128:
            out.append(char)
        else:
            out.append("\\u{0:04x}".format(ord(char)))
    return "".join(out)


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "src", "main", "resources")
    emit(os.path.join(root, "code-quality.properties"), 1,
         "# code-quality i18n (English, default locale)\n"
         "# Generated by tools/make-i18n.py - edit that file, not this one.")
    emit(os.path.join(root, "code-quality_ko_KR.properties"), 2,
         "# code-quality i18n (Korean)\n"
         "# Confluence reads plugin properties as ISO-8859-1, so non-ASCII is \\uXXXX escaped.\n"
         "# Generated by tools/make-i18n.py - edit that file, not this one.")


if __name__ == "__main__":
    main()
