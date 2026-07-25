# round-3 설계안 개정 4차 — 처리 기록

> 일시: 2026-07-25 · 입력: `REVIEW-round3-r3.md`(9/10 `fix-required`) · 대상: `round3-proposal-city-guanxi.md` 제자리 수정 (1230 → 1320줄, 새 파일 0[본 기록 제외], 미커밋, 코드 무수정, `docs/wiki/raw/**` 무수정)
> 상태: **재채점 대기** (동일 시험지 + 독립 reviewer, 4번째)

## 항목별 처리

| 항목 | 처리 | 위치 |
|---|---|---|
| **MAJOR-1** T2 목록 불완전 (유일한 N, 문항 7) | R1~R6 **전 티켓**에 확장점→구현자 추적을 돌려 §7.1-2를 **전면 재작성**. 6+1 → **11 편집 + 신규 마이그레이션 1**(12행 표). 지목된 `WorldActionContext.kt` 한 줄이 아니라 **추적 결과 전체**를 실었다 | §7.1-2 `#### 개정 4차`(`:968`~`:1028`) |
| **MAJOR-1** 절차 일반화 | 자기채점 절에 **개정 4차 규율** 신설 — "확장점을 이름으로 적었으면 그 확장점의 프로덕션 구현자·소비자까지 grep으로 세어 파일명으로 적는다" + 적용 절차 3단계(①인터페이스 grep ②레지스트리/`when` 양방향 ③센 수를 숫자로 적는다). 1차·3차·4차 **연속 3회** 같은 실패였음을 명시 | 자기채점 서두(`:1292`) |
| **MAJOR-2** R3‖R2 병렬 | **철회 → 순차(생산자 R2 → 소비자 R3).** 근거 두 개를 파일 단위로 제시: (i) v2 시나리오 JSON의 1·7월 `event` 행이 완전 중복, (ii) `WorldActionContext.kt`가 두 티켓의 컨텍스트 구현자로 동일. 여기에 등록 순서 의존(`EngineEventConfig.kt:79-81` 체인이 R2 산출물, 미등록 이름은 `EventAction.kt:70-74`가 예외) | §9.2 R3 행 + 개정 4차 블록(`:1113`, `:1120`~`:1126`) |
| **MAJOR-2** 부작용 | R2가 최대 티켓이 됐음을 명시하고 **분해 시 20 → 21 가능**을 적되 "동일 산출물의 분해이지 범위 추가가 아니다"로 한정. **권고 수량은 20 단일값 유지** | §9.2(`:1126`), 자기채점 9행 |
| **MINOR-1** emergent 수량 | §4.5 실측(`:572` 표 — MENTOR·COMRADE·RIVAL·GRUDGE 4종이 `OPENSAM-56`·`61`에, RESCUE만 오픈 후)에 맞춰 **"5종 중 4종"으로 통일**. 고친 곳 3군데(`:1184` · `:1196` · 자기채점 9행). 채점자가 함께 지목한 `:17`은 애초에 수량 표현이 아니었다(관계 사건이 6·7번 티켓에 붙는다는 서술) — 고칠 것이 없어 그대로 뒀다 | §9.4 ×2 + 자기채점 9행 |
| **MINOR-2** `ScenarioImporter` "항상" | 제거하고 `ignoreDefaultEvents` 가드(`:807-818`)를 **두 분기 표**로 재작성. `false`(기본값 `ScenarioJson.kt:299`) = `DEFAULT_EVENTS` 12행 강제 INSERT → v2 행을 덧붙이면 **이중 수입**이고 지울 seam이 없음 / `true` = 시나리오 JSON이 행 집합 전체 저작. **판정: v2는 `true`**(선례 `scenario_910.json:16`), 대가는 12행 전사 = 데이터 이중 진실 1건 | §7.1-2 항목 2(`:938`~`:963`) |
| **MINOR-3** 오프바이원 | `CheJeungchuk.kt:35-37` → **`:36-38`**(파일 확인: `:36` KDoc, `:37` `fun getCost`, `:38` 본문). `UpdateNationLevel`은 **반박**(아래) | §2.3(`:211`, `:221`) |

## 저자 반박 (코드 근거 첨부)

**MINOR-3의 절반은 채점 지적이 틀렸다.** 채점자는 `UpdateNationLevel.kt:145-146` → `:146-147`을 요구했으나 파일 실측은:

```
:143  val updatedNation = nation.copy(
:144      level = newLevel,
:145      gold = nation.gold + grant,
:146      rice = nation.rice + grant,
:147      meta = updatedMeta,
```

