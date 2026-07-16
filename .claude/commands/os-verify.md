# /os-verify — 현재 diff 검증

$ARGUMENTS: (선택) --run 이면 실제 실행

1. `scripts/agent/verify-changes.sh` 로 diff 분류와 필요한 최소 검증 확인 (`--run`으로 실행 가능).
2. `docs/agent/verification.md` 행렬 기준으로 부족분을 직접 실행.
3. 판정은 exit code가 아니라 `BUILD SUCCESSFUL` + 테스트 XML `failures="0" errors="0"` 인용으로.
4. 보고 형식: **실행한 검증(결과 인용) / 실행하지 않은 검증 / 실패(원문)** 3분류 — 미실행을 통과로 쓰지 않는다.

중단 조건: 검증 도구 자체가 불가(예: Docker 미가동 → IT skip 명기, 브라우저 불가 → `채점대기`).
