# Model Failover Playbook (OpenSam 작업용)

목표: API 504 / rate limit / timeout 발생 시 작업을 끊지 않고 다음 모델로 자동 승계한다.

## 기본 체인
1. `sonnet` (primary)
2. `gpt` (fallback-1)
3. `gemini` (fallback-2)

## 실패로 간주할 조건
- HTTP 429 (rate limit)
- HTTP 504 / provider gateway timeout
- provider unavailable / overloaded
- 실행 제한 시간 초과

## 승계 규칙
- 같은 작업 단위를 유지한다 (배치 단위).
- 실패 시 현재까지 완료/미완료/에러를 8~12줄로 요약해 다음 모델에 그대로 전달한다.
- 같은 배치에서 모델만 바꿔 재투입한다.
- 한 모델당 재시도 1회, 이후 다음 모델로 이동한다.
- 3개 모델 모두 실패하면 10분 백오프 후 재개한다.

## 상태 연속성 규칙
모든 배치는 다음 체크포인트를 남긴다.
- 대상 파일 목록
- 적용된 변경 요약
- 미완료 항목
- 다음 모델이 바로 이어받을 TODO

체크포인트 위치:
- `qa/failover-checkpoints/<phase>-<batch>.md`

## 커밋 규칙
- 배치 완료 시점마다 작은 커밋
- 커밋 메시지에 모델 표기
  - 예: `phase2(features1): parity fixes [model:sonnet]`

## 운영 메모
- 모델 전환은 품질 저하보다 진행 연속성이 우선인 상황에서 사용
- 충돌/품질 이슈가 보이면 즉시 메인 세션에서 코드리뷰 후 수정
