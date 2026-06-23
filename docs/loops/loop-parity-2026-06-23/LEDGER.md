# LEDGER — loop-parity-2026-06-23

> 측정 → 1가설 → 재측정 → 채택/폐기.  
> 형식: `| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |`

## 0바퀴 베이스라인

| 바퀴 | 가설 | 점수 | 채점자 | 판정 | 원인/비고 |
|---|---|---|---|---|---|
| 0 | 베이스라인 | BE 3218/0 green, FE tsc green, vitest 107/0 green, agent-system 0/0 green | tools/parity/gate.sh backend + FE tsc/test + agent-system check | 채택 | main @ e8d562be, 2026-06-23 22:34 |

| 1 | `tools/smoke.sh`가 `localhost:8080`을 하드코딩해 GATEWAY_API_PORT 충돌 시 거짓 FAIL | smoke FAIL→green, agent-system 0/0 | `tools/smoke.sh` + 수동 health probe | 채택 | hidche-web이 호스트 8080 점유; env-aware 포트로 해결 |

## 백로그 (승인 전)

- WAVE 2a: auction `auction_bid`→`auctionBid` / betting `bet`→`placeBet` 인테이크 casing silent-no-op.
- WAVE 4a: FE `묠력`→`묵력` mojibake.
- Phase 2: long-sim PHP 캡처 하네스.

| 2 | `InMemoryTurnWorld.allocateNationId/GeneralId`가 live key만 볼 때 동일 틱 내 삭제된 id를 재사용해 flush INSERT 단계에서 `DuplicateKeyException` 발생 | backend gate 3221/0 green, FE tsc/vitest green, agent-system 0/0 green | `tools/parity/gate.sh backend` + FE gates + `agent-system/check.py --strict` | 채택 | 삭제 집합을 max에 포함, 크로스-틱 재사용은 W0b 백로그 유지 |
| 3 | `InMemoryTurnWorld`의 id high-water mark가 영속화되지 않아 재기동 후 삭제된 nation/general id를 재사용해 참조 오염 가능 | backend gate 3226/0 green, FE tsc/vitest green, agent-system 0/0 green | `tools/parity/gate.sh backend` + FE gates + `agent-system/check.py --strict` | 채택 | `world_state.meta.maxNationId/maxGeneralId`에 단조 증가 high-water mark 저장/복원 |
| 3b | WAVE 1b 백로그: `DiplomacyMonthProcessor` 틱 호출 | backend gate 3215/0 green, FE tsc/vitest green, agent-system 0/0 green | `tools/parity/gate.sh backend` + FE gates + `agent-system/check.py --strict` | 채택 | `MonthlyPostUpdateHook` Q9이 이미 불가침/선포 term 카운트다운 처리; `DiplomacyMonthProcessor`는 미사용 orphan → 삭제 |
| 3c | WAVE 1c 백로그: `checkStatistic` 빈 람다 스텁 | `StatisticFlushIT` green, engine `StatisticEncodePathGuardTest` green | `:infra:test --tests StatisticFlushIT` + engine tests | 채택 | `DaemonLoopConfig`에서 `CheckStatisticCalculator.compute` 호출 + `ChangeRecorder.recordStatisticInsert` + `JdbcFlushExecutor` step-12 flush 이미 배선 완료 |

## 바퀴 기록