`:147`은 `meta`이지 자원이 아니다. **원문 `:145-146`이 옳다.** 문서 본문에 반박을 근거와 함께 남겼다(`:211`). 같은 지적의 다른 절반(`CheJeungchuk`)은 유효해서 정정했다 — 채점 항목을 통째로 수용/기각하지 않고 줄 단위로 갈랐다.

## MAJOR-1 추적 결과 — 티켓별 확장점 → 구현자 → T2 파일

| 티켓 | 필요한 확장점 | 프로덕션 구현자/소비자 (grep 실측) | T2에 새로 들어온 파일 |
|---|---|---|---|
| R1 | v2 원장 **읽기** 진리 | `InMemoryTurnWorld`는 `WorldSnapshot`으로만 생성. 프로덕션 조립점 **1개** — `BootstrapConfig.kt:55`(`src/baseline`의 `CqrsBaselineMain.kt:180`은 main 아님) | `turn/InMemoryTurnWorld.kt`(`:10` snapshot, `:42` world) · `boot/WorldSnapshotLoader.kt`(`:51`) |
| R2 | 도시 귀속 봉록용 컨텍스트 | v1 `ProcessIncomeContext`(`ProcessIncome.kt:215-219`) 재사용 불가 — `IncomeGeneral`(`:51-55`)에 `cityId` 없음, 그 타입은 T1. 구현자 **1개** = `WorldActionContext.kt`(`:76`,`:116`,`:300`,`:302`,`:322`). 생성 지점 **4개**(`WorldEventContextFactory.kt:72`·`WorldActionContext.kt:920`·`MonthlyPostUpdateHook.kt:198`·`:322`) | `world/WorldActionContext.kt` |
| R3 | 도시병사 컨텍스트 | v1 `DisasterWorldView`(`RaiseDisaster.kt:253-259`) 재사용 불가 — `DisasterCity`(`:56-62`)에 `garrison` 없음, 게다가 env-read(`:227`)라 미스가 무음. 구현자 **1개** = 같은 `WorldActionContext.kt`(`:65`,`:124`,`:600`) | **0개**(R2와 동일 파일 ⇒ MAJOR-2의 파일 단위 증거) |
| R4·R5 | 인테이크 배선 | 계약 `InstantActionRegistry.kt:28-42`(5단계). 5단계 불가 — `InstantActionController` 분류기가 `:83-84`에서 T1 집합(`InstantActionRegistry.kt:56`·`inherit/InheritActionRegistry.kt:47`)을 읽음. 턴-예약 경로는 `CommandRegistry.kt:121`~`:224 else -> RestAction`(T1, 미등록 = 무음 휴식)이라 기각 | `reserve/CommandWireMapper.kt`(`:43`,`:140-149`) · `run/TurnDaemonCommandDispatcher.kt`(`:326`,`:397`) |
| R6 | read 엔드포인트 등록 | `GameApiApplication.kt:8 @SpringBootApplication`이 `opensamguk.gameapi` 전체 스캔 | **0개** |

**0 편집으로 남는 것도 근거와 함께 적었다** — `CommandReserveService`는 인테이크 분기(`:120-144`)에서 `CommandRegistry`를 거치지 않는다(`registry.resolve`는 Model A의 `:177` 한 곳). `GameApiSecurityConfig`는 `:47 anyRequest().permitAll()`이라 매처 추가 불필요(v1과 같은 널 허용 principal + 소유권 가드 자세를 복사; 인증을 조이려면 그때 티켓이 `:42-47`을 새로 선언).

## 설계가 실제로 바뀐 지점 (문구 정정 아닌 것)

