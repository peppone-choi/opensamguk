# Phase 4X-A 휘하 인물(가신)·부곡 — 실화면 보고 (2026-09-06)

spec `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md`(v3.1, 교차 비평 `cleared`), 계획 §4X-A.
로컬 스택(docker compose, game-api·game-engine 이미지 재빌드 → V55 적용 확인 `flyway_schema_history 55|t`), 계정 `uitestpi3tu7` / 장수 1495 「추적w17」(검증용 SQL 로 crew 1000 주입 뒤 엔진 재시작).

## 경로
`/game/pep/my#retinue` → 서약(부장) → 편성(병력 100·군량 300) → 지휘관 배정 → 작전실 슬롯. 명령은 전부 인테이크(`/api/command/*`) → 엔진 `RetainerHandler` → flush 8g → `/api/my-retinue` 재조회(202 ≠ 성공, 터미널 결과까지 폴링).

| 캡처 | 확인한 것 |
|---|---|
| `reports/ui-redesign/phase4xa/01-retinue-empty.png` | 빈 상태 두 패널(「휘하 인물이 없습니다 / 서약하면 여기 나옵니다」, 「부곡이 없습니다」), rules 기반 폼(비용 500 금·유지 30/30, 병력 최소 100), 「잠정」 칩, 병력 0 이면 편성 버튼 점선 + 사유 「병력이 100 미만입니다」 |
| `02-retinue-after.png` | 서약 결과가 인테이크 사유 그대로: 두 번째 시도는 「같은 이름의 휘하가 있습니다.」(중복 이름 게이트) |
| `03-retinue-commander.png` | 가신 「부장을」(관계 부장·충성 50/100·임무 select·해제), 부곡 「부곡 1」(보병 100·훈련 60·**사기 66 = 60 + 6 배정 보너스**·피로 0·군량 300(3개월)), 장수 요약 병사 1,000 → 900 · 군량 1,561 → 1,261 · 자금 1,000 → 500(서약) — 합 보존 |
| `04-war-room-slot.png` | 작전실 조작 대상 바 「휘하 1명 · 부곡 1」 배지 → `/game/pep/my#retinue` |

DB 확인: `general_retainers` 1행(1495, 부장을, lieutenant, loyalty 50), `command_inbox` 의 `retainerPledge` APPLIED.

## 실측이 잡은 결함

- 첫 실행에서 서약은 DB 에 도달했지만 UI 결과가 오지 않았다 — 엔진 로그 `turn-daemon-loop tick failed … unknown result type=boardRead`. Phase 4C-1 의 `boardRead` 결과 코드가 직렬화기의 접힌 집합에 없어 **턴 루프 전체가 멈추던** 결함(인테이크가 CLAIMED 로 굳음). `25b8232f` 로 고치고 wire 왕복 회귀 테스트를 추가했다. 재빌드 뒤 위 캡처가 정상 경로다.

## 게이트(전부 녹색)
common wire 2 · logic `RetainerRulesTest` 4 · engine `RetainerIntakeTest` 8 + `RetainerMonthlyNoopGateTest` 3(적색 프로브: 행 0 동일·행 1 상이) + `HotColdWorldCatalogGuardTest` 11 · infra `RetainerFlushIT` 1(PG16) + V32 인벤토리 10 · game-api `RetinueReadControllerTest` 3 · game vitest(`retinue-panels`) 4 · 엔진 전체 1051(회귀 2건 수정) · logic 전체 3432.

## 남은 것
- EXISTING 가신(기존 장수 서약)은 다음 절편(ADR-LITE-017 범위 안). 역할·임무의 명령 효율 효과는 별도 절편.
- 부장 배정 사기 보너스는 PR 비평 S3 로 「부곡 생애 한 번」 으로 고정(V56 `commander_bonus_applied`).
