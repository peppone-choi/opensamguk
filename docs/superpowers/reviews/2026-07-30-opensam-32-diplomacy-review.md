# OPENSAM-32 외교 상태 전이 독립 리뷰

Scope: OPENSAM-32 D4-08~13의 PHP 대비 제의 payload/RNG, 수락 상태 전이, reserved/instant/flush/UI 경로

Reviewer: `op32_independent_review` (`fable-deep-reasoner`, read-only)

## 관측

### MAJOR — D4-10 빠른 실행 폼 누락

- `web/game/app/game/diplomacy/page.tsx`는 모든 빠른 외교 명령을
  `pinnedArgType="nation"`으로 연다.
- `CommandModal` pinned mode는 catalog를 읽지 않고 form 없는 명령을
  합성하므로 `che_불가침제의`의 필수 `year/month`가 사라진다.
- 기존 페이지 테스트는 `che_종전제의` props만 확인해 이 결함을 놓쳤다.
- 실제 컴포넌트 RED: 국가 선택 뒤 `year/month` spinbutton 0개,
  `CommandModal.form-spec` 1 failed / 2 passed.
- Remediation: 페이지 하드코딩 없이 backend catalog의 authoritative
  `formSpec`을 pinned launch에서도 소비하고 실제 세 필드 제출을 단언한다.

### MAJOR — 세 proposal destination color 불일치

- PHP:
  - `che_종전제의.php:132-146`
  - `che_불가침제의.php:182-196`
  - `che_불가침파기제의.php:131-145`
  모두 destination `MessageTarget`에 `$destNation['color']`를 넣는다.
- Kotlin 세 resolver는 destination color를 `#000000`으로 고정한다.
- `MessageTarget.toArray()`는 color를 실제 저장 body에 직렬화하며,
  `ProcessNationCommand`는 destination nation을 이미 preload한다.
- Remediation: 세 GoldenTest에 구별되는 destination color를 먼저
  단언해 RED를 만든 뒤 preload된 색을 사용하는 최소 수정을 적용한다.
  icon은 기존 P7/config 격리를 유지한다.

### MAJOR — 분리 seam을 실제 종단 경로로 과장

- `DiplomaticMessageHandlerTest`는 수동 `MessageSnapshot`에서 시작한다.
- `ReservedDiplomacyDestTargetTest`는 proposal이 아닌 accept command부터
  시작한다.
- `DiplomacyUpdateFlushIT`는 수동 `FlushPayload`에서 시작한다.
- 따라서 현재 green 합은 `.ai/task.md`가 요구한 actual
  proposal→message→accept→recorder→JDBC 한 경로의 증거가 아니다.
- Remediation: fixture 재사용을 먼저 설계 검토하고, 가장 작은 실제
  통합 회귀를 추가한다.

### MINOR — D4-13 동일 월드 연속성

- 불가침 파기 수락과 trade 상태 선전포고는 현재 서로 다른 월드 테스트다.
- 상태 정의상 결론은 타당하지만, 같은 월드에서
  `NON_AGGRESSION → TRADE → DECLARATION`을 실행하는 회귀가 더 강하다.

### QUESTION — 상태 전이 밖 byte parity 경계

- production accept는 등록 resolver를 우선하며 종전/파기 수락 resolver는
  상태만 기록한다. PHP의 다중 로그/event 전체와 동일하다고 주장할 수 없다.
- OPENSAM-32의 완료 주장은 Jira의 상태 전이 범위로 제한하고, 시간/icon 및
  accept log/event 미포팅은 명시적 격리 또는 후속 작업으로 남겨야 한다.

## Remediation 상태

- D4-10 UI: backend catalog form 소비와 lookup/row/form 누락
  fail-closed를 구현했다. 최종 3 files / 16 tests와 typecheck가 PASS다.
- destination color: 세 resolver와 GoldenTest의 RED→GREEN을 완료했다.
  최종 logic 8 suites / 72 tests가 PASS다.
- 실제 통합 경로: lifecycle proposal → DB message → accept → 양방향 flush를
  `DiplomaticMessageWorldScopeIT`로 연결했다.
- D4-13 same-world sequence: 위 IT의 같은 world/process에서
  `NON_AGGRESSION → TRADE → DECLARATION`을 관측했다.
- backend 최종 focused gate: engine 34/34, infra 2/2, failure/error/skip 0.
- live browser: 필수 compose runtime 설정 부재로 `채점대기`.

## 최종 재검토

- 최초 3 MAJOR와 1 MINOR의 코드 finding은 모두 해소됐다.
- matching catalog row에 form이 없는 edge도 독립 재검토 후 실제 RED
  1/6을 확인하고 fail-closed로 수정해 frontend 16/16을 재확인했다.
- accept 다중 로그/event 전체 PHP 패러티는 Jira 상태 전이 범위 밖의
  알려진 후속 항목으로 격리했으며 이번 완료 주장은 상태 전이에 한정한다.
- Agent OS 상태·handoff·allowed-file 문서를 실제 diff와 최종 증거에 맞췄다.
- 최종 read-only 재검토에서 BLOCKER/MAJOR/MINOR/QUESTION이 모두 0이며,
  current handoff의 다음 티켓도 OPENSAM-33 한 건으로 정합화됐다.

Verdict: cleared
