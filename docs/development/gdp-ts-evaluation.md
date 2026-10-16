# gdp-ts 적용 평가 (PR-G0)

> 상태: 평가 문서. 패키지 설치·코드 변경 없음
> 기준: `peppone-choi/opensamguk` main `fad208cdcabaf3b6b9c6f4a8e6a49824ca619a18`
> 마지막 검토: 2026-10-16

## 결론

- **TS 계층(`web/gateway`, `web/game`)에 `@gdp-ts/core`를 설치하지 않는다(NO-GO).** TS 계층은 DB 없이 요청을 JVM API로 넘기는 BFF(화면용 중계 서버)다. 증명을 만들 신뢰 근거가 TS 안에 없다.
- JVM(gateway-api, game-api, game-engine)에는 npm 패키지를 쓰지 않는다. 원리만 대응시킨 **후보 설계**를 아래에 남긴다. 실제 JVM 리팩터링은 별도 이슈와 사용자 우선순위 결정 전에는 시작하지 않는다(현재 미승인).
- 예약 명령은 **실행 시점에 권한을 다시 판정**하는 현재 구조를 유지한다. 접수 때 얻은 증명을 저장해 실행 때 재사용하지 않는다.

## 검토한 원문

| 항목 | 값 |
|---|---|
| 저장소 | https://github.com/rauchg/gdp-ts |
| 고정 revision | `0b6ebeeec827b05210f3acfa4149a70dda9a0de5` (2026-10-07) |
| npm | `@gdp-ts/core@0.1.0` |
| 라이선스 | MIT, Copyright (c) 2026 Guillermo Rauch |
| 읽은 파일 | `skills/gdp-ts/SKILL.md`, `skills/gdp-ts/references/recipe.md`, `skills/gdp-ts/references/limits.md` |
| peer 요구 | TypeScript >= 5.4, ESLint >= 9 또는 oxlint >= 1.86 (lint 사용 시) |

원문은 자료로만 읽었다. 패키지 설치, 예제 실행, 원문 스킬의 저장소 반입은 하지 않았다.

## gdp-ts가 요구하는 것

recipe의 핵심 규칙은 다음과 같다.

1. ID에 브랜드 타입을 붙인다(`ProjectId`와 `Host`를 섞어 넘기지 못하게 한다).
2. 사실 하나마다 신뢰 모듈(`proofs/`) 하나를 두고, 그 모듈만 증명을 만든다. 신뢰 모듈은 **실제 자원(DB 등)과 대조해서** 증명을 만든다.
3. 민감한 함수는 증명을 인자로 요구한다. 증명이 없거나 다른 값에 대한 증명이면 컴파일되지 않는다.
4. 핸들러에서 값을 `name(...)`으로 묶고 증명을 얻은 뒤, `null`이면 401/403으로 바꾼다.
5. "Client-supplied values (cookies, tokens, ids in a body) prove nothing until a trusted module checks them against the resource." (recipe §2)
6. 증명은 확인한 순간의 사실이다. 요청 범위 안에서만 쓰고, 경합이 중요하면 트랜잭션이나 제약을 쓴다(limits.md "Stale proofs").

## 이 저장소의 TS 계층 실측 (기준 커밋)

| 항목 | 실측 |
|---|---|
| `route.ts` | 27개(`web/gateway/app/api` 22, `web/game/app/api` 2, `web/gateway/app/hwiha` 2, `web/gateway/app/profile-icons` 1) |
| `middleware.ts` | 2개(`web/gateway`, `web/game`) |
| Server Action(`'use server'`) | 0개 |
| DB·캐시 클라이언트 import(`pg`, `postgres`, `mysql2`, `@prisma/client`, `drizzle-orm`, `kysely`, `typeorm`, `mongodb`, `redis`, `ioredis`) | 0개 |

예:

