# Current State

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
