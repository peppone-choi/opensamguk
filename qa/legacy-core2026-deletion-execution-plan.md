# Legacy/Core2026 삭제 실행 플랜

목표: `legacy/` + `core2026/` 완전 삭제 가능 상태 달성

## 삭제 게이트 (모두 충족 필요)
- [ ] 기능 패러티 100%
- [ ] 행동 패러티(권한/예외/제약/로그) 통과
- [ ] 자동+수동 검증 통과
- [ ] 운영 기능 공백 0

## Batch 1 — P0 블로커
- [ ] history UI/동작 완전 이관
- [ ] auction UI/동작 완전 이관
- [ ] traffic API 구현 + 프론트 연결

## Batch 2 — P0 운영 기능
- [ ] admin 전역 가입/로그인 스위치
- [ ] admin 정리 배치(scrub 계열) 대체
- [ ] admin 제재 세분화/운영 로그/외교 운영 뷰
- [ ] OAuth 운영 토큰 연장/임시비번 운영 플로우 대체

## Batch 3 — P1 정합
- [ ] message option 규약(overwrite/hide/delete) + 삭제시간 정책
- [ ] processing/commands 인자 계약 통일
- [ ] chief 고급 편집 동작 정합
- [ ] map 세부 룰 정합

## Batch 4 — P1/P2
- [ ] battle-center/simulator 포맷/정렬/권한 정합
- [ ] betting 통합 모델 동등성 보강
- [ ] npc-list 의미 분리 보강
- [ ] emperor/dynasty 네이밍 정합

## Batch 5 — 삭제 게이트 검증
- [ ] 참조 탐색: 코드 내 legacy/core2026 경로 참조 0건
- [ ] e2e + parity 테스트 통과
- [ ] 운영 시나리오 체크리스트 통과
- [ ] 삭제 PR 준비(롤백 플랜 포함)
