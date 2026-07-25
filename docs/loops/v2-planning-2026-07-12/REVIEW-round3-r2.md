# round-3 독립 reviewer 재채점 #2

> 일시: 2026-07-25 · 대상: `round3-proposal-city-guanxi.md`(개정 2차, 1058줄) · 시험지: `GOLDENSET-round3-city-guanxi.md` 10문항, 부분 충족 = N
> **VERDICT: `fix-required` · 총점 6/10** (1차 5/10 → +1). 저자 자기채점 10/10 기각.
> 1차와 다른 reviewer. THOROUGH → ADVERSARIAL 에스컬레이션 — **개정으로 새로 들어온 수치 하나가 날조로 확인**되어 개정분 인용 전체를 원본 대조로 재검사했다.

## 채점표

| # | 판정 | 요지 |
|---|---|---|
| 1 도시중심 정의 | **Y** | 묘섭 인용 4건 재확인. **도시 원장은 장식이 아니다** — 봉록이 174장수 × `getBill=dedLevel*200+400` × bill 100~500%로 국가 최대 지출급이고 그것이 도시별 3분기 판정을 통과한다 |
| 2 도시 자원·공백지화 | **N** | **F1 날조 · F2 `prev_income` · F3 무자금** |
| 3 4축을 기존 축 위에 | **Y** | 1차 M0이 비워 둔 자리를 실제로 채웠다(`IncomeTick.kt:41` `1.05.pow`) |
| 4 관계망·능력치 보정 | **N** | 설계는 크게 닫혔다(M2·M3·M4·M5 전부 해소). 남은 건 v1 불변 증명 한 조각 — **F4** |
| 5 임원진 6종 | **Y** | m6 두 번째 표 실제 추가 확인, 자격 요건 7/6/5품관 원문 일치 |
| 6 특색·규모·지역병종 | **Y** | m3 정정 반영, 인용 전부 일치 |
| 7 v1 패러티 불변 증명 | **N** | T1/T2 재작성·`--diff-filter=MD`·`logic/` 전체 잠금은 **진짜 개선**. 그러나 **T1 성립 근거가 코드로 거짓** — **F5** |
| 8 규칙 4 적합성 | **Y** | 1차 택1의 (b) 채택. `LEDGER.md:11` 문언이 직접 지시하는 처분 |
| 9 오픈 경로 티켓 수량 | **N** | M6·M7은 실제 해소. 그러나 **20의 근거 두 축이 무너진다** — **F6 소급 반쪽 · F7 UNKNOWN 재발** |
| 10 G0 관계 | **Y** | ADR 인용·직교성·부채 1건 성립 |

## C1 "네 번째 길"에 대한 코드 답변

**질문: `ProcessIncome`을 치환하면 국고에 수입이 들어오는가?**
**답: 안 들어온다. 자원 증식도 즉사도 아니지만, 설계안이 세지 않은 네 번째 대가다.**

치환 후 국고의 **자동** 유입은 둘뿐:

| 경로 | 실측 | 성질 |
|---|---|---|
| `ProcessWarIncome.kt:77` | `Σ city.dead/10`, `level<=0` 제외 | 전쟁이 나야. gold만, rice 0 |
| `UpdateNationLevel.kt:145-146` | `gold/rice += newLevel*1000` | 작위 상승 1회성 |

나머지 유입(헌납·몰수·초토화·군량매매세·물자조달·장비매매·탈취·하야·등용수락·정복노획)은 **전부 장수 개인 잔고를 옮기는 이전(transfer)**이다. → **v2 국고 = 세원 없이 이전으로만 사는 계정.**

유출은 그대로:

| 유출 | 실측 |
|---|---|
| 병종연구 9종 | `Event*Yeongu.kt:44-47` — 5종 각 100,000+100,000 / 4종 각 50,000+50,000 = **총 650,000 gold + 650,000 rice** |
| 물자원조 | `CheMuljaWonjo.kt:95-97` 작위 9면 회당 **90,000+90,000** |
| 증축 | `CheJeungchuk.kt:36` `develcost*500 + 60000` |
| 포상 | `ChePosang.kt:82` 국고를 0까지 전액 소진 가능 |
| **반년 감쇠** | `ProcessSemiAnnual.kt:167-177` `>100000 → ×0.95` … **이 leaf에 nation 가산 항 0줄 = 순수 드레인** |

### F3 — 대가 목록에 네 번째가 빠졌다

