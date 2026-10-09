# 단일 예약 취소 API

이 계약은 서버 API의 단일 예약 취소를 설명한다. 화면의 취소 버튼과 브라우저 연동은 별도 작업이다.

## 현재 예약 조회

`GET /api/reserved-commands?generalId=<장수 ID>`는 인증된 사용자의 예약만 반환한다. 실제 예약 slot에는
`turnIdx`, `action`, `arg`, `brief`, UUID `revision`이 있다. 내용과 revision은 같은 JDBC SELECT로 읽는다.
예약이 없는 slot은 목록에 없으며 휴식 예약으로 대체하지 않는다.

## 취소 요청과 성공 복구

```http
DELETE /api/reserved-commands?generalId=<장수 ID>&turnIdx=<slot>&revision=<UUID>
Authorization: Bearer <access token>
Idempotency-Key: <클라이언트가 생성한 UUID>
```

장수 ID, slot, 조회한 revision과 요청 UUID를 전송 전에 보관한다. 세계는 API 프로세스가 담당하는 세계로
고정된다. 휘하의 유효 slot은 0부터 11까지다. 소유권은 general 행을 잠근 뒤 다시 확인한다.

성공은 삭제·빈 slot 확인·취소 자체 inbox/result/outbox의 동일 트랜잭션 commit 이후 HTTP 200으로 반환한다.
응답은 `status=RESOLVED`, `type=reservationCancelled`, `ok=true`, `accepted=true`, `receiptRecorded=true`이며
`requestId`는 요청 UUID다. `result`에는 장수 ID, slot, `reservationRevision`, `slotEmpty=true`가 있다.

응답을 잃었으면 같은 세계·장수·slot·revision·요청 UUID로 재전송한다. 서버는 기존 성공 receipt를 먼저
조회하여 동일 결과를 반환한다. 재전송은 현재 slot을 다시 삭제하지 않는다. 같은 UUID에 다른 의도를
붙이거나 다른 사용자의 UUID를 사용하면 `IDEMPOTENCY_CONFLICT`로 거절한다.

`GET /api/command/result/<요청 UUID>`에서도 제출자의 durable 최종 결과를 복구할 수 있다. 성공 receipt는
제출 계정에 귀속되므로 나중에 장수 소유권이 바뀌어도 제출자는 조회할 수 있다. 타인·다른 세계에는 결과를
노출하지 않는다. 원래 예약 요청의 실행 결과나 seq2를 취소 성공으로 덮어쓰지 않는다.

`slotEmpty=true`는 취소 commit 당시의 사실이다. 이후 새 예약이 들어올 수 있으므로 현재 상태는 예약 GET으로
다시 읽는다. 과거 성공 receipt와 현재 replacement가 함께 존재하는 것은 정상이다.

## 비접수 거절

거절 응답은 `accepted=false`, `receiptRecorded=false`이며 예약·inbox·result·outbox에 새 변경을 남기지 않는다.
인증 실패는 401, 소유권 실패는 403, 잘못된 인자는 400이다. 빈 slot 또는 revision이 바뀐 slot은
409 `REVISION_MISMATCH`로 거절한다. 세계 규칙을 확인할 수 없으면 `POLICY_UNAVAILABLE`이다.

409 `WORLD_EXECUTING`은 같은 세계의 daemon generation이 실행 fence를 보유하여 신규 취소를 받을 수 없다는
기술적 admission 결과다. 다른 장수나 미래 slot에도 적용되며 해당 slot의 행동이 시작됐다는 뜻은 아니다.
이 결과는 `retryable=true`로 반환한다. 이때는 같은 요청을 나중에 재전송할 수 있다. 성공 receipt 재조회는
이 fence 없이 먼저 수행한다. 모든 거절을 영구 receipt로 기록하거나 모든 거절을 자동 재시도 대상으로 삼지 않는다.

## 실행 원자성과 복구

daemon은 예약 snapshot과 첫 효과 계산 전에 world별 exclusive transaction advisory fence를 얻는다.
취소는 같은 key의 shared try-lock을 사용한다. daemon의 전체 효과 flush와 실제 외부 commit까지 fence를 유지한다.
일반 예약 쓰기의 replacement 의미는 유지하며, 기존 revision 검사는 새 예약을 ring 소비로부터 보호한다.

flush와 fence는 같은 DataSource의 명시적인 JDBC transaction manager를 사용한다. recorder 정리, generation
확정, 로컬 version·clock 반영과 결과 발행·ACK는 실제 commit 뒤에 실행한다. commit 뒤 전달 오류를 DB 실패로
재분류하거나 확정된 효과를 다시 실행하지 않는다.

계산 이후 실패나 commit 결과 불명은 `RELOAD_REQUIRED`다. fence를 잃은 옛 payload는 보관·재실행하지 않는다.
runner는 실패한 전체 Spring context를 닫고 기존 primary 데이터에서 새 context·world·service·buffer를 구성한다.
복구 부팅은 seed를 비활성화하며 빈 세계를 추정 생성하지 않는다. 자동 context 재구성은 JVM 프로세스당 한 번만
시도한다. 재구성 실패 또는 이후 재차 복구가 필요한 상태에서는 자동 반복 없이 실패 상태를 운영자가 확인해야 한다.

generation 동안 connection 하나와 world fence가 유지된다. 큰 generation과 연속 catch-up에서 취소를 받을 수 없는
기간의 실제 운영 비용은 별도 측정이 필요하다. 이 문서는 운영 DB 수정·reset·배포를 지시하지 않는다.
