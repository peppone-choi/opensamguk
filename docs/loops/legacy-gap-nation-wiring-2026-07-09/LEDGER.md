# LEDGER — legacy-gap-nation-wiring-2026-07-09

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |
|------|------|-------------|--------|------|------------|
| 0 | (베이스라인) 브랜치 5커밋 적재 후 gate | — -> gate green | `tools/parity/gate.sh backend` | 기록 | 측정 중 |
| 1 | killturn 전역 + nation registry 미배선 + term absolute | red silent -> green | NationCommandDispatchTest + gate | 채택 | ee3bb3a8 |
| 2 | general 채널 부재 + mailbox first-playable | partial -> green | MailboxControllerTest + gate | 채택 | ffde2d11 |
| 3 | unregistered nation cmds silent no-op | many no-ops -> bridge | NationCommandDispatchTest 몰수/피장 | 채택 | 45006c11 |
| 4 | 피장파장 KV + 초토화 drain | missing KV/city -> green | NationCommandDispatchTest | 채택 | 3b15f7f5 |
| 5 | 허보 등 staging 부재 | partial move -> staged | NationCommandDispatchTest 허보 | 채택 | 044eb146 |
| 6 | ship: push/merge/deploy | local green -> prod | gate + prod health | 채택 | PR#149 merged ae97e95e; deploy run 28988835286 success; health 200 UP |

## 승인 대기
없음 (사용자: 커밋·푸시·머지·원격 업데이트 명시 지시)

## 백로그
- processTournament WAVE 8
- multi-general strategic broadcast PLAIN
- event research inheritance point
- Interval (b), chooseInstantNationTurn (c)

## Ship evidence
- PR: https://github.com/peppone-choi/opensamguk/pull/149
- Deploy: https://github.com/peppone-choi/opensamguk/actions/runs/28988835286 success
- Health: /health, game/gateway actuator, /api/game/health all 200 UP
- world_state clock: blocked (no SSH pubkey to EC2 from this host) — 채점대기 turn-advance
