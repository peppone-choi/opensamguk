# OPENSAM-197 — 명령 결과 조회 소유권 검사

Scope: 명령 결과 조회 소유권 검사 — infra/ (V41 `command_inbox.owner_user_id` + `CommandInboxRepository.findRequestOwner`)와 app/ (game-api `CommandController.ownsRequest` + `CommandReserveService` 제출자 기록 + 계약 테스트).

게임 로직·RNG·로그·골든·플러시 경로는 건드리지 않는다.

바뀐 파일 (전부 백엔드 — `app/`, `infra/`):

- `infra/src/main/resources/db/migration/V41__command_inbox_owner_user.sql` (신규)
- `infra/src/main/kotlin/opensamguk/infra/persistence/CommandInboxRepository.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/web/CommandResultLookupTest.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/web/CommandControllerSecurityTest.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/web/CommandControllerHtmlSanitizerTest.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/reserve/CommandReserveServiceIT.kt`

## 1. 무엇이 문제였나

OPENSAM-45(명령 결과 push 신호)를 만들면서 발견한 결함이다. 원래 계획은 `commandSettled` 이벤트에
`requestId`를 실어 보내는 것이었는데, 실을 수 없다는 걸 코드로 확인했다:

- `CommandController.commandResult(@PathVariable requestId)` — 인증 주체 파라미터가 아예 없었다.
- `GameApiSecurityConfig`는 `/api/command/**`를 `permitAll`로 둔다.
- SSE 릴레이(`RealtimeRelayController.fanOut`)는 수신자별 필터링이 없다 — 월드 채널의 payload는
  접속한 **모든** 브라우저에 도달한다.

즉 `requestId`를 아는 사람이 곧 그 명령의 성공 여부·deny 사유·결과 payload를 읽는 사람이었다.
보안이 값의 비밀성 하나에만 기대고 있었고, requestId는 발급 주체(FE)와 릴레이 양쪽에 흐른다.
OPENSAM-45에서는 신호에서 식별자를 **삭제**해 유출 경로를 막았고(작은 수정), 엔드포인트 자체의
결함은 이 티켓으로 분리했다.

## 2. 무엇을 했나

1. **V41 마이그레이션** — `command_inbox.owner_user_id integer` (NULL 허용).
2. **`findRequestOwner(worldId, requestId) → RequestOwner(generalId, ownerUserId)`** — 인테이크가 202
   **이전에** 기록한 행에서 제출자를 읽는다(S4 durable 명령 경로의 선기록을 그대로 활용).
3. **`ownsRequest(userId, requestId)`** — 두 증인 중 하나면 통과:
   - `owner_user_id == 인증 주체` (장수선택처럼 아직 소유하지 않은 장수로 내는 명령의 유일한 증거), 또는
   - `general_id == resolver.resolveGeneralId(주체)` (일반 명령. `reserve()`는 owner를 남기지 않는다).
   확인 불가 = 거절: 비인증 호출, 인테이크 행 없음, 두 증인 모두 불일치.

### 티켓 문구와의 의도적 차이 — 404가 아니라 PENDING

티켓 본문은 "아니면 404(존재 여부도 흘리지 않는다)"라고 썼다. 구현은 **거절도 미처리와 똑같은
`200 {status:"PENDING", requestId}`** 로 답한다. 이유는 두 가지다.

- 이 엔드포인트의 문서화된 계약이 "항상 200 — 폴링 채널이므로 404를 쓰지 않는다"이다. 404를 새로
  도입하면 FE 폴링 루프(`pollCommandResultResponse`)가 이를 종료 조건으로 오해할 수 있다.
- 404는 오히려 **존재 여부를 흘린다**. 미처리(=PENDING)와 남의 것(=404)이 다르게 답하면 남의
  requestId를 넣어 보며 유효성을 떠볼 수 있다. 응답이 동일해야 아무것도 새지 않는다.

정보 은닉 목표는 더 강하게 달성된다(구분 불가), 다만 티켓 문구와 상태 코드가 다르므로 여기 기록한다.

