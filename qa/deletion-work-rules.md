# Legacy/Core2026 삭제 목표 작업 규칙 (운영본)

목표: `legacy/` + `core2026/`를 안전하게 삭제 가능한 상태까지 구현/검증한다.

## 0) 기본 원칙

1. **코드를 믿는다**: 문서/추정이 아니라 실제 소스와 실행 결과로만 판단.
2. **삭제 기준 우선**: "기능 있음"이 아니라 "삭제해도 운영 공백 없음"을 기준으로 판정.
3. **작게, 자주 커밋**: 배치/기능 단위로 커밋하고 한 커밋에 한 목적만 담는다.
4. **선 코드리뷰, 후 커밋**: `git diff` 검토 없이 커밋 금지.

## 1) 삭제 게이트 (모두 충족 시에만 삭제)

- [ ] 기능 패러티 100%
- [ ] 행동 패러티(권한/예외/제약/로그) 충족
- [ ] 자동+수동 검증 통과
- [ ] 운영 기능(관리자/OAuth/NPC/통계) 공백 0

## 2) 기존 실행 계획(반드시 포함)

기준 파일: `qa/legacy-core2026-deletion-execution-plan.md`

### Batch 1 (P0)

- history 완전 이관
- auction 완전 이관
- traffic API + 프론트 연결

### Batch 2 (P0)

- admin allow_join/allow_login
- admin scrub/정리 배치 대체
- admin 제재 세분화/운영 로그/외교 운영 뷰
- OAuth 운영(토큰 연장/임시비번) 대체

### Batch 3 (P1)

- message option 규약(overwrite/hide/delete + 삭제시간)
- processing/commands 인자 계약 통일
- chief 고급 편집 정합
- map 세부 룰 정합

### Batch 4 (P1/P2)

- battle-center/simulator 정합
- betting 동등성 보강
- npc-list 의미 분리 보강
- emperor/dynasty 네이밍 정합

### Batch 5 (게이트)

- legacy/core2026 참조 0건
- e2e + parity 테스트 통과
- 운영 시나리오 체크리스트 통과
- 삭제 PR + 롤백 플랜

## 3) 작업 프로토콜

1. 작업 시작 전: 범위/완료조건(DoD) 3줄로 명시
2. 구현: 백엔드→프론트→테스트 순서
3. 검증:
   - 정적: `git diff`, 타입/빌드 확인
   - 동적: 핵심 API 호출/화면 플로우 확인
4. 보고: 변경 파일, 리스크, 다음 액션을 짧게 보고

## 4) 커밋 규칙

- 커밋 전 필수:
  - `git diff --staged` 또는 `git diff` 리뷰
  - 스텁/TODO/placeholder 금지 확인
  - 인코딩/로케일 이슈 확인
- 커밋 메시지 형식:
  - `feat(parity): ...`
  - `fix(parity): ...`
  - `test(parity): ...`
  - `chore(parity): ...`

## 5) 차단/장애 대응

- 429/504/timeout 발생 시 페일오버 체인 적용: **opus → gpt → gemini**
- 모델당 1회 재시도 후 다음 모델 승계
- 배치별 체크포인트 기록: `qa/failover-checkpoints/<phase>-<batch>.md`

## 6) 금지 사항

- 문서만 보고 패러티 완료 판정 금지
- 검증 없는 "완료" 보고 금지
- 대규모 혼합 커밋 금지
- legacy/core2026 삭제 선행 금지(게이트 충족 전)

## 7) 최종 삭제 절차

1. 코드 참조 0건 확인
2. 기능 회귀 0건 확인
3. 운영자 시나리오 최종 사인오프
4. `legacy/`, `core2026/` 삭제 커밋
5. 태그/릴리즈 노트/롤백 경로 기록
