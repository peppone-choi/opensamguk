# /os-analyze — 기능/갭 분석 (코드 수정 금지)

$ARGUMENTS: 분석 대상 (명령 코드, 기능, 버그 증상)

`docs/agent/prompt-pack.md`의 "기능 분석" 프롬프트를 그대로 수행한다:
- 최신 승인 ADR/spec·현재 구현 근거 필수. PHP path+line은 명시적으로 요청된 역사/동결 회귀 비교에서만 `docs/superpowers/WORKING_SYSTEM.md` §Historical PHP comparison protocol을 따른다.
- 관련 파일 / 유사 구현·테스트 / 영향 범위(intake→engine→flush→FE) / 대안 2+ / 권장안 / 위험 / 사람 결정 필요 항목.
- 확인 불가 항목은 UNKNOWN.

결과 저장: 응답 + (요청 시) `docs/superpowers/research/`.
중단 조건: 승인된 제품 근거를 확정할 수 없음 → 검색 경로와 UNKNOWN을 보고 후 중단. 역사 비교에서만 PHP 경로 부재를 동일하게 다룬다.