§2.3은 유입·유출을 **열거**했으나 **충분성을 한 번도 묻지 않았다.** "남는 유입·유출은 위 표 그대로 전부 실제 잔고 위에서 돈다"(`:160`)는 회계적 참 / 게임적 미검증 단정. 대가 3건에 **"국가 지출 전 항목이 세원을 잃고 이전 수입에만 의존하게 된다"**가 없다. 자기채점 취약점 3은 "반년 감쇠 대상 금액과 국력 계산 입력이 작아진다"만 적었을 뿐 지출 자금원 자체를 다루지 않는다.

국가 커맨드가 "통째로 죽는" 것은 아니다 — AI는 실제로 헌납한다(`GenWarMoveFamily.kt:87,487-551` `TRIBUTE_ACTION="che_헌납"`, `GenFoundFamily.kt:531-533`, `AutorunGeneralPolicy.kt:54` `canNPC헌납` 기본 true). 그러나 **650k짜리 연구를 10,000 단위 헌납으로 메우는 경제**가 되고, 그 전환을 인지·정량화한 흔적이 없다.

### F2 — `ProcessIncome`의 네 번째 산출물을 설계안이 모른다 (1차도 못 잡음)

```
ProcessIncome.kt:156          prevIncome[nation.id] = grossIncome
WorldActionContext.kt:332-334 recorder.recordKv("nation_env", nationId, "prev_income_$resource", value)
```
유일한 소비자가 NPC 경제다:
```kotlin
// AiInstanceState.kt:137-146
val prevIncomeGold = (nationStor["prev_income_gold"] as? Number)?.toDouble() ?: 1000.0
val rawMax = maxOf(minimumResourceActionAmount, prevIncomeGold/10.0, prevIncomeRice/10.0,
                   nation.gold/5.0, nation.rice/5.0, (year-startYear-3)*1000.0)
```
`maxResourceActionAmount`는 NPC 헌납·포상·몰수의 **금액**을 정한다(`AiInstanceStateTest.kt:109-116`이 핀으로 박음). v2 leaf가 이 KV를 안 쓰면 전 국가가 영구히 `?: 1000.0` 폴백으로 떨어지고, 세수가 국고에서 빠진 만큼 `nation.gold/5` 항도 함께 죽는다.

**설계안 1058줄에 `prev_income`이 0회 등장한다.** §2.3의 "치환하는 leaf는 `ProcessIncome` 하나뿐"이라는 전수 회계가 그 leaf의 산출물을 전수하지 않았고, 하필 §2.6이 스스로 최대 리스크로 지목한 **NPC 경제**를 친다. (v1 골든은 별도 DB·프로파일이라 무영향 — 패러티 결함이 아니라 v2 설계 공백.)

### F1 — `BAD_STATE_CODES` 라벨 **날조** (CLAUDE.md 패러티 규율 5 직접 위반)

집합 `{3,4,5,6,7,8,9}`는 **맞다.** 그러나 `:219`가 붙인 라벨이 `RaiseDisaster.kt:104-127`과 **7개 중 7개 전부 다르다.**

| 제안서 `:219` | 실제 |
|---|---|
| 홍수 3 | 3 = 추위(`:108`) / 혹한(`:122`) / 눈(`:124`) |
| 메뚜기 4 | 4 = 역병(`:106`) |
| 태풍 5 | 5 = 지진(`:107,113,118,123`) |
| 지진 6 | 6 = 태풍(`:114`) |
| **가뭄** 7 | 7 = 홍수(`:112`). "가뭄"은 원본에 **없는 이름** |
| 역병 8 | 8 = 메뚜기(`:117`) / 흉년(`:119`) |
| **전염병** 9 | 9 = 황건적(`:109,125`). "전염병"은 원본에 **없는 이름** |

1차가 "UNKNOWN 정직 표기"로 무해 판정했던 자리를 "**확정** — 1차의 UNKNOWN 해소"라고 채우면서, 파일을 열어 **집합만 세고 이름은 지어냈다.** 이것 하나만으로 문항 2는 N이다.

**부수** — 월 게이트 근거도 잘못된 파일. `:202`·`:215`가 `RaiseDisaster.kt:98 BOOMING_RATE`를 근거로 삼는데 그건 **호황 확률표**이지 leaf 발화 일정이 아니다(키가 우연히 `{1,4,7,10}`). 정본은 `EventStore.kt:171,180,190,197`의 `["Date","==",null,{1|4|7|10}]`. **결론(월 게이트 + `RaiseDisaster` 직후)은 참이고 M1을 실제로 닫는다** — 리셋(`:146-148`)이 leaf 본문 안·무조건·연도 게이트 이전인 것도 확인. 근거만 틀렸다.

