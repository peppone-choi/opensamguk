# /os-debug — 근본 원인 디버깅

$ARGUMENTS: 증상 (실패 테스트/로그/prod 관측)

`docs/agent/prompt-pack.md`의 "근본 원인 디버깅" 프롬프트를 수행한다:
1. `docs/agent/failure-cases.md` + `.claude/HARNESS.md` §6 두 ops lesson 먼저 대조 — 단 패턴 매칭을 증거 없이 결론으로 승격 금지.
2. 가설 ≥3개(근거 + 판별 실험) → 실험 실행 → 원인 확정.
3. **원인 확정 전 수정 금지.** 테스트 삭제·약화·근거 없는 골든 기대값 수정 금지. 원인 확정 후 승인된 제품 변경은 명시적 이유와 회귀 증거로 기대값을 갱신할 수 있다.
4. 수정 후 회귀 검증(`docs/agent/verification.md`), 새 패턴이면 `failure-cases.md`에 추가 제안.

중단 조건: 가설 전멸 → 추가 관측 계획 보고. prod 상태 변경이 필요하면 사람 승인부터.
