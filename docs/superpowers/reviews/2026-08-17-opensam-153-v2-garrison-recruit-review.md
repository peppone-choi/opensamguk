# OPENSAM-153 (v2 R4) — 도시병사 보충: 자기 검토 + 선언 기록

- 대상: `V2GarrisonRecruitRules/Handler`(신규) + wire variant(신규) + 인테이크 컨트롤러(신규) + 경계 수정 4파일 + v2-lab 신규 라우트
- 브랜치: `feat-opensam-153-v2-garrison-recruit` (base = `main`, R3 머지 완료 `d7943ed5`)
- 일자: 2026-08-17

Scope: OPENSAM-153 v2 도시병사 보충 커맨드 — wire variant(common/) · 인테이크 컨트롤러와 매퍼 배선(app/) · 엔진 규칙·핸들러·디스패치(app/) · v2-lab 제출 라우트(web/)
Verdict: cleared

## 0. 선행 조건에서 벗어난 점 — 먼저 적는다

티켓 DoD의 선행 조건에 **OPENSAM-45/46/47**(v2 SSE 계열)이 걸려 있고 셋 다 아직 `할 일`이다.
그럼에도 착수한 근거: R4의 DoD가 실제로 요구하는 결과 회신은 **v1 result-poll 규약**
(OPENSAM-13/135, 이미 main에 있음)이고, v2 SSE는 "결과를 밀어 주는" 상위 개선이지 이 티켓의
성립 조건이 아니다. FE는 `pollCommandResultResponse`로 `RESOLVED`까지 폴링하며, 45/46/47이 붙으면
그 폴링이 푸시로 바뀔 뿐 계약(`ok`/`reason`)은 그대로다. **다만 DoD 문자 그대로의 조건은 미충족이다** —
R3가 §0에 적은 것과 같은 종류의 이탈이며, 여기서 명시적으로 기록한다.

## 1. 무엇을 만들었나

장수가 자기 도시의 v2 원장 금을 써서 같은 행의 `garrison`을 올린다. 판정은 순수 함수
`recruitDecision`(draw 0, 월드 접근 0)이 갖고, `V2GarrisonRecruitHandler`는 월드 조회·소속 검사·
델타 적용만 한다. 인테이크는 v1 분류기를 건드리지 않는 **신규 게이트 컨트롤러**
(`POST /api/v2/garrison-recruit`)이고, FE는 `v2-lab/garrison` 신규 라우트다.

RNG draw 0 — 규칙·핸들러 어디에도 `RandUtil` 파라미터가 없다. 타입이 보장한다.
**로그를 남기지 않는다.** 로그 문자열은 v1 패러티 대상이라 v2가 새 문구를 만들면 로그 게이트의
의미가 흐려진다. 결과는 result-poll로만 회신한다.

## 2. 설계안에 없던 **세 번째 T1 벽** — 발견 사항

설계안 §11은 T1 벽을 둘만 적었다(변형 중첩 선언 / v1 인테이크 분류기). 실제로는 셋이었다:

**`TurnDaemonCommandResultSerializer.selectSerializer`는 닫힌 화이트리스트다**
(`TurnDaemonCommandResult.kt:635-700`). 등록되지 않은 `type` 문자열은 컴파일이 아니라
**flush/직렬화 시점에 `IllegalArgumentException`** 으로 터지고, 엔진 인코드 경로
(`TurnDaemonEvent.CommandResult` 중첩)는 이를 우회할 수 없다. 즉 **신규 결과 타입은 T1 편집 없이는
불가능**하다.

해결: 새 타입을 만들지 않고 `CommandLifecycleResult`를 그대로 쓴다 —
`type = "executionApplied"/"executionRejected"`(둘 다 `COMMAND_LIFECYCLE_TYPES`에 이미 등록,
`:601-607`) + `commandKind = IMMEDIATE` + `actionCode = "v2GarrisonRecruit"`.
`opensamguk.engine.intake.ProfileIconSyncHandler`가 자기 typed-intake 결과에 쓰는 것과 **같은 모양**이다.
**T1 편집 0.**

## 3. U9 종결 — 대안 (a)는 불필요하다

