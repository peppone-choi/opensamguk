# /os-checkpoint — 상태 저장 (리셋/인수인계 전 필수)

$ARGUMENTS: (선택) 체크포인트 사유

다음 3개 파일을 **현재 사실로** 갱신한다 (`docs/agent/prompt-pack.md` "작업 인수인계" 프롬프트 준수):

1. `.ai/current-state.md` — Updated at, 완료/진행/다음 행동, 검증 결과(실행/미실행 구분).
2. `.ai/handoff.md` — 다음 에이전트가 대화 없이 재개 가능하게. 결정 vs 추측 분리, 실패한 접근을 Do not repeat에.
3. `.ai/ownership.md` — 종료한 소유권 해제.

원칙: 장황한 로그를 넣지 않는다(압축 기준: `docs/agent/context-strategy.md` §압축 원칙). 검증 결과는 인용 가능한 것만 기록.
호출 시점: 계획 확정 후 / 주요 구현 후 / 검증 실패 후 / 컨텍스트가 길어졌을 때 / 에이전트 전환 전.