**부수 2** — `:151` "거절 문구가 이미 국고가 부족합니다"는 절반만 참. `Presets.kt:315`는 확인되나 `reqNationRice:322-328`은 **"병량이 부족합니다."**

## F4 — 주입 지점 3파일 중 어느 것을 여는지 여전히 미단정 (문항 4)

§4.3이 (A) seam 개설을 택한 것은 진전이고 실측도 정확하다 — `EngineGeneralActionPipelineBuilder.kt:14` final·`@Bean`/`@Component` 0건·`DaemonLoopConfig.kt:229`에서 로컬 `val`로 `new`되어 3소비자(`:241`·`:295`·`:328`)에 전달, `FrontInfoController.kt:377,392` 인라인, `@ConditionalOnMissingBean` 리포 전체 0건. seam 부재는 참.

그러나 시험지 4가 요구한 건 "**증명해야 한다**"이고 1차 C2 요구 (ii)는 "3파일 각각 새 파일인가 기존 파일인가를 **파일명 단위로 단정**"이었다. 개정안은 편집을 `OPENSAM-35`에 귀속시켰으나 **그 범위가 UNKNOWN**(`:376`)이고, §7.1-2의 "T2에서 실제로 열리는 파일 6개, 지금 전부 적는다" 목록에 **셋 중 하나도 없다.** §7.2 게이트 ③이 "사전 명시 집합과 정확히 일치, 초과 = 위반"인데 사전 명시가 없어 **착수 시점에 게이트가 자동 실패한다.**

**추가 발견 — 표시 경로가 엔진과 구조적으로 다르다.** `FrontInfoController.kt:394`는
```kotlin
truncate(pipeline.onCalcStat(logicGeneral, statName, base.toDouble())).toInt() - base
```
로 **`GetStatValue`를 우회**한다. 표시 경로엔 교차증강(`GetStatValue.kt:54-64`)도 부상(`:53`)도 `clamp`(`:63,65`)도 없다. → **§4.7의 실효 상한 통솔 ±6 / 무력·지력 ±8은 엔진 값이고, 화면은 전 스탯 ±6을 보여준다.** §4.3 부수 발견은 `scenarioEffectRegistry` 누락만 적었고(그것도 참) 이 차이는 못 잡았다.

## F5 — "leaf 치환이 `logic/`·`app/` 어느 쪽도 안 연다"가 성립하지 않는다 (문항 7)

`WorldActions.kt:30-56`이 22개 leaf 등록 체인인 건 맞다. 그런데 **`WorldActions.register` 프로덕션 호출부는 리포 전체에 하나뿐이다.**
```kotlin
// app/game-engine/.../config/EngineEventConfig.kt:79-81
@Bean fun eventActionFactory(): EventActionFactory = WorldActions.register(EventActionFactory())
```
나머지는 전부 테스트. 따라서 §7.1-2의 `V2WorldActions.register(WorldActions.register(factory))`는 **반드시 이 파일 이 줄에 쓰여야 한다** = T2 편집 1건. 그것이 T2 6파일 목록에도, §9.2 R2 산출물("v2 `event` 행 + `V2WorldActions` 등록 체인" — 파일명 없음)에도 없다.

**더 나쁜 두 가지가 미기재:**
1. **DB `event` 행 대체는 all-or-nothing.** `EngineEventConfig.kt:46-68`이 `rows.isEmpty()`면 `withDefaults`, 아니면 **빈 `EventStore()`에 DB 행만** 적재 — 병합이 아니다. 한 행이라도 있으면 12행 `DEFAULT_EVENTS`가 통째로 무시된다. 그리고 `ScenarioImporter.kt:806-836`이 시드 때 `defaultWireRows()`를 **항상** 넣으므로 실서버 `event` 표는 절대 비지 않는다 → v2 DB는 **12행 전부 재시드** 필요, 그 시드의 소유 티켓이 §9.2에 없다.
2. **DB 행만으론 치환 불성립.** `EventAction.kt:70-74`가 미등록 이름에 `IllegalArgumentException("존재하지 않는 Action입니다 :…")`. 팩토리 등록이 선행 필수.