설계안 §11의 U9("`@Serializable` sealed 서브클래스를 원 파일 밖에 두어도 wire 직렬화가 성립하는가")는
추측이 아니라 **테스트로** 닫았다: `common/src/test/.../wire/v2/V2CityGarrisonRecruitWireTest.kt` 3케이스
(왕복 직렬화 / 판별자 = `v2GarrisonRecruit` / v1 variant 동시 왕복) 전부 그린. Kotlin 1.5+ sealed 규칙
(같은 패키지·같은 모듈이면 파일이 달라도 됨)이 그대로 성립하며 `TurnDaemonCommand.kt`(T1)는 열지 않았다.
따라서 설계안이 남긴 대안 (a)(v2 전용 sealed 타입 + 어댑터 분기)는 **쓰지 않는다.**

## 4. 규칙 하나하나의 근거 — 지어낸 숫자가 없다

| 규칙 | 값 | 근거 |
| --- | --- | --- |
| 하한 | 100명 미만 deny | `che_징병.php:107` |
| 상한 | `통솔 × 100` 초과 deny | `che_징병.php:95-96` (v1은 clamp, 우리는 deny — §4-1) |
| 인구 게이트 | `pop - amount >= 30000` | `che_징병.php:118` = `GameConst.minAvailableRecruitPop` |
| 비용 | `phpRound(amount × 0.09)` | 보병 cost 9 / 100명 (`GameUnitConstBase.php:53-54`) |
| 인구 차감 | `pop - amount` | `che_징병.php:209` |
| 치안 차감 | `trust - (amount/pop)×100`, 하한 0 | `che_징병.php:209` (`costOffset=1`, pop = 차감 **전** 인구) |

반올림은 `phpRound`(half-away-from-zero)다. `Math.round`/`kotlin.math.round`는 쓰지 않았고
테스트가 `105 × 0.09 = 9.45 → 9`로 고정한다.

### 4-1. clamp가 아니라 deny인 이유

v1 `che_징병`은 통솔 초과분을 조용히 잘라 낸다. v2 인테이크는 **결과가 사용자에게 문자열로 회신되는**
경로라(result-poll), 조용히 다른 수량을 처리하면 FE가 요청한 값과 실제 값이 갈린다. 사용자가 다시
제출할 수 있도록 사유를 붙여 거절한다. **의도된 divergence이며 v1 경로는 손대지 않았다.**

## 5. 선언된 divergence — 둘

1. **지불 주체가 장수 금이 아니라 도시 원장 금이다.** v1 징병은 장수의 금을 쓴다. v2 R1~R3이 세운
   도시 원장 모델에서 도시병사는 도시의 자산이므로 도시가 낸다. 장수 금은 건드리지 않는다.
2. **기술 계수를 곱하지 않는다.** v1 비용은 국가 기술로 보정되지만, 도시 원장은 국가 기술을 모르고
   R4가 국가 상태를 읽기 시작하면 v2 경계가 넓어진다. 기본 단가만 쓴다.

**상한을 따로 두지 않은 이유**: `garrison` 상한의 근거가 묘섭·PHP 어디에도 없다. 지어내는 대신
**인구 게이트가 실질 상한** 역할을 한다(보충할수록 인구가 줄어 `minAvailableRecruitPop`에서 멈춘다).
근거 없는 상수를 새로 만들지 않는다.

## 6. 내가 잡은 결함 — 서브에이전트 산출물 검토 결과

세 건 다 **내가 직접 고쳤다**(보고를 근거로 삼지 않는다는 규칙 그대로).