- `web/gateway/app/api/account/password/route.ts`는 쿠키의 access 토큰을 Bearer로 붙여 `${GATEWAY_API_URL}/auth/account/password`로 넘긴다.
- `web/gateway/lib/auth.ts`의 `requireAdmin`은 화면 표시용 확인이다. 실제 권한은 JVM이 판단한다(`docs/admin/README.md`: 실제 권한은 Gateway의 `ROLE_ADMIN`, game-api의 인증 주체, 장수 소유권·직책과 각 API의 서버 검사가 결정).

## TS에 쓰지 않는 이유

- TS에서 `defineProof`로 증명을 만들려면 근거가 필요하다. TS에 있는 근거는 쿠키 토큰과 JVM 응답뿐이다. 이것으로 증명을 만들면 recipe §2가 금지하는 "클라이언트 값을 그대로 믿는 증명"이 된다.
- 민감한 동작(DB 쓰기, 권한 판단)은 모두 JVM에서 일어난다. TS에 증명 타입을 붙여도 실제로 막는 경로가 없고, 검사했다는 착각만 생긴다.
- 의존성 추가와 lint 버전 충돌만 늘어난다(아래 "버전 충돌").

## JVM 후보 설계 (미승인, 참고용)

원리만 Kotlin으로 옮긴 모습이다. 구현하려면 별도 이슈와 승인이 필요하다.

| gdp-ts 개념 | Kotlin 후보 |
|---|---|
| 브랜드 ID | `@JvmInline value class GeneralId(val value: Long)` 등 |
| 증명 | 권한 모듈 안에서만 만들 수 있는 `sealed` 타입(생성자 `internal` 또는 `private`) |
| 정책(증명의 합집합) | `sealed interface`의 하위 타입들, `when`으로 승인 사유 기록 |
| 민감한 함수 | 저장소·서비스 함수가 증명 인자를 요구 |
| 증명 생성 시점 | 요청 처리 중 DB·월드 상태와 대조한 직후 |

지켜야 할 경계:

