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

## 게임 화면 연결(web/game)

작전실 명령 흐름의 「지금 예약」 줄이 위 계약을 쓴다. 서버 계약이 main에 합쳐지고 게이트웨이 BFF가
DELETE의 `Idempotency-Key`를 그대로 전달하기 전에는 실제 서버에서 동작하지 않는다. 지금까지의 시험은
단위 시험과 `page.route` 합성 응답을 쓴 브라우저 시험이며, 실제 JWT·PostgreSQL을 거친 검증이 아니다.

- 조회: `parseReservedCommands`(`web/game/lib/api/reservation-cancel-contract.ts`)가 `result === true`, 요청한 장수,
  0–11의 중복 없는 slot, UUID `revision`이 있는 완전한 행만 받는다. 그 밖의 응답은 읽기 오류이며 빈 12순으로 그리지 않는다.
  취소 확인 전 조회(preflight)와 receipt 뒤 다시 읽기는 이 엄격한 해석만 쓴다.
- 표시 호환: 12순 표시(`useTurnSlots`)는 `parseReservedDisplay`를 쓴다. 검사는 같되 `revision` 키가 없는 행(B1 이전 조회)만
  `revision: null`인 읽기 전용 행으로 보인다. 그 행은 취소 버튼이 막히고 확인 창·DELETE로 가지 않으며, 대체 UUID를 만들지 않는다.
  있는데 UUID가 아닌 revision, 남의 장수·중복·범위 밖 slot은 표시 조회에서도 읽기 오류다.
- 전송: `web/game/lib/api/reservation-cancel.ts`가 본문 없는 DELETE에 원래 revision과 의도 UUID(`Idempotency-Key`)를 싣는다.
  조회·DELETE·결과 GET 모두 의도를 얼린 탭의 서버(`server=`)를 명시한다. 세계 ID는 붙이지 않는다.
- 응답 해석: DELETE 성공 receipt, 결과 GET(`accepted`·`receiptRecorded` 없음), `BLOCKED` 거절, `{error}` 필터 봉투를
  서로 다른 해석기로 읽는다. 의도와 맞지 않는 2xx·비JSON·5xx·연결 끊김은 결과 불명(UNKNOWN)이다.
- 의도 기록: 확인을 누르면 UUID를 한 번 만들고, 계정·서버·(받았을 때만) generation·장수·slot·revision·UUID·상태를
  `sessionStorage`에 저장한 뒤에만 보낸다. 같은 탭의 이동·새로고침에서 결과 GET으로 이어 가며 DELETE를 저절로 보내지 않는다.
  같은 UUID의 기록은 약해지지 않는다: 어느 화면이든 receipt(`confirmed`)를 남기면 늦게 온 옛 DELETE의 연결 끊김·거절이
  그것을 결과 불명으로 낮추거나 지우지 못하고, 결과 불명도 깨끗한 거절로 지워지지 않는다. 기록은 새 현재 목록으로 끝을
  확인했을 때, 또는 모호함·receipt가 없던 의도가 분명히 거절됐을 때만 지운다. receipt가 남은 의도는 다시 보내지 않고 결과 GET을 읽는다.
- 서버 세대: 작전실이 `front-info.global.generation`을 명령 흐름에 넘기고, 안전한 0 이상의 정수만 취소 범위의 십진 문자열로 바꾼다.
  값이 없거나 잘못되면 `null`이며 다른 필드로 추정하지 않는다. 세대 변경은 지금 확인창·상태만 끝내고 이전 기록은 보존한다.
  원래 세대로 돌아오면 같은 UUID의 결과 조회로 이어 가며 DELETE를 저절로 다시 보내지 않는다. 세대는 요청 wire에 추가하지 않는다.
- 계정 확인: `AuthProvider`가 로그인 확인을 마친 뒤(`loading` 아님)에만 기록을 읽고·보이고·이어 간다. 확인 중에 남아 있는
  이전 사용자는 계정으로 쓰지 않으며, 계정이 바뀌거나 확인이 다시 시작되면 지금 화면의 범위(epoch)만 끝나고 기록은 그대로 남는다.
- 기다림의 한도: 취소 조회·DELETE·결과 GET은 각각 20초 안에 끝나지 않으면 요청을 끊고(AbortController) 연결 끊김으로 다룬다.
  보낸 뒤의 시간 초과는 결과 불명(UUID 유지)이지 되돌리기가 아니며, 보내기 전 조회의 실패는 아무것도 보내지 않는다.
  끊긴 뒤 늦게 도착한 `/api/auth/me` 재발급은 DELETE를 다시 보내지 않는다. 다른 `fetchGame` 호출의 동작은 바뀌지 않는다.
- 401 뒤 다시 보내기: `fetchGame`의 선택 guard가 재발급된 `user.id`가 의도의 계정과 같고 화면 범위와 원래 서버가
  살아 있을 때만 같은 요청을 한 번 더 보낸다. guard를 넘기지 않는 다른 호출의 동작은 바뀌지 않는다.
- 보통 예약 POST와 취소 DELETE는 같은 서버·장수·slot에 대해 동기 잠금을 공유해 동시에 나가지 않는다.