## F6 — 관계망 −2의 결정적 근거가 반쪽 (문항 9)

`01-backbone-micro.md:190` 직접 확인:
```
189:### Phase V2-3 (선행: V2-1 `che_출병` metadata / 공유 Exit: 3 event fixture·event diff 0)
190:- 3-a `operations` / 3-b `operation_participants` / 3-c `operation_routes` / 3-d `operation_events` 스키마 각 1티켓
```
**표 실재·티켓 3-b·Phase V2-3 전부 참이다.** 1차가 "미검증 전제"로 남긴 것이 해소됐고 그건 저자의 공이다. (다만 `V2-3 = OPENSAM-56` 매핑은 이 줄이 아니라 `README.md:63`.)

**그러나 그 줄은 컬럼을 하나도 정의하지 않는다.** 리포 전체 `operation_participants` 12 hit 전부 표 이름 나열, **DDL·컬럼 목록 0.** 가장 가까운 `product-spec.md:170-176`의 `Operation`은 `targetCityId` + **자기편** `participants`/`roles`(MAIN|SUPPORT|SCOUT|SUPPLY|RESERVE)뿐 — **적군 참가자를 기록하지 않는다.**

§4.5는 RIVAL을 "같은 `Operation`에서 **서로 반대편으로** 참가해 종료"로 정의했다. → **COMRADE는 소급 가능, RIVAL은 소급 근거 없음.** §9.4 `:949`의 "COMRADE·RIVAL을 소급 생성할 수 있다"는 확인되지 않은 절반을 전부인 것처럼 말한 것이고 **그 문장이 곧 −2의 근거다.** 판정: 근거는 참이되 **확대해석**.

(문항 4와 9는 분리 채점했다 — 관계망 설계 자체는 §4에 완전하고 사용자 결정 "능력치 버프에도 영향"은 §4.7에 반영돼 있다. 문항 4의 N은 일정 판정 때문이 아니다.)

## F7 — UNKNOWN 위에 결론을 세운 곳이 자리만 옮겨 재발 (문항 9)

§9.2 `:912`가 R0을 조건부 +1로 두면서 스스로 적는다: "R0은 관계망(오픈 후) 전용이 아니라 **R2의 leaf 등록 체인도 같은 seam을 쓴다**." F5대로 R2는 `EngineEventConfig.kt:81`을 **반드시** 연다 → R0은 조건부가 아니라 **R2 선행 필수**이고, 그 티켓이 `OPENSAM-35` 범위인지 UNKNOWN인 채 총계 20이 제시됐다. 시험지 9는 "증분을 **티켓 수량**으로"인데 "20 또는 21"은 수량이 아니다. **1차 M5와 정확히 같은 실패 양식** — 자기 결론의 공급원 하나를 확인하지 않은 채 단정. 확인 방법(`OPENSAM-35` 본문 조회)이 열려 있고 저자 자신이 취약점 1로 적었다.

## 1차 지적 18건 반영 대조

**해소 15 / 부분 1 / 말만 바꿈+신규 결함 1.** MINOR 7건 전부 해소.

- **C1** — *말만 바꿈 + 신규 결함*. 미러 폐기·42지점 무접촉·precheck 해소는 성립. 그러나 문제가 사라진 게 아니라 **회계에서 게임 밸런스로 이동**했고(F3) 치환 leaf의 4번째 산출물을 놓쳤다(F2)
- **C2** — *부분*. (i) 게이트 `app/**`·`infra/**` 추가 해소 / (iii) R8 game-api 경로 해소 / **(ii) 3파일 단정 미해소**(F4·F5)
- **M0** 해소(+R2+R3 병합 유도) · **M1** 해소(근거 파일만 틀림) · **M2** 해소(`bondIndex`, 상호 원한 표현 가능) · **M3** 해소(2축, 애증=0) · **M4** 해소 — **코드 재검증 완료**: `GetStatValue.kt:89-91`이 `withIActionObj`를 그대로 전달하고 `:64` 파이프라인이 `:54-60` 교차증강 **뒤에** 실행되므로 무력 = +6 + `phpRound((raw+6)/4) − phpRound(raw/4)` ≤ +2 = **+8**, 통솔은 교차증강 없어 +6. 야드스틱 부상 10%(무력 80 × 10% = −8)도 정확 · **M5** 해소(UNKNOWN 정정 + `scenario-system.md:60` 인용까지) · **M6** 해소 · **M7** 해소(P1~P6 산출물별 6티켓, P3이 하한임을 자진 기재) · **M8** 해소 · **m1~m7** 전부 해소