1. **R1 산출물 확대 — 읽기 경로 + 설정 게이트.** flush 5파일만으로는 v2 leaf·핸들러가 잔액을 볼 수 없다. 게다가 v1 DB에는 `v2_city_ledger`가 없으므로(방어선 1) 무조건 도는 SELECT는 **빈 결과가 아니라 부팅 예외**다. → v2 원장 read를 **쿼리 자체가 돌지 않는 게이트** 뒤에 두고(신설 없이 `OPENSAM-35` 0A-b bean 게이트 소비, `01-backbone-micro.md:76`), "게이트 off로 v1 부팅 1회"를 R1 DoD에 넣었다. **T2 조건 (b)가 "빈 컬렉션 가드"에서 "쿼리 미실행 가드"로 강화된 첫 사례.**
2. **R2 산출물 확대 — v2 컨텍스트 구현 + 생성자 불변 제약.** `WorldActionContext`에 **메서드만** 추가한다(생성자를 넓히면 T2가 3파일 더 열린다). 컨텍스트 수급 방식은 **cast-ctx**로 고정 — env-read는 미스가 무음 no-op(`WorldEventContextFactory.kt:23-31`)이라 원장이 조용히 틀어지는데, cast-ctx는 크래시라 즉시 보인다.
3. **R3가 R2의 소비자가 됐다.** 병렬 → 순차. 오픈 경로 수량은 불변(20)이나 **일정 형태가 바뀐다**.
4. **R4·R5의 인테이크 배선이 v1 계약에서 의도적으로 갈라진다.** 5단계("새 컨트롤러를 만들지 말 것")를 따를 수 없어 v2 전용 코드 레지스트리 + 인테이크 컨트롤러를 **신규 파일로** 만든다. v1 계약을 어긴 것이 아니라, 그 계약이 T1 레지스트리를 전제하므로 v2에서 성립하지 않는다는 사실의 기록이다.
5. **v2 시나리오가 `ignoreDefaultEvents: true`로 확정됐다**(MINOR-2). 그 결과 R2·R3가 같은 JSON 한 파일을 고치게 되어 MAJOR-2의 논거가 됐다.
6. **`web/**` 계층 공백을 명시했다.** T1도 T2도 아니고 §7.2 게이트 ②③에 잡히지 않는다 — 패러티 표면·JVM 결합이 없어 v1 골든을 물리적으로 움직일 수 없기 때문. 대신 "기존 페이지 무수정, 새 라우트만 추가"를 R4~R6 DoD 문장으로 넣었다.

## 최종 오픈 경로 = **20 · 단일값, 조건부 항목 없음** (불변)

기준 14(`README.md:55-65`) + R1~R6 = **20**. 개정 4차는 **티켓을 하나도 더하거나 빼지 않았다** — 바뀐 것은 R1·R2의 산출물 크기와 R2→R3 순서다.

R2가 최대 티켓이 되어 반나절 규율로 분해하면 **20 → 21**이 될 수 있으나, 이는 동일 산출물의 분해이지 범위 추가가 아니며 분해 여부는 착수 시점 판단이다. **권고는 20 단일값** — 개정 3차가 조건부 R0을 없앤 것과 같은 이유로 조건부 항목을 되살리지 않는다.

## 잔여 UNKNOWN 9건 (§11) — 어느 것도 위 결론의 전제가 아니다

U1~U8은 개정 3차와 동일. 신설 1건:

| # | UNKNOWN | 확인 방법 | 확인 실패 시 |
|---|---|---|---|
| U9 | **`@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 두어도 wire 직렬화가 성립하는가** — `TurnDaemonCommand.kt`는 74개 variant를 **전부 중첩 선언**(`:14`~`:940`, grep 실측 중첩 74·파일 밖 0)하고, 리포 전체에서 `@Serializable` sealed 4계열의 서브클래스를 다른 파일에 둔 **선례 0건** | 같은 패키지 신규 파일 + 최상위 서브클래스 1개 + `@SerialName` + 왕복 직렬화 테스트로 **컴파일 확인**(R4 착수 첫 작업) | (a) v2 전용 wire sealed 타입 + 디스패처 어댑터 분기 1개(기본값) 또는 (b) T1 예외 사람 승인. **어느 쪽이든 수량 20 불변**(T2 파일 0~1개 증가) |

`common/**`는 T1이지만 **신규 파일 추가는 T1도 허용**하므로, U9가 성립하면 T1 위반 없이 닫힌다. 성립하지 않을 때의 대안까지 미리 적어 두었다.

## 저자가 남긴 주의 (오케스트레이터 확인 필요)

1. 개정 3차와 동일 — `git status`에 이 세션이 손대지 않은 파일 수정이 다수 잡히고, 그중 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md`·`product-spec.md`·`LEDGER.md`는 이 설계안이 인용하는 파일이다. 채점자도 "HEAD 기준 유효성 미검증"으로 남겼다. **커밋 전 diff와 인용 줄번호 재검증 필요.**
2. 이번 세션이 수정한 파일은 `round3-proposal-city-guanxi.md` **하나**이고, 새로 만든 것은 이 기록 파일 하나다. 코드·골든·`docs/wiki/raw/**` 무수정, 커밋 없음.
3. **자기채점 취약점 0번을 새로 올렸다** — "전부 적는다"가 세 바퀴 연속 거짓이었고, 4차의 추적도 *내가 필요하다고 생각한 확장점*에서만 시작했으므로 완전성은 여전히 보장되지 않는다. 구조적으로는 §7.2 게이트 ③(선언 집합 = 실제 diff)이 착수 시점에 초과분을 잡는 것으로만 닫힌다.
