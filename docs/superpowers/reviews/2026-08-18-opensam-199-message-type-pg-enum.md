# OPENSAM-199 — 서신함 조회가 항상 500 (PG enum 바인딩)

Scope: `infra/src/main/kotlin/opensamguk/infra/entity/MessageEntity.kt` 의 `message.type` JPA 매핑 교체 + 신규 `PostgresValueEnumJdbcType`
Verdict: cleared

## 무엇을 고쳤나

라이브 실측(2026-08-18). 로그인 후 서신함이 항상 비어 있었고, API 를 직접 치면 500 이었다.

```
GET /api/game/api/mailbox/recent?sequence=0
→ 500 {"status":500,"error":"Internal Server Error","path":"/api/mailbox/recent"}

game-api 로그:
SQL Error: 0, SQLState: 42883
ERROR: operator does not exist: message_type = character varying
select ... from message me1_0
where me1_0.world_id=? and me1_0.mailbox=? and me1_0.type=? and me1_0.valid_until>?
```

DB 에는 행이 정상적으로 있다(`select * from message` 3행 실측). **읽기 경로만** 죽는다.

### 두 가지가 동시에 틀렸다

1. **타입** — `message.type` 은 Postgres ENUM `message_type` 인데 `@Enumerated` 는
   varchar 로 바인딩한다. PG 에 `message_type = varchar` 연산자가 없어 42883.
2. **값** — `@Enumerated(STRING)` 은 `.name`(`PRIVATE`)을 쓰는데 DB enum 라벨은
   소문자 `.value`(`private`)다. 타입만 고쳐도 값이 안 맞는다.

쓰기 경로(`JdbcFlushExecutor.messageCreateMany`)는 `CAST(:type AS message_type)` +
`.value` 로 이미 옳게 처리하고 있었다. 그래서 **저장은 되고 조회만 죽는** 비대칭이
생겼고, 왕복 단위 테스트로는 드러나지 않았다.

### 수정 — 기성품을 먼저 다 시도하고, 안 되는 것을 확인한 뒤 최소 커스텀

값 변환은 `AttributeConverter`(`.value`)로 처리한다 — `AuctionEntity` 의 두 converter 와
동일한 형태이며 새 패턴이 아니다.

타입 바인딩은 기성 수단이 **셋 다 안 됐다**. 라이브에서 하나씩 확인한 결과다.

1. **`columnDefinition = "message_type"`** — Hibernate 가 DDL 에만 쓴다. 바인딩은 그대로
   varchar 라 42883 이 재현됐다(재빌드 후 실측 500).
2. **`@JdbcTypeCode(SqlTypes.OTHER)`(`ObjectJdbcType`)** — String 을 `[B`(byte[])로 unwrap
   하려다 죽는다. 실측: `Could not convert 'java.lang.String' to '[B' using
   'StringJavaType' to unwrap`.
3. **`SqlTypes.NAMED_ENUM`(`PostgreSQLEnumJdbcType`)** — 소스 확인 결과
   `st.setObject(index, ((Enum<?>) value).name(), Types.OTHER)` 로 **`.name()`(대문자)** 만
   보낸다(hibernate-core 6.6.11, `PostgreSQLEnumJdbcType.java:99`). DB 라벨은 소문자라
   값이 안 맞는다.

그래서 하는 일이 한 가지뿐인 최소 JdbcType 을 새로 뒀다 — converter 가 만든 **문자열을
`Types.OTHER` 로 보내** PG 가 대상 ENUM 으로 추론하게 한다. 쓰기 경로의
`CAST(:type AS message_type)` 와 대칭이다.

```kotlin
@Convert(converter = MessageTypeValueConverter::class)
@JdbcType(PostgresValueEnumJdbcType::class)
@Column(name = "type", nullable = false, columnDefinition = "message_type")
var type: MessageType,
```

`PostgresValueEnumJdbcType` 은 `.value` 라벨을 쓰는 다른 PG ENUM 에도 그대로 쓸 수 있다
(`ng_auction_type` 의 `buyRice` 등). 이번 PR 에서는 `message.type` 에만 붙였다 — 아래
"남은 것" 참고.

