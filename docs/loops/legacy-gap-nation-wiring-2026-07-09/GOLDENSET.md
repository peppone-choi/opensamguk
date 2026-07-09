# GOLDENSET — legacy-gap-nation-wiring-2026-07-09

## Mode
패러티/버그 (기존 repo gate = 시험지)

## Graders (결정적)
1. `tools/parity/gate.sh backend` → BUILD SUCCESSFUL + test XML failures=0 errors=0
2. `opensamguk.engine.turn.NationCommandDispatchTest` — live nation-pass dispatch/bridge
3. 골든/assert 약화 금지

## Ship grader
- PR merge to main + prod health + (가능 시) world_state clock sample

## Non-goals this loop
- Tournament engine port (WAVE 8)
- Interval NotImplemented (PHP-identical)
- chooseInstantNationTurn quarantine
