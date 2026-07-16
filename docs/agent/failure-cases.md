# Failure Cases — 재발 방지용 오류 메모리

발표용 회고가 아니라 **재발 방지 장치**다. `OBSERVED` = 이 저장소에서 실제 발생(증거 인용), `PREVENTIVE` = 예방 목적의 대표 패턴(발생한 적 없음을 명시). 새 사례는 같은 형식으로 추가하고, 반복 위반되는 규칙은 검증 스크립트/훅으로 승격을 검토한다.

## AI-Failure-001: 허위 완료 — false-green 완료 선언

- Status: **OBSERVED**
- Category: 허위 완료
- Trigger: gradle이 호스트 context-mode 래퍼를 통과하며 `task-notification` exit 0이 부정확. 별건으로, 배포 러너 유실+디스크 포화 시 deploy 런이 pending→cancelled로 조용히 소멸.
- Incorrect behavior: exit code만 보고 "테스트 통과/배포 완료"로 보고. 실제로는 미실행·미배포.
- Detection signal: 출력 tail에 `BUILD SUCCESSFUL` 부재, 테스트 XML 부재/카운트 불일치, GH Actions 런 상태, prod에 변경 미반영.
- How it is caught: `tools/parity/gate.sh`(XML까지 검증), `verification.md` 대원칙, deployer의 prod 검증.
- Immediate recovery: `--rerun-tasks`로 재실행 → XML 직접 확인 → 배포는 Actions 런 로그와 prod 상태로 재검증.
- Recovery prompt: "방금 성공 주장한 검증을 다시 실행하되, exit code가 아니라 출력 tail의 `BUILD SUCCESSFUL`+테스트 카운트와 `**/build/test-results/test/*.xml`의 `failures=\"0\" errors=\"0\"`을 인용해 증명하라. 인용 불가 항목은 '미검증'으로 재분류하라."
- Permanent prevention: 완료 보고에 증거 인용 의무화(`verification.md`), `scripts/agent/verify-changes.sh`.
- Evidence: `AGENTS.md` §gradle context-mode 주의; `docs/superpowers/SESSION_HANDOFF.md` 2026-06-12 §0a-1(배포 파이프라인 사망 은폐).

## AI-Failure-002: 기술적 환각 — 존재하지 않는 코드/위조 산출물

- Status: **OBSERVED**
- Category: 기술적 환각 (패러티 위조 포함)
- Trigger: PHP 정본을 확인하지 않고 "그럴듯한" 코드·로그·wire 코드를 생성.
- Incorrect behavior: ① FE가 미등록 명령 코드 `OpenUniqueAuction`을 전송(정본은 `auctionOpenUnique`) — 예약이 조용히 위조됨. ② 경매 위조 로그 push 6사이트 — `log_scope` enum 외 값이 flush `BatchUpdateException` 틱 롤백 = **턴 동결 지뢰**. ③ PHP `fight()` 대신 결정론 점수비교로 대체 구현(대회 승패·로그 패러티 미달).
- Detection signal: intake `intakeCodes` 부재(precheck AVAILABLE인데 엔진 deny), 로그 문자열이 PHP 캡처와 byte 불일치, PHP 원본 path+line 인용 부재.
- How it is caught: PHP oracle 프로토콜(`WORKING_SYSTEM.md` — path+line 인용 의무), 골든 게이트, cross-agent critique, 재채점 워크플로.
- Immediate recovery: PHP 원본 확인 → 위조 산출물 제거 → 실 골든 캡처 후 재포팅.
- Recovery prompt: "방금 산출물에서 PHP 원본 근거(`legacy/devsam-core` path+line)가 없는 모든 상수·로그 문자열·코드 식별자를 나열하라. 각각 PHP 원본을 찾아 인용하거나, 찾지 못하면 해당 부분을 '근거 없음'으로 표시하고 제거 계획을 제시하라. 골든/테스트는 수정 금지."
- Permanent prevention: 규율 5조(faithful, never fabricate), 골든은 실 캡처만, 격리는 증거+백로그 필수.
- Evidence: `docs/superpowers/SESSION_HANDOFF.md` 2026-06-12 바퀴 23(W-1)·24(W-9); `docs/loops/live-gap-closure-2026-07-10/LEDGER.md` 바퀴 8(fight() 갭).