1. **`world.updateCity` → `applyCityDirtyFree`.** `updateCity`는 월드의 `dirtyCityIds`를 함께 세워
   ChangeRecorder 말고 **두 번째 dirty 원천**을 만든다(설계 Risk #4: 조용한 flush 분기).
   `PersonnelHandler.applyCity`가 정석이며 그 모양으로 맞췄다.
2. **논리 City를 직접 `copy`하던 diff.** `trust`는 컬럼 diff와 meta jsonb **양쪽**에 실려야 하는데
   논리 City를 copy 하면 meta 의 trust 가 옛 값으로 남아 두 값이 갈린다. 엔진 `City`를 먼저 만들고
   양쪽을 `toLogicCity`로 변환해 diff 하도록 고쳤고, 새 테스트가 컬럼·meta 동일값을 고정한다.
3. **T1 동결 테스트 수정.** 매퍼 케이스가 v1 `CommandWireMapperTest.kt`에 추가돼 격리 게이트 ②가
   VIOLATION 을 냈다. 되돌리고 `reserve/v2/V2CommandWireMapperTest.kt`(신규, `/v2/` 제외 대상)로
   옮겼다 — 게이트 재실행 PASS.

4. **KDoc 안의 `*/`.** 옮겨 적은 게이트 설명에 glob `**/v2/**` 를 그대로 썼는데 그 안의 `*/` 가
   주석을 끝내 `compileTestKotlin` 이 깨졌다. 처음엔 **stale XML 때문에 초록으로 보였다** — 그래서
   §10의 수치는 전부 재실행 후의 것이고, 새 클래스가 XML에 실제로 존재하는지도 파일명으로 확인했다.

또한 `CityGarrisonRecruitCommand.kt`의 KDoc이 `V2NamingConventionGuardTest`의 소스 텍스트 스캔에
걸리는 선언 패턴을 문자 그대로 포함하고 있어(그 가드는 주석을 구분하지 못한다고 스스로 적어 둔다)
문구를 바꿨다.

## 7. 명명 가드와 sealed 패키지 규칙의 정면 충돌 — 리뷰 사안으로 남긴다

`V2NamingConventionGuardTest`는 `V2`로 시작하는 클래스가 `opensamguk.*.v2.*` 안에 있을 것을 요구한다.
그러나 sealed 서브클래스는 **부모와 같은 패키지**(`opensamguk.common.wire`)여야 하므로 `.v2` 로 옮길 수
없다. 그래서 접두사를 떼고 `CityGarrisonRecruit`로 두었다. v2 소속은 (a) 파일 KDoc, (b)
`@SerialName("v2GarrisonRecruit")`, (c) 파일명으로 드러낸다. 이는 그 가드가 스스로 적어 둔 한계
("`V2`로 시작하지 않는 v2 코드는 리뷰 사안") 에 해당하며, **여기가 그 리뷰다.**

## 8. 게이트 ③ — 티켓 사전선언 대비 **초과 2파일**

게이트 ③ 목록 4파일 중 티켓이 선언한 것은 2개(`CommandWireMapper.kt`,
`TurnDaemonCommandDispatcher.kt`)이고, 다음 **2파일이 초과**다:

- `app/game-engine/.../run/TurnRunService.kt` — 디스패처 생성자에 `v2CityLedger` 전달
- `app/game-engine/.../config/DaemonLoopConfig.kt` — 이미 존재하던 `ObjectProvider<V2CityLedgerStore>`(:215)를 `TurnRunService(`에 전달

**왜 불가피한가**: 디스패처는 턴 실행마다 새로 만들어지는 일반 객체이고 Spring 스코프의 원장 빈을
스스로 조회할 수 없다. 생성 체인(`DaemonLoopConfig → TurnRunService → TurnDaemonCommandDispatcher`)을
따라 내려보내는 것 말고는 경로가 없다. 두 편집 모두 **파라미터 한 줄 추가**이며 기본값 `null` 이라
기존 호출부·기본 동작은 불변이다(원장이 없으면 fail-closed deny). 초과이므로 여기에 크게 적는다.

## 9. 유실 방지 — `null` 이 아니라 명시적 deny

디스패처 분기는 원장 빈이 없을 때 `null` 이 아니라 `V2GarrisonRecruitHandler.unavailable(...)`을
돌려준다. `dispatch`가 `null`을 반환하면 결과 행이 만들어지지 않아 FE 폴링이 20회(≈6초) 후 그대로
끝나고 **요청이 조용히 사라진다**(202만 보고 성공 토스트를 띄우는 것과 같은 종류의 위조).
`V2GarrisonRecruitDispatchTest`가 이 경로를 고정한다.

## 10. 검증 증거

- `:common:test` + `:app:game-engine:test --rerun-tasks` → XML 집계 **tests 1129 / failures 0 / errors 0 / skipped 1**
  (skip 1건은 기존 `LongSimReplayGateTest` 12개월 골든 — P5 백로그, 이 티켓과 무관)
- `:app:game-api:test --rerun-tasks` → **tests 512 / failures 0 / errors 0**
  (신규 `TEST-opensamguk.gameapi.reserve.v2.V2CommandWireMapperTest.xml` = tests 2 / failures 0 존재 확인)
- `scripts/agent/v2-isolation-gate.sh` → ② PASS · ⑤ PASS · C1 PASS (③ 목록은 §8에서 대조)
- `web/game`: `pnpm lint` 신규 경고 0, `pnpm build` 성공 — 라우트 `/game/v2-lab/garrison` 생성 확인
- v1 골든/RNG/로그: **건드린 파일 0** — 게이트 ②가 증명한다