## 저자 반박 4건 판정

| # | 판정 | 근거 |
|---|---|---|
| 1 leaf 레지스트리 → `app/**` 편집 0 | **세부 타당 / 결론 부당** | `EngineEventConfig.kt:87-89`가 빈 파이프라인 빈인 건 맞다 — 1차 줄 번호가 틀렸다. 그러나 **같은 파일 8줄 위 `:79-81`이 프로덕션 유일 팩토리 생성 지점**이다. "`app/**` 편집 0"은 **거짓** — `WorldActions.kt`(T1) 또는 `EngineEventConfig.kt`(T2) 중 **하나는 반드시 연다**. 설계안이 T1을 안 연다 했으므로 후자 |
| 2 M8 "(a)가 가장 쌈"은 틀렸다 | **타당** | `EngineGeneralActionPipelineBuilder.kt:43,54` `modulesFor`가 장수별 조립·상한 없음. 1차 "[가장 쌈]" 라벨에 근거가 없었다. (b)도 택1이었으므로 (b) 채택은 규칙 위반 아님 |
| 3 `aux` 채널은 존재하나 `GetStatValue.kt:64`가 안 채움 | **결론 타당 / 문언 부정확** | `ActionPipeline.kt:24` `aux` 실재, `:64` 미전달·T1 확인. **"유일한 호출부"는 거짓** — 프로덕션 `onCalcStat` 호출부는 **23곳**. 통무지를 계산하는 건 `GetStatValue.kt:64`와 `FrontInfoController.kt:394` **둘**이라 실질 결론은 성립하지만 그 둘째가 F4의 표시/엔진 divergence다 |
| 4 M0이 R2+R3 병합까지 간다 | **타당** | `ProcessIncome.kt:124-167` — 수입`:125-130`·봉록`:132-133`·3분기`:141-153`·장수별 지급`:160-166`이 전부 한 `for (nation in ordered)` 안. 1차가 R2 축소만 본 게 맞다 |

## 새 주장 날조 점검

**참**: 부상 10% 야드스틱 −8 · 실효 상한 ±6/±8 · `GameConst.kt:48 maxDedLevel=30` · `CheJangbiMaemae.kt:239` · `JdbcFlushExecutor.kt:2182`(오히려 저평가 — `@Scheduled` 리포 전체 0건) · `cqrs-consistency-failure-contract.md:502`(축자) · `LEDGER.md:11`(축자) · ADR-018/019/020 전부(019의 "폐기 아닌 유예" 문언까지 정확) · **묘섭 인용 8/8 축자 일치**, `docs/wiki/raw/**` 무수정 확인

**거짓/부정확**: `BAD_STATE_CODES` 라벨 **7/7 날조** · 월 게이트 인용 근거 오귀속(결론은 참) · `Presets.kt:315`는 gold만(rice는 "병량이 부족합니다") · `01-backbone-micro.md:190`은 컬럼 정의 0, `OPENSAM-56` 매핑은 `README.md:63`

**확인 불가**: census "35파일/42파일" 산출 정규식 미상, 재현값 26~53으로 흩어짐(결론을 떠받치지 않으므로 무영향). 복붙 10곳은 참

## fix-required 항목

**CRITICAL-1** `§2.4 :219` 라벨 날조 — `{3..9}` 유지하되 이름 7개를 `RaiseDisaster.kt:104-127` 실제 `DISASTER_TEXT`로 교체(3=추위/혹한/눈, 4=역병, 5=지진, 6=태풍, 7=홍수, 8=메뚜기/흉년, 9=황건적). "가뭄"·"전염병" 삭제. `:202`·`:215` 월 게이트 근거를 `EventStore.kt:171,180,190,197`로 교체.

**CRITICAL-2** `§2.3`에 `prev_income` 항 추가 — `ProcessIncome.kt:156` → `WorldActionContext.kt:332-334` → `AiInstanceState.kt:137-146` 경로 명시 후 (a) 도시 원장 합계로 계속 쓸 것인지 (b) `?: 1000.0` 폴백 수용인지 (c) NPC 금액 산식 v2 포크인지 **셋 중 하나로 단정**.

