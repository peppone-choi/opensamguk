# Work-unit 연결·명령 QA·병합 후 완료 기록

PR-A의 기본값은 **report**, GitHub writer는 **DRY_RUN**, Jira binding은
**UNBOUND**다. 저장소 배포본만 구현한 상태는 SOURCE_ONLY/PENDING_DEPLOYMENT다.
저장소 병합만으로 META watcher·Cloud dispatch·tracker 쓰기가 활성화되지 않는다.

## 기존 호출 경로

- META의 start-task는 probe 후 reserve에서 issue/AC/lease를 확인하고 worktree를 만든다.
  연결 없는 기존 작업은 report에서 v1 호환으로 남으며 enforce에서 거절한다.
  연결된 신규 작업은 v2 등록이다. --issue OWNER/REPO#N, --legacy-ac,
  --scope ALL_INPUTS, --dry-run을 지원한다. dry-run은 branch/worktree/BOARD/state를 쓰지 않는다.
- task-lifecycle migrate-v2 PROJECT TASK --issue REF는 활성 v1 작업만 명시적으로
  이전한다. 기존 owner/session/nonce를 다른 작성자에게 넘기지 않는다.
- pr-loop evaluate는 설치된 라이브러리로 현재 head의 이슈·AC·명령 영향·QA·lease와
  필요한 execution check를 재검산한다. report 위반은 workUnit 결과에 표시한다.
  REPORT는 exact head의 naming-lint 완료 뒤 한 번 검증하고 (repo, PR, head)별로 STATE에
  캐시한다. API·archive·timeout·읽기 오류는 UNKNOWN으로 기록하며 기존 PR action을 유지한다.
  ENFORCE는 현재 증거를 재확인하며 부족한 증거에서 병합을 보류한다.
  게이트 표면 변경에는 기존 docs/tests 리뷰 면제를 적용하지 않는다.
- naming-lint의 구조 검사는 현재 CI의 성공을 기다리지 않는다. changes에서 새 레인은
  기존 filter에 OR로 추가한다. 실행 receipt는 기존 XML no-skip 및 원본 browser shard
  검사 이후 기록하고 jvm/web 모음 잡에서 검사한다.
- watcher는 scan과 DRY_RUN drain을 호출한다. lifecycle은 v2의 실제 MERGED 확인 후
  ENFORCE에서 audit·outbox가 durable해야 정리한다. REPORT의 부족한 완료 증거는
  수용 기준 완료나 outbox intent를 만들지 않는 noncompletion 기록으로 남기고 기존
  안전한 worktree 정리와 lease 해제를 진행한다. 기존 v1 cleanup은 scan 실패와 독립적이다.
  auto-merge 요청은 완료 증거가 아니다.

## 작업 데이터

work-units/binding.json은 repository·mode·Jira 검증 상태를 선언한다. 초기값에
실제 Jira instance/project/transition이나 이슈 번호를 만들지 않는다.
작업별 work-units/units/UNIT.json은 manifest v1이며 한 PR에 한 단위다.
schema.py의 manifest 검사가 정본이다. 최종 head/merge/check/review는 commit 전에는
알 수 없으므로 manifest에 넣지 않고 병합 후 audit에 기록한다.
verification.results는 작성자의 주장으로 실행 증거를 대체하지 않는다.

권위 있는 GitHub issue 본문 형식:

    <!-- work-unit-ac v1 -->
    priority: P2
    depends: #123
    inputs: court.reward
    - AC-1: 사용자에게 결과를 표시한다
    - AC-2 [check: gap-closed court.reward UI_READY]: 배달 증거를 갖춘다
    <!-- /work-unit-ac -->

위 이슈 번호는 형식 예시이며 실제 binding이 아니다. priority 생략은 P3다.
CRLF/LF·줄 끝 공백·빈 줄을 정규화한 블록의 sha256을 사용한다.
AC 없는 이슈는 AC_MIGRATION_PENDING이다. 소유자의 명시 --legacy-ac는 전체 본문의
LEGACY-WHOLE 지문을 사용하며 자동 완료 대상이 아니다.

