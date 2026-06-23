# LEDGER — loop-parity-2026-06-23

> 측정 → 1가설 → 재측정 → 채택/폐기.  
> 형식: `| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |`

## 0바퀴 베이스라인

| 바퀴 | 가설 | 점수 | 채점자 | 판정 | 원인/비고 |
|---|---|---|---|---|---|
| 0 | 베이스라인 | BE 3218/0 green, FE tsc green, vitest 107/0 green, agent-system 0/0 green | tools/parity/gate.sh backend + FE tsc/test + agent-system check | 채택 | main @ e8d562be, 2026-06-23 22:34 |

| 1 | `tools/smoke.sh`가 `localhost:8080`을 하드코딩해 GATEWAY_API_PORT 충돌 시 거짓 FAIL | smoke green 전→후 | `tools/smoke.sh` | 채점중 | hidche-web이 호스트 8080 점유 중 |

## 백로그 (승인 전)

- WAVE 1b: DiplomacyMonthProcessor 틱 호출 (불가침/정전 term 카운트다운).
- WAVE 1c: checkStatistic 실제 연산 (빈 람다 스텁 교체).
- WAVE 2a: auction `auction_bid`→`auctionBid` / betting `bet`→`placeBet` 인테이크 casing silent-no-op.
- WAVE 4a: FE `묠력`→`묵력` mojibake.
- Phase 2: long-sim PHP 캡처 하네스.

## 바퀴 기록

