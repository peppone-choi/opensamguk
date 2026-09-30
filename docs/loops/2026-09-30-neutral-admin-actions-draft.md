# 중립 관리자 조치 계약 초안

상태: C10 제안, C8 권한·운영 계약 합의 전. API 구현 또는 운영 사용을 허가하는 문서가 아니다. 기존 block/killturn/refreshScore 메타를 새 동작의 정본으로 재사용하지 않는다.

## 제안 접수와 결과

POST /api/admin/people/actions:

- action: ACCESS_ALLOW, ACCESS_DENY, MUTE, TURN_BLOCK, BLOCK_3, UNBLOCK, FORCE_DEATH, NOTIFY 중 **지원 계약과 처리기가 검증된 것만** 허용 목록에 공개한다.
- generalIds: 양수·중복 제거, 처리 world 장수만. 서버가 권한을 판정하고 다른 world·없는 대상을 mutation 전 거부한다.
- scope: action별 정해진 표적(예: PRIVATE_MAIL, PERSONAL_TURN). action의 이름만으로 로그인·모든 쓰기 범위를 확대하지 않는다.
- expiresAt: UTC 종료 시각. 제한 조치에는 필수 유효 시각 또는 승인된 영구 제한 이유. 기간·최대값·wall clock과 phase 선택은 C8과 합의한다.
- reason: 감사 사유. message는 NOTIFY에만, 인증 토큰·비밀정보를 원장이나 사용자 응답에 남기지 않는다.
- idempotencyKey: 관리자 actor·world·정규화한 대상/조치/기간과 fingerprint를 묶어 중복 실행을 막는다.

응답 202는 {status:ACCEPTED, requestId}만 뜻한다. affected를 완료 건수로 보이지 않는다. GET /api/admin/actions/{requestId}는 해당 world ADMIN만 {state:ACCEPTED|APPLIED|REJECTED, outcomes:[{generalId, applied, code}], appliedAt}을 조회한다. claim·처리·대상 결과·감사는 durable intake/ChangeRecorder/flush 트랜잭션에 함께 기록한다. 부분 성공 허용 여부는 C8과 합의 전 미정이다.

## 의미 초안과 미지원 경계

| action | 합의할 중립 의미 | 현재 지원 판정 |
|---|---|---|
| ACCESS_DENY/ALLOW | 계정 전체 로그인인지 해당 world 접근인지 범위·장수 교체/신원·만료 규칙 | 현 refreshScore throttle는 로그인 차단 아님. 새 정본 전 미지원 |
| MUTE | PRIVATE_MAIL 발신 제한·읽기 허용·공개/외교/회의실 별도 범위 | 기존 block1은 서신 거부와 killturn 부작용 혼합. 새 정본 필요 |
| TURN_BLOCK | 개인 행동·이동·대기·오프라인 위임·이미 접수 입력의 제한/기간 | HWIHA가 legacy block lifecycle을 우회. 새 정본 필요 |
| BLOCK_3 | 위 제한의 명시적인 합성인지 의미·만료·해제 | 단계 숫자를 규칙으로 재사용하지 않음. 합의 전 미지원 |
| UNBLOCK | 어떤 제한 ID/범위를 해제하는지, 기존 만료/복구·이력 | block=0만으로 모든 제한 해제 못 함. 새 정본 필요 |
| FORCE_DEATH | 장수 사망·소유권·가신/부곡/계승/관직/전투 원장 정리·증거·가역 범위 | legacy 휴식 예약+killturn=0 불가. 독립 처리·결합 검증 전 미지원 |
| NOTIFY | 관리자 명의 개인 서신인지 시스템 알림인지·NPC 대상·감사/수신 실패 | 현 sendMessage는 후보 근거이며 새 중립 접수/종단 검증 전 지원 완료 아님 |

## 권한·검증 게이트

- JWT ADMIN 역할 검증 + 처리 world 운영 권한. 일반 USER 및 다른 world 요청 403. 운영 행위 대상의 사용자 승인은 구현·검증·리뷰 후 C0가 모은다.
- 설정/조치 정책 원장과 운영 감사는 C8, game-api 중립 계약/조회 투영은 C10이 경계를 합의한다. 임의 ADMIN 토큰으로 운영 검증을 실행하지 않는다.
- 음성 검사: role 누락/USER, 다른 world/id, 만료·유효하지 않은 기간, unsupported action, 중복 요청, forged requestId. handler 없는 action의 접수는 거부.
- 양성 검사: 허용된 중립 action만 API→durable inbox→실제 HWIHA handler→flush→재시작→최종 접근/턴/서신 효과, 만료·해제·감사까지 확인. API 202나 메타 setter 테스트만으로 성공을 선언하지 않는다.

## 진입 계약 보존

현재 GET/POST /api/join은 JoinController가 MakeGeneral 생성을 접수한다. 출사 P-E04의 enlistment와 생성 T3 쓰기를 이 경로의 이름만으로 동일 취급하지 않는다. C7 생성 API 계약과 K5의 단계별 소비를 계약판에서 맞춘다. 로비 join 링크·GameEntry 미등록 이동·register 별칭·middleware rewrite 증거는 메타 2026-09-30-c10-admin-contract-audit.md에 보존했다.