work-queue next --project opensamguk --json은 열린 이슈·종료된 같은 repo 의존·lease를
읽고 회귀 tier, 매 7일 aging priority, 의존 수, 생성 시각, 번호로 정렬한다.
읽기 실패는 UNKNOWN_AUTH다. 연결 없는 공백은 GAP_PENDING_ISSUE로 남는다.
work-queue explain OWNER/REPO#N과 migration-report는 배정·기존 열린 PR 이전 사유를
보여 준다. lease는 단일 호스트 파일 lock이며 전역 원자성을 주장하지 않는다.

## 영향·QA·공백

base의 surface-map으로 변경 전후 경로와 blob을 분류한다. 삭제·rename도 이전 경로를
검사하고 리터럴/상수는 영향에 추가만 한다. 중앙 API/hook/lib/registry/help·제품 기본
소스는 넓게 분류한다. 휘하 input IDs와 동결 삼모 명령 namespace는 구분한다.
UNRESOLVED는 NA가 아니며 무조건 보고/거절한다. NONE의 NA는 도출 이유와 같아야 한다.
manifest·생성 공백·test root·issue-template 경로도 명시적으로 분류한다.

직접 영향의 Kotlin QA는 실제 test 경로·클래스·@Test 함수 내부의 ID/해석 가능한 상수,
UI QA는 실제 e2e test 블록의 ID를 요구한다. 보수적인 정적 하한이며 동적·복잡한 별칭은
수동 근거 없이 통과시키지 않는다. 주석에 ID만 써서는 증거가 되지 않는다.
BROAD에는 모든 해당 레인과 기존 input evidence gate가 필요하다.
PLANNED의 QA 예외는 실제 상태·등록 부재와 함께 PLANNED_NOT_DELIVERED로 기록한다.

command-work-gaps-v1.json은 현재 catalog/baseline에서 재계산한다. PLANNED,
HANDLER_NO_UI, DEBT_MISSING_EVIDENCE 조건이 남으면 항목·trackedBy를 삭제할 수 없다.
카탈로그 삭제·단계 후퇴는 별도 검토 사유다. 현재 원장 97행과 고정 baseline 74행은
다르며 court.reward의 상태/evidence는 이 변경에서 승격하지 않는다.

## 신뢰와 bootstrap

work_unit_gate.py check/lanes는 trusted base의 validator/lib를 임시 디렉터리에 꺼내
Python -I와 stdlib/trusted import 경로만 사용한다. head의 코드는 import/실행하지
않는다. candidate blob은 regular-file mode·크기·경로 검사를 거친 Git 데이터로 읽는다.
symlink/submodule·경로 탈출·과도한 blob은 거절하며 head tree를 파일로 펼치지 않는다.

base에 게이트가 없는 첫 PR은 BOOTSTRAP_NOT_ENFORCED다. head validator를 권위 있게
실행하지 않는다. 현재 main에는 게이트가 있는데 base에 없으면
STALE_BASE_REBASE_REQUIRED다. 이후 gate 변경은 이전 base의 validator로 검사한다.
schema 전환은 먼저 양쪽 schema를 지원하고 다음 PR에서 manifest를 전환한다.
head workflow 자체는 수정 가능하므로 CI만으로 우회 불가를 주장하지 않는다.

## 완료 outbox와 외부 쓰기

