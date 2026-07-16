# /os-analyze — 기능/갭 분석 (코드 수정 금지)

$ARGUMENTS: 분석 대상 (명령 코드, 기능, 버그 증상)

`docs/agent/prompt-pack.md`의 "기능 분석" 프롬프트를 그대로 수행한다:
- PHP 오라클 근거(`legacy/devsam-core` path+line) 인용 필수 — 패러티 대상이면 `docs/superpowers/WORKING_SYSTEM.md` §PHP oracle protocol 준수.
- 관련 파일 / 유사 구현·테스트 / 영향 범위(intake→engine→flush→FE) / 대안 2+ / 권장안 / 위험 / 사람 결정 필요 항목.
- 확인 불가 항목은 UNKNOWN.

결과 저장: 응답 + (요청 시) `docs/superpowers/research/`.
중단 조건: PHP 원본을 찾지 못함 → 검색 경로 보고 후 중단.
