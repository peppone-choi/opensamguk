# Current State

## RTK14 전체 장수·5능력치 배포 준비 — 2026-08-04

- 엑셀 1,000행의 15개 열을 비공개 source JSON으로 round-trip하고, 15개 populated 런타임 시나리오마다 장수 번호 1–1000을 정확히 한 번씩 표현한다. 생성물과 원본은 gitignored이며 private GitHub Actions secret만 등록됐다.
- 기존 장수는 소속·도시·관직·대사를 유지하면서 통솔·무력·지력·정치·매력과 생년·등장년·몰년을 갱신한다. 엑셀에만 있는 343명은 빙의 가능한 기본 장수로 추가한다. 시나리오 전용 legacy-only 351행은 모두 근거가 있는 정치·매력 override를 사용하며, 동명이인 source 후보 소진 충돌 38건은 exact runtime identity override로 처리하고 미검토 fallback은 fail-closed다.
- exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7`의 리뷰는 released-V26 forward-repair gap을 P1으로 판정했다. 현재 working tree는 **V26을 전혀 건드리지 않는다**: `V26__npc_lifecycle_phase_units.kt`와 `V26NpcLifecycleMigrationTest.kt`는 origin/main으로 byte-for-byte 되돌렸고, RTK14 lifecycle repair는 전부 새 world-scoped migration `V38__rtk14_npc_lifecycle_repair.kt`(test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`) 하나로 모았다. 따라서 이미 V26을 지난 월드에 별도 future repair가 필요하다는 이전 제한은 더 이상 유효하지 않다. 단, V38 자체의 실행·배포·live 결과는 아직 없다.
- 마이그레이션 번호 정리: claim-request migration은 `V36__general_owner_claim_request.sql` → **`V37__general_owner_claim_request.sql`**로 renumber했다. origin/main이 이미 `V36__diplomacy_casualties.sql`을 싣고 있어 V36이 둘이면 Flyway가 duplicate version으로 실패하기 때문이다.
- V26 확장이 아니라 V38인 이유: 이미 `flyway_schema_history`에 V26을 기록한 DB는 V26을 절대 재실행하지 않으므로 V26을 확장해도 업그레이드된 월드에는 닿지 않는다. 별개로 fresh DB에서는 Flyway가 `ScenarioSeedRunner`(`ApplicationRunner`)보다 먼저 돌아 `world_state`가 비어 있고 V26은 즉시 반환하므로 신규 월드에서도 그 확장은 도달 불가였다. 아직 어떤 월드도 기록하지 않은 V38에 repair 전체를 두는 것이 이미 마이그레이션된 월드와 새로 시드된 월드를 같은 최종 상태로 수렴시킨다.
- V38은 world-scoped이며 모든 월드에서 실행된다. external-over-classpath effective scenario를 사용하고, `name[2]`/`nation[4]`의 실제 action identity로만 매칭하며, `rtk14Added`를 제외한다. universal strict-shape checks가 불완전한 legacy event를 보존하고, 검증된 grouped event는 appearance year별로 분할하며, ambiguous identity는 fail-closed한다.
- CodeRabbit remediation: ambiguous future row 선택 문제는 V26 확장이 아니라 V38의 duplicate future-appearance fail-closed로 닫았고, importer는 `appearanceYear > deathYear`를 import 전에 거부하며, possession의 conditional reservation delete는 `takeIf` 부수효과 대신 명시적 branch로 수행한다. V37 request-id reconciliation, `general_ex` RNG isolation, typed tuple-24 marker, and shared effective-scenario resolution remain in scope.
- Docker PR #25는 weak indentation scan을 rendered Compose JSON contract로 대체하고 daemon-host relative mount 문제를 `COMPOSE_HOST_DIR` default로 닫는다. 이는 candidate-branch validation이며, this remediation의 merge/deploy/live completion 주장이 아니다.
- Focused evidence: importer 21, possession 21, and the Docker focused contract test are green; the deep repair-migration re-review is CLEARED. V26 evidence no longer applies to this branch because V26 and its test are reverted to origin/main. The repair coverage now lives in `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases: external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case); it has not been re-run in this documentation pass. Earlier backend-wide evidence predates these working-tree fixes and is not a final full-gate result for them.
- Remaining: source fix commit/push → source PR #356 and Docker PR #25 each receive three new sequential exact-SHA mention reviews and any required fixes → merge → deploy → `pep` reseed → live DB/API/UI/clock verification. None of those release steps is complete.

## 현재 상태 요약 — 2026-07-25

CQRS 정합성 하드닝 트랙과 F4 프론트 액션 배선이 함께 main에 반영됐다. 아래는 정본 최신 상태이며, 그 뒤 히스토리 절은 압축된 기록(증거는 PR/리뷰 아티팩트가 정본)이다.

### CQRS 하드닝 (ARCH-S1–S6) — S5까지 build-only 완료, main 머지

전부 **build-only**(프로덕션 cutover/activation 미수행), 라이브 게임 동작·패러티 골든 불변.

| 그룹 | 티켓 | PR / 커밋 | 상태 |
|------|------|-----------|------|
| 월드 스코프 (B1) | OPENSAM-127~129 | #302~#305 | main 머지 — 로더/쿼리/예약/Redis 키/flush를 `world_id`로 스코프, 동일 local-ID 2월드 격리 게이트 통과 |
| flush 무결성 (B2) | OPENSAM-130~132 | #307~#309, #311 | main 머지 — `DeltaGenerationSession`, `world_version` CAS + `writer_epoch`, `FlushRecoveryGate` |
| S4 durable 명령 경로 | OPENSAM-133~136 | **#312** | main 머지 · GH #279/#280/#281/#282 **CLOSED** · 독립 리뷰 `Verdict: cleared` — command_inbox 선기록, durable result/outbox, consumer-group wake + post-commit ACK, 크래시/리플레이 매트릭스 |
| S5-T1 hot/cold 카탈로그 | OPENSAM-137 | 커밋 `4e7095df` | main 머지 (build-only) |
| S5-T2 bounded boot reads | OPENSAM-138 | **#314** | main 머지 — 부팅 아카이브 읽기 bounded/on-demand화 |
| S5-T3 minVersion read barrier | OPENSAM-139 | **#315** | main 머지 — game-api `minVersion` read barrier, stale read → 409 `VERSION_NOT_VISIBLE` |
| S6 롤아웃 | OPENSAM-122 (#268) | — | **잔여** — canary/expand-backfill/replica ADR, S2–S5 완료 후 착수 |

- 에픽 #266(ARCH-S4)은 자식 T1–T4가 닫혔지만 **activation/operational 잔여** 때문에 OPEN 유지.
- ARCH-S1-T3(OPENSAM-125 / #271) 용량 임계값·admission policy는 OPEN(병렬 capacity work).

### F4 프론트 액션 배선 — main 머지

| 티켓 | PR | 내용 |
|------|-----|------|
| OPENSAM-13 | #316 | 엔진 deny 결과를 web/game에 표면화 |
| OPENSAM-6 | #317 | 외교 서신 승인/거부 응답 배선 |
| OPENSAM-8 | #318 | 내정보(my-page) 즉시 액션 |
| OPENSAM-7 | #319 | 인사부 roster read model + 장수 임면 배선 |

메일함 서신 삭제(`web/game/app/game/mailbox/page.tsx` → `deleteMessage` intake)도 배선 완료.

### 오늘(2026-07-25) Jira Done 처리

OPENSAM-6 / 7 / 8 / 13 / 97 / 123 / 124 를 완료 처리함. (97 = 초상 수집 page 모드 승격, 123 = CQRS 로컬 집계 기준선 재현, 124 = 국가 벌크 증거 + 데몬 라이프사이클 고정 — 커밋 `11bb0322`/`e013e47c`/`b6cb77f0`.)

### 다음 착수 후보

OPENSAM-137은 완료됐으므로 S5 잔여(hot/cold 활성화 follow-up) 또는 S6 착수 판단은 활성화 정책(#271, #268) 게이트를 따른다. 프로덕션 deploy/cutover·골든 위조·force-merge 금지.

---

## 히스토리 (압축) — 증거 정본은 PR/리뷰 아티팩트

### CQRS B1 — OPENSAM-127~129
process-world reads + flush scope + two-world isolation. main 머지 (#302~#305).

### CQRS B2 — OPENSAM-130~132 (build-only, 2026-07-21)
- OPENSAM-130 (#307): DeltaGenerationSession prepare/commit/abort.
- OPENSAM-131 (#308): world_version CAS + writer_epoch fence on flush.
- OPENSAM-132 (#309/#311): FlushRecoveryGate + intake/tick stop; FLUSH_RETRY resume.
- 리뷰: `docs/superpowers/reviews/2026-07-21-opensam-13{0,1,2}-*.md` — Verdict cleared.

### S4 durable 명령 경로 — OPENSAM-133~136 (build-only, PR #312 머지 2026-07-22)
- **OPENSAM-133 / #279 (ARCH-S4-T1)**: `command_inbox` 선기록(202 이전), DB-before-Redis intake, reserved ring + inbox 트랜잭션, 안정 intent fingerprint, 중복 request-id 처리. GH CLOSED 2026-07-23 (build-only).
- **OPENSAM-134 / #280 (ARCH-S4-T2)**: durable inbox claim/reclaim(lease), Redis consumer-group wake + PEL 인계 + post-commit ACK. GH CLOSED 2026-07-23 (build-only).
- **OPENSAM-135 / #281 (ARCH-S4-T3)**: durable `command_result`/`command_outbox`(V35), 같은 flush TX 커밋, `CommandOutboxRelay` 재시도, 예약/큐 terminal 상관. GH CLOSED. 리뷰 최종 `Verdict: cleared` — `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md`.
- **OPENSAM-136 / #282 (ARCH-S4-T4)**: 크래시/리플레이 매트릭스. GH CLOSED.
- 잔여(비-차단): 프로덕션 deploy/cutover 미수행, 에픽 #266 activation 잔여, `reservationRevision` 계약 설계 잔여, 광역 `verify-changes.sh --run` Gradle stall(툴링 baseline). 상세 트리아지: `docs/superpowers/research/2026-07-23-ticket-triage-next.md`.

### S5-T1 hot/cold 카탈로그 — OPENSAM-137 (build-only, 커밋 `4e7095df`)
`logic/.../memory/HotColdCatalog.kt`(ALWAYS_HOT/PHASE_HOT/QUERY_ONLY_COLD) + `HotColdWorldCatalogGuardTest`(스냅샷 로더/런타임 read-seam/직접 SQL 스캔). 독립 리뷰 반복 후 method-agnostic reader 탐지로 수렴. S5 런타임 prefetch 활성화는 미수행.

### S5-T2 bounded boot reads — OPENSAM-138 (build-only, PR #314)
`WorldSnapshotLoader`가 statistic/history/global-log full-scan 제거, `ArchiveHistoryReader`/`StatisticSnapshotReader` on-demand 시seam. 아카이브 flush 복구는 `DatabaseHooks`가 pending 마커만 싣고 `JdbcFlushExecutor`가 재시도 TX 내부에서 읽도록 교정. 리뷰 5회차 cleared — `docs/superpowers/reviews/2026-07-23-opensam-138-bounded-boot-review.md`. 리터럴 JFR/heap 비교(#284)와 광역 `verify-changes.sh --run`은 미실행.

### S5-T3 minVersion read barrier — OPENSAM-139 (build-only, PR #315 머지 2026-07-24)
- `TurnDaemonEventEnvelope`가 nullable `committedWorldVersion` 운반(레거시 envelope 디코드 보존), 데몬/API terminal result 행이 커밋 버전 인코드.
- `GET /api/command/result/{requestId}`가 `committedWorldVersion` 최상위 노출.
- game-api `minVersion` 인터셉터: command-result read = read-your-writes, ranks/history/world-log/admin = eventual, 그 외 = authoritative.
- `ReadConsistencyBarrier`가 전용 `game-api-read-barrier` Hikari 풀로 `world_state.world_version` 폴링, stale read → 409 `VERSION_NOT_VISIBLE`(worldId/currentVersion/requiredVersion/retryAfterMs).
- 독립 리뷰 cleared — `docs/superpowers/reviews/2026-07-24-opensam-139-minversion-read-barrier-review.md`.
- **상태: PR #315로 main 머지 완료** (이전 "commit/push/PR pending"은 해소됨).

### 미해결 툴링 baseline (제품 결함 아님)
광역 `scripts/agent/verify-changes.sh --run`가 `--rerun-tasks` Gradle 매트릭스에서 반복 stall(exit 143)하는 현상은 여러 워커에서 관측됨. 집중 모듈 테스트·strict `check.py`·독립 리뷰는 green. 개인 `.codex/config.toml` overlay(`max_threads`/`max_depth`)는 이 트랙에서 편집/스테이징하지 않음.
