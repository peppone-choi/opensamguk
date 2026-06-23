# LEDGER — parity-bug-2026-06-23

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 | 승인대기 |
|---|---|---|---|---|---|---|
| 0 | 베이스라인 | backend 3216 green | `tools/parity/gate.sh backend` | 채택 | 시작 상태 | 없음 |
| 1 | `isunited` world_state 컬럼 영속화 + 재기동 로드 | backend 3217 green, FE tsc/vitest green | `tools/parity/gate.sh backend`, `pnpm tsc --noEmit`, `pnpm test` | 채택 | `CheckEmperior`/`InvaderEndingAction`이 meta["isunited"]만 쓰다보니 재기동 시 통일/엔딩 플래그 유실 → 건국/천하통일 재탐지 불가 | 특기 밸류 셀 UI 버그 |

## Backlog (next wheels)

- WAVE-1a: Ruler succession (`nextRuler`/`deleteNation`) live wiring.
- WAVE-1b: `DiplomacyMonthProcessor` tick caller — 이미 `MonthlyPostUpdateHook.postUpdateMonthlyDiplomacy`에 연결됨 (가설 0에서 stale). 남은 갭은 외교 term 만료 로그/액션 처리.
- Long-sim Phase 1: `checkEmperior` 통일 탐지 (user asked why not 건국; this is the unification detector).
- User report: "특기 밸류 셀 날아갔다/낮아졌다" — need page/screenshot.