- **예약 명령은 실행 때 다시 판정한다.** 제품 규칙인 휘하(`.ai/decisions.md` ADR-LITE-065) 예약 입력(`CommandReserveService.HWIHA_RESERVABLE_ACTIONS`)은 접수 때 game-api `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt`가 호출자 본인 장수인지 확인한 뒤 `CommandPrecheckService`를 거치지 않고 `gameapi/reserve/CommandReserveService.kt`의 `reserveInternal`로 보낸다. 여기서 입장 검사(`inputCatalog.rejectionFor`, 장수 소유·구금을 보는 `CaptiveAdmission.requireFreeActor`)를 거친 뒤, 입력 대부분은 입력별 admission(`gameapi/reserve/*Admission.kt`의 `canonicalArguments`)이 소유권과 입력별 규칙·월드 상태도 판정한다. 예를 들어 `FieldAdmission`은 장수 소유권과 `FieldRules.assess`를 보므로, 남의 縣에서 농지를 예약하면 접수 때 `FOREIGN_COUNTY`로 거부된다. 보루 점령(`action.siegeRoadFort`)·항복 권고(`action.demandSurrender`)는 별도 admission 없이 같은 함수 안에서 인증·예약 순·인자 형식을 확인한다. 실행 때는 엔진 `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt`의 휘하 분기가 `InputRegistry`로 입력별 handler(예: `engine/campaign/FieldHandler.kt`의 예약 장수 소유권과 `FieldRules.assess` 재확인)를 호출해 실시간 월드와 다시 판정하고, 거부되면 휴식으로 대체하지 않고 장수 기록 `INPUT_REJECTED`(`inputId`·`code`)를 남긴다. 레거시(비휘하, ADR-LITE-066 은퇴 대상) 명령의 단건 예약(`POST /api/command/{code}`)은 접수 때 `gameapi/precheck/CommandPrecheckService.kt`로 `:logic` 제약을 `ConstraintMode.PRECHECK`로 평가하고, 실행 때 `ReservedTurnHandler`가 같은 `:logic` 제약을 `ConstraintMode.FULL`로 다시 평가해 거부되면 휴식으로 대체하며 사유를 남긴다. 예약 큐 조작 `/api/command/bulk`·`push`·`repeat`(장수·국가)는 `CommandPrecheckService`를 거치지 않는다. 두 경로 모두 결과는 `engine/run/TurnRunService.kt`가 `executionApplied`/`executionRejected`(commandKind `RESERVED_TURN`, 휘하 거부는 `code` 포함)로 기록한다. 예약 장수 턴은 `general_turn` 링에 있고 `TurnDaemonCommandDispatcher.kt`의 명령 스트림을 타지 않는다. `logic/src/main/kotlin/opensamguk/logic/command/CommandSchemaCatalog.kt`의 `precheck`는 sandbox v2 명령 2종(`city.garrison.recruit`·`city.resources.transport`)의 인자 형식만 검사하고 직책·소유권·월드 상태는 보지 않으므로 권한 재판정 근거가 아니다. 접수 때 얻은 증명을 저장해 턴 실행에 재사용하면, 그 사이 바뀐 직책·소유권·월드 상태를 놓친다(limits.md "Stale proofs"). 증명 도입 시에도 휘하 handler와 레거시 `FULL` 평가의 실행 시점 재판정을 없애지 않는다.
- 기존 회귀 시험을 유지한다. 예: 휘하 `app/game-engine/src/test/kotlin/opensamguk/engine/campaign/FieldHandlerTest.kt`(`changed ownership rejects at execution with no cost`: 縣 소속이 바뀌면 실행 때 `FOREIGN_COUNTY`로 거부되고 비용이 없음. 장수 소유권 `FORBIDDEN` 재확인 시험은 아님), `app/game-engine/src/test/kotlin/opensamguk/engine/turn/ReservedTurnHandlerTest.kt`(휘하 입력 채널 거부 시 `INPUT_REJECTED` 기록, 레거시 제약 거부 시 휴식 대체·사유 기록), `app/game-api/src/test/kotlin/opensamguk/gameapi/reserve/ReservationCancelIT.kt`, `logic/src/test/kotlin/opensamguk/logic/command/CommandSchemaCatalogTest.kt`(v2 인자 형식).
- JVM 적용 PR에는 실제 턴 실행 재검증이 필요하다. 단위 시험만으로 완료 처리하지 않는다. 조건(휘하 입력 기준, 레거시 명령만으로는 충족하지 않음): 같은 휘하 입력을 접수 후 실행 전에 권한을 잃게 만들었을 때 `executionRejected`(`code` 포함)와 `INPUT_REJECTED` 기록이 나는 IT 1건, 정상 경로 `executionApplied` 1건, 기존 예약 IT 전부 통과.

## 버전 충돌

gdp-ts lint는 oxlint >= 1.86을 요구한다. anti-slop 시범(PR-A1 계획)은 oxlint `1.78.0`에 고정한다. 둘을 함께 쓰려면 oxlint 버전을 함께 올리는 별도 PR이 필요하다. 이 문서는 어느 쪽도 바꾸지 않는다.

## 하지 않은 것

- `@gdp-ts/core` 설치, 예제·시험 실행: 하지 않음(NOT_RUN, 이 결론에서는 불필요).
- gdp-ts 스킬의 저장소 반입: 하지 않음. ADR-LITE-047(Status: proposed, 삭제는 저장소에 반영됨)에 따라 이 저장소는 에이전트 하네스(`.agents/skills`, `skills-lock.json` 등)를 두지 않는다.
- JVM 코드 변경: 하지 않음(미승인).

## 다시 검토할 조건

- TS 계층이 DB나 권한 원천을 직접 가지게 되는 경우.
- JVM 권한 증명 리팩터링이 이슈로 승인되는 경우(위 후보 설계와 경계를 출발점으로 한다).