**MAJOR-1** `§2.3` 대가 네 번째 추가 — "국가 지출 전 항목(병종연구 650k+650k · 물자원조 회당 ≤90k · 증축 · 포상)이 세원을 잃고 이전 수입에만 의존한다. `ProcessSemiAnnual.kt:167-177`은 순수 드레인, 자동 유입은 `ProcessWarIncome.kt:77`(전쟁 시 gold만)과 `UpdateNationLevel.kt:145-146`(1회성)뿐." 정량 기재 + 오픈 전 관측 항목 등록.

**MAJOR-2** `§7.1-2` T2 목록에 `EngineEventConfig.kt` 추가 — `:79-81`이 프로덕션 유일 생성 지점임을 근거로 명시, §9.2 R2 산출물에 파일명 기재. 동시에 `:46-68` **all-or-nothing** 성질 + `ScenarioImporter.kt:806-836`이 `event` 표를 항상 채운다는 사실 + **v2 DB 12행 재시드의 소유 티켓** + `EventAction.kt:70-74` 미등록 예외.

**MAJOR-3** R0을 조건부에서 **R2 선행 필수로 승격**하거나 `OPENSAM-35`를 조회하라. 총계는 20/21 택일이 아니라 단일 수량이어야 문항 9를 충족한다.

**MAJOR-4** `§9.4 :949` 소급 근거를 **RIVAL에 대해 UNKNOWN으로 강등**. COMRADE 소급 유지, RIVAL은 "표 스키마 확정 시 재판정". 잔여 UNKNOWN 목록에도 추가(현재 6건에 없다).

**MINOR-1** `§4.3`에 `FrontInfoController.kt:394`가 `GetStatValue`를 우회한다는 사실 추가 — 표시 경로엔 교차증강·부상·clamp 없어 §4.7 실효 ±8이 화면엔 ±6. §9.4 P6 산출물 반영.
**MINOR-2** `:151` — "국고가 부족합니다"는 `reqNationGold`만, rice는 "병량이 부족합니다"(`Presets.kt:322-328`).
**MINOR-3** `:949` — `V2-3 = OPENSAM-56` 매핑은 `README.md:63`(해당 파일에 `OPENSAM-` 0건).

## 확인 불가 (N 사유 아님)

`OPENSAM-35` 티켓 본문(Jira 미조회 — F7 판정은 티켓 내용이 아니라 "UNKNOWN 위에 R2가 섰다"는 구조에 근거) · census 정규식 · **`operation_participants` 컬럼(리포 전체 정의 0건 — 이 "확인 불가"가 곧 MAJOR-4의 근거)** · RTK14 원본 필드(설계를 얹지 않아 무영향) · 세율 곱 위치 divergence의 밸런스 영향(시뮬레이션 없이 판정 불가, 저자도 취약점 5로 자진 기재)

## 1차 판정 재검토 (무비판 수용 안 함)

**1차가 틀렸거나 부정확했던 곳**: C2의 leaf 치환 근거(`EngineEventConfig.kt:89`는 레지스트리가 아니다 — 저자 지적이 맞다. 다만 정정 후에도 결론은 살아남고 같은 파일 `:81`에서 더 강하게 성립) · M8 "(a)가 가장 쌈"(근거 없는 라벨) · "`BAD_STATE_CODES`는 UNKNOWN 정직 표기라 무해"(1차 시점엔 옳았으나 개정이 그 자리를 날조로 채웠다).

**1차가 옳았고 여전히 옳은 곳**: C1 진단(미러 구현 불가), M0~M7 전부, m1~m7 전부, 그리고 총평 — "**자기 주장을 코드로 끝까지 밟아보지 않았다**". 개정 2차는 그 습관을 크게 고쳤다(코드 인용 밀도가 확연히 올랐고 M0·M4·M5는 저자가 직접 코드를 읽어 닫았다). **남은 4개의 N은 전부 한 번 더 밟았으면 나왔을 자리다** — `ProcessIncome`의 4번째 산출물, `WorldActions.register`의 호출부, `DISASTER_TEXT`의 실제 라벨, `operation_participants`의 컬럼.

## 재채점 최단 경로

CRITICAL-1은 파일 한 번 읽으면 끝난다. CRITICAL-2 + MAJOR-1이 문항 2를 마저 닫고, MAJOR-2가 문항 7, MAJOR-3 + MAJOR-4가 문항 9를 닫는다. 문항 4는 MAJOR-2·3이 해결하는 seam 단정에 종속되므로 **네 항목이 동시에 움직인다.** 9/10 또는 10/10이 한 바퀴 안에서 가능하다.
