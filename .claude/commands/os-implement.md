# /os-implement — 승인된 계획 구현

$ARGUMENTS: 계획 위치/바퀴 번호 + 태스크

전제: 승인된 계획이 있어야 실행. 없으면 `/os-start-task`부터.

`docs/agent/prompt-pack.md`의 "기능 구현" 프롬프트를 수행한다:
- `.ai/ownership.md`에 등록 후, Allowed files 안에서 최소 범위 구현. 기존 패턴 우선.
- 제품·회귀 규율(`CLAUDE.md`) 준수: 결정론적 replay, 의도적 수치/로그 변경 기록, 동결 기준선, ChangeRecorder 델타, 결과에 영향을 주는 삽입 순서.
- 완료 전: `scripts/agent/verify-changes.sh`로 최소 검증 확인 → `docs/agent/verification.md` 행렬 실행 → 증거(XML/tail) 인용.
- 상태 갱신: `.ai/current-state.md`.

중단 조건: 계획-현실 불일치, 범위 밖 수정 필요(→승인 요청), 같은 가설로 검증 3회 실패(→`/os-debug`).
