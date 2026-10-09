# PR 리뷰·병합 루프

현재 head의 독립 리뷰와 필수 CI를 확인한다. Claude 또는 Codex의 토큰이 없으면 사용 가능한 쪽이 작성 세션과 분리된 독립 리뷰를 맡는다. 자기 작성 PR의 자기 판정은 금지한다. 승인된 수정·관련 검증을 마치면 바로 PR을 연다. 작성 중에는 draft, 완료하면 ready로 바꾼다.

- 한 head에 독립 판정은 한 번이다. 반드시 고칠 결함만 `BLOCKED`, 권장은 후속 PR로 넘긴다. head가 바뀌면 새 판정이 필요하다.
- 작성자는 PR 본문에 자신의 세션 ID를 `marker author`로 넣는다. 다른 세션이 코드를 수정하면 그 세션 ID도 추가하며 기존 작성 세션은 지우지 않는다. 공유 GitHub 계정의 login은 세션 구분 기준이 아니다.
- 판정 첫 줄은 `머지 판정: 머지 가능` 또는 `머지 판정: 불가`, 둘째 줄은 `독립 리뷰어: Claude` 또는 `독립 리뷰어: Codex`다. v2 마커의 head·실제 리뷰 도구·작성 세션·리뷰 세션이 일치해야 한다. PR 본문에 없는 작성 세션이나 모든 작성 세션 중 하나와 같은 리뷰 세션은 무효다. 세션 ID는 신원 인증 수단이 아니므로 작성 참여 여부는 독립 리뷰어도 확인한다.
- 세션 마커를 쓰지 않은 기존 PR의 v1 Claude 판정은 호환 유지한다. 기존 Codex 일반 코멘트를 자동으로 승격하지 않는다. Codex 판정을 받으려면 작성자가 실제 작성 세션 ID를 등록한다.
- 문서·시험·생성물만 바뀐 PR의 기존 D141 리뷰 면제는 유지한다. 혼합 PR은 독립 판정이 필요하다.
- 독립 판정 `MERGEABLE`과 필수 CI 초록이 갖춰지면 `gh pr merge --squash --auto --match-head-commit <현재 head>`로 병합한다. 보호가 없는 브랜치도 관측 CI가 하나 이상이며 모두 초록이어야 한다. 관리자 우회·보호 설정 변경은 금지한다.
- main의 실제 실패가 확인되면 복구 PR만 병합한다. `main-red-recovery` 라벨과 본문 `Main RED 복구: <run id>`가 필요하다. 취소·조회 실패는 정지 사유가 아니다. 선행 PR은 본문 첫 줄에 `먼저 병합: #N`을 적고 선행 병합 전 draft로 둔다.

## 명령

work-unit 연결·영향·QA·완료 기록은
[배포본 계약](../../../docs/development/work-unit-enforcement.md)을 따른다.
PR-A의 report 결과와 BOOTSTRAP_NOT_ENFORCED를 enforce 성공으로 해석하지 않는다.
게이트 표면 변경은 docs/tests 면제 대상이 아니며 설치된 host의 재검산과 독립 검토가 필요하다.
verification.results 같은 작성자 주장은 실제 실행 receipt를 대체하지 않는다.
main 병합 전 구조 검사에서 현재 CI의 성공을 기다리지 않는다. 실제 MERGED 후에는
audit/outbox durable 확인이 필요하며 부분 AC·epic·legacy를 자동 종료하지 않는다.
기존 PR 이전에는 manifest 또는 만료·이유·승인자 있는 명시 예외가 필요하다.

추가 명령은 bin/work-queue next|explain|migration-report와
bin/work-complete record|scan|drain|status|attest|export-jira|ingest-receipt다.
기본 drain은 DRY_RUN이다. 실제 META 배포·writer 인증·Jira binding은 이 저장소 변경으로
활성화되지 않으며 별도 확인 없이 권한이나 브랜치 보호를 확대하지 않는다.

```sh
bin/pr-loop marker author --author-session SESSION_ID
bin/pr-loop marker verdict --sha HEAD --verdict MERGEABLE --agent codex --author-session WRITER_ID --reviewer-session REVIEWER_ID
bin/pr-loop status --json [--repo OWNER/REPO --pr N]
bin/pr-loop main-ci --json
bin/pr-loop claim N --repo OWNER/REPO --by NAME
bin/pr-loop release N --repo OWNER/REPO --by NAME
bin/pr-loop-watch
```

watcher는 새 CLI 프로세스에서 리뷰한다. 작성 세션을 resume하지 않는다. `PR_LOOP_REVIEWER=auto`가 기본이며 실행 파일과 조율자가 지정한 토큰 가용성을 따른다. Claude 토큰이 없으면 `PR_LOOP_CLAUDE_AVAILABLE=0`, Codex 토큰이 없으면 `PR_LOOP_CODEX_AVAILABLE=0`으로 watcher 환경을 설정한다. 둘 다 없으면 리뷰를 시작하지 않는다. 가용성은 기본 1이며 인증 파일·비밀값을 읽거나 토큰 확인용 모델 호출을 하지 않는다. 명시적으로 `PR_LOOP_REVIEWER=claude|codex`를 선택할 수도 있다.

`claim`·`release`는 자기 소유자만 사용하며 다른 작업의 잠금을 지우지 않는다. 실패한 리뷰 실행은 로그에 남고 자동으로 같은 head를 재실행하지 않는다. 상태 조회는 매번 최신 GitHub 값을 읽으며 판정·CI·head를 병합 직전 재확인한다.