## AI-Failure-003: 범위 이탈 — 과잉 적용/무단 확산

- Status: **OBSERVED**
- Category: 범위 이탈
- Trigger: 한 지점의 수정 원칙(마스킹)을 요청 범위보다 넓게 적용.
- Incorrect behavior: 바퀴 18의 mailbox 마스킹이 over-mask로 확산되어 정상 표시까지 가려지는 회귀 발생(W-3) — 이후 diplomacy type 게이트 + 단건 마스킹으로 정정.
- Detection signal: `git diff --stat`이 계획된 파일 목록을 초과, 요청과 무관한 동작 변화, 재채점에서 판정 뒤집힘.
- How it is caught: 재채점(critic) 워크플로, `.ai/task.md`의 Allowed files 대조, parity-reviewer.
- Immediate recovery: 범위 밖 diff 원복 → 최소 범위로 재구현 → 회귀 테스트 추가.
- Recovery prompt: "현재 `git diff --stat`을 `.ai/task.md`의 In scope/Allowed files와 한 줄씩 대조하라. 계약 밖 변경은 각각 (a) 원복 또는 (b) 필요 사유+사람 승인 요청으로 분류하고, 원복부터 실행하라."
- Permanent prevention: task.md 범위 계약, 커맨드 `/os-implement`의 "소유 파일만·최소 범위" 규칙, 리뷰 단계의 diff-요구사항 대조.
- Evidence: `docs/superpowers/SESSION_HANDOFF.md` 2026-06-12 바퀴 22(W-3, "바퀴 18 over-mask 회귀 수정").

## AI-Failure-004: 검증 없는 배포 신뢰 — health green ≠ 서비스 정상

- Status: **OBSERVED**
- Category: 허위 완료 (운영 변형)
- Trigger: 배포 후 `/actuator/health` green만 보고 정상 선언.
- Incorrect behavior: ① nginx 정적 upstream이 stale IP를 물어 전 라우트 502(재시작 순서 문제). ② `commandBlockMs` 미배선 → Redis 무한 블록 → 턴 데몬 동결 — health는 green인데 `world_state`가 전진하지 않음.
- Detection signal: `world_state.current_year/month` 미전진, 로그의 `RedisCommandTimeoutException`, 라우트별 502.
- How it is caught: deployer 검증 절차(§`.claude/HARNESS.md` §6 두 ops lesson).
- Immediate recovery: `docker restart opensamguk-nginx`(A) / `commandBlockMs` 유한값 배선(B). 상세는 HARNESS §6.
- Recovery prompt: "배포를 완료로 선언하기 전에: (1) 업스트림 전부 health 확인 후 nginx를 마지막으로 재시작했는지, (2) prod DB에서 `world_state.current_year/current_month`가 시간이 지나며 실제 전진하는지 두 가지를 관측 결과로 인용하라. 인용 불가면 배포는 미완료다."
- Permanent prevention: `lifecycle-ops.md` 배포 게이트, deployer 에이전트 정의.
- Evidence: `.claude/HARNESS.md` §6 OPS LESSON A/B.

## AI-Failure-005: 병렬 에이전트 소유권 충돌

- Status: **PREVENTIVE** (동일 파일 동시 수정 사고의 직접 기록은 없음 — 단, 공유 파일 co-widen 시 머지 충돌 위험은 `CLAUDE.md`가 명시)
- Category: 협업 충돌
- Trigger: 두 에이전트가 ownership 등록 없이 같은 파일(특히 `CommandWireMapper.kt` 등 공유 확장점)을 수정.
- Detection signal: 머지 충돌, 상대 diff 소실, `.ai/ownership.md` 미등록 작업.
- Immediate recovery: 작업 중단 → ownership 확인 → 늦게 시작한 쪽이 재배치.
- Recovery prompt: "지금 수정 중인 파일 목록을 `.ai/ownership.md`와 대조하라. 타 에이전트 소유 파일이 있으면 즉시 수정을 멈추고 읽기 전용으로 전환한 뒤, 충돌 사실을 handoff에 기록하라."
- Permanent prevention: `collaboration-protocol.md` single-writer + worktree 격리 + foundation-first.
- Evidence: 규칙 근거 — `CLAUDE.md` §How phases are built(disjoint 요구).