## 범위 (전수 확인)

DB 의 enum 컬럼은 6개다.

```
diplomacy_letter.state :: diplomacy_letter_state
log_entry.scope        :: log_scope
log_entry.category     :: log_category
message.type           :: message_type
ng_auction.type        :: ng_auction_type
ng_auction.req_resource:: ng_auction_resource
```

이 중 JPA 엔티티에서 `@Enumerated` 를 쓰던 것은 `MessageEntity` **하나뿐**이다.
`ng_auction` 은 이미 converter 규약이고, 나머지 셋은 JPA 엔티티 매핑 자체가 없다
(JDBC 경로만 접근). 따라서 같은 결함의 형제 사이트는 없다.

## 증거

### 라이브 판정 (수정 전 → 후)

```
# 수정 전
GET /api/game/api/mailbox/recent?sequence=0 → 500
ERROR: operator does not exist: message_type = character varying (42883)

# 수정 후
GET /api/game/api/mailbox/recent?sequence=0 → 200
{"result":true,"private":[{"id":3,...,"text":"e2e-mailbox-delete-1787026971540-148463",
 "time":"2026-08-18 13:22:51"}],"public":[],"national":[],"diplomacy":[],
 "sequence":3,"nationID":0,"generalName":"op17870269550784",...}
```

### 라이브 E2E

```
pnpm exec playwright test e2e/mailbox-delete-live.spec.ts
[1/2] canceling then confirming mailbox deletion, the terminal result reloads without that row
  1 passed (13.2s)
  1 skipped
```

통과한 시나리오는 OPENSAM-198 이 망가뜨렸던 삭제 성공 경로 전체다 — 로그인 → 서신 전송
(intake 202 → `command/result` RESOLVED 폴링) → 목록 렌더 → 취소 → 확인 → terminal 결과로
행 소멸까지.

### 백엔드 회귀

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --rerun-tasks
BUILD SUCCESSFUL in 15m 27s
files=58 tests=239 failures=0 errors=0 skipped=0
```

## 채점대기 — deny 시나리오

`e2e/mailbox-delete-live.spec.ts:333` 의 deny 케이스는 **채점대기**다. 통과 처리하지 않았다.

이 스펙은 `E2E_MAILBOX_DENIAL_TEXT`/`_REASON`/`_SCOPE` 로 지정한 픽스처가
`:362` 에서 **프론트엔드에서 삭제 가능해 보여야** 한다고 요구한다
(`configured denial fixture must be frontend-deletable`).

시도한 픽스처(자기 서신의 `time` 을 10분 과거로 옮겨 서버 5분 게이트를 걸리게 하는 것)는
부적합하다 — 프론트가 `isMessageDeletable` 로 같은 5분 규칙을 적용해 삭제 버튼 자체를
숨기므로 서버 deny 경로에 도달하지 못한다(실측 실패). 이 스펙이 요구하는 것은
"프론트에서는 삭제 가능해 보이지만 서버가 거부하는" 픽스처이며, 그런 상태를 만들려면
별도 준비(권한 경계 등)가 필요하다. 시도에 쓴 픽스처 행은 삭제해 원복했다.

## 남은 것

- `AuctionEntity` 의 `ng_auction_type`/`ng_auction_resource` 는 `columnDefinition` 만 있고
  같은 JdbcType 이 없다 — **같은 결함일 가능성이 있으나 이번에 실측하지 않았다.**
  `where type = ?` 로 필터하는 읽기 쿼리가 생기면 같은 42883 이 난다. UNKNOWN 으로 남긴다.
- deny 시나리오 픽스처(위 채점대기).

## 발견 경로

OPENSAM-198 수정 후 라이브 E2E 재주행이 다음 단계로 넘어가며 드러났다.
`mailbox-delete-live.spec.ts` 가 연달아 두 번째로 잡아낸 실동작 결함이다.