## 3. 증거

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test :app:game-api:test --rerun-tasks`
→ BUILD SUCCESSFUL. XML 집계: infra 239 / game-api 526, **failures 0 / errors 0 / skipped 0**
(skipped 0 = Docker가 있어 IT도 실제로 돌았다 — `CommandReserveServiceIT` 포함).

새 계약 테스트(`CommandResultLookupTest`):

| 테스트 | 세우는 계약 |
| --- | --- |
| 남의 requestId는 결과가 준비돼 있어도 PENDING과 구분되지 않는다 | RESOLVED 페이로드가 실재해도 `ok`/`result` 미노출 |
| 인증이 없으면 결과를 읽지 못한다 | permitAll 경로의 비인증 호출 거절 |
| 아직 소유하지 않은 장수에 낸 명령도 제출 계정이면 읽는다 | 장수선택 회귀 방지(`owner_user_id` 증인) |
| 마이그레이션 이전 행은 자기 장수의 명령일 때만 읽는다 | `owner_user_id IS NULL` 행의 general_id 폴백 |
| 인테이크 기록이 없는 requestId는 읽지 못한다 | 원장에 없는 값은 거절 |

기존 10개 계약 테스트는 **약화하지 않고** "제출한 본인이 읽는" 경우로 조여, 인증 주체와 소유자
행을 함께 세운 뒤 읽도록 바꿨다. IT에는 `findRequestOwner` 왕복 단언을 더했다.

**비어 있지 않음 증명(non-vacuity).** `ownsRequest` 게이트 한 줄을 지우고 재실행하니 정확히 거절
계열 3개만 FAILED, 나머지 12개는 그대로 green. 게이트가 실제로 이 단언들을 지탱한다.

## 4. 스스로 공격해 본 것

- **일반 명령이 못 읽게 되나?** `reserve()`는 `owner_user_id`를 남기지 않는다(NULL). 그러나 POST 경로가
  이미 인증 시 `generalId == 본인 장수`를 강제하므로 general_id 증인이 항상 성립한다. IT가 이 조합
  (generalId=10, owner NULL)을 실제 DB로 확인한다.
- **비인증 제출자는?** F2 전환기 폴백(비인증 POST)으로 낸 명령은 이제 결과를 못 읽는다. web/game은
  프록시가 Bearer를 붙이므로 실사용 경로에는 영향이 없다. **알려진 축소이며 의도한 것이다** —
  비인증 제출의 결과를 읽히려면 애초에 누구의 것인지 확인할 방법이 없다.
- **마이그레이션 이전 행은?** owner NULL + general_id 폴백으로 정상 동작한다. 폴링 창은 제출 직후
  6초라 배포 시점에 살아 있는 행 자체가 사실상 없다.
- **fingerprint를 건드렸나?** 아니다. `intentFingerprint`의 입력은 그대로다(멱등/중복 제출 판정 불변).
- **daemon-write 규칙?** 위반 없음 — game-api의 읽기 한 건(SELECT)과 인테이크 INSERT 열 추가뿐이다.

## 5. CodeRabbit이 잡은 실제 결함 — `publishImmediate` 경로 (2차 커밋)

첫 커밋은 `CommandReserveService.publishImmediate`를 놓쳤다. 이 경로는 `general_id`도 `owner_user_id`도
남기지 않으므로, 새 소유권 검사 아래에서는 그 `requestId`가 **영원히 PENDING**이 된다. 즉 보안 수정이
기능 회귀를 만들 뻔했다. 호출자를 모두 확인했다(테스트 제외 실코드):

| 호출자 | 결과 폴링 | 조치 |
| --- | --- | --- |
| `SelectPoolController.refresh` | 예 | `ownerUserId = 주체` |
| `NpcPolicyController.updateNpcPolicy` | 예 | `ownerUserId = 주체` |
| `DiplomaticMessageController.accept/decline` | 예 | `ownerUserId = 주체` |
| `PossessionController.claim` | 예(응답의 requestId) | `ownerUserId = 주체` — claim 대상 장수는 아직 남의 것이라 general_id 증인이 성립하지 않는다 |
| `JoinController.join` | 예 | `ownerUserId = 주체` — 장수를 **생성**하는 명령이라 소유 행이 아직 없다 |
| `ProfileIconSyncController.sync` | 아니오(M2M 토큰 경로, 사용자 주체 없음) | null 유지 |
| `AdminWriteController` / `AdminGeneralModerationService` | 아니오(어드민 FE에 requestId 소비 없음 — `web/game/app/admin` grep 0건) | null 유지 |

기본인자 대신 **오버로드**(`publishImmediate(command)` → `publishImmediate(command, null)`)로 넣었다.
Kotlin 기본인자는 Mockito 스텁의 매처 개수를 어긋나게 해 무관한 테스트(Admin·ProfileIconSync 등)까지
깨뜨린다 — 오버로드면 1-인자 스텁이 그대로 유효하다. 소유자를 넘기게 된 5개 호출자의 테스트는
`eq(주체)`를 요구하도록 **조였다**(약화 아님). fingerprint 입력은 건드리지 않았다.

## 6. 남긴 것

- `permitAll` 자체는 그대로다. 이 티켓은 엔드포인트 내부의 소유권 검사만 닫는다 — 시큐리티 설정을
  같이 바꾸면 회귀 범위가 커지고, 검사가 있는 한 결과는 새지 않는다.
- `CommandReserveServiceIT`의 손DDL은 Flyway 스키마의 수기 복제다. V41을 여기 손으로 옮겼고, 그
  사실을 주석으로 남겼다(다음 마이그레이션도 같은 손질이 필요하다).

Verdict: cleared