work-complete record/scan/drain/status/attest/export-jira/ingest-receipt가 정본 entrypoint다.
META의 PR_LOOP_STATE/work-units 아래 audits/outbox/leases를 보존한다.
record는 merged=true·main 대상·merge SHA의 main 조상 관계·squash 1-parent·H/M manifest
blob 일치·exact H CI/check/실행 receipt·독립 검토·현재 AC·등록 또는 명시 attestation을
요구한다. 필요한 실행 레인의 skipped/neutral/tests0/누락 클래스·spec은 증거가 아니다.
`record --registration PROJECT/TASK`는 STATE/tasks의 해당 등록만 읽는다. 임의 파일 입력은
거절하며 schema·repository·branch·active/prepared 단계·실제 lease/nonce를 확인한다.
등록과 lease는 O_NOFOLLOW로 읽고 symlink 경로를 거절한다. 로컬 STATE는 설치 호스트의
신뢰 경계이며 원격 작성자 인증이나 여러 호스트 사이의 잠금 증거로 주장하지 않는다.
queued/in_progress/cancelled 대체 실행은 PENDING_CI, 실제 실패는 RED다.

audit·intent·lease는 임시 파일 write/fsync 후 exclusive link로 완성된 inode만 공개하고
임시 파일 삭제와 directory fsync를 수행한다. 기존 파일은 덮어쓰지 않는다.
찢어진 audit·intent는 이유와 원본을 STATE/work-units/quarantine에 보존하며 status에 표시한다.
건강한 audit만 기록된 중단은 다음 scan이 복구한다. cleanup 후에도 outbox는 남는다.
같은 AC 지문의 유효 audit criteria를 합산하며 남은 AC는 부분 완료 댓글만 만든다.
PR-A는 모든 AC가 충족돼도 종료 후보를 MANUAL로 남긴다. prose·epic·legacy를 PR 하나로
자동 종료하지 않는다. CAS/If-Match는 확인되지 않았으며 원자적 원격 종료를 보장하지 않는다.

기본 drain은 쓰기 0이다. --write에도 사전에 검증된 writer-host.json의 host·bundleHash·
enabled·approvedBy가 일치해야 한다. 이 PR은 그 파일이나 인증을 생성하지 않는다.
댓글의 marker와 작성자를 모든 페이지에서 찾고 쓰기 후 readback한다. 401/403은
PENDING_AUTH, 429는 Retry-After, 5xx는 backoff이며 실패를 DONE으로 만들지 않는다.
이 보장은 단일 호스트 flock/중복 제거이며 전역 exactly-once가 아니다.

검증된 Jira binding 이후에도 MVP는 comment intent만 내보낸다. 부모가 기존 connector로
수행한 receipt의 intentId/issueKey/issueId/instanceHost/performedAt/remoteCommentId와
readBack marker·key·status가 모두 맞아야 parent-connector-readback DONE이다.
transition은 PENDING_MAPPING이며 키·instance·project·transition을 추측하지 않는다.
플랫폼 connector 접근은 CLI/CI 인증 증거가 아니다.

## 배포·인수 경계

1. PR-A report 구현과 독립 Claude 검토, 부모 단일 병합.
2. 실제 META 담당·위치·현재 버전을 확인한다. watcher를 한 tick 쉬게 하고 README의
   bin/lib/docs/tests 전체 배포 목록을 옮긴다. 기존 활성 프로세스를 임의 교체하지 않는다.
3. 설치 bundle hash, start-task --dry-run, watcher 호출 로그, 실제 merged audit/outbox를 확인한다.
4. PR-B에서 실제 CI RED/GREEN·QA 실행 receipt와 기존 PR manifest/명시 예외 이전을 확인한다.
5. 별도 PR-C에서 enforce를 전환한다. 예외는 만료·이유·승인자를 기록하고 자동 종료하지 않는다.

L1 소스 시험, L2 fake-gh/임시 Git의 실제 bin 통합, L3 실제 CI/실행 receipt,
L4 실제 META hash/probe/watcher/audit, L5 실제 tracker 쓰기/readback을 분리한다.
L3/L4/enforce 전에는 로컬 경로가 강제됐다고 보고하지 않는다. Cloud dispatch 호출과
수동/비소유자 경로는 아직 미확인이다. 권한·브랜치 보호·secret·OAuth·webhook은 변경하지 않는다.
reward 및 tools/ops/pep 보존 작업과 PRIVATE/fullbundle 수용 검사는 별도 소유로 남는다.
