# /os-review — 적대적 코드 리뷰

$ARGUMENTS: 리뷰 범위 (기본: 현재 diff)

`docs/agent/lifecycle-review.md` 절차 + `docs/agent/prompt-pack.md` "코드 리뷰" 프롬프트를 수행한다:
- 요구사항(`.ai/task.md`) 대조 → 패러티 5차원 → one-daemon-write → 테스트 적정성 → 하드코딩 → 보안/성능.
- 심각도 `BLOCKER/MAJOR/MINOR/QUESTION`, 각 지적에 파일:라인·근거·확신 수준. 근거 없는 지적 금지.
- 판정: `cleared` / `fix-required` / `quarantined-with-proof`.

결과 저장: 비자명 변경이면 `docs/superpowers/reviews/<date>-<scope>.md` (strict CI가 요구).
주의: 구현자와 같은 컨텍스트에서 자기 승인 금지 — 별도 에이전트/세션에서 수행.
