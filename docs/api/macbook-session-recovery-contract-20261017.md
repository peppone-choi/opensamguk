# localhost 접속·입력·복구 연결 계약

기준 소스: app main `eb74c531a9768474d6f24e3ca6200df6a8e84d67` (2026-10-17). 이 문서는 현재 코드의 정적 대조이며 실행 인수·독립 승인·Claude 수락을 뜻하지 않는다. 담당: Codex 백엔드 계약 조사. 코드·공용 타입의 새 계약을 만들지 않는다.

정본: [역할별 스토리](../qa/user-stories/README.md), [로컬 QA](../admin/local-qa.md), [예약 취소](reservation-cancellation.md), [일관성·terminal](hwiha-consistency-terminal-contract.md). 현재 main의 스토리 index 마지막 변경은 `7904ac64467304f20b8774a453daa07808f6c61a` (#1640)다. 인계 참고 `5aea9ba584ecb29d1723d1331fb5c8bb9642fa17`은 그 직접 후속 커밋이며 군주·재야 두 문서만 정정했다. 현재 index와 5aea index의 blob은 동일한 `80a0c978826918a5240a313caf4f141de0ba581b`다. 아래 기능의 실제 실행 상태는 모두 NOT_RUN이다.

## 1. 신원과 경계

- **기존 구현 확인:** 브라우저 계정 세션은 gateway의 httpOnly 쿠키, 게임 행위자는 해당 API 프로세스 world에서 계정이 소유한 장수다. 공개 서버 ID, world ID, 서버 generation, 전투 sessionEpoch, daemon writerEpoch는 서로 다른 값이다.
- **일반 room API 부재:** gateway/game-api의 현재 controller mapping 조사에서 방 생성·일반 방 join·일반 게임 session 생성 REST endpoint를 찾지 못했다. 현재 입장은 로비 서버 선택 → `/game/<serverId>` → 자기 장수 조회다. 전투 BattleSession을 일반 로비 방으로 해석하지 않는다.
- **미연결:** 일반 room 수용 조건을 위한 별도 제품 계약은 확인되지 않았다. 새 endpoint나 room 상태를 이 문서에서 제안 구현으로 확정하지 않는다.
- **기존 보호:** 서버 admission이 HTTP/SSE/WS에 적용된다. 익명 401 `AUTH_REQUIRED`, 비공개·VERIFYING 403 `SERVER_NOT_PUBLIC`, 원천 미확인·로컬 용량 503 `SERVER_ADMISSION_UNAVAILABLE`이다. 성공 캐시로 실패를 열지 않는다 (`app/game-api/src/main/kotlin/opensamguk/gameapi/security/ServerAdmission.kt:36`).

## 2. 로그인 → 서버 → 장수

### 브라우저 인증

```http
POST /api/auth/login
Content-Type: application/json

{"username":"<입력 계정>","password":"<입력 비밀번호>"}
```

기존 구현은 200 `{user:{id,username,email,nickname,role,picture,imageServer}}`와 `sam_access`·`sam_refresh` 쿠키를 반환한다. 브라우저 응답에 JWT를 반환하지 않는다. backend의 `/auth/login`은 `{accessToken,refreshToken,user}`이며 BFF에서 변환한다. 실제 토큰·비밀번호는 기록하지 않는다.

- 근거: `web/gateway/app/api/auth/login/route.ts:6`, `app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AuthController.kt:53`, `app/gateway-api/src/main/kotlin/opensamguk/gateway/dto/AuthDto.kt:26`.
- 누락 입력 400 `{error}`, 연결 실패 502 `{error}`, upstream 실패는 해당 HTTP status와 `{error}`. 프론트는 모든 실패를 자격 증명 오류로 합치지 않는다.
- `GET /api/auth/me`는 200 `{user}`. access 인증 실패(401/403)에 refresh를 시도한다. refresh도 인증 실패면 쿠키를 지우고 401 `{user:null}`. 네트워크/5xx는 쿠키를 보존하고 502다 (`web/gateway/app/api/auth/me/route.ts:18`).
- 쿠키 수명 7일, access JWT 만료와 별개다. access path `/`, refresh path `/api/auth`, 둘 다 httpOnly·SameSite=Lax. localhost HTTP 운영 빌드는 기존 `COOKIE_SECURE=false` 구성 필요 조건을 확인한다. 실제 환경값을 읽거나 새 키를 생성하지 않는다 (`web/gateway/lib/cookies.ts:8`).

### 서버 선택과 읽기

- `GET /api/servers` → 200 `{servers:[{id,name,generation,gameUrl}]}`. `gameUrl=/game/<id>`, generation은 nullable이다. 원천 unknown은 503 `{error:{code:"SERVER_LIST_UNAVAILABLE"}}`, no-store. 빈 목록과 unknown을 구분한다 (`web/gateway/app/api/servers/route.ts:8`, `app/gateway-api/src/main/kotlin/opensamguk/gateway/publication/api/ServerPublicationDto.kt:8`).
- 브라우저 game BFF는 `/api/game/<upstream-path>?server=<serverId>`다. 예: `/api/game/api/front-info?server=qa160`. `server` query 또는 `sam_server`로 대상만 고르고 upstream 전송 시 `server` query를 제거한다. 명시 서버를 다른 서버로 대체하지 않는다 (`web/gateway/app/api/game/[...path]/route.ts:87`, `:202`).
- backend `GET /api/front-info?generalId=<선택적 본인 장수>` → `{result:true,global,general,nation,city,recentRecord,aux}`. 장수 미보유는 빈 general·null nation/city를 포함하는 별도 정상 상태다. HTTP 200만으로 장수 보유·입장 완료를 선언하지 않는다 (`app/game-api/src/main/kotlin/opensamguk/gameapi/controller/FrontInfoController.kt:171`).
- `front-info.global.generation`은 `config.server_generation` 출처다. 값이 없으면 다른 필드로 추정하지 않는다 (`FrontInfoController.kt:646`).

스토리 연결: `US-WAND-01`의 기존 장수 작전실 진입·새로고침·모바일 재접속, `US-LORD-01`과 `US-SUB1-01`의 역할 및 소속 읽기. 로그인·기존 장수·권한 관측을 각각 증명해야 한다.

## 3. 입력 → 예약 → 턴 terminal

현재 일반 HWIHA 입력은 다음 경로다. 브라우저 예시는 BFF prefix `/api/game`와 `server` 선택을 붙인다.

```http
GET /api/commands/travel-options?generalId=<본인 장수>
POST /api/command/action.travel?generalId=<본인 장수>&turnIdx=0
Content-Type: application/json

{}
```

- 위 `action.travel`은 견문이며 `PersonalInput.TRAVEL`이다. `PersonalInput.parse`는 이 입력에 빈 JSON 객체만 허용하므로 `{}`를 실제 body로 보낸다. body 누락(null)·추가 필드는 유효하지 않다 (`logic/src/main/kotlin/opensamguk/logic/input/PersonalInput.kt:19`, `:24`, `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/PersonalAdmission.kt:21`). `travel-options`도 같은 PersonalInput.TRAVEL을 조회한다 (`PersonalOptionsController.kt:16`).
- 이동 `TravelInput.MOVE=action.move`와는 다른 입력이다. 이동/강행군에는 `{"destinationProvinceId":"<options에서 고른 province ID>"}`가 필요하고 귀환 `action.return`은 `{}`다. 견문 예시에 이동 목적지를 붙이지 않는다 (`logic/src/main/kotlin/opensamguk/logic/input/TravelInput.kt:11`).
- 기존 접수 응답: HTTP 202 `{status:"AVAILABLE",requestId:"<UUID>",turnIdx:0}`. 실행 완료·자원 변경을 뜻하지 않는다 (`app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:95`, `:202`).
- 서버 slot은 0–11, 화면 URL `slot`은 1–12다. `slot=0`은 순 미지정 의미이므로 01순의 서버 0을 화면 URL에 그대로 쓰지 않는다 (`web/game/lib/command-flow/url.ts:28`, 스토리 README §4.1).
- 익명 401, 본인 장수 불일치 403. 정책 미확인·도메인 거절은 HTTP 200 `{status:"BLOCKED",code,reason}`일 수 있다. 성공 status만으로 통과시키지 않는다 (`CommandController.kt:103`).
- `/api/v2/commands/{commandId}` 및 `/precheck`는 sandbox profile와 property가 모두 켜져야 존재한다. 기본 제품 입력으로 가정하지 않는다. 이 경로의 `ACCEPTED`, `NEEDS_INPUT` 422, `BLOCKED` 422, `UNKNOWN` 404를 일반 `/api/command`와 혼합하지 않는다 (`app/game-api/src/main/kotlin/opensamguk/gameapi/command/CanonicalCommandController.kt:37`).

```http
GET /api/reserved-commands?generalId=<본인 장수>
GET /api/command/result/<requestId>
```

- 예약 읽기: `{result:true,generalId,slots:[{turnIdx,action,brief,arg,revision}],turnTime,turnTerm,year,month,turnPhase,turnPhaseText,date,autorunLimit}`. 빈 slot은 목록에 없다. `revision`은 실제 예약 UUID다. 익명 401, 장수 없음·타인 조회 403 (`app/game-api/src/main/kotlin/opensamguk/gameapi/web/ReservedCommandsController.kt:56`).
- 결과 읽기: `{status:"PENDING",requestId,phase?:"reservationAccepted"}` 또는 `{status:"RESOLVED",requestId,ok,type,reason?,result,committedWorldVersion?}`. `RESOLVED`와 `ok:true`를 함께 확인한다. `PENDING`은 시간 초과·실패·성공으로 바꾸지 않는다.
- 결과는 world 고정, inbox의 제출 계정/장수 소유권 확인 뒤 Redis 우선·durable fallback이다. 타인·없는 키·소유권 미확인은 같은 PENDING 응답으로 비노출한다. 따라서 PENDING은 요청 존재의 증거도 아니다 (`CommandController.kt:254`, `:292`, `:301`).
- terminal에는 `committedWorldVersion`이 있을 수 있으나 현재 front-info의 단순 200을 그 version의 AUTHORITATIVE 읽기로 보장하지 않는다. 이 일관성 조건은 별도 계약의 목표/현행 구분을 따른다.

스토리 연결: `US-WAND-02`, `US-SUB1-02`는 접수·링 revision·자기 턴 terminal·상태/기록 변화·새로고침의 다섯 단계 모두 필요하다. `US-SUB1-03`은 이동·턴 신호, `US-SUB1-07`과 `US-WAND-07`은 권한/거절 경계다.

## 4. 턴 이벤트

- 브라우저 `GET /api/game/sse/turn` → backend `GET /sse/turn`. 단일 EventSource를 공유하고 BFF가 쿠키에서 Bearer를 전달한다 (`web/game/hooks/useSSE.ts:7`, `web/gateway/lib/gameProxyPath.ts:1`).
- SSE 이름은 JSON `type`에서 얻으며 기본 이름은 `turnCompleted`. `turnCompleted` payload는 `{type,at,lastTurnTime,year,month,turnNumber,turnPhase?,turnPhaseText?}` (`app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeRelayController.kt:258`, `common/src/main/kotlin/opensamguk/common/wire/RealtimeEvent.kt:23`).
- `commandResult` 깨움 신호도 같은 연결을 사용한다. 각 명령의 소유권·최종 결과는 결과 GET으로 확인한다 (`web/game/hooks/useSSE.ts:43`, `web/game/lib/commandResultEvents.ts:15`).
- 실제 commit 뒤 결과 발행·clock 반영이 수행된다. 이벤트 수신 자체로 해당 스토리 자원/예약/기록 일치를 대체하지 않는다 (`app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt:671`, `:687`).

## 5. 예약 취소와 브라우저 복구 범위

```http
DELETE /api/reserved-commands?generalId=<본인 장수>&turnIdx=0&revision=<조회한 UUID>
Idempotency-Key: <이 취소 의도의 UUID>
```

- 성공은 동일 트랜잭션 commit 뒤 200 `{status:"RESOLVED",type:"reservationCancelled",ok:true,accepted:true,receiptRecorded:true,requestId,result:{generalId,turnIdx,reservationRevision,slotEmpty:true}}`다. 과거 성공과 현재 replacement가 함께 있을 수 있어 예약 목록을 다시 읽는다.
- 인증401/권한403/인자400, revision 불일치409 `REVISION_MISMATCH`, 실행 fence409 `WORLD_EXECUTING`(`retryable:true`). 비접수 거절은 `accepted:false,receiptRecorded:false`. 같은 UUID+같은 의도를 재전송하며 다른 의도는 `IDEMPOTENCY_CONFLICT`다 (`docs/api/reservation-cancellation.md:13`, `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/ReservationCancelController.kt:19`).
- BFF는 해당 DELETE에만 `Idempotency-Key`를 전달한다. 이미 main에 있는 전달 코드를 다시 만들지 않는다 (`web/gateway/app/api/game/[...path]/route.ts:221`).
- 탭 journal은 계정·서버·받았을 때만 generation·장수·slot·원 revision·UUID·상태를 보존한다. 같은 탭 새로고침 뒤 GET으로 복구하며 DELETE를 자동 재전송하지 않는다. 닫은 탭/다른 탭/다른 기기의 동일 journal 복구를 보장하지 않는다 (`web/game/lib/command-flow/reservation-cancel-journal.ts:1`, `docs/api/reservation-cancellation.md:82`).
- 화면 epoch와 generation 변경은 현재 UI 범위를 끝내고 오래된 응답이 새 계정/세계 화면을 바꾸지 않게 한다. daemon writerEpoch와 같은 값이 아니다. 요청당 20초 timeout 뒤 전송 결과 불명은 UUID를 유지한다 (`docs/api/reservation-cancellation.md:87`).

스토리 연결: `US-SUB1-06` 예약 취소·중복 제출. 통신 중단 뒤 원 UUID receipt 복구와 replacement 보호는 이 스토리의 기존 중복실행/영속 축에 증거를 붙인다. 새 스토리를 대신 만들지 않는다.

## 6. 전투 목록 → 전투 session

- `GET /api/battles/active?generalId=<본인 장수>` → JSON 배열. 각 원소는 `{battleId,worldId:string,kind,sourcePhase,phase,joinDeadlineAt?,observedAt,mySeat:{sourceKeys:[{kind,sourceId}]},...}`다. RUNNING은 phase LIVE로 투영한다. 미확인 pacingMode/controller/place/sides/replayId를 채우지 않는다 (`app/game-api/src/main/kotlin/opensamguk/gameapi/battle/realtime/BattleActiveDto.kt:9`, `BattleActiveQuery.kt:29`).
- 오류 봉투는 `{error:{code,message}}`, no-store. 익명401 `AUTH_REQUIRED`, 장수 인자400 `INVALID_GENERAL_ID`, 타인403 `FORBIDDEN`, 원천503 `SOURCE_UNAVAILABLE` (`BattleActiveController.kt:12`).
- 조건부 `POST /api/battles/{worldId}/{battleId}/join-ticket` → 200 `{joinTicket}`, no-store. 익명401, world/참가/소유권 불일치404. `battle.join-ticket.enabled=true`여야 존재하며 기본은 꺼짐이다 (`BattleJoinTicketController.kt:17`, `app/game-api/src/main/resources/application.yml:32`).
- WS `/ws/battles/<serverId>/<worldId>/<battleId>?lastSeenEventSeq=<0 이상 십진수>`는 query 없이도 허용한다. subprotocol은 `battle.v1`과 발급 ticket 두 개다. Origin allowlist·실제 world·현재 소유권·publication을 검증한다. URL/Authorization/Cookie로 ticket을 전달하지 않는다. backend handshake는 Authorization/Cookie header가 있으면 거절한다 (`BattleWebSocketAdmission.kt:51`).
- 현재 실제 initial snapshot은 schemaVersion2 JOINING, tick0/eventSeq0만 지원한다: `{schemaVersion:2,t:"SNAPSHOT",worldId,battleId,sessionEpoch:string,tick,eventSeq:string,phase:"JOINING",joinDeadlineAt,authorityRevision:string,field,ownUnits,deployment:{revision:string,allowedCells,ownPositions}}` (`BattleV2JoiningSnapshot.kt:31`, `:60`).
- 입력 frame `DEPLOYMENT_MOVE`는 `clientCommandId,sourceKey,targetCell,expectedEpoch,expectedAuthorityRevision,expectedDeploymentRevision`을 요구한다. `COMMAND`는 `clientCommandId,expectedEpoch,expectedAuthorityRevision,issuedTick,scope,intentType,intentPayload:{rally}`다. epoch/revision은 십진 문자열, 중복·추가 필드는 거절한다 (`BattleV2WireCodec.kt:17`). 이 decoder 존재는 RUNNING 실플레이 연결 증거가 아니다.
- ACK는 `{schemaVersion:2,t:"ACK",battleId,sessionEpoch,clientCommandId,inputId,replayed,receipt:{verdict,reasonCode,deploymentRevisionAfter}}`. 수신 ACK를 전투 완료로 세지 않는다 (`BattleV2WireCodec.kt:71`).
- **미연결:** 현재 JOINING publisher는 RUNNING·tick/event replay를 fail-closed한다. 프론트의 phase 없는 snapshot/command 경로도 draft라고 명시한다. 끊김 뒤 자동 재접속·DELTA 재생은 없으며 stale epoch/authority/deployment 때 새 ticket·snapshot을 한 번 요청하는 범위다 (`BattleV2JoiningSnapshot.kt:47`, `web/game/lib/battle/use-battle-session.ts:3`).

스토리 연결: `US-LORD-10`의 출병 예약·terminal·군단/전투 목록·방 재접속. index의 옛 draft PR 표시는 최신 병합 계보와 다르므로 그 문구로 미병합을 단정하지 않는다. 병합 사실은 조작/완주 PASS를 뜻하지 않는다. 전략 `REALTIME`은 전술 전투 pacing과 별개이며 현재 부팅에서 `NOT_IMPLEMENTED: REALTIME strategic layer`로 막힌다 (`app/game-engine/src/main/kotlin/opensamguk/engine/boot/ActiveWorldMapValidator.kt:11`, `ScenarioSeedRunner.kt:290`).

## 7. daemon 저장·epoch 복구

- 내부 flush fence는 `{worldId,writerEpoch,expectedWorldVersion}`. `world_state` CAS는 world ID·version·writer epoch가 같아야 version을 1 올린다. stale이면 `StaleWorldWriterException`, 전체 flush 트랜잭션 실패다. 현재 구현에 expectedVersion/epoch가 없는 legacy 분기도 있으므로 모든 caller가 항상 CAS라고 일반화하지 않는다 (`app/game-engine/src/main/kotlin/opensamguk/engine/flush/WorldWriterFence.kt:8`, `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:637`).
- `TurnRunService`는 payload에 현재 version/epoch를 기록하고 commit 뒤 version·clock·결과를 반영한다 (`TurnRunService.kt:753`). snapshot loader는 primary의 `world_version,writer_epoch`를 읽는다 (`app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt:387`).
- stale CAS·계산 이후 실패·commit 불명은 `RELOAD_REQUIRED`; 오래된 payload를 무조건 재실행하지 않는다 (`app/game-engine/src/main/kotlin/opensamguk/engine/flush/FlushRecoveryGate.kt:125`). 기존 문서는 seed 없이 primary에서 context를 재구성하며 자동 시도는 JVM당 한 번이라고 규정한다 ([예약 취소 계약 §실행 원자성과 복구](reservation-cancellation.md#실행-원자성과-복구)).
- 부곡 content revision은 생성 때 1, 실제 내용 변경마다 증가한다. 한 flush에 여러 변경을 모으면 DB revision보다 2 이상 높을 수 있다. 저장은 DB보다 높은 revision만 허용하며 같은/낮은 revision이면 앞선 native write와 world CAS까지 rollback한다. 커밋 전 rollback 후에는 동일 payload를 재시도할 수 있다 (`InMemoryTurnWorld.kt:544`, `JdbcFlushExecutor.kt:1785`, `BattleOriginProducerIT`). 이는 클라이언트 예약 UUID나 전투 sessionEpoch와 별개인 내부 저장 계약이다.
- 같은 immutable payload 재시도는 commit되지 않았음이 알려진 `FLUSH_RETRY`에만 해당한다. CAS 저장의 성공 replay를 보장하지 않으며, 위 취소 UUID의 성공 receipt 재조회와 다른 계약이다 (`FlushRecoveryGate.kt:16`). 복구 경로는 현재 코드와 대상 시험으로 별도 검증한다.
- **미확인:** 정확한 통합 SHA에서 실제 프로세스 재시작·epoch 인계·receipt 중복 없음·게임 상태 readback을 아직 실행하지 않았다. 브라우저 새로고침, 테스트의 가짜 SSE, 모듈 CAS 시험은 이 인수의 대체 증거가 아니다.

## 8. 첫 수직 묶음의 실행·증거 제안

이 절은 **제안/NOT_RUN**이다. 프론트가 수락한 계약으로 기록하지 않는다. 담당/시험 슬롯과 격리 localhost를 확보한 통합 담당이 실행한다. 기존 DB 초기화·공개 전환·새 키 생성 지시는 포함하지 않는다.

진행 중 첫 백엔드 묶음은 GitHub #1666 ↔ Jira OPENSAM-351의 저장 CAS 회귀이며 이 문서가 해당 작업의 성공을 선행 판정하지 않는다. 공동 점수는 meta `docs/coord/READINESS-20261017.json`이 관리하며 현재 인계 기준 25/100이다. 아래 경로 조사만으로 점수를 올리지 않는다.

1. `US-WAND-01`: localhost 정확한 app/Docker main, `/api/auth/me`의 역할, 서버 목록, 본인 `front-info`, 새로고침/다른 모바일 프로필을 확인한다.
2. `US-WAND-02` 또는 `US-SUB1-02`: 실제 견문 1건의 requestId → 링 revision → 자기 턴 RESOLVED/ok → 상태/기록 → 재접속을 확인한다.
3. `US-SUB1-06`: 취소 응답 손실·같은 UUID 조회·새 replacement 보호를 확인한다. 별도 재시작 인수에서는 primary world/epoch·durable terminal·중복 비용 없음도 관측한다.
4. `US-LORD-10`: 기존 출병 입력에서 실제 조우 session으로 이어지는지 확인한다. 미구현 RUNNING·전략 REALTIME을 BLOCKED로 유지하고 두 모드 완주 점수에 전용하지 않는다.

예상 대상 시험(이번 문서 작업에서 실행하지 않음): `:app:game-api:test --tests '*CommandResultLookupTest' --tests '*ReservationCancelIT'`, `:infra:test --tests '*WorldVersionCasIT'`, `:app:game-engine:test --tests '*TurnRunServiceFlushRecoveryTest'`. Testcontainers skip는 PASS가 아니며 전체 JVM은 개발 묶음 이후 기존 유예를 따른다.

각 실행은 기존 story ID → 원래 Then/검증 축 → 실제 명령/요청 → 정제 로그·산출물 SHA256 → 정확한 통합 main SHA → 연결/저장CAS/epoch복구/운영 항목을 하나의 대장에 연결한다. 취소·비활성 CI는 PASS가 아니다. 원본 실패/XML은 덮어쓰지 않는다. 점수는 독립 리뷰·시험·통합 회귀·실제 환경 인수에 맞춰 통합 담당만 갱신한다.

문서 작업 검증: 시작·완료 지침 검사 PASS (각 fail0/warn6), diff 공백·로컬 링크·전체 경로 참조 확인 PASS. 기능 시험·JVM·브라우저·Docker 실행 NOT_RUN.
