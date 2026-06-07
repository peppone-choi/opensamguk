# EXECUTION_PLAN — opensamguk 풀-패러티(0.9.0) 단일 마스터 실행계획

> **HEAD**: `426ae33` (branch `parity-final`)
> **출처**: 482-에이전트 2단계 전수 감사 raw — `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (5.1MB, jq 슬라이스만).
> **grand truth**: `legacy/devsam-core` (PHP). 2차 구조 오라클 = `legacy/devsam-core2026` (TS). **모든 divergence에서 PHP가 승**.
> **날조 금지** — 모든 단위·file:line은 raw JSON 또는 실제 PHP grep에서 확인됨. 원천 미확정 항목은 **BLOCKED**로 명시(값 채우기 금지).
> **빌드 금지(읽기전용 계획)**. 게이트 실행은 별도 세션: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...` (출력 tail + test XML로 검증, exit-0 불신).
>
> 본 문서는 5개 그룹 worklist(`exec/00-tier0.md`, `exec/01..04-*.md`)를 **단일 마스터로 통합**한 정본이다. 섹션 파일은 그룹별 worklist로 그대로 유지된다.

---

## 1. 개요 — 전체 인벤토리 + 목표 + 신뢰도 한계

### 1.1 인벤토리 (raw 전수 감사)

| 구분 | 수 |
|------|----|
| 전체 단위(total) | **768** |
| 미포팅(missingPort) | **202** |
| 부분포팅(partialPort) | **100** |
| 현재만/잉여(currentOnly) | **72** |
| 비교 완료(comparisons) | **457** (평균 충실도 62/100) |

충실도 분포: `0–29: 80건` · `30–49: 54건` · `50–69: 92건` · `70–89: 135건` · `90–100: 96건`. high-severity gap **1,013개**, blocked(상위 의존 즉시수정 불가) **294개**, parityViolations **1,026건**.

미포팅 202 내역(kind별): command 29 · admin 10 · event 12 · domain 24 · read-api 5 · intake-api 6 · php-page 5 · vue-page 2 · vue-component 39 · php-ajax 41 · fe-ts 17.

### 1.2 목표 = 운영 게이트 (사용자 명시)

**풀-패러티 0.9.0 = 운영 시작 게이트.** 패러티 완성판을 0.9.0으로 공개하며, 그 이후(1.0.0+)에 오픈삼국 독자기능을 더한다. 따라서 본 계획의 미포팅 202 + 부분포팅 100(저충실도 우선)을 **전면적으로** 닫는 것이 운영 진입 조건이다. divergence(인증 평탄화, 멀티운영자 grade, 서버개폐 docker화 등)는 1.0.0 백로그로 명시 수용하되, **데이터/동작(필터·정렬·마스킹·필드셋·RNG draw·로그 byte)** 은 패러티 게이트 대상이다.

### 1.3 신뢰도 한계 (반드시 인지)

전수 감사는 2단계로 총 **482개 에이전트**가 분담했고, 그중 **13개 에이전트가 실패**하여 약 **311개 단위가 미비교**(768 − 457 = 311)다. 즉 "비교 457건"은 감사 완료 범위만 반영하며, 미비교 311건의 충실도는 현재 알 수 없다. 미포팅/부분/잉여 카운트(202/100/72 = 374)는 **인벤토리 단계** 분류로 비교 단계와 독립이다. → "전수"는 인벤토리 기준이며, **충실도 게이트는 부분 커버리지**임을 전제로 읽을 것. 미비교 311건은 후속 감사로 보완.

### 1.4 모듈 매핑 규칙 (전 그룹 공통)

- **명령** = `logic/actions/*`(ActionDefinition: parseArgs/buildConstraints/resolve) + `logic/.../CommandRegistry.kt`(when 1줄) + intake `app/game-api/.../reserve/CommandWireMapper.kt` + `AvailableCommandsController.kt` 카탈로그 + game-engine `ReservedTurnHandler.kt`(범용 배선됨) + golden.
- **이벤트 액션** = `logic/.../world/<Action>.kt`(EventAction leaf) + `logic/.../world/A3EventActions.kt`(`.register("<Key>")`) — `WorldActions.register`가 A3을 이미 체이닝(`WorldActions.kt:24`).
- **read** = `app/game-api` controller + DTO (+ `infra/.../read/*Repository` 재사용/신규).
- **FE** = `web/game`(게임 내) 또는 `web/gateway`(엔트런스/로비/어드민).
- **admin** = `app/gateway-api`(루트DB) + game-engine intake(강제뮤테이션) + `web/gateway/app/admin` / `web/game`(게임 내 관리).
- **골든 Y 판정** = PHP run() 본문이 `$rng->next*/choice*/pick*`를 실제 draw하면 Y → `tools/php-golden` 캡처 + draw-for-draw GoldenTest. draw 0이면 N → deterministic effect/log byte-parity(0-draw 명시 assert).

---

## 2. 글로벌 실행 순서 + 의존 그래프

### 2.1 위상 (Tier-0 하드선행 → A∥B → C → D)

```
                ┌─ Area2 Scenario 도메인(B1 GeneralBuilder→B3 Pool→B2 Nation) ★하드선행
   Tier-0 ─────┤    (장수/국가 mint draw 루트 — A3 NPC/이민족 event 선결 토대)
                ├─ Area1 utilGame ✅완료 (보류 3종 배선만)
                ├─ Area3 trigger base → YAGNI, 첫 구체 트리거와 동행
                └─ Area4 wire 시드 → 전제붕괴, 그룹 A에 흡수 (공유 stub widening 1회)
        │
        ▼
   ┌─────────────────────────┬─────────────────────────┐
   │  그룹 A (명령+이벤트)    ║  그룹 B (어드민)         │   ← A∥B 병렬(파일 disjoint)
   │  /parity-wave 골든 15건  ║  BE→FE 엄격, intake 골격 │
   └─────────────────────────┴─────────────────────────┘
        │
        ▼
   그룹 C (FE 미포팅) — Tier-0 Area2 + 그룹 A wire 일부 소비
        │
        ▼
   그룹 D (read DTO 형상) — 대체로 독립이나 FE 계약 정합상 C 뒤가 안전
```

핵심 하드선행: **Tier-0 Area2(B1→B3→B2)** 가 모든 장수/국가 mint draw 루트. 그룹 A의 A3(RaiseInvader/RaiseNPCNation/CreateManyNPC/Reg*) 와 A2(인재탐색 NPC 생성)는 B1+B3 완료가 선결. 그룹 C의 C2(선택풀/빙의)도 Area2 read seam 의존.

### 2.2 disjoint 그룹 / 공유 hot-file 규율 (CLAUDE.md foundation-first)

병렬 worktree 가족은 **disjoint 파일**이어야 한다. 아래 공유 hot-file은 **1회 widening(creator) 후 각 단위가 disjoint 본문(consumer)** 으로 채운다 — co-widen 금지(merge conflict):

| 공유 hot-file | creator(1회 widening) | consumer(disjoint 본문) |
|----------------|------------------------|--------------------------|
| `CommandRegistry.kt` (when 분기) | Tier-0 마지막: 그룹 A 명령 코드 빈 케이스 추가 | 각 명령이 자기 when 1줄 |
| `CommandWireMapper.kt` intakeCodes / wire variants | Tier-0 마지막: 신규 wire 코드 stub | 각 명령/FE write가 자기 wire 본문 |
| `TurnDaemonCommandDispatcher.kt` | Tier-0 마지막: 빈 dispatch 케이스 | 각 명령이 자기 dispatch 본문 |
| `F4Dto.kt` (vote+diplomacy 공유) | — | **vote는 신규 `VoteDto.kt`로 분리**해 disjoint화(권장). diplomacy만 F4Dto 유지 |
| `A3EventActions.kt` register | — | 각 이벤트 액션이 register 1줄(append, 충돌 적음) |
| `web/.../app/admin/page.tsx` | — | B1e/B2f **순차**(creator-then-consumer) 또는 탭별 컴포넌트 추출 후 병렬 |
| `AuctionController.kt`+`AuctionDto.kt` | — | D1·D2·D3 **한 worktree 순차** |
| `MailboxController.kt` | — | D7+D8 **한 worktree**(봉투+마스킹 공유) |

### 2.3 골든 캡처 총량 (tools/php-golden 실 캡처 필수, 날조·약화 금지)

- **그룹 A 골든 Y = 15건**: A1 계략5(화계/파괴/탈취/선동/첩보) + 단련 + 접경귀환 + InstantRetreat + ResetStat(=9), A2 견문 + 인재탐색(=2), A3 RaiseInvader + RaiseNPCNation + CreateManyNPC + LostUniqueItem(=4).
- **Tier-0 Area2 골든 Y = 3건**: B1 GeneralBuilder · B2 ScenarioNation · B3 RandomNameGeneral.
- **그룹 B 골든 검토 = 2건**: B5-force-rehall(CheckHall) · B1c-income(ProcessIncome RNG 여부).
- **그룹 C 골든 Y = 2건**: C2 j_select_picked_general(`allStat^1.5` 가중추첨, `/parity-wave` 이관) · C4 j_simulate_battle(전투엔진 ONE RandUtil(warSeed)).
- **그룹 D 골든 Y = 1건**: D11 GetDiplomacy(conflict % `round(100*killnum/sum,1)` PhpRound).

캡처 불가 시 quarantine + 백로그(sibling byte-match 증명). 게이트 약화·골든 편집 금지. 실패는 fix Kotlin, never the golden.

---

## 3. 섹션 본문 (Tier-0 → A → B → C → D 인라인)

> 아래는 5개 그룹 worklist 전문을 순서대로 통합한 것이다. 표·근거(file:line)·BLOCKED 표기 보존. 섹션 파일도 그룹별 worklist로 그대로 유지된다.

---


<a id="section-tier0"></a>

> ── 인라인: `docs/superpowers/gap/exec/00-tier0.md` (그룹 worklist 전문) ──

# exec/00-tier0 — Tier-0 공유 토대 (4 Area)

> 데이터 정본: `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (jq 슬라이스) + legacy `legacy/devsam-core` grand-truth 직접 grep. **날조 금지** — 모든 단위·file:line은 raw JSON 또는 실제 파일에서 확인됨.
>
> **세션 정정 반영(MASTER_GAP §5의 aspirational 항목 교정):**
> - **Area 1 utilGame 16종 = ✅ 완료+커밋(c2293ba)**, `web/game/lib/utilGame/`. 잔여 = 보류 3종 배선뿐.
> - **Area 4 wire 시드 = 🔴 전제붕괴**: DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner 가 logic에 부재 → 명령 포팅(그룹 A)에 흡수. Tier-0 독립 단위 아님.
> - **Area 3 trigger base = 단독포팅 YAGNI** → 첫 구체 트리거와 함께 포팅.
> - 맵 정합 = ✅ 완료(b3d0ff1).
>
> Tier-0의 **유일한 실질 잔여 작업 = Area 2 Scenario 도메인**(B1–B3 장수/국가 생성). 나머지 3 Area는 ✅완료(잔여 배선만) 또는 흡수.

모듈 매핑 규칙:
- **명령** = `logic/actions/*` + `CommandRegistry` + intake(`CommandWireMapper`) + game-engine dispatcher + golden
- **FE** = `web/game`(또는 `web/gateway`)
- **read** = `app/game-api` controller + DTO
- **admin** = `app/gateway-api` + `web/gateway/app/admin`

---

## Area 1 — utilGame (✅ 완료, 잔여 = 보류 3종 배선)

`legacy/devsam-core/hwe/ts/utilGame/` 19파일 중 **16종이 `web/game/lib/utilGame/`로 포팅 완료**(커밋 c2293ba). `index.ts`에서 전부 re-export. 감사 fidelity: getNPCColor=100, formatVoteColor=100, formatOfficerLevelText=95 (raw JSON `comparisons[]`).

### 포팅 완료 16종 (배선 불요, 참조용)

| # | 포팅 파일 | legacy 출처 | 비고 |
|---|---|---|---|
| 1 | `calcInjury.ts` | `utilGame/calcInjury.ts` | 부상 계산 |
| 2 | `formatDefenceTrain.ts` | `utilGame/formatDefenceTrain.ts` | 수비/훈련도 표기 |
| 3 | `formatDexLevel.ts` | `utilGame/formatDexLevel.ts` | 숙련 등급(`DexLevelMap`) |
| 4 | `formatGeneralTypeCall.ts` | `utilGame/formatGeneralTypeCall.ts` | 통/무/지 비율 호칭(31L) |
| 5 | `formatHonor.ts` | `utilGame/formatHonor.ts` | 명성 표기 |
| 6 | `formatInjury.ts` | `utilGame/formatInjury.ts` | 부상 한글명 |
| 7 | `formatLog.ts` | `utilGame/formatLog.ts` | 로그 마크업 렌더 |
| 8 | `formatOfficerLevelText.ts` | `utilGame/formatOfficerLevelText.ts` | 관직명(BE F4StateText와 byte-동치, fid 95) |
| 9 | `formatRefreshScore.ts` | `utilGame/formatRefreshScore.ts` | 새로고침 점수 |
| 10 | `formatTournament.ts` | `utilGame/formatTournament.ts` | 토너먼트 type/step (fid 55 — 헤더 step 라벨/HH:MM은 별도 갭) |
| 11 | `formatVoteColor.ts` | `utilGame/formatVoteColor.ts` | 투표 색(fid 100) |
| 12 | `getNPCColor.ts` | `utilGame/getNPCColor.ts` | NPC 색(fid 100) |
| 13 | `isValidObjKey.ts` | `utilGame/isValidObjKey.ts` | 키 검증 |
| 14 | `nextExpLevelRemain.ts` | `utilGame/nextExpLevelRemain.ts` | 경험치 잔여 |
| 15 | `techLevel.ts` | `utilGame/techLevel.ts` | 기술 등급(`convTechLevel` 등) |
| 16 | `tournament.ts` | `utilGame/tournament.ts` | `calcTournamentTerm` |

보조: `_helpers.ts`(내부 헬퍼), `index.ts`(barrel export).

### 보류 3종 — 잔여 배선 서브태스크

`index.ts:2` 주석에 명시된 보류 항목. legacy 직접 확인 결과:

| id | kind | legacy(file) | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| **formatCityName** | fe-ts | `hwe/ts/utilGame/formatCityName.ts` (14L) | `web/game/lib/utilGame/formatCityName.ts` | GetConst cityConst | N | tsc + 단위(cityID→name) | 🔴 보류 | **배선 흐름 거의 완성**: legacy `formatCityName(target, gameConst): gameConst.cityConst[cityID].name`. BE는 이미 `GetConstResponse.cityConst: List<CityConstItem{id,name,...}>` 제공(`app/game-api/.../dto/GetConstDto.kt:43,72-74`), FE는 `api.gameConst() → '/api/const'`(`web/game/lib/api.ts:69`) + `GameConstResponse`(`types.ts:319`) 보유. **서브태스크**: (1) `cityConst` 배열을 `{[id]: name}` 룩업으로 인덱싱(legacy는 obj, BE는 array — adapter 1줄), (2) `formatCityName.ts` 추가 + `index.ts` export, (3) city 미존재 시 legacy는 `throw`이나 FE는 graceful 권장(divergence 주석). RNG 무관 → 골든 불요. |
| **postFilterNationCommandGen** | fe-ts | `hwe/ts/utilGame/postFilterNationCommandGen.ts` | `web/game/lib/utilGame/postFilterNationCommandGen.ts` | formatCityName(↑), JosaUtil, defs `TurnObj` | N | tsc + 단위(che_발령 brief 치환) | 🔴 보류 | che_발령 turnObj의 brief/tooltip을 `《부대명》【도시명】로 발령`으로 후처리(`gameConst.cityConst[destCityID].name` + JosaUtil `로`). **선결**: formatCityName 배선 + JosaUtil 포팅 여부 확인(`common`의 `Josa` 커널이 BE에 있으나 FE TS josaPick 별도 필요). **현 FE가 che_발령 brief를 어디서 만드는지 먼저 확인**(SelectGeneralField/CommandModal). 발령 명령 자체가 FE 미배선이면 그룹 A/C 발령 작업과 동시 처리. |
| **getNewMsgToast** | fe-ts | `hwe/ts/utilGame/getNewMsgToast.ts` | (Vue 전용 — React 재설계 필요) | MessagePanel, ReadLatestMessage API | N | — | 🔴 보류(blocked 일부) | raw fidelity=5. legacy = 새 메시지 토스트 팩토리(title/body/액션2 '보러가기'·'이미읽음'/variant warning/10분 delay/채널 dedup/읽음처리). 현 `web/game/components/Toast.tsx`는 무관한 범용 토스트(3초 success/error/info). **Vue Toast 시스템 전제** → React 재설계 작업이며 Tier-0 포매터 시드 범위 밖. 읽음처리 API(`ReadLatestMessage`) game-api 엔드포인트 미발견 → 해당 동작 **blocked**. **이 항목은 Tier-0에서 제외하고 메시지 FE 패밀리(그룹 C)로 이관 권장.** |

---

## Area 2 — Scenario 도메인 (★ Tier-0 핵심 잔여 — B1/B2/B3 장수·국가 생성)

월드 부트스트랩(턴 명령 아님). legacy 총 ~1567L, **RNG 빌드 파이프라인 전무**. raw `comparisons[]` fidelity:
- `Scenario(world bootstrap)` = **18** ("핵심 RNG 빌드 파이프라인은 전혀 미포팅")
- `MakeGeneral` = **55** (draw 순서 byte-parity ✅ 골든 강함, 그러나 **production caller 0개**, GeneralBuilder 전체 미포팅)

### 레거시 규모 (직접 wc -l)

| legacy 파일 | 라인 | 역할 |
|---|---|---|
| `Scenario/GeneralBuilder.php` | **737** | 장수 빌더 — fillRandomStat/fillRemainSpecAsRandom/build + general·general_turn·rank_data INSERT |
| `Scenario.php` | 630 | 월드 빌드 오케스트레이터(getGameConf 조각만 포팅됨) |
| `Scenario/Nation.php` | **200** | 국가 빌더 — build(availableNationType choice) + postBuild(군주선출 + nation_turn 시드) |
| `AbsGeneralPool.php` | **111** | 장수 풀 추상 기반 |
| `GeneralPool/RandomNameGeneral.php` | **101** | 랜덤 장수명 풀(성/중간이름/끝이름 3 draw) |
| `GeneralPool/SPoolUnderU30.php` | — | U30 풀(JSON: `GeneralPool/Pool/UnderS30.json`) |

### GeneralBuilder.php RNG draw 종류 (★ 골든 필수 — 직접 grep)

draw-for-draw 패러티 대상. file:line은 `legacy/devsam-core/hwe/sammo/Scenario/GeneralBuilder.php`:

| draw | 메서드 호출 | line | 비고 |
|---|---|---|---|
| 특기(전특) | `SpecialityHelper::pickSpecialWar($rng, $general)` | :117, :124 | setSpecialOption '랜덤전특'/'랜덤' |
| 특기(내특) | `SpecialityHelper::pickSpecialDomestic($rng, $general)` | :120, :127 | setSpecialOption '랜덤내특'/'랜덤' |
| 랜덤 분기 | `$rng->nextBool(2/3)` | :123 | '랜덤' 시 전특/내특 택1 |
| 친화 | `$rng->nextRangeInt(1, 150)` | :177, :353, :413 | setAffinity / fillRemainSpecAsZero / fillRemainSpecAsRandom |
| 스탯 타입 | `$rng->choiceUsingWeight($pickTypeList)` | :314, :455 | fillRandomStat 통/무/지 가중선택 |
| 주스탯 | `defaultStatNPCMax - $rng->nextRangeInt(0, defaultStatNPCMin)` | :317 | mainStat |
| 부스탯 | `minStat + $rng->nextRangeInt(0, toInt(defaultStatNPCMin/2))` | :318 | otherStat |
| 성격(ego) | `$rng->choice(GameConst::$availablePersonality)` | :383, :489 | |
| 생몰 | `$env['year'] + $rng->nextRange(-5, 5)` / `birth + $rng->nextRangeInt(60, 80)` | :417, :418 | fillRemainSpecAsRandom birth/death |
| dex 분배 | `$rng->choice([[...],[...],[...]])` | :475 | 무 타입 dex 배열 택1 |
| 도시 배치 | `$rng->choice(CityHelper::getAllCities())` / `getAllNationCities($nationID)` | :640, :643 | cityID 미지정 시 |
| 턴타임 | `\sammo\getRandTurn($rng, $env['turnterm'], turntime)` | :656 | |
| killturn | `($death-$year)*12 + $rng->nextRangeInt(0, 11) + $month - 1` | :662 | birth 기반 |

→ **MakeGeneral.kt(entrance Join.php 경로)는 다른 draw 시퀀스**라 GeneralBuilder 시나리오 mint를 대체 불가(raw `comparisons` 명시). 별도 골든 필요.

### RandomNameGeneral.php draw (직접 grep, file:line `GeneralPool/RandomNameGeneral.php`)

| draw | 호출 | line |
|---|---|---|
| 성 | `$rng->choice(GameConst::$randGenFirstName)` | :35 |
| 중간이름 | `$rng->choice(GameConst::$randGenMiddleName)` | :36 |
| 끝이름 | `$rng->choice(GameConst::$randGenLastName)` | :37 |

`pickGeneralFromPool(db, rng, owner, pickCnt, prefix)` (:65) → `pickGeneral1FromPool` (:30) 루프.

### Nation.php draw (직접 grep, file:line `Scenario/Nation.php`)

| draw | 호출 | line | 비고 |
|---|---|---|---|
| 국가 타입 | `$rng->choice(GameConst::$availableNationType)` | :83 | type 미지정 시. **주의**: AI 건국(`GenFoundFamily`)에는 있으나 시나리오 시점 생성엔 없는 draw(raw 명시) |
| postBuild 군주선출 | `ORDER BY leadership+strength+intel DESC LIMIT 1` | :171 | RNG 아님 — 결정론 정렬 |
| postBuild nation_turn | 12..chiefLevel × maxChiefTurn '휴식' 시드 | :180-194 | RNG 아님 |

### DB write 형상 (build :668-734)

`general` INSERT(:668) → `general_turn` INSERT(:723, 30행 ring) → `rank_data` INSERT(:734). `GeneralCreateFlushIT`가 이미 (general + 30 general_turn + 37 rank_data) 형상 검증(MakeGeneral 경로). **JDBC-only — `infra/seed` 또는 `engine.boot` 패키지(write-path scan 밖, one-daemon-write-rule 비위반).** 기존 `ScenarioImporter.kt`(infra/seed) 옆에 위치 가능.

### 단위 표

| id | kind | legacy(file) | 대상 파일(제안) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| **B1 GeneralBuilder** | domain | `Scenario/GeneralBuilder.php` (737L) | `logic/world/GeneralBuilder.kt` (순수 draw) + `infra/seed/GeneralBuilderFlush.kt` (INSERT) | GameConst, SpecialityHelper(`pickSpecialWar/Domestic`), CityHelper, getRandTurn(✅ MakeGeneral 재사용) | **Y (필수)** | `GeneralBuilderGoldenTest` draw-for-draw + cursor + `GeneralBuilderFlushIT`(general+general_turn+rank_data) | 🔴 미포팅(MakeGeneral=다른 경로 55%) | (a) **골든 캡처**: `tools/php-golden` Docker(시나리오 1010) GeneralBuilder build draw 시퀀스 dump → `golden/scenario/장수빌더-*.json`. (b) 순수 draw 포팅: fillRandomStat(:313)·fillRemainSpecAsZero(:347)·fillRemainSpecAsRandom(:400)·build(:542) draw 순서 13종(위 표) line-for-line. (c) flush 형상은 MakeGeneral IT 패턴 재사용. (d) **/parity-close 급** — 단일 단위로 full draw+flush gate. |
| **B2 Scenario/Nation** | domain | `Scenario/Nation.php` (200L) | `logic/world/ScenarioNation.kt` + `infra/seed/ScenarioNationFlush.kt` | GameConst $availableNationType, B1(generals 카운트), KVStorage(nation_env) | **Y** (build :83 type choice) | `ScenarioNationGoldenTest`(type choice) + flush IT(nation update + nation_turn insertIgnore) | 🔴 미포팅 | (a) build(:70) — cities/capital 해석 + type choice(:83, 단 type 지정 시 draw 없음). (b) postBuild(:156) — 군주선출(결정론 정렬 :171) + nation_turn 휴식 시드(:180). (c) **현 ScenarioImporter.kt(infra/seed)가 이미 nation 행 INSERT 수행** → 중복/책임 경계 확인 필수(F1 시드와 충돌 회피). |
| **B3 GeneralPool / RandomNameGeneral** | domain | `AbsGeneralPool.php`(111L) + `GeneralPool/RandomNameGeneral.php`(101L) + `SPoolUnderU30.php` + `Pool/UnderS30.json` | `logic/world/pool/AbsGeneralPool.kt` + `RandomNameGeneral.kt` (+ JSON 리소스) | GameConst $randGen{First,Middle,Last}Name, B1(builder 위임) | **Y** (이름 3 draw + builder draw) | `RandomNameGeneralGoldenTest`(성/중간/끝 choice 순서 + builder) | 🔴 미포팅 | (a) `AbsGeneralPool` 추상(builder/info/uniqueName/validUntil 필드). (b) `RandomNameGeneral`: pickGeneral1FromPool 3 choice(:35-37) + occupyGeneralName(:14) 중복회피 루프. (c) `UnderS30.json`은 리소스로 커밋. (d) **NPC 생성 경로(RaiseNPCNation·CreateManyNPC) 소비처** → 그룹 A event 작업의 선결 토대. |
| **RaiseNPCNation (소비처, 참고)** | event | `Event/Action/RaiseNPCNation.php` | `logic/event/RaiseNPCNation.kt` | **B1+B3 선결** | Y | event golden | 🔴 미포팅 | 공백지 NPC 국가 자동생성(거리필터 + 평균도시/장수 산출 + `pickGeneralFromPool`). **B1/B3 완료 후 그룹 A에서 처리** — Tier-0 아님, 의존관계 명시용. |

### 의존관계 (B1-B3 → NPC/이민족 event)

```
B1 GeneralBuilder ──┐
                    ├─→ B3 GeneralPool/RandomNameGeneral ──→ (그룹A) RaiseNPCNation / CreateManyNPC / RegNPC
B2 Scenario/Nation ─┘                                    └─→ (그룹A) 이민족 event (RaiseInvader 계열)
```

B1이 모든 장수 mint의 draw 루트. B3는 B1을 위임 사용. B2는 B1 카운트 의존. **NPC/이민족 event(그룹 A)는 B1+B3가 선결**되어야 골든 가능.

### /parity-close 급 서브태스크 분해 (B1 예시)

1. `tools/php-golden` Docker로 GeneralBuilder build draw 캡처 → `golden/scenario/장수빌더-*.json` (시나리오 1010, fiction/non-fiction 분기 포함).
2. `logic/world/GeneralBuilder.kt` 순수 draw 포팅 — 위 13 draw line-for-line, `RandUtil` 1개 thread-by-reference.
3. `GeneralBuilderGoldenTest` — seed 재구성 + draw-for-draw byte parity + inner-DRBG cursor(stateIdx/bufferIdx) + 모든 outcome 필드.
4. `infra/seed/GeneralBuilderFlush.kt` — general/general_turn/rank_data INSERT (`GeneralCreateFlushIT` 패턴 재사용).
5. `GeneralBuilderFlushIT` — 영속 형상 검증(real Postgres).
6. 한 logical commit (`Co-Authored-By: Claude Opus 4.8 (1M context)`).

---

## Area 3 — trigger base (단독포팅 YAGNI → 첫 구체 트리거와 함께)

### 현황 대비 (직접 확인)

**현 logic = battle trigger 100% 완료** (raw `comparisons`):
- `logic/war/trigger/TriggerCaller.kt` — 추상 base(constructor/insert/sortOuterAscending=ksort/merge=array_merge/fire=draw order). fidelity 95.
- `logic/war/trigger/WarUnitTriggerCaller.kt` — battle subclass(`instanceof BaseWarUnitTrigger` 게이트). fidelity **100**.
- `logic/war/trigger/BaseWarUnitTrigger.kt` — battle trigger 추상.
- 골든: `BattleReplayGateTest`(draw+cursor parity) + `TriggerCallerOrderTest`(fire-order/dedup/merge).

**미포팅 = general(pre-turn-execute) 측 trigger** (각 9L 추상, 직접 cat 확인):
- `GeneralTriggerCaller.php` (9L 전문): `class GeneralTriggerCaller extends TriggerCaller` + `checkValidTrigger($t instanceof BaseGeneralTrigger)`.
- `BaseGeneralTrigger.php` (9L 전문): `abstract class BaseGeneralTrigger extends ObjectTrigger { __construct(General $general){$this->object=$general;} }`.

### 단위 표

| id | kind | legacy(file) | 대상 파일(제안) | 의존 | 골든 | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| GeneralTriggerCaller | domain | `GeneralTriggerCaller.php` (9L) | `logic/war/trigger/GeneralTriggerCaller.kt` | TriggerCaller.kt(✅), BaseGeneralTrigger | N(추상, 자체 draw 없음) | 첫 구체 트리거의 fire-order test | 🔴 미포팅 | **단독포팅 YAGNI** — checkValidTrigger = `trigger is BaseGeneralTrigger` 1줄. TriggerCaller.kt 추상 이미 존재. |
| BaseGeneralTrigger | domain | `BaseGeneralTrigger.php` (9L) | `logic/war/trigger/BaseGeneralTrigger.kt` (또는 general 패키지) | `Trigger`/`ObjectTrigger` 추상(현 war/trigger) | N | — | 🔴 미포팅 | `object = general` 만 보유하는 추상. WarUnit이 아닌 General을 object로 갖는 변형. |
| GeneralTrigger/che_도시치료 외 | domain | `GeneralTrigger/{che_도시치료,che_병력군량소모,che_부상경감,che_아이템치료}.php` | `logic/war/trigger/general/*.kt` | ↑ base 2종 | Y(트리거 발동 draw — 발동 시) | 트리거별 골든 | 🔴 미포팅 | **포팅 방침: 첫 구체 트리거(예: che_도시치료) 작업 시 base 2종을 함께 생성**(creator-then-consumer 단일 PR). 추상만 단독 포팅 금지(소비처 없는 dead abstract). |

**방침 요약**: GeneralTriggerCaller/BaseGeneralTrigger는 각 9L 추상 — 단독 PR이면 소비처 0개 dead code. WarUnitTriggerCaller 포팅 패턴(decision #2 append() 제외, fire=draw order, instanceof 게이트)을 그대로 따라 **첫 구체 GeneralTrigger와 묶어서 포팅**.

---

## Area 4 — wire 시드 (🔴 전제붕괴 → 그룹 A 흡수)

### 전제붕괴 설명

MASTER_GAP §5 / FOUNDING_SEAM_FIX가 가정한 "wire 시드"(intakeCodes + CommandWireMapper wire variants + TurnDaemonCommand variant를 Tier-0에서 1회 widening)는 **DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner 의 logic resolver가 존재한다는 전제** 위에 섰다. 실측(raw `missingPages[]` intake-api):

- `General/DieOnPrestart` — "TurnDaemonCommand.DieOnPrestart 정의는 common에 있으나 CommandWireMapper intakeCodes 미등록, dispatcher 미바인딩. 엔드포인트 없음."
- `General/DropItem` — 동일(common 정의만, intake/dispatcher/resolver 부재).
- `General/InstantRetreat` — 동일.

즉 **common에 enum variant만 있고 logic actions/* resolver·dispatcher 분기·intake가 전부 없다.** wire만 시드해도 dispatch 대상이 없어 deny/throw. ResetStat/CheckOwner도 logic 부재 동일.

→ **wire는 명령 자체의 일부**(resolver + registry + intake + dispatcher + golden이 한 단위). 따라서 **그룹 A 명령 포팅에 흡수**하며, 각 명령 /parity-close 시 자기 wire variant를 함께 widening한다. **단, 공유 파일(`CommandWireMapper.kt`, `TurnDaemonCommandDispatcher.kt`, intakeCodes)은 co-widen 금지** → 그룹 A 진입 직전 **Tier-0 마지막 단계로 "스텁 widening 1회"**(빈 케이스 추가)만 수행하고, 각 명령이 disjoint하게 자기 케이스 본문을 채운다.

### 그룹 A 흡수 매핑

| id | kind | legacy(file) | 흡수 대상(그룹 A 명령) | 의존 | 골든 | 상태 | 핵심 fixSpec |
|---|---|---|---|---|---|---|---|
| General/DieOnPrestart | intake-api | `API/General/DieOnPrestart.php` | 해당 명령 /parity-close (resolver+registry+intake+dispatcher) | common enum(✅), logic resolver(🔴) | Y(있으면) | 🔴 흡수 | logic resolver 신설 → CommandWireMapper intakeCodes 등록 → dispatcher 바인딩 → endpoint. wire는 이 단위 내부. |
| General/DropItem | intake-api | `API/General/DropItem.php` | 동일 | 동일 | Y | 🔴 흡수 | 아이템 드롭 resolver + flush(아이템 삭제 delta). |
| General/InstantRetreat | intake-api | `API/General/InstantRetreat.php` | 동일 | 동일 | Y | 🔴 흡수 | 즉시 퇴각 resolver. |
| ResetStat | intake-api | `API/General/ResetStat.php`(추정) | 동일 | 동일 | Y | 🔴 흡수 | 스탯 초기화 resolver. |
| CheckOwner | intake-api | (precheck/constraint 계열) | 해당 명령 precheck | Presets/Constraint | N | 🔴 흡수 | 명령별 owner 제약 — `Constraint`로 포팅. |

**Tier-0 잔여 = 그룹 A 진입 직전 공유 widening 1회뿐**:
1. `CommandWireMapper.kt` intakeCodes에 그룹 A 명령 코드 빈 케이스 추가(creator).
2. `TurnDaemonCommandDispatcher.kt` 빈 dispatch 케이스 추가(creator).
3. 이후 각 명령(consumer)이 disjoint 본문 채움 — co-widen 충돌 회피.

---

## Tier-0 종합 — 실질 잔여 작업

| Area | 상태 | 잔여 작업 |
|---|---|---|
| **1 utilGame** | ✅ 16종 완료(c2293ba) | formatCityName 배선(GetConst cityConst 이미 제공, 1 adapter), postFilterNationCommandGen(formatCityName+Josa 선결), getNewMsgToast → 메시지 FE 패밀리(그룹 C)로 이관 |
| **2 Scenario** | 🔴 핵심 잔여 | **B1 GeneralBuilder(737L, 13 draw, 골든+flush IT)** · B2 Scenario/Nation(200L) · B3 GeneralPool/RandomNameGeneral(212L, 3 draw) — /parity-close 급. NPC/이민족 event(그룹 A)의 선결 토대 |
| **3 trigger base** | 🔴 YAGNI 대기 | GeneralTriggerCaller+BaseGeneralTrigger(각 9L) — 첫 구체 트리거(che_도시치료 등)와 단일 PR |
| **4 wire 시드** | 🔴 흡수 | 전제붕괴 — 그룹 A 명령 포팅에 흡수, Tier-0은 공유 stub widening 1회만 |

**순서**: Area 1 보류 배선(경량) → **Area 2 B1→B3→B2**(★ 진짜 토대) → Area 4 공유 stub widening → (그룹 A 진입) → Area 3는 그룹 A 첫 트리거와 동행.


---


<a id="section-groupA"></a>

> ── 인라인: `docs/superpowers/gap/exec/01-groupA-commands-events.md` (그룹 worklist 전문) ──

# 실행계획 — 그룹 A: 명령 + 이벤트 액션 포팅

> 데이터 소스: `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (jq 슬라이스), legacy PHP grand truth grep. 날조 없음 — 모든 RNG-bearing 판정·file:line 근거는 실제 PHP grep 결과.
> PHP=grand truth(`legacy/devsam-core/hwe/sammo`). RNG draw 순서/개수/메서드 인자 = 패러티 타깃. 골든은 `tools/php-golden` 실제 캡처에서만, 날조 금지.
> 빌드 금지(읽기 전용 계획). 게이트는 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...`로 실행할 것(실행은 별도 세션).

## 모듈 매핑 규칙 (그룹 A 공통)

- **명령 포팅** = `logic/.../actions/<family>/<Cmd>.kt`(ActionDefinition: parseArgs/buildConstraints/resolve) + `logic/.../actions/CommandRegistry.kt`(when 분기 1줄) + `app/game-api/.../reserve/CommandWireMapper.kt`(intake 식별자, turn-reserved는 기본 미포함이 정책) + `app/game-api/.../web/AvailableCommandsController.kt`(GENERAL_COMMAND_CODES 카탈로그 노출) + `app/game-engine/.../turn/ReservedTurnHandler.kt`(registry.resolve 디스패치 — 이미 범용 배선됨) + golden.
- **이벤트 액션 포팅** = `logic/.../world/<Action>.kt`(EventAction leaf) + `logic/.../world/A3EventActions.kt`(`.register("<Key>") { ... }`) — `WorldActions.register`가 `A3EventActions.register`를 이미 체이닝(`WorldActions.kt:24`)하므로 A3 키 추가만으로 엔진 노출됨.
- **골든필요(RNG-bearing Y)** 기준: PHP run() 본문이 `$rng->next*/choice*/pick*`를 실제로 draw하면 Y → `tools/php-golden` 캡처 + draw-for-draw GoldenTest. draw 0이면 N → deterministic effect/log byte-parity GoldenTest로 충분(draw 시퀀스 게이트 불요, 0-draw 명시 assert).
- 등록 지점 근거: `CommandRegistry.kt:93-132`(when actionCode), `AvailableCommandsController.kt`(GENERAL_COMMAND_CODES), `ReservedTurnHandler.kt:259`(resolve 호출), `A3EventActions.kt:23-25`, `WorldActions.kt:20-36`.
- 계략 카탈로그는 **이미 존재**: `GameConst.kt:437-441` "계략" to [che_선동, che_탈취, che_파괴, che_화계], `GameConst.kt:430`(che_첩보). sabotage 상수 `GameConst.kt:34-35`(sabotageDamageMin=100/Max=800).

---

## A1 — 미포팅 명령 (29 command)

RNG-bearing 정밀 판정(legacy grep, type-hint 제외 본문 draw만):

| 파일 | 본문 draw | 골든 |
|------|-----------|------|
| che_화계 | nextRangeInt×2(피해) + nextBool(성공판정) + nextRangeInt 부상 + exp/ded nextRangeInt(성공/실패 각각) | **Y** |
| che_파괴 | nextRangeInt×2(def/wall) | **Y** |
| che_탈취 | nextRangeInt×2(gold/rice) | **Y** |
| che_선동 | nextRangeInt(secu) + nextRange(trust)/50 | **Y** |
| che_첩보 | nextRangeInt(1,100) exp + nextRangeInt(1,70) ded | **Y** |
| che_단련 | choiceUsingWeightPair + choiceUsingWeight | **Y** |
| che_접경귀환 | choice(nearestCityList) ×1 | **Y** |
| che_강행 | 본문 draw 0 (grep -c=1은 RandUtil type-hint) | **N** |
| che_숙련전환 | 0 | **N** |
| che_전투태세 | 0 | **N** |
| che_모반시도 | 0 | **N** |
| che_전투특기초기화 | 0 | **N** |
| che_내정특기초기화 | 0 (23줄, 박형 위임) | **N** |
| che_등용수락 | 0 (accept-trigger) | **N** |
| cr_인구이동 | 0 | **N** |

### A1 실행표

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트(테스트) | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| che_화계 | command | Command/General/che_화계.php:223,224,288,292,293,325,326 | logic/.../actions/military/CheHwagye.kt(신규) + CommandRegistry "che_화계" + AvailableCommandsController 카탈로그(GameConst 계략 이미 등록) + golden | sabotage 상수(GameConst:34-35, 존재), 계략 아이템 hook(ItemHooks.kt 존재), 부상($injuryGeneral) | **Y** | CheHwagyeGoldenTest(draw 순서: 피해 nextRangeInt×2 → 성공 nextBool → [실패분기: exp/ded nextRangeInt(1,100)/(1,70)] / [성공분기: 부상 + 아이템소비 + exp/ded nextRangeInt(201,300)/(141,210)]) byte-match + after-state | 🔴 미포팅 | resolve()에 run()(che_화계.php:55-352) 두 분기 draw-순서 그대로 이식: prob=sabotageDefaultProb+calcSabotageAttackProb-calcSabotageDefence, /dist, valueFit(0,0.5). agri/comm nextRangeInt valueFit(null,city). nextBool(prob) 실패→exp/ded+statType_exp+1+로그; 성공→부상+계략아이템 tryConsumeNow+exp/ded(201-300/141-210)+statType_exp. PhpRound·valueFit·JosaUtil. CheMuljaJodal.kt:58-94가 draft/rng/log API 레퍼런스 |
| che_파괴 | command | Command/General/che_파괴.php:33,34 | logic/.../actions/military/ChePagoe.kt + CommandRegistry + 카탈로그 + golden | sabotage 상수, 도시 def/wall 변이 seam | **Y** | ChePagoeGoldenTest(nextRangeInt×2 → city def/wall valueFit 감소 + 로그) draw-for-draw | 🔴 미포팅 | resolve(): defAmount/wallAmount = valueFit(nextRangeInt(min,max), null, city[def/wall]); city.def/wall 차감 draft 적재; secuAmount/번호서식 로그. 60줄 소형 — 화계 prob/부상 없음(피해+로그만) |
| che_탈취 | command | Command/General/che_탈취.php:39,40 | logic/.../actions/military/CheTalchwi.kt + CommandRegistry + 카탈로그 + golden | sabotage 상수, yearCoef, commRatio/agriRatio, 국가 gold/rice 적립 seam | **Y** | CheTalchwiGoldenTest(gold/rice nextRangeInt×2 + level·yearCoef·ratio 곱 + PhpRound + 본인/국가 자원 이동) | 🔴 미포팅 | resolve(): gold=nextRangeInt(min,max)*city.level*yearCoef*(0.25+commRatio/4); rice 동형(agriRatio). PhpRound(half-away) 적용, toInt 절단 구분. 탈취 자원 본인/국가 적립 draft + 로그 |
| che_선동 | command | Command/General/che_선동.php:34,36 | logic/.../actions/military/CheSeondong.kt + CommandRegistry + 카탈로그 + golden | sabotage 상수, city secu/trust 변이, 부상 count | **Y** | CheSeondongGoldenTest(secu nextRangeInt → trust nextRange/50 → injuryCount 로그) draw 순서 | 🔴 미포팅 | resolve(): secuAmount=valueFit(nextRangeInt,null,city.secu); trustAmount=valueFit(nextRange(min,max)/50,...); city.secu/trust 차감; injuryCount + number_format 로그("치안 …, 민심 …, 장수 …명 부상"). nextRange(실수) vs nextRangeInt(정수) draw 구분 |
| che_첩보 | command | Command/General/che_첩보.php:205,206 | logic/.../actions/military/CheCheobo.kt + CommandRegistry + 카탈로그(GameConst:430 존재) + golden | spy KV(nation.spy json), dist 분기, 첩보 fog 가시성(이미 구현 6a2b1f9) | **Y** | CheCheoboGoldenTest(dist 분기 로그 + spy[destCity]=3 KV + exp nextRangeInt(1,100) + ded nextRangeInt(1,70)) | 🔴 미포팅 | resolve(): dist별 정보로그(0/2/else 분기), nation.spy json[destCityID]=3 KV write, exp/ded nextRangeInt, increaseInheritancePoint(active_action, **0.5** — 첩보만 예외 주석 php:213), gold/rice 차감, leadership_exp+1, StaticEventHandler, checkStatChange. exportJSVars(cities/distanceList)는 read DTO |
| che_단련 | command | Command/General/che_단련.php:89,117 | logic/.../actions/develop/CheDanryeon.kt + CommandRegistry + 카탈로그 + golden | choiceUsingWeightPair/choiceUsingWeight RNG, 무병사 가드 | **Y** | CheDanryeonGoldenTest(choiceUsingWeightPair → choiceUsingWeight 2-draw 순서 + incStat) | 🔴 미포팅 | resolve(): [pick,multiplier]=choiceUsingWeightPair([...]); incStat=choiceUsingWeight([leadership,strength,intel]). 병사 없는 단련(crew==0 가드). 능력경험치 증가 + 로그. CheGyeonmun(A2)과 SightseeingMessage 가중치 패턴 공유 |
| che_접경귀환 | command | Command/General/che_접경귀환.php:92 | logic/.../actions/military/CheJeopgyeongGwihwan.kt + CommandRegistry(미등록 — missing-port) + 카탈로그 + golden | nearestCityList(국경 인접) 계산, CheGwihwan.kt 형제 | **Y** | CheJeopgyeongGwihwanGoldenTest(choice(nearestCityList) 1-draw → city 이동 + 로그) | 🔴 미포팅 | che_귀환(CheGwihwan.kt)과 **별개 커맨드**(접경=인접 적/공백 도시로 귀환). nearestCityList 구성(php:75-92) 후 destCityID=rng.choice(list). general.city 변경 draft + "{도시}로 귀환" 로그. choice 인자(list 순서)가 draw 패러티 핵심 |
| che_강행 | command | Command/General/che_강행.php | logic/.../actions/military/CheGanghaeng.kt + CommandRegistry + 카탈로그(GameConst:434 존재) + golden(deterministic) | 이동거리/소모 계산, CheIdong.kt 형제 | **N** | CheGanghaengGoldenTest(0-draw 명시 + 이동 경로/추가소모 effect/log byte) | 🔴 미포팅 | 강행군(무리한 이동) — 일반 이동 대비 더 먼 거리·추가 gold/rice 소모. draw 없음. CheIdong.kt 경로계산 재사용 + 강행 추가비용. deterministic — 캡처 후 effect/log 고정 |
| che_숙련전환 | command | Command/General/che_숙련전환.php:159-173 | logic/.../actions/military/CheSukryeonJeonhwan.kt + CommandRegistry + 카탈로그 + golden(det) | dex{armType} 변수, getDexLevelList | **N** | CheSukryeonJeonhwanGoldenTest(0-draw + dex 이전 + 로그) | 🔴 미포팅 | srcDex=dex{srcArmType}, cutDex 차감, addDex를 dex{destArmType}에 가산(php:159-166). "{src}숙련 {cut}을 {dest}숙련 {add}로 전환" 로그(JosaUtil 을/로). exportJSVars(armType/dexLevelList)는 read. draw 없음 |
| che_전투태세 | command | Command/General/che_전투태세.php:53-55 | logic/.../actions/military/CheJeontuTaese.kt + CommandRegistry + 카탈로그 + golden(det) | crew, train/atmos margin, techCost | **N** | CheJeontuTaeseGoldenTest(0-draw + train/atmos 변이 + cost) | 🔴 미포팅 | cost=[round(crew/100*3*techCost),0](php:55, PhpRound). 전투태세 전환 시 훈련/사기 변동. constraints(php:39-47): NotBeNeutral/NotWanderingNation/OccupiedCity/ReqGeneralCrew/ReqGeneralGold/ReqGeneralRice/ReqGeneralTrainMargin(max-10)/ReqGeneralAtmosMargin(max-10) 정확 이식. draw 없음 |
| che_모반시도 | command | Command/General/che_모반시도.php:69-96 | logic/.../actions/nation/CheMobanSido.kt + CommandRegistry + 카탈로그 + golden(det) | officer_level 12=군주 쿼리, 군주 강등 | **N** | CheMobanSidoGoldenTest(0-draw + officer_level swap + 로그 4종) | 🔴 미포팅 | WAVE_7.md 신규계획 항목. lordID=군주(officer_level=12) 쿼리; general.officer_level=12, lord.officer_level=1(php:81-83). 【모반】 globalHistory + generalAction "모반 성공" + generalHistory + lordLogger history(박탈) 4종 로그(JosaUtil 이/가). draw 없음 |
| che_전투특기초기화 | command | Command/General/che_전투특기초기화.php | logic/.../actions/personnel/CheJeontuTeukgiChogihwa.kt + CommandRegistry + 카탈로그 + golden(det) | 전투특기 슬롯 reset, 비용 | **N** | CheJeontuTeukgiChogihwaGoldenTest(0-draw + 특기 클리어 + cost/log) | 🔴 미포팅 | InheritResets.kt(유산포인트 reset)와 **별개 서브시스템**. 전투특기(specialWar 류) 슬롯을 초기화. 비용 차감 + 로그. draw 없음. che_내정특기초기화와 형제 |
| che_내정특기초기화 | command | Command/General/che_내정특기초기화.php(23줄) | logic/.../actions/personnel/CheNaejeongTeukgiChogihwa.kt + CommandRegistry + 카탈로그 + golden(det) | 내정특기(specialDomestic) reset; 전투특기초기화 위임형 | **N** | CheNaejeongTeukgiChogihwaGoldenTest(0-draw + 특기 클리어) | 🔴 미포팅 | 23줄 박형 — che_전투특기초기화 로직 위임/공유(대상 특기 종류만 내정으로 교체). 전투특기초기화 포팅 후 파생. draw 없음 |
| che_등용수락 | command | Command/General/che_등용수락.php(217줄) | logic/.../actions/personnel/CheDeungyongSurak.kt + CommandRegistry + (intake: accept-trigger 경로) + golden(det) | 등용 메시지(DiplomaticMessage 류 scout) 소비, CheDeungyong.kt 짝 | **N** | CheDeungyongSurakGoldenTest(0-draw + 국가 이적 effect + 로그) | 🔴 미포팅 | P6 deferred(CheDeungyong.kt 주석). non-reservable **accept-trigger**(예약커맨드 아님) — 등용 제의 메시지 수락 시 장수 belong/nation 이적. che_불가침수락(A2)과 동일하게 메시지-수락 intake 경로 배선 필요. draw 없음 |
| cr_인구이동 | command | Command/Nation/cr_인구이동.php(197줄) | logic/.../actions/nation/CrInguIdong.kt + CommandRegistry "cr_인구이동" + AvailableCommandsController 국가카탈로그 + golden(det) | 도시 pop 이동(src→dest), 국가 커맨드(cr_) | **N** | CrInguIdongGoldenTest(0-draw + 두 도시 pop 변이 + 로그) | 🔴 미포팅 | cr_ = 국가 커맨드 패밀리(NationCommand). src 도시 인구를 dest 도시로 이동(거리·상한 제약). cr_건국(CrGeonguk.kt) 패턴 참조. draw 없음 |

#### Area4 흡수분 (Tier-0 Area4 wire 전제붕괴 → A1로 흡수)

> 정정 근거: DieOnPrestart/DropItem/InstantRetreat/ResetStat/CheckOwner 가 logic에 부재. **이들은 `Command`가 아니라 `legacy/.../API/` 핸들러**(instant/inherit-action). 명령 포팅과 wire 시드를 분리하지 말고 함께 처리.

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| DieOnPrestart | command(API/instant) | API/General/DieOnPrestart.php(extends BaseAPI) | logic/.../actions/instant/DieOnPrestart.kt + instant-action registry + game-api intake(instant) | 프리스타트(개전 전) 게이트, general 사망 처리 | **N** | DieOnPrestartGoldenTest(0-draw + 사망 effect/로그) | 🔴 미포팅 | 개전 전(prestart) 장수 사망 instant API. Command 아님 — BaseAPI 핸들러를 logic ActionDefinition + game-api instant intake로 모델링. draw 없음 |
| DropItem | command(API/instant) | API/General/DropItem.php(extends BaseAPI) | logic/.../actions/instant/DropItem.kt + instant registry + game-api intake | 보유 아이템 제거(item KV), 본인 effect | **N** | DropItemGoldenTest(0-draw + item 슬롯 clear + 로그) | 🔴 미포팅 | 아이템 버리기 instant. 장수 item 슬롯 비우기 draft + 로그. draw 없음 |
| InstantRetreat | command(API/instant wrapper) | API/General/InstantRetreat.php:64(commandObj->run(new RandUtil(...))) | logic/.../actions/instant/InstantRetreat.kt + instant registry + game-api intake | **내부에서 다른 Command를 RandUtil로 실행**(wrapper) — 대상 즉시퇴각 커맨드 의존 | **Y** | InstantRetreatGoldenTest(wrapped command draw-stream 보존) | 🔴 미포팅 | 즉시 퇴각 instant — 내부에서 commandObj.run(RandUtil(seed))로 퇴각 커맨드 실행(php:64). wrapped 커맨드의 draw가 그대로 노출되므로 **골든 Y**. RandUtil 시드 구성·draw 위임이 패러티 핵심 |
| ResetStat | command(API/InheritAction) | API/InheritAction/ResetStat.php:148(nextRangeInt(3,5)),149(choiceUsingWeight) | logic/.../actions/instant/inherit/ResetStat.kt + inherit-action registry + game-api intake | 유산포인트 reset 서브시스템, 능력치 재배분 | **Y** | ResetStatGoldenTest(nextRangeInt(3,5) → choiceUsingWeight 루프 draw-for-draw) | 🔴 미포팅 | 능력치 초기화 inherit-action. `foreach range(nextRangeInt(3,5)) { choiceUsingWeight([leadership,strength,intel]) }`(php:148-149) — 가변길이 루프 draw. 골든 Y. InheritResets.kt 인접 |
| CheckOwner | command(API/InheritAction) | API/InheritAction/CheckOwner.php(extends BaseAPI) | logic/.../actions/instant/inherit/CheckOwner.kt + inherit-action registry + game-api intake | 유산 소유권 검증(read-ish), 0-draw | **N** | CheckOwnerGoldenTest(0-draw + 소유권 판정/응답) | 🔴 미포팅 | 유산 아이템 소유권 확인 inherit-action. draw 없음. 대부분 검증/응답 — read seam에 가까우나 instant intake로 모델링 |

---

## A2 — 부분포팅 명령 run()/resolve() 마감 (등록O, 본체 스텁/누락 + 골든 부재)

> 공통: 등록·constraint·argTest는 이미 충실. **resolve() 본체 + 골든이 핵심 갭.** 외교 4종(종전/불가침)은 **DiplomaticMessage 발송 effect + message intake/flush가 공통 인프라 갭** — 한 번에 넓혀라(che_불가침수락/파기수락/종전수락은 instant-nation registry 미배선까지 공통).

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트(테스트) | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| che_견문 | command | Command/General/che_견문.php:55-122 | logic/.../actions/develop/CheGyeonmun.kt:39(스텁 resolve) + SightseeingMessage 테이블(신규) + golden | SightseeingMessage 17버킷 테이블 포팅, tryUniqueItemLottery/checkStatChange/StaticEventHandler(존재) | **Y** | Che견문GoldenTest(pickAction **정확히 2-draw**: choiceUsingWeightPair → choice, type 비트마스크별 exp/gold/rice/stat_exp, Wounded nextRangeInt(10,20)/(20,50) cap80) draw-for-draw + after-state(exp/gold/rice/injury/*_exp) | 🟡 스텁(35) | resolve()에 run() 전체 이식: (1)SightseeingMessage.pickAction 2-draw(choiceUsingWeightPair=버킷, choice=텍스트, php SightseeingMessage.php:106-111 순서). (2)type 비트마스크 exp/leadership_exp/strength_exp/intel_exp/gold/rice 증감 + DecGold/DecRice floor-0(increaseVarWithLimit). (3)Wounded/HeavyWounded nextRangeInt(10,20)/(20,50) cap80. (4):goldAmount:/:riceAmount: 치환 로그 → addExperience→checkStatChange→StaticEventHandler→tryUniqueItemLottery(php:111→117 순서). CheMuljaJodal.kt:58-94 레퍼런스 |
| che_해산 | command | Command/General/che_해산.php:62-119 + func.php:1713-1805(deleteNation) | logic/.../actions/founding/CheHaesan.kt:41(스텁) + golden | deleteNation cascade(GAP-WORLD seam), OccupyCity 이벤트, alternative 표현(GeneralActionDraft) | **N** | Che해산GoldenTest(0-draw 명시 + gold/rice 절삭 + cascade set + 로그 3종 + makelimit=12) byte/델타 | 🟡 스텁(35) | resolve() 충실 이식: (1)<init-turn: yearMonth<=init이면 "다음 턴부터 해산" 로그 + alternative=che_인재탐색 + early-return(GeongukTest.kt:253 lastAlternative 패턴). (2)국가 전 장수 gold>defaultGold→절삭, **gold>defaultRice→rice 절삭(legacy 버그 byte-동일 재현, php:90-95)**. (3)deleteNation cascade: belong/troop/officer_level/officer_city/nation=0, permission=normal, max_belong aux, PLAIN 멸망 로그+history, 【멸망】 global history, 도시 nation=0/front=0, troop/nation/nation_turn/diplomacy 삭제, ng_old_nations 보존. (4)군주 makelimit=12. (5)로그 3종(세력해산/global/history). (6)OccupyCity는 엔진 seam 위임 명기. draw 0 |
| che_인재탐색 | command | Command/General/che_인재탐색.php:55-(실패/성공 분기) | logic/.../actions/personnel/CheInjaeTamsaek.kt:47(스텁) + golden | NPC-pool 생성(P6 write-seam), pickGeneralFromPool, foundProp 월드쿼리 시드, fillRemainSpecAsRandom | **Y** | Che인재탐색GoldenTest(실패+성공 두 시드: foundProp nextBool → [실패: choiceUsingWeight, exp+100/ded+70/stat+1] / [성공: age nextRangeInt(20,25), deathYear delta nextRangeInt(10,50), pickGeneralFromPool 이름 picking, exp+200/ded+300/stat+3]) draw-for-draw | 🟡 스텁(35) | resolve() 두 분기 draw-순서: foundProp=calcFoundProp(maxgeneral, npc count들)→totalGen/totalNpcCnt는 월드쿼리 시드 ctx 주입(ReservedTurnHandler.kt:252-253 패턴). foundNpc=nextBool(foundProp). 실패: choiceUsingWeight(경험가중)+gold-=req(0하한)+exp+100+ded+70+incStat1+checkStatChange+tryUniqueItemLottery(genGenericUnique '인재탐색'). 성공: age nextRangeInt(20,25), deathYear delta nextRangeInt(10,50), pickGeneralFromPool(0,1) 이름(CheUibyeongMojip.kt:89), fillRemainSpecAsRandom, increaseInheritancePoint(P6 seam no-write 주석), 자원차감+exp200/ded300/stat3+StaticEventHandler+tryUniqueItemLottery. 로그 3종. NPC row insert는 P6 write-seam 위임(draw 순서만 정확히 소비) |
| che_종전제의 | command | Command/Nation/che_종전제의.php(run, draw 0) | logic/.../actions/nation/CheJongjeonJeui.kt(resolve 로그만) + **DiplomaticMessage 발송 effect**(공통) + golden | message:send effect kind(신규), message store/mailbox flush, validUntil=max(30,turnterm*3) | **N** | Che종전제의GoldenTest(0-draw + DiplomaticMessage(TYPE_STOP_WAR) payload/validUntil/title + 장수로그 byte) + intake IT(메일박스 적재) | 🟡 부분(35) | (1)message:send effect 추가 → resolve()가 src(장수/국가 color/image)·dest(destNation id0/name/color)·title='{국명}의 종전 제의 서신'·validUntil=now+max(30,turnterm*3)분·option{action:STOP_WAR,deletable:false} emit. (2)ReservedTurnHandler/instant 경로에서 message store flush 배선. (3)dest ActionLogger(빈 flush 확인). (4)StaticEventHandler 훅(외교 공통). che_종전수락 짝 |
| che_불가침제의 | command | Command/Nation/che_불가침제의.php(run, draw 0) | logic/.../actions/nation/CheBulgachimJeui.kt(로그만) + DiplomaticMessage effect(공통) + golden | message:send effect, mailbox(9000+destNationId), turnterm | **N** | Che불가침제의GoldenTest(0-draw + DiplomaticMessage(TYPE_NO_AGGRESSION) year/month payload/validUntil + 로그) + intake IT | 🟡 부분(35) | constraints(beChief/notBeNeutral/existsDestNation/differentDestNation/reqMinimumTreatyTerm 6개월/disallowDiplomacyBetweenStatus) 이미 충실. (1)외교 메시지 effect emit: title '{국명}와 {year}년 {month}월까지 불가침 제의 서신', action='no_aggression', payload{year,month}, validUntil=max(30,turnterm*3). (2)메일함 insert flush. (3)setResultTurn(LastTurn), StaticEventHandler. che_불가침수락이 소비할 페이로드 |
| che_불가침파기제의 | command | Command/Nation/che_불가침파기제의.php(run, draw 0) | logic/.../actions/nation/CheBulgachimPagiJeui.kt(로그만) + DiplomaticMessage effect(공통) + golden | message:send effect, custom actionContextBuilder(turnterm 주입), che_불가침파기수락 짝 | **N** | Che불가침파기제의GoldenTest(0-draw + DiplomaticMessage(TYPE_CANCEL_NA, deletable=false) + 로그) + intake IT | 🟡 부분(35) | (1)message-send effect: msgType=diplomacy, mailbox=9000+destNationId, validUntil=now+max(30,turnterm*3), option{action:cancel_na, deletable:false}. (2)turnterm 주입 위해 custom actionContextBuilder(che_불가침제의 패턴). (3)setResultTurn/StaticEventHandler. (4)che_불가침파기수락이 키로 찾아 소비. FE submit(reqArg destNationId) |
| che_불가침수락 | command(instant-nation) | Command/Nation/che_불가침수락.php | logic/.../actions/instant/nation/che_불가침수락(현 ActionDef 실재, orphaned) → opensamguk logic 포팅 + **instant-nation registry**(공통) + message-accept intake | instant-nation registry/loader(신규, 형제 공통), recv_assist/resp_assist KV, destNation 이름 조회 | **N** | Che불가침수락GoldenTest(0-draw + diplomacy:patch state=7/term + resp_assist KV + 로그 4종) + accept intake IT | 🟡 orphaned(35) | (1)instant-nation registry/loader 신설(키 che_불가침수락 +형제). (2)message-accept intake: 수락 시 ActionDef load→constraint(hasFullConditionMet)→resolve, 실패시 INVALID. (3)resp_assist["n{id}"]=[id, recv[..][1]??0] KV effect. (4)로그 4종(actor action+history, dest general+history "{국명}와 {year}년 {month}월까지 불가침에 성공"). (5)destNationName=실제 국명+JosaUtil('와')(현재 숫자 id). (6)constraint 추가 ReqDestNationValue('nation','소속','==',destGeneral.nationID). (7)argTest: destGeneralId≠self, year>=startYear |
| che_불가침파기수락 | command(instant-nation) | Command/Nation/che_불가침파기수락.php | logic/.../actions/instant/nation/che_불가침파기수락(ActionDef 실재, orphaned) → opensamguk 포팅 + instant-nation registry(공통) + intake | instant-nation registry(공통), 양방향 diplomacy patch, destNation 이름 | **N** | Che불가침파기수락GoldenTest(0-draw + diplomacy 양방향 state=2/term=0 + 로그 6종 + StaticEventHandler) | 🟡 orphaned(35) | (1)registry/intake 공통 배선(형제와 함께). (2)resolve() 누락 effect: 로그 6종(현재 1건) + destGeneral 로그 + StaticEventHandler 등가. (3)diplomacy 양방향 state=2,term=0. (4)destNationName 국명 조회(숫자 id 수정). (5)constraint 소속검증(level>0 치환 → nation==destGeneral.nationID) |
| che_종전수락 | command(instant-nation) | Command/Nation/che_종전수락.php | logic/.../actions/instant/nation/che_종전수락(ActionDef 실재, resolve 2 patch+9 log) → opensamguk 포팅 + instant-nation registry(공통) + message-accept intake | instant-nation registry/loader(공통), SetNationFront effect, diplomacy:patch(엔진 지원 inMemoryWorld.applyDiplomacyPatch) | **N** | Che종전수락GoldenTest(0-draw + diplomacy 양방향 state=2/term=0 + 9 log 순서 + SetNationFront×2) | 🟡 orphaned(45) | (1)INSTANT_NATION_COMMAND_KEYS + loader 신설(종전수락+불가침수락+불가침파기수락 공통). (2)diplomacy 종전 메시지 수락 intake: load→constraint→resolve→flush(diplomacy:patch 엔진 지원). (3)누락 effect: SetNationFront(nationId+destNationId, patch 후), StaticEventHandler. (4)constraint 치환 복원(level>0 → ReqDestNationValue nation==destGeneral.nationID). (5)dest-side 로그 flush 순서 |

#### A2 공통 인프라 (외교 4종 + accept 3종이 공유 — 1회 넓힘)

| 인프라 | 대상 | 소비처 | 노트 |
|--------|------|--------|------|
| **message:send effect kind** | logic actions engine effect 유니온 + ReservedTurnHandler/instant flush 분기 + message store/mailbox(diplomacy=9000+destNationId) | che_종전제의/불가침제의/불가침파기제의 | validUntil=now+max(30,turnterm*3)분 공통 공식. turnterm 주입 위해 custom actionContextBuilder 필요 |
| **instant-nation registry/loader** | INSTANT_NATION_COMMAND_KEYS + 동적 importer + loadInstantNationActionSpecs + 엔진 definition map | che_종전수락/불가침수락/불가침파기수락(현 orphaned) | turn 커맨드의 NATION_TURN_COMMAND_KEYS 대응물. 메시지-수락 intake가 이걸로 dispatch |
| **message-accept intake** | game-api 외교 메시지 accept 엔드포인트(router/diplomacy respondLetter agree 분기) → ActionDef load→constraint→resolve→flush | accept 3종 + che_등용수락(A1) | 현재 agree 분기는 letter를 ACTIVATED로만 flip하고 커맨드 미실행 → 수락이 무효 |
| **StaticEventHandler 외교 훅** | 외교 커맨드 실행 후 정적 이벤트 핸들러 호출 공통 메커니즘 | 외교 7종 공통 | StaticEventHandler.kt 존재 — 외교 커맨드 resolve 후 호출 지점 배선 |

---

## A3 — 이민족/NPC 이벤트 액션 (12 event)

RNG-bearing 정밀 판정(legacy Event/Action grep):

| 파일 | 본문 draw | 골든 |
|------|-----------|------|
| RaiseInvader | choice(capital) + nextRangeInt(leadership/mainStat 등) ×다수 | **Y** |
| InvaderEnding | 0 | **N** |
| AutoDeleteInvader | 0 | **N** |
| RaiseNPCNation | choice(cities) + choice(colors) + pickGeneralFromPool 등 | **Y** |
| RegNPC | 0 (외부 주입 NPC 등록만) | **N** |
| RegNeutralNPC | 0 | **N** |
| CreateManyNPC | pickGeneralFromPool + nextRangeInt(age 등) ×다수 | **Y** |
| CreateAdminNPC | 0 (13줄) | **N** |
| BlockScoutAction | 0 | **N** |
| UnblockScoutAction | 0 | **N** |
| ChangeCity | 0 | **N** |
| LostUniqueItem | nextBool(lostProb) | **Y** |

### A3 실행표

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요 | 게이트(테스트) | 상태 | 핵심 fixSpec/서브태스크 |
|----|------|--------------|----------------------|------|:--:|--------|------|------------------------|
| RaiseInvader | event | Event/Action/RaiseInvader.php:76,240,241(+다수) | logic/.../world/RaiseInvaderAction.kt(신규) + A3EventActions.register("RaiseInvader") | Tier-0 Area2(NPC/도시 생성 seam), capital 후보 계산, findNextCapital(BFS — PHP wins), 스펙 nextRangeInt | **Y** | RaiseInvaderActionTest + Golden(choice(capitalCandidates) → 장수 leadership/mainStat nextRangeInt(specAvg*1.2~1.4) 루프) draw-for-draw | 🔴 미포팅 | 게임 후반 핵심 이벤트(이민족 침략자 발생). newCapital=choice(capitalCandidates)(php:76). 침략 NPC 다수 생성: leadership/mainStat=nextRangeInt(toInt(specAvg*1.2), toInt(specAvg*1.4))(php:240-241) 등. NPC row 생성은 Area2 seam 위임, draw 순서만 정확. A3EventActions.kt:23-25 register 패턴 |
| InvaderEnding | event | Event/Action/InvaderEnding.php(73줄) | logic/.../world/InvaderEndingAction.kt + A3EventActions.register | 침략자 종료 정리, 보상/로그 | **N** | InvaderEndingActionTest(0-draw + 종료 effect/로그) | 🔴 미포팅 | 침략자 이벤트 종료(보상/정리). draw 없음. RaiseInvader 짝 |
| AutoDeleteInvader | event | Event/Action/AutoDeleteInvader.php(45줄) | logic/.../world/AutoDeleteInvaderAction.kt + A3EventActions.register | 침략자 자동 삭제(조건부 NPC/국가 제거) | **N** | AutoDeleteInvaderActionTest(0-draw + 삭제 cascade) | 🔴 미포팅 | 침략자 자동 삭제(만료 조건). draw 없음 |
| RaiseNPCNation | event | Event/Action/RaiseNPCNation.php:60,158(+pickGeneralFromPool) | logic/.../world/RaiseNPCNationAction.kt + A3EventActions.register | Area2 NPC/국가 생성 seam, GetNationColors, pickGeneralFromPool | **Y** | RaiseNPCNationActionTest + Golden(choice(cities) → choice(GetNationColors) → pickGeneralFromPool 이름 picking) draw-for-draw | 🔴 미포팅 | NPC 국가 발생. target=choice(cities)(php:60), color=choice(GetNationColors())(php:158), 군주/장수 pickGeneralFromPool. draw 순서가 패러티 핵심. 국가/도시/장수 row는 Area2 seam |
| RegNPC | event | Event/Action/RegNPC.php(61줄, draw 0) | logic/.../world/RegNPCAction.kt + A3EventActions.register | Area2 장수 등록 seam(외부 주입 NPC) | **N** | RegNPCActionTest(0-draw + general row 적재) | 🔴 미포팅 | NPC 장수 등록(사전 정의 NPC를 월드에 주입). 본문 draw 없음(pickGeneralFromPool도 미사용). general 생성 seam만 |
| RegNeutralNPC | event | Event/Action/RegNeutralNPC.php(59줄, draw 0) | logic/.../world/RegNeutralNPCAction.kt + A3EventActions.register | Area2 장수 등록 seam(nation=0 중립) | **N** | RegNeutralNPCActionTest(0-draw + 중립 general row) | 🔴 미포팅 | 중립 NPC 장수 등록(nation=0). RegNPC 변형. draw 없음 |
| CreateManyNPC | event | Event/Action/CreateManyNPC.php:38,39(+다수) | logic/.../world/CreateManyNPCAction.kt + A3EventActions.register | Area2 NPC 생성 seam, pickGeneralFromPool, fillRemainSpecAsRandom | **Y** | CreateManyNPCActionTest + Golden(pickGeneralFromPool(0,cnt) → age nextRangeInt(20,25) 루프 + spec draw) draw-for-draw | 🔴 미포팅 | 다수 NPC 일괄 생성. pickGeneralFromPool(db,rng,0,cnt)(php:38), 각 age=nextRangeInt(20,25)(php:39) + fillRemainSpecAsRandom. CheUibyeongMojip NPC 패턴. draw 순서/개수(cnt 루프) 핵심 |
| CreateAdminNPC | event | Event/Action/CreateAdminNPC.php(13줄, draw 0) | logic/.../world/CreateAdminNPCAction.kt + A3EventActions.register | Area2 장수 생성 seam(행정 NPC) | **N** | CreateAdminNPCActionTest(0-draw + admin general) | 🔴 미포팅 | 행정 NPC 생성(13줄 박형). draw 없음. CreateManyNPC/Reg* 위임 가능성 — legacy 확인 |
| BlockScoutAction | event | Event/Action/BlockScoutAction.php(24줄, draw 0) | logic/.../world/BlockScoutAction.kt + A3EventActions.register | 정찰/임관 차단 플래그(env/global KV) | **N** | BlockScoutActionTest(0-draw + block 플래그 set) | 🔴 미포팅 | 정찰(스카웃/임관) 차단 플래그 set. draw 없음. env/global KV 토글 |
| UnblockScoutAction | event | Event/Action/UnblockScoutAction.php(25줄, draw 0) | logic/.../world/UnblockScoutAction.kt + A3EventActions.register | 차단 해제 플래그 | **N** | UnblockScoutActionTest(0-draw + block 해제) | 🔴 미포팅 | 정찰 차단 해제. BlockScoutAction 짝. draw 없음 |
| ChangeCity | event | Event/Action/ChangeCity.php(189줄, draw 0) | logic/.../world/ChangeCityAction.kt + A3EventActions.register | 도시 속성(level/region/supply 등) 변경 effect | **N** | ChangeCityActionTest(0-draw + 도시 속성 patch) | 🔴 미포팅 | 도시 속성 변경 이벤트(189줄 — 여러 속성 분기). draw 없음. 도시 row patch draft |
| LostUniqueItem | event | Event/Action/LostUniqueItem.php:61(nextBool(lostProb)) | logic/.../world/LostUniqueItemAction.kt + A3EventActions.register | 유니크 아이템 KV, 확률 분실 | **Y** | LostUniqueItemActionTest + Golden(nextBool(lostProb) → item clear/로그) draw-for-draw | 🔴 미포팅 | 유니크 아이템 확률 분실. nextBool(lostProb)(php:61) 단일 draw 후 분실 시 item 슬롯 clear + 로그. 1-draw 골든 |

---

## 실행 순서 권고 (의존·foundation-first)

1. **A2 공통 인프라 먼저**(message:send effect, instant-nation registry/loader, message-accept intake, StaticEventHandler 외교훅) — 외교 7종이 전부 consume. 단일 creator-then-consumer 시퀀스.
2. **A3 Area2 NPC/도시 생성 seam** — RaiseInvader/RaiseNPCNation/CreateManyNPC/Reg* 가 공유. seam 먼저, 액션은 disjoint 병렬.
3. **A1 계략 5종**(화계/파괴/탈취/선동/첩보) — sabotage 상수·카탈로그 이미 존재, 서로 disjoint 파일 → 병렬. 각 골든 Y.
4. **A1 deterministic 명령**(강행/숙련전환/전투태세/모반시도/특기초기화 2종/등용수락/cr_인구이동) — draw 0, 병렬. 등용수락은 A2 message-accept intake 의존.
5. **A1 Area4 흡수**(InstantRetreat/ResetStat 골든 Y; DieOnPrestart/DropItem/CheckOwner det) — instant/inherit-action registry(신규) 의존.
6. **A2 부분포팅 본체**(견문/해산/인재탐색/외교 4종) — 1·2 인프라 위에서.

> 골든 Y 총: A1 계략5 + 단련 + 접경귀환 + InstantRetreat + ResetStat = 9; A2 견문 + 인재탐색 = 2; A3 RaiseInvader + RaiseNPCNation + CreateManyNPC + LostUniqueItem = 4. **합 15건이 `tools/php-golden` 실제 캡처 필요.** 나머지는 deterministic effect/log byte 캡처(0-draw 명시 assert).
> 모든 골든은 PHP 실제 캡처에서만 뱅킹. 캡처 불가 시 quarantine + 백로그(sibling byte-match 증명) — 날조·게이트 약화·골든 편집 금지.


---


<a id="section-groupB"></a>

> ── 인라인: `docs/superpowers/gap/exec/02-groupB-admin.md` (그룹 worklist 전문) ──

# 그룹 B — 어드민(Admin) 실행계획

> **데이터 소스**: `_full_audit_2026-06-07.raw.json` (`.missingPages[]`, `.partialPorts[]`, `.comparisons[]`) + 실제 legacy PHP grep/Read.
> **grand truth**: `legacy/devsam-core` PHP. 날조 금지 — 모든 단위는 file:line 근거.
> **읽기전용 산출**: 빌드/실행 없음.

---

## 0. 권한모델 divergence (전 단위 공통 전제 — 반드시 먼저 읽을 것)

legacy 어드민은 **`member.GRADE` 0–9 다단계** 게이트다. opensamguk은 **`users.role ∈ {USER, ADMIN}` boolean** 로 평탄화됐다(`UserEntity.role:String`, `infra/.../entity/UserEntity.kt:38`; `SecurityConfig` `requestMatchers("/admin/**").hasRole("ADMIN")` `app/gateway-api/.../security/SecurityConfig.kt:38`).

legacy grade 임계값 (감사 확인):
| grade | 의미 | 게이트하는 단위 |
|------|------|----------------|
| `<4` 거부 | 게임관리 마스터 | `_119`/`_119_b` (`hwe/_119.php:8`, `hwe/_119_b.php:10`) |
| `<5` 거부 | 부운영자 | `j_get_userlist`(목록조회, `:9`), `j_server_change_status`(notice/open/close, `:27`), `BanEmailAddress`(`:39`), grade>=4 `_admin1` 본문 일부 |
| `<6` 거부 | 운영자 | `_admin1`(`:9`), `_admin2`/`_admin2_submit`(`:10/:18`), `_admin5`(`:23`)/`_admin5_submit`(`:15`), `_admin7`(`:40`), `_admin8`(`:15`), `_admin_force_rehall`(`:10`), `j_set_userlist`(상태변경, `:10`), `reset`(서버리셋, `change_status:90`) |
| 대상보호 | — | `j_set_userlist`: 대상 grade ≥ 본인 grade면 거부(`:162`), `set_userlevel` param ≥ 본인 grade 거부(`:274`) |
| ACL | per-server | `j_server_change_status`: `openClose`/`reset`/`notice`/`update`/`fullUpdate` ACL(`:39,50,137`) |

**divergence 결정 (0.9.0 패러티 vs 운영 현실)**:
- 0.9.0 기준 = 단일 `ADMIN` role로 전 어드민 게이트(현 구조 유지). grade 4/5/6 다단계 + per-server ACL은 **의도된 인증 divergence**로 수용 — parityViolation 아님(audit `ts-admin-server`/`ts-admin-member` 명시).
- **단, `j_set_userlist`의 "대상이 자신과 같거나 높은 권한이면 거부"(`:162,274`)는 단순 ADMIN으로는 표현 불가** → 다른 ADMIN을 강제탈퇴/차단할 수 있는 보안 구멍. **B-AUTH 결정 필요**: ① `UserEntity`에 `grade:Int` 컬럼 추가(권장, 1.0.0 멀티운영자 대비) 또는 ② "ADMIN은 다른 ADMIN을 변경 불가" 단일 규칙. 본 계획은 **②(self/peer 보호 규칙)** 을 기본값으로, grade 컬럼은 B-AUTH 백로그로 둔다.

---

## 1. 현 admin 구현 실측 — 무엇이 stub인가

| 구현물 | 위치 | 실측 | legacy 대응 |
|--------|------|------|------------|
| `AdminController` | `app/gateway-api/.../controller/AdminController.kt` | `/admin/version` · `/admin/deploy/status` · `/admin/deploy` 3개만. **전부 DevOps(GHCR 태그 재배포)** — legacy 어드민 기능 0개 | `j_updateServer.php`(부분), 나머지 전무 |
| `AdminDto` | `app/gateway-api/.../dto/AdminDto.kt` | `ServiceVersion`/`ServerVersion`/`VersionResponse`/`DeployStatus`/`DeployRequest`/`DeployResult` — 전부 배포용 | — |
| `AdminSeeder` | `app/gateway-api/.../config/AdminSeeder.kt` | ENV `ADMIN_USERNAME`/`ADMIN_PASSWORD`로 ADMIN 1명 멱등 시드. 정상 | — |
| `web/gateway/app/admin/page.tsx` | 동 | 탭 3개(`회원 관리`/`서버 제어`/`게임 환경`). **`서버 제어`=배포 UI만**, `회원 관리`·`게임 환경`=`'준비 중'` PLACEHOLDER(`:339`) | 라벨만 verbatim, 본문 전무 |
| `StatusController` | `app/game-engine/.../status/StatusController.kt` | `GET /admin/turn-daemon/status` → **하드코딩 stub**(`paused=false, running=false` 항상, `:22`) | `_119` 락(plock) 상태 표시의 후보 백엔드 |
| `UserEntity` | `infra/.../entity/UserEntity.kt` | `username/password/email/nickname/role/createdAt/updatedAt`. **GRADE·BLOCK_DATE·delete_after·oauth_type·PICTURE/IMGSVR·member_log(loginDate) 전부 부재** | `member` 테이블 |

**결론**: 어드민 영역은 *배포 기능을 제외하면 100% stub*. legacy의 게임 내 관리(시간/봉급/락/회원/일제/로그/외교/명전)는 단 하나도 포팅되지 않음. `'게임 환경'` 탭은 legacy 근거 없는 신규 분류이나 `_119`/`_admin1`의 의도된 자리로 재해석한다.

---

## 2. BE-먼저 의존 순서 (FE는 전부 BE에 BLOCKED)

audit가 admin FE gap 대부분을 `blocked=true`로 표시한 근본 원인 = **대응 BE 엔드포인트/데이터모델 부재**. 따라서 순서는 엄격히 BE→FE.

```
[B-AUTH 권한규칙 결정]  ──┐
[B-DATA UserEntity 확장] ─┼──▶ [BE 엔드포인트 신설] ──▶ [FE admin 탭 본문] ──▶ tsc
[game_env 노출(read/write)]┘        (gateway-api +              (web/gateway/app/admin)
                                     game-engine intake)
```

- **B-DATA**(UserEntity 컬럼 확장: grade?/blockUntil/deleteAfter/oauthType/picture/imgsvr + member_log 상당 loginDate)는 거의 모든 회원관리 단위의 선결. Flyway 마이그레이션 1건.
- **game_env 키**(turntime/starttime/tnmt_time/maxgeneral/maxnation/startyear/turnterm/msg/isunited)는 이미 world_state/config jsonb 또는 KVStorage 상당으로 존재(`WorldSnapshotLoader`/`BootstrapConfig`가 참조) — read 노출 + 어드민 write 경로만 신설.
- **strict parity 위반 forced-mutation**(강제사망/블럭/하야/방랑/숙련도지급)은 general 테이블 직접 변경 → **one-daemon-write-rule** 때문에 gateway-api JDBC 직접쓰기 불가. game-engine intake(Redis XADD) 또는 game-api 경유 intake로 가야 함 → 아키텍처상 가장 무거운 단위(B6).

---

## 3. 단위 표

> 컬럼: **id | kind | legacy(file:line) | 대상 파일(opensamguk) | 의존 | 골든(RNG Y/N) | 게이트 | 상태 | fixSpec/서브태스크**
> kind: `admin-be`=gateway-api 또는 game-engine BE, `admin-fe`=web/gateway, `admin-data`=infra 스키마, `admin-intake`=game-engine 강제뮤테이션.

### B0 — 기반 (Tier-0, 모든 후속의 선결)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B0-AUTH** | 결정 | (권한모델 §0) | `infra/.../entity/UserEntity.kt`, `AdminController` self-guard 헬퍼 | — | N | 단위테스트(self/peer 거부) | 🔴 미착수 | self/peer 보호 규칙 확정. legacy `j_set_userlist:162,274` 동작을 "ADMIN은 다른 ADMIN/자기 자신 강등·삭제·차단 불가"로 단순화. grade 컬럼 확장은 B-AUTH-EXT 백로그(1.0.0 멀티운영자). |
| **B0-DATA** | admin-data | `member` 컬럼(`j_get_userlist:28-40`) | `infra/.../entity/UserEntity.kt` + `infra/.../db/migration/V__admin_member_fields.sql` + `UserRepository` 쿼리 | — | N | infra IT(컬럼 매핑) | 🔴 미착수 | `UserEntity`에 `grade:Int?`(divergence면 nullable), `blockUntil:LocalDateTime?`, `deleteAfter:LocalDateTime?`, `oauthType:String?`, `picture:String?`, `imgsvr:Boolean`, `lastLoginAt:LocalDateTime?`(member_log 대체) 추가. Flyway V마이그레이션. loginDate는 `member_log.action_type=login` 최신 → 별도 login_log 테이블 or `lastLoginAt` 갱신. |
| **B0-GAMEENV** | admin-be | `game_env` KVStorage(`_admin1.php:34`, `_119.php:13`) | `app/game-api/.../read/GameEnvReadRepository.kt` + game-engine `world_state`/config 노출 | — | N | game-api IT | 🟡 부분(world_state 존재) | turntime/starttime/tnmt_time/maxgeneral/maxnation/startyear/turnterm/msg/isunited read 노출. write는 B1/B5에서 소비. **world_state 컬럼 부재 필드(opentime/starttime 등) 확인 필요** — 없으면 B0-GAMEENV-EXT 백로그(데이터모델 보강 선행, 날조 금지). |

### B1 — 게임관리(`_119` + `_admin1`) = '게임 환경' 탭 BE+FE

legacy 2개 화면이 opensamguk '게임 환경' 탭으로 합류. 시간/봉급/락 + 시작시간/최대장수/최대국가/시작년도/턴시간/운영자메시지/중원정세로그.

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B1a-time-be** | admin-be | `_119_b.php:31-97` (분당김/분지연/토너분당김/토너분지연) | game-engine 신규 lifecycle 엔드포인트 `POST /admin/turn-daemon/shift-time` + gateway-api 프록시 | B0-GAMEENV | N | game-engine IT | 🔴 | turntime/starttime/tnmt_time을 ±N분. legacy는 `DATE_SUB/ADD INTERVAL`로 general.turntime + ng_auction.close_date(finished=0) 일괄 조정 + tryLock(분당김 10회/분지연 5회). **one-daemon-write-rule** → game-engine이 InMemoryTurnWorld 직접 시프트 + ChangeRecorder flush. tnmt_time만 조정(토너분당김/지연)은 별도 분기. |
| **B1b-lock-be** | admin-be | `_119.php:17,36` + `_119_b.php:104-115`(락걸기/락풀기) | `app/game-engine/.../status/StatusController.kt` 확장 `POST /admin/turn-daemon/pause`·`/resume` + `GET /status`를 실 상태로 | B0 | N | game-engine IT | 🟡 stub 존재 | 현 `StatusController.status()`는 하드코딩(`:22`). plock(GAME) = 데몬 일시정지. `TurnDaemonLifecycle`에 paused 플래그 + 게이트 추가, status를 실값으로. 락걸기=tryLock 최대10회, 락풀기=unlock. 표시 `현재 : 동결중/가동중`. |
| **B1c-income-be** | admin-be | `_119_b.php:98-103`(금지급=processGoldIncome, 쌀지급=processRiceIncome) | game-engine `POST /admin/income/gold`·`/income/rice` → `ProcessIncome` 호출 | B0 | **검토** | game-engine IT | 🟡 `ProcessIncome.kt` 존재 | `logic/.../world/ProcessIncome.kt` 이미 포팅됨(월틱 경로). 어드민 수동 트리거 = 같은 로직 1회 실행. **봉급 계산 자체는 결정적이나 ProcessIncome 내부 RNG 호출 여부 확인** — 호출 있으면 골든 Y(seed 고정 캡처), 없으면 N. 월틱 골든 재사용 가능성. |
| **B1d-gameenv-set-be** | admin-be | `_admin1_submit.php:39-78`(변경/로그쓰기/변경1~4/N분턴) | gateway-api `POST /admin/game-env`(msg/starttime/maxgeneral/maxnation/startyear) + game-engine `changeServerTerm`(턴기간) | B0-GAMEENV | N | game-api IT + game-engine IT | 🔴 | msg=운영자메시지, 로그쓰기=`pushGlobalHistoryLog(["<R>★</><S>{log}</>"])`(world_history nation_id=0, **로그 byte-parity 대상**), starttime/maxgeneral/maxnation/startyear = game_env write, N분턴=`ServerTool::changeServerTerm(turnterm)`(general.turntime 재계산 동반 — game-engine). |
| **B1e-game-env-fe** | admin-fe | `_119.php`+`_admin1.php` 전체 | `web/gateway/app/admin/page.tsx` '게임 환경' 탭 본문 | B1a-d | N | tsc + 수동 QA | 🔴 PLACEHOLDER(`:339`) | `'준비 중'` 제거. 시간조정(minute±) / 토너시간(minute2±) / 봉급(금·쌀) / 락(걸기·풀기 + 동결중/가동중 표시) + 운영자메시지 textarea / 중원정세추가 / 시작시간 / 최대장수·국가 / 시작년도 / 턴시간 버튼군(1·2·5·10·20·30·60·120분). 각 → `/api/proxy/admin/...` POST. 라벨 verbatim 패러티(`_119`/`_admin1`). |

### B2 — 회원관리(루트DB, `j_get_userlist`/`j_set_userlist`/`BanEmailAddress`) = '회원 관리' 탭

루트DB(gateway 공유) 유저 관리. 게임 내 general 아님 → gateway-api + UserEntity. **strict 위반 아님(루트DB 직접 쓰기 OK, one-daemon-write-rule 무관)**.

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B2a-userlist-be** | admin-be | `j_get_userlist.php:16-58` | gateway-api `GET /admin/users` + `AdminUserDto` | B0-DATA | N | gateway-api IT | 🔴 | member 전체 + loginDate(member_log 최신 login) + 서버별 아이콘(imgsvr 분기 완성 URL) + system REG/LOGIN 플래그. opensamguk: `UserRepository` 전체 + lastLoginAt + role→grade라벨. **grade<5 거부**(divergence: ADMIN). |
| **B2b-system-toggle-be** | admin-be | `j_set_userlist.php:36-70`(allow_login/allow_join) | gateway-api `POST /admin/system/{allow_login\|allow_join}` + `system` 상당 KV/테이블 | B0 | N | gateway-api IT | 🔴 | 가입/로그인 전역 허용 Y/N. opensamguk엔 `system.REG/LOGIN` 상당 부재 → **신규 system_flag 테이블 or config KV** 필요(데이터모델 보강). 로그인/가입 경로(AuthController)가 이 플래그를 읽도록 배선. |
| **B2c-scrub-be** | admin-be | `j_set_userlist.php:72-144`(scrub_deleted/scrub_icon/scrub_old_user) | gateway-api `POST /admin/users/scrub/{deleted\|icon\|old}` | B0-DATA | N | gateway-api IT | 🔴 | 탈퇴(delete_after<today) 정리 / 미사용 아이콘(1개월+, FS glob — opensamguk 이미지 CDN이라 **scrub_icon은 N/A 또는 백로그**) / 6개월+ 미접속 정리. affected count 반환. |
| **B2d-user-cmd-be** | admin-be | `j_set_userlist.php:146-301`(delete/reset_pw/block/unblock/set_userlevel) | gateway-api `POST /admin/users/{id}/{action}` | B0-AUTH, B0-DATA | N | gateway-api IT(self/peer 거부 포함) | 🔴 | delete=member 삭제, reset_pw=랜덤6자 임시PW(`Util::randomStr(6)`)+detail 반환, block=grade0+block_date(param일, ≤0이면 50년), unblock=grade1+block_date null, set_userlevel=grade 설정(1~본인-1). **대상보호 B0-AUTH 규칙 적용**. |
| **B2e-ban-email-be** | admin-be | `BanEmailAddress.php:34-58` | gateway-api `POST /admin/ban-email` + `banned_member` 상당 테이블 | B0 | N | gateway-api IT | 🔴 | `sha512(salt+email+salt)` 해시 영구차단. opensamguk엔 banned_member 테이블 부재 → 신규 + 회원가입 경로가 이 해시 체크하도록 배선. **grade<5 거부**(divergence). |
| **B2f-member-fe** | admin-fe | `admin_member.ts`+`admin_userlist.php` | `web/gateway/app/admin/page.tsx` '회원 관리' 탭 | B2a-e | N | tsc + 수동 QA | 🔴 PLACEHOLDER | 가입/로그인 라디오 토글 + 계정정리 3버튼 + 11열 테이블(코드/유저명/EMAIL+@줄바꿈+authType/등급+차단만료/닉네임/전콘/장수명(서버별)/가입일/최근로그인/탈퇴신청/명령) + 행당 6버튼(강제탈퇴/암호변경/유저차단(기간prompt)/차단해제/영구차단/별도권한(등급prompt)). 등급라벨 매핑(0차단/1일반/4특별/5부운영자/6운영자) `admin_member.ts` 정본. |

### B3 — 일제정보(`_admin5`) = read + 국가변경 (게임서버 내)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B3a-nation-stats-be** | admin-be | `_admin5.php:1-353` | game-api `GET /admin/nation-stats?type=&type2=` + DTO | B0 | N | game-api IT | 🔴 | 국가별 전체 통계(국력/장수/도시/기술/자원/숙련/인구 등) + 정렬(type 0~17) + 역사 통계. **read-only** → game-api JPA. legacy 정렬키 18종 verbatim. |
| **B3b-nation-change-be** | admin-intake | `_admin5_submit.php:22-46`(국가변경) | game-engine intake `admin_nation_change` 핸들러 | B0, B6-INTAKE | N | game-engine IT | 🔴 | admin **자신의** general 소속 국가 강제 변경(nation/officer_level/officer_city + 양국 gennum ±1). general 직접 변경 → **one-daemon-write-rule** → intake. |
| **B3c-admin5-fe** | admin-fe | `_admin5.php` UI | `web/game/app/game/admin5/` (게임서버 내, web/game) | B3a-b | N | tsc + QA | 🔴 | 게임 내 화면이므로 **web/game**(gateway 아님). 정렬 select + 통계 테이블 + 국가변경 폼. |

### B4 — 로그/외교 정보(`_admin7`/`_admin8`) = read-only

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B4a-general-log-be** | admin-be | `_admin7.php:1-181` | game-api `GET /admin/general-log?gen=&query_type=` + DTO | B0 | N | game-api IT | 🔴 | 장수 상세/개인기록/전투기록/장수열전/전투결과. 정렬 queryMap 4종(turntime/recent_war/name/warnum) verbatim(`:15-31`). read-only JPA. |
| **B4b-diplomacy-be** | admin-be | `_admin8.php:1-124` | game-api `GET /admin/diplomacy-all` + DTO | B0 | N | game-api IT | 🔴 | 전 국가간 외교(교전/선포/통상/불가침) 전체. 기존 `DiplomacyController`(중립 마스킹된 GetDiplomacy) 와 달리 **마스킹 없음**(어드민). read-only. |
| **B4c-log-diplo-fe** | admin-fe | `_admin7`/`_admin8` UI | `web/game/app/game/admin7/`·`admin8/` | B4a-b | N | tsc + QA | 🔴 | 게임 내(web/game). 로그 select+테이블 / 외교 매트릭스. read 렌더. |

### B5 — 강제 명전 등록(`_admin_force_rehall`)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B5-force-rehall-be** | admin-intake | `_admin_force_rehall.php:1-32` | game-engine `POST /admin/force-rehall` → `CheckHall` + `InheritancePointManager.mergeTotalInheritancePoint`/`applyInheritanceUser` | B6-INTAKE, B0-GAMEENV(isunited) | **Y(검토)** | game-engine 골든 IT | 🔴 | isunited 후만 실행(아니면 거부). age>=40 & npc<2 전 장수 `CheckHall` + npc=0 전 장수 상속포인트 재계산. **CheckHall은 명전 등록 — RNG/로그 byte-parity 가능성** → 골든 캡처 검토. `Rebirth.kt`/`ReservedTurnHandler`에 CheckHall 상당 존재(재사용). general 변경 → intake. |

### B6 — 어드민 forced-mutation intake 기반 (B1a/B3b/B5 공통 선결)

| id | kind | legacy | 대상 파일 | 의존 | 골든 | 게이트 | 상태 | fixSpec |
|----|------|--------|----------|------|------|--------|------|---------|
| **B6-INTAKE** | admin-intake | `_admin2_submit.php` 외 일괄 general write | game-engine `app/game-engine/.../intake/AdminMutationHandler.kt` + game-api precheck + `CommandWireMapper` 확장 | B0-AUTH | N | game-engine IT(권한+멱등) | 🔴 | **one-daemon-write-rule 준수 골격**: gateway/game-api는 general/nation 직접쓰기 금지. 어드민 강제뮤테이션은 game-engine intake(Redis XADD or game-api 경유)로만. 권한 검증(ADMIN)은 game-api precheck. 이 핸들러가 B6a~g(아래) 케이스를 dispatch. |

### B6a~g — 회원관리(게임서버 내, `_admin2`/`_admin2_submit`) 강제뮤테이션

> 전부 game-engine intake 경유(general/general_turn/general_access_log/nation 변경). genlist 다중 대상.

| id | kind | legacy(`_admin2_submit.php`) | 의존 | 골든 | fixSpec |
|----|------|------------------------------|------|------|---------|
| **B6a-block** | admin-intake | 블럭해제/1·2·3단계블럭/무한삭턴 (`:50-100`) | B6-INTAKE | N | block 0/1/2/3 + killturn(24, 무한=8000) + member.block_num/block_date(루트DB). 1단계=발언권, 2·3단계=gold/rice 0 + 턴블럭. |
| **B6b-forcekill** | admin-intake | 강제사망 (`:101-112`) | B6-INTAKE | N | killturn=0 + turntime=now + general_turn[0] action=휴식. |
| **B6c-dex** | admin-intake | 보·궁·기·귀·차숙10000 (`:135-187`) | B6-INTAKE | N | dex1~5 += 10000 + 각 장수 Message 발송("N숙련도+10000 지급!"). **Message byte-parity**. |
| **B6d-access** | admin-intake | 전체/개별 접속허용·제한 (`:40-49,188-197`) | B6-INTAKE | N | general_access_log.refresh_score 0(허용)/1000(제한). 전체=true 조건. |
| **B6e-message** | admin-intake | 메세지 전달 (`:198-204`) | B6-INTAKE | N | genlist 각 → 어드민 general발 private Message(만료 9999-12-31). |
| **B6f-command-set** | admin-intake | 하야입력/방랑해산 (`:205-223`) | B6-INTAKE | N | general_turn[0] action=che_하야 또는 (turn0=che_방랑, turn1=che_해산). brief 동반. **che_하야/방랑/해산 명령 포팅 의존**(그룹 A). |
| **B6g-admin2-fe** | admin-fe | `_admin2.php` UI | B6a-f | N | 게임 내(web/game) 회원선택 multi-select(NPC색/블럭배경) + 12행 명령 버튼군. 라벨 verbatim. |

---

## 4. 서버 개폐(`j_server_*`) — 아키텍처 divergence 노트 (대부분 백로그)

| id | legacy | 현 대응 | 결정 |
|----|--------|---------|------|
| **j-server-get-status** | `j_server_get_status.php` (color/korName/name/exists/enable) | `web/gateway`가 **정적 `servers.json`** 으로 서버목록 관리(`lobby/page.tsx` import) | 🟡 동적 API화는 선택. 다중 서버 운영 시 `GET /admin/servers` 신설. **저우선** |
| **j-server-change-status** | `j_server_change_status.php` (notice/open/close/reset) | AdminController **부재**. open/close=`.htaccess`(PHP) → opensamguk은 **배포(compose)** 로 대체 | 🔴 **개념 divergence**. notice(system.NOTICE)만 즉시 가치 有 → `POST /admin/notice` 신설 권장. open/close/reset은 docker/배포 영역(별 인프라). **부분만 포팅** |
| **j-update-server** | `j_updateServer.php` (git pull+webpack) | `AdminController /admin/deploy`(GHCR 태그) | ✅ **기능 등가 divergence**(audit partial). 추가 작업 없음 |
| **j-server-get-admin-status** | valid/run/installed/version/ACL | `/admin/version`(버전만) | 🟡 valid/run/installed/한글명·색상/ACL 누락. 배포모델 전환으로 대부분 무의미. **버전 표는 유지, 상태 라벨만 선택 보강** |

**노트**: 서버 개폐 영역은 PHP의 파일시스템(.htaccess) + git pull 모델을 docker/GHCR 배포로 **의도 전환**한 영역이라, 대부분 패러티 대상이 아니라 백로그/divergence. **단 `system.NOTICE`(공지) 변경은 게임 무관 루트 기능이라 B2와 함께 `POST /admin/notice`로 포팅 권장.**

---

## 5. 실행 순서 요약 (의존 위상)

```
1. B0-AUTH (권한규칙 결정)        ← 최우선, 모든 회원/intake의 선결
2. B0-DATA (UserEntity 확장+Flyway) ┐ 병렬 가능
   B0-GAMEENV (game_env read 노출)  ┘
3. B6-INTAKE (forced-mutation 골격)  ← B1a/B3b/B5/B6a-g 선결
4. ── 병렬 웨이브 (disjoint 파일) ──
   B2a-e (회원관리 BE, 루트DB)
   B1a-d (게임환경 BE, game-engine)
   B3a/B4a/B4b (read-only BE, game-api)
   B6a-f (회원 강제뮤테이션 intake)
   B5 (명전, 골든 검토)
   B3b (국가변경 intake)
5. ── FE 웨이브 (BE green 후) ──
   B1e (게임환경 탭, web/gateway)
   B2f (회원관리 탭, web/gateway)
   B3c/B4c/B6g (게임 내, web/game)
6. tsc(양 web) + 수동 QA
```

**병렬 격리 규칙**(CLAUDE.md): worktree 가족은 disjoint 파일. `page.tsx`는 단일 파일 co-widen 위험 → B1e/B2f는 **순차**(creator-then-consumer) 또는 탭별 컴포넌트 추출 후 병렬.

---

## 6. 골든 필요(RNG-bearing) 판정

| 단위 | RNG | 근거 |
|------|-----|------|
| **B5-force-rehall** | **Y(검토)** | `CheckHall` 명전 등록 + 상속포인트 재계산 — 로그/순위 byte-parity. 골든 캡처(isunited 시나리오) 검토. |
| **B1c-income** | **검토** | `ProcessIncome` 내부 RNG 호출 여부 확인. 있으면 Y(월틱 골든 재사용), 없으면 N. |
| **B6c-dex / B6e-message** | N(로그 byte-parity) | RNG 없음. 단 Message 텍스트("N숙련도+10000 지급!") byte-parity 대상. |
| 그 외 전부 | **N** | 결정적 CRUD(블럭/사망/시간시프트/접속/회원관리/통계/외교). 단위테스트로 충분. |

---

## 7. 미해결/백로그 (날조 금지 — 데이터모델 보강 선행 항목)

- **B0-GAMEENV-EXT**: opentime/turntime substr 표시 필드가 world_state 컬럼에 없으면 → 데이터모델 보강 선행(audit `j-server-basic-info` blocked 항목과 동일 뿌리). 없는 값 날조 금지.
- **B2b system_flag**: opensamguk에 `system.REG/LOGIN` 상당 부재 → 신규 테이블/KV 필요.
- **B2e banned_member**: 신규 테이블 + 회원가입 경로 배선 필요.
- **B2c scrub_icon**: opensamguk 이미지=CDN(opensam-images)이라 FS glob 정리 **N/A 가능** — 적용 대상 확인 후 백로그/제외.
- **B-AUTH-EXT**: grade 0–9 다단계 + per-server ACL 복원(1.0.0 멀티운영자). 0.9.0은 ADMIN 단일로 수용.
- **서버 개폐(j_server_change_status open/close/reset)**: docker/배포 모델 전환으로 의도 divergence. `system.NOTICE`만 `POST /admin/notice`로 부분 포팅.
- **CreateAdminNPC**: PHP 본체가 'NYI' 스텁(`Event/Action/CreateAdminNPC.php`) → 포팅 불필요(audit 명시).


---


<a id="section-groupC"></a>

> ── 인라인: `docs/superpowers/gap/exec/03-groupC-fe-missing.md` (그룹 worklist 전문) ──

# 그룹 C — FE 미포팅 실행계획 (web/game·web/gateway)

> 정본: PHP legacy = `legacy/devsam-core/hwe/*` (grand truth). divergence는 PHP가 이긴다.
> 데이터 소스: `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (jq 슬라이스) + 실파일 grep/Read 교차검증.
> 모듈 매핑 규칙: 명령=logic actions/* + CommandRegistry + intake(CommandWireMapper)+game-engine dispatcher + golden / FE=web/game(또는 gateway) / read=app/game-api controller+DTO / admin=app/gateway-api + web/gateway/app/admin.
> 빌드 금지(읽기전용 계획). 본 문서는 골든필요(RNG-bearing) 여부를 게이트 컬럼으로 명시.

## 0. 이번 감사에서 확정된 인테이크 아키텍처 사실 (계획 전제)

근거 grep/Read:

1. **인테이크 공통 시임 = `POST /api/command/{code}`** — `app/game-api/.../web/CommandController.kt:55` → `CommandReserveService.reserve(...)` → durable `general_turn` + 데몬 poke. precheck AVAILABLE일 때만 202 reserve. 즉 **새 컨트롤러를 매 명령마다 만들 필요 없음**; 명령형 mutation은 이 단일 시임으로 들어간다.
2. **wire 코드는 이미 광범위하게 존재** — `app/game-api/.../reserve/CommandWireMapper.kt` 에 `diploSendLetter`(:279) / `diploRollbackLetter`(:287) / `diploDestroyLetter`(:291) / `boardArticle`(:208) / `boardComment`(:214) / `selectPoolPick`(:296) / `selectPoolUpdate`(:303) 가 **이미 등록됨**. 따라서 이 7종은 *"BE intake 먼저"가 사실상 이미 끝났고*, 남은 일은 **(a) FE submit 호출 배선 + (b) read DTO shape 정합(카드 렌더)** 이다.
3. **반면 wire 코드가 부재한 4종** — `set_my_setting` / `vacation` / `set_npc_control(write)` / `myBoss(인사 write)` 는 CommandWireMapper에 코드가 **없음**(grep 0). 이들은 **신규 wire 코드 + logic action + game-engine 핸들러 + dispatcher + (필요시) golden** 까지 풀 인테이크 빌드가 필요 → C1 내에서 가장 무겁다.
4. **SelectPoolHandler 는 deny-only 스텁** — `app/game-engine/.../intake/SelectPoolHandler.kt:31,37` `reason="미구현"`. 픽 경로는 RNG-bearing(`allStat^1.5` 가중추첨) → **골든 필요, `/parity-wave` 이관**. update 경로는 결정론적(골든 부담 없음).
5. **SimulatorController 는 난수 스텁 + FE 키 불일치** — `SimulatorController.kt`: `(100..500).random()` 반환; FE는 `{attackerGeneralId,defenderGeneralId}` 송신, 컨트롤러는 `body["attackerId"]` 읽음 → 항상 에러 분기.
6. **utilGame(Tier-0) = ✅ 완료** — `web/game/lib/utilGame/` 16+종 존재(formatLog/techLevel/calcInjury/formatInjury/formatDexLevel/formatHonor/getNPCColor/formatOfficerLevelText/tournament 등). 본 그룹 컴포넌트는 이를 **소비**만 한다. 보류: `formatCityName`/`postFilterNationCommandGen`(GameConstStore 배선 대기), `getNewMsgToast`(Vue 전용).

---

## C1 — write 인테이크 + 컴포넌트

> "BE intake 먼저" 원칙. 단 §0-2에 따라 **두 부류로 갈린다**:
>   · **C1-α (wire 기존)**: diplo 3종 + board 2종 — FE submit + read DTO 정합만.
>   · **C1-β (wire 신규)**: set_my_setting / vacation / set_npc_control(write) / myBoss(write) — 풀 인테이크 빌드.
> 컴포넌트(AuctionResource/UniqueItem·BettingDetail·BoardArticle/Comment·TipTap)는 read 표시면이자 일부 write surface.

### C1-α  외교 서신 3종 + 게시판 2종 (wire 기존 → FE submit + DTO 정합)

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_diplomacy_send_letter | php-ajax write | `hwe/j_diplomacy_send_letter.php` + `hwe/t_diplomacy.php` + `hwe/ts/diplomacy.ts` + `hwe/j_diplomacy_get_letter.php` | FE: `web/game/app/game/diplomacy/page.tsx`(+`#newLetter` 작성 폼 신규) · api: `web/game/lib/api.ts` `diploSendLetter` · read DTO: `app/game-api/.../dto/F4Dto.kt` `DiplomacyLetter` + `DiplomacyController.kt` | wire `diploSendLetter`(:279) **기존**, 엔진 `DiplomacyLetterHandler.handleSend` **포팅됨** | **N** (메시지 분기/aux 삽입순서 결정론) | diplomacy page에서 작성→제출→202; read 카드 src/dest/state 렌더 정합(Vitest + 수동 QA) | 🔴 미배선(FE 작성 폼 부재) | (1) FE 외교 서신 **작성 폼** 신규(수신국 select=자국·재야 제외+level, 본문, 서명인 generalIcon/generalName). (2) `api.command('diploSendLetter', args)` 배선. (3) **read DTO shape 정합** — BE 평탄 `srcNationId/textBrief` ↔ FE 기대 `src{nationName,nationColor,generalName,generalIcon}/dest{...}/stateOpt/aux/state(케이싱)`. (4) `detail` permission<3 → '(권한이 부족합니다)' 마스킹. (5) 한글 행 라벨 verbatim(문서 번호/이전 문서/상태/서명인). 값 날조 0 — 단 `level` 컬럼 실존 매핑 직전 재확인. |
| j_diplomacy_rollback_letter | php-ajax write | `hwe/j_diplomacy_rollback_letter.php` + `ts/diplomacy.ts` | FE: `web/game/app/game/diplomacy/page.tsx` LetterCard · api: `lib/api.ts` `diploRollbackLetter` | wire `diploRollbackLetter`(:287) **기존**, 엔진 `handleRollback` **포팅됨**(Model A turn-reserved) | **N** (결정론) | LetterCard 회수 버튼 노출조건(state=='proposed' && src.nation==my) + confirm + 202; 알림 '회수 했습니다.' | 🔴 미배선(카드 read-only) | (1) LetterCard 회수 버튼 추가(가시성 게이트). (2) confirm('회수하시겠습니까?'). (3) `api.command('diploRollbackLetter',{...})`. (4) 성공/실패 알림 문자열 verbatim('회수 했습니다.'/'회수를 실패했습니다: …'). (5) 서명자 장수명·아이콘(aux) 표시. **BE 신규 작업 0** — 기존 인테이크로 해소. |
| j_diplomacy_destroy_letter | php-ajax write | `hwe/j_diplomacy_destroy_letter.php` + `ts/diplomacy.ts` + `j_diplomacy_get_letter.php` | FE: `web/game/app/game/diplomacy/page.tsx` LetterCard · api: `lib/api.ts` `diploDestroyLetter` · read DTO: `F4Dto.kt DiplomacyLetter`(+`state_opt` 필드) | wire `diploDestroyLetter`(:291) **기존**, 엔진 `handleDestroy`(`DiplomacyLetterHandler.kt:221-288`) **포팅됨** | **N** (1단계 요청/2단계 cancelled 전이 결정론) | 파기 버튼 노출/disable(state_opt 기반) + '파기 요청'/'파기' 2단계 라벨 + 202 | 🔴 미배선(카드 read-only) | (1) LetterCard `.btnDestroy` 추가(상호 동의 파기). (2) **read 응답에 `state_opt` 추가**(버튼 노출/진행상태 라벨 결정자 — 현 DTO 누락). (3) `api.command('diploDestroyLetter',{...})`. (4) src/dest Party 객체·brief/detail·stateOpt 로 카드 렌더 정합. (5) 발신/수신 서명 장수명·아이콘. |
| j_board_article_add | php-ajax write | `hwe/j_board_article_add.php` + `ts/components/BoardArticle.vue` | FE: `web/game/app/game/board/page.tsx`(글쓰기 폼) + **컴포넌트 `BoardArticle`(신규)** · api: `lib/api.ts` `boardArticle` · 스키마: `infra/.../V1__baseline.sql:352`(author_icon 결정) | wire `boardArticle`(:208) **기존**, 엔진 `BoardHandler.handleArticle`+`BoardActions.addArticle` **포팅됨**(검증 라인순서 EXACT) | **N** | board page 글쓰기(isSecret/title/text) 제출→202; null/blank/permission 게이트 동작; 비밀실 permission<2 차단 | 🔴 write 경로 부재(read-only) | (1) FE **BoardArticle 컴포넌트**(제목/본문/댓글목록/댓글입력) + 글쓰기 폼(isSecret 토글). (2) `api.command('boardArticle',{isSecret,title,text})`. (3) **author_icon 결정** — legacy는 INSERT+64px 초상 렌더, 현 스키마부터 DTO·FE까지 전구간 부재(parityViolation MEDIUM). 복원=마이그레이션+DTO+FE 일괄 / 미복원=백로그 결정 필요. (4) TipTap(아래) 소비. checkLimit/increaseRefresh는 QUARANTINE(P8). |
| j_board_comment_add | php-ajax write | `hwe/j_board_comment_add.php` + `ts/components/BoardComment.vue` | FE: `web/game/app/game/board/page.tsx` CommentRow · 컴포넌트 `BoardComment`(신규) · api: `lib/api.ts` `boardComment` | wire `boardComment`(:214) **기존**, 엔진 `BoardHandler.handleComment` **포팅 완료**(감사: parityViolation 0건, 게이트/INSERT/DTO/FE 1:1 일치) | **N** | 댓글 제출 {articleNo,text} maxlength 250 → 202; null/blank/존재/권한 게이트 | 🟡 **백엔드+DTO 완료**, FE 폼만 | 거의 완료 상태. (1) FE 댓글 행 3필드[작성자|본문|날짜(slice 5,16)] + '댓글 달기' 폼(placeholder '새 댓글 내용', maxlength 250, '등록'). (2) `api.command('boardComment',{articleNo,text})`. **댓글엔 author_icon 없음이 정상**(article만). date 타임존 직렬화만 별도 확인. |

### C1-β  개인/국가 설정 write 4종 (wire **신규** → 풀 인테이크 빌드)

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_set_my_setting | php-ajax write | `hwe/j_set_my_setting.php` | logic action 신규 + `CommandRegistry` + wire 신규 `setMySetting`(CommandWireMapper) + 엔진 핸들러 신규 + dispatcher · FE: `web/game/app/game/my-generals/page.tsx`(또는 b_myPage 설정 패널) · api `lib/api.ts` | **wire 부재**(grep 0) → 신규. 필드: `defence_train`/`tnmt`/`use_treatment`/`use_auto_nation_turn` | **N** (개인 KV write 결정론) | 설정 저장→202; general.aux KV 반영 IT(real Postgres flush) | 🔴 인테이크 전무 | (1) wire 코드 `setMySetting` 추가. (2) logic action(개인 설정 4필드 KV write) + 엔진 핸들러 + dispatcher. (3) ChangeRecorder dirty 경유 flush(인라인 write 금지). (4) FE 설정 패널(토너먼트/수비훈련/환약/자동사령턴 토글)+submit. b_myPage 설정 패널과 연동(C3 참조). |
| j_vacation | php-ajax write | `hwe/j_vacation.php` | logic action 신규 + wire 신규 `vacation` + 엔진 핸들러 + dispatcher · FE: my-generals/b_myPage 설정 패널 · api | **wire 부재** → 신규. 동작: `killturn` 3배 연장(autorun_user 서버 제외) | **N** | 휴가 명령→202; killturn ×3 반영 IT | 🔴 인테이크 전무 | (1) wire `vacation`. (2) logic action(killturn=killturn*3, autorun_user 게이트) + 핸들러 + dispatcher. (3) FE '휴가' 버튼(설정 패널). |
| j_set_npc_control | php-ajax write | `hwe/j_set_npc_control.php` (+표시 정본 `v_NPCControl.php`/`PageNPCControl.vue`) | logic action 신규(국가정책 23필드 KV write) + wire 신규 `setNpcControl` + 엔진 핸들러 + dispatcher · FE: `web/game/app/game/npc-control/page.tsx`(DnD 우선순위) · read 보강: `NpcPolicyController.kt`+`NpcPolicyResponse`(F4Dto) | **wire 부재** → 신규. read도 부분(meta 키 구조 불일치→실데이터 빈값) | **N** (KV write 결정론) | 정책 저장→202; nation_env(KV) 반영 IT; DnD 우선순위(사령20/일반15) 직렬화 정합 | 🔴 write 전무 + read 28% (날조 2키) | (1) wire `setNpcControl` + logic action(generalPriority 등 23필드 + 우선순위 배열 nation_env write). (2) 엔진 핸들러 + dispatcher. (3) **read 정합 동반** — 현 `defaultPolicy`는 23키 중 6키만+2키 날조(`reqHumanWarUprising`/`autorun_user`)+2키 기본값 오류(`reqNationGold/Rice`); meta 키 구조가 정본과 달라 currentPolicy/priority/lastSetters 항상 빈값. zeroPolicy 파생(GameUnitConst.costWithTech/develcost 포팅 미확인=BLOCKED 후보). (4) FE 좌우 2칼럼 DnD(비활성↔활성), 35행동 툴팁(`helpTexts.ts`=C2). 인증 게이트가 정본보다 넓음→parityViolation 표기. |
| j_myBossInfo (인사부 write) | php-ajax write | `hwe/j_myBossInfo.php` | logic action 신규(임관/해임/전출/배속) + wire 신규 `myBoss` + 엔진 핸들러 + dispatcher · FE: `web/game/app/game/my-boss/page.tsx`(현 read-only) · api | **wire 부재** → 신규. action=임관/해임/전출/배속 | **N** (직위 변경 결정론, 단 권한 게이트 패러티) | 인사 write→202; officer_level 변경 IT; 권한 게이트(군주/수뇌) | 🔴 read만(MyController GET /my-boss) | (1) wire `myBoss`. (2) logic action(4 action 분기, 권한 게이트) + 핸들러 + dispatcher. (3) FE my-boss 페이지에 인사 조작 버튼 + submit. 별도 `j_general_set_permission`(외교권/감찰권 일괄 UPDATE)은 군주 전용 write — 동일 패턴으로 wire `generalSetPermission` 추가(C1-β 확장). |

### C1 컴포넌트 (read 표시면 + write surface)

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| TipTap | vue-component | `hwe/ts/components/TipTap.vue` | `web/game/components/common/TipTap.tsx`(신규) | npm `@tiptap/react` | N | 에디터 마운트 + 툴바(되돌리기/재실행/굵게/기울기/밑줄) + HTML 추출 | 🔴 부재 | 리치 텍스트 에디터 래퍼. BoardArticle 글쓰기·물자원조 등에서 소비. legacy 툴바 항목 verbatim. |
| BoardArticle | vue-component | `hwe/ts/components/BoardArticle.vue` | `web/game/components/board/BoardArticle.tsx`(신규) | BoardComment·TipTap·`utilGame.getNPCColor`(✅) | N | 제목/본문/author_icon/댓글목록/댓글입력 렌더 | 🔴 부재 | author_icon 64px(스키마 결정 종속). j_board_article_add submit surface. |
| BoardComment | vue-component | `hwe/ts/components/BoardComment.vue` | `web/game/components/board/BoardComment.tsx`(신규, 또는 page.tsx CommentRow 유지) | — | N | 작성자/본문/날짜 단일 행 | 🟡 page.tsx에 인라인 가능 | 댓글 단일 행. 아이콘 없음(정상). |
| AuctionResource | vue-component | `hwe/ts/components/AuctionResource.vue` | `web/game/components/auction/AuctionResource.tsx`(신규) → `web/game/app/game/auction/page.tsx` | read: `AuctionController.kt`(GetActiveResourceAuctionList fid=35) · wire `auctionBid`/`auctionOpenBuyRice`(:259)/`auctionOpenSellRice`(:266) **기존** | N | 쌀 구매/판매 2섹션 8컬럼 테이블 + 인라인 입찰(min=시작가/max=마감가/step=10) + 등록 폼 + 최근20 로그 | 🔴 부재(auction page 22%) | 통화 접두사, 단가/마감가 컬럼, '경매 등록' 폼(매물타입·수량·기간턴·시작가·마감가). 입찰/등록 wire는 기존 → submit 배선만. |
| AuctionUniqueItem | vue-component | `hwe/ts/components/AuctionUniqueItem.vue` | `web/game/components/auction/AuctionUniqueItem.tsx`(신규) → auction/page.tsx | read: `Auction/GetUniqueItemAuctionList/Detail`(fid=15) · wire `auctionOpenUnique`(:273)/`auctionBid` **기존** | N | 익명 입찰(obfuscatedName), 유산포인트 잔여, min=ceil(최고*1.01)/max=remainPoint+confirm, 진행중/종료 목록 | 🔴 부재 | 아이템 툴팁, 주최자(익명), 종료일시/최대지연, 입찰자 목록. '금/쌀↔유니크' 모드 토글로 AuctionResource와 양립. |
| BettingDetail | vue-component | `hwe/ts/components/BettingDetail.vue` | `web/game/components/betting/BettingDetail.tsx`(신규) → `web/game/app/game/betting/page.tsx` | read: `BettingController.kt`(b_betting fid=10) · wire `placeBet`(:128) **기존** | N | 후보 선택 + 배당 순위 + 베팅 제출 | 🔴 부재(betting page 12%) | placeBet wire 기존 → submit 배선 + 상세 카드 렌더. |

---

## C2 — 선택풀/빙의 (Tier-0 Area2 의존)

> read(j_get_select_pool) + FE(select_general_from_pool.ts·select_npc.ts) + write(j_select/update_picked_general·j_select_npc).
> **핵심 게이트 분기**: 픽 경로 = RNG-bearing(`allStat^1.5` 가중추첨) → **골든 Y, `/parity-wave` 이관**. update/claim = 결정론.
> Tier-0 Area2(선택풀 read seam) 선결: SelectPoolRepository 스텁 → 실 read.

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_get_select_pool | php-ajax read | `hwe/j_get_select_pool.php` + `ts/select_general_from_pool.ts` | read: 신규 컨트롤러 `SelectPoolController.kt`(GET `/api/select-pool`) + DTO `SelectPoolPick`(uniqueName/generalName/imgsvr/picture/통무지/dex[5]/personal/special*/specialDomesticName·Info/specialWarName·Info) + 신규 `SelectPoolReadRepository` | **read 전무**(컨트롤러/DTO/repo/FE 0). `putInfoText()` 보강 + dex합 오름차순 정렬 | N (read) | GET 응답 {result,pick[],validUntil}; dex합 정렬; FE 카드 렌더 | 🔴 0% | (1) read 컨트롤러+DTO+repo 신규. (2) **특기 표시명/설명(`specialDomesticName/Info`,`specialWarName/Info`)=BLOCKED** — `:logic` iAction getName/getInfo 인스턴스화 부재(GetConstController에 이미 BLOCKED 명시). 해당 필드만 quarantine + 백로그. (3) validUntil 카운트다운. PossessionController `/claimable`는 npc=2 빙의 흐름(별개) — 혼동 금지. |
| select_general_from_pool.ts | fe-ts (289L) | `hwe/ts/select_general_from_pool.ts` + `select_general_from_pool.php` | FE: 신규 페이지 `web/game/app/game/select-pool/page.tsx` + 컴포넌트(카드그리드+생성폼) · api `lib/api.ts` | j_get_select_pool(read) + j_select/update_picked_general(write) | N(FE) | 카드그리드(이름·초상64·통무지·성격·특기2·dex 5병종·유효시간 카운트다운) + 생성폼(전콘여부·성격select·통무지 input·능력치조정 4버튼·범위/총합 안내·생성/리셋) + npcmode==2 게이트 + maxgeneral 초과 차단 | 🔴 부재(현 CharacterClaim은 빙의만) | 2-stage 흐름(선택 후 생성). hasGeneralID false→buildGeneral / true→pickGeneral(교체). 임관 권유 메시지 섹션(getInvitationList: 국가별 scoutmsg, 색상배경)도 동반. 상수 소스 존재(GameConst defaultStat 15/80/165, availablePersonality, personalityName). |
| select_npc.ts | fe-ts (436L) | `hwe/ts/select_npc.ts` + `j_get_select_npc_token.php` | FE: `web/game/components/game/CharacterClaim.tsx`(보강) 또는 신규 select-npc 페이지 · api | j_select_npc(write claim) + token GET | N(FE 표시) / 추첨 동작 BLOCKED | 빙의 카드(통무지+**성격/내정특기/전투특기 한글명**) + npcmode==1 분기 | 🟡 빙의 claim 동작 OK, 카드 필드 누락 | **즉시 조치**: ClaimableGeneral DTO에 `special`(내정특기명)/`special2`(전투특기명)/`personal`(성격명) 3종 추가(소스 존재: `GameConst.personalityNameOf`,`SpecialityHelper.domesticName/warName`, FrontInfoController 동일패턴) + `officerLevel` 잉여노출 **제거**. **BLOCKED**: NPC 풀 가중추첨(5명+select_npc_token+valid_until+pick_more+'다른 장수 보기' 재추첨+keepCnt) — `select_npc_token` 테이블 V1~V10 전무, 추첨 RNG 포팅 미확인. npcmode!=1 차단+maxgeneral 가드 — game_env가 world_state.config 미기재(BLOCKED). |
| j_select_picked_general | php-ajax write (생성) | `hwe/j_select_picked_general.php` | wire `selectPoolPick`(:296) **기존(필드만)** + 엔진 `SelectPoolHandler.handlePick`(스텁) + read seam `SelectPoolRepository`(스텁) | wire 필드 SET 존재, **핸들러 deny-only 스텁** | **Y (RNG-bearing `allStat^1.5` 가중추첨)** → **`/parity-wave`** | 골든 draw-for-draw(스탯검증·성격검증·build·동시성·member_log) | 🔴 스텁('미구현') | **`/parity-wave` 이관 필수**(골든 부재로 blocked). 추가발견: wire arg 키가 legacy POST 키(pick/personal/use_own_picture)와 달라 **별칭 fallback 권장**. legacy L11-12 strength/intel을 'leadership' 키에서 읽는 버그 → strict-패러티 vs divergence **골든으로 확정(날조 금지)**. |
| j_update_picked_general | php-ajax write (교체) | `hwe/j_update_picked_general.php` | wire `selectPoolUpdate`(:303) **기존** + `SelectPoolHandler.handleUpdate`(스텁) + `SelectPoolRepository.findPoolEntry`(스텁) | 결정론(추첨 없음) | **N** | info 적용·mark-then-swap 동시성·next_change 쿨다운·owner_name·2 로그·npcmode/장수존재 게이트 | 🔴 스텁('미구현') | 결정론 → 골든 부담 없음, 일반 close 가능. **wire 필드 시그니처 정정**: 현 update 분기가 leadership/strength/intel/personalityName/useOwnPicture를 파싱하나 legacy update는 `$pick`만 읽음(나머지는 신규생성 경로 인자) → divergence 제거. 상수 소스 존재(blocked 아님). |
| j_select_npc | php-ajax write (빙의) | `hwe/j_select_npc.php` (+`j_get_select_npc_token.php` 표시) | 현 impl: `GeneralPossessionService.claim()` + `PossessionController`(POST `/api/general/claim`, GET `/api/generals/claimable`) | 표시 필드는 token GET에서 | N(claim 액션) / 추첨 BLOCKED | claim {result,reason}; 카드 표시 필드 정합 | 🟡 claim OK, 표시/추첨 갭 | §C2 select_npc.ts와 동일 — special/personal 3필드 추가 + officerLevel 제거(즉시). 토큰 추첨/keep/npcmode/maxgeneral = 테이블·KV 소스 선결(BLOCKED). penalty/npc-flip write 누락은 one-daemon-write 의도이식(DEFERRED, parityViolation 아님). |

---

## C3 — 저충실도 read 페이지 (blocked 낮은 것 우선)

> read=app/game-api controller+DTO 보강 + web/game page 정합. 대부분 **해석 소스가 Kotlin에 이미 존재**(getOfficerLevelText/getHonor/getDedLevelText/personalityNameOf/SpecialityHelper) → blocked 낮음.
> **우선순위(blocked 낮음→높음)**: ① a_genList/b_myGenInfo(헬퍼 이미 이식, 미사용) → ② a_npcList/a_kingdomList → ③ b_my* 카드 묶음 → ④ a_hallOfFame/a_emperior(BE empty 하드리턴, 테이블 부재=높은 blocked).

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| a_genList.php | php-page read (fid 30) | `hwe/a_genList.php` | FE `web/game/app/game/rankings/generals/page.tsx` · read `GeneralListController.kt`/`GeneralsController.kt` | 헬퍼 `GeneralListText`(officerLevelText/honor) **이미 이식, 미사용** | N | 15컬럼(얼굴/이름NPC색/연령/성격/특기2/Lv/국가/명성/계급/관직/통무지+부상보너스/삭턴/벌점) + 15종 서버정렬(기본=벌점 DESC) | 🔴 30%(raw 노출) | **blocked 최저 — 최우선.** (1) officer_level raw→`getOfficerLevelText`(헬퍼 이미 존재, 미사용=명백 버그). (2) experience→Lv(getExpLevel)/명성(getHonor), dedication→계급(getDedLevelText). (3) 누락 컬럼(연령/성격/특기2/삭턴/벌점) 추가. 벌점(refresh_score_total)=`general_access_log` 부재로 **BLOCKED**(P8). |
| b_myGenInfo.php | php-page read (fid 25) | `hwe/b_myGenInfo.php` | FE `web/game/app/game/my-generals/page.tsx` · read `MyController.kt` `/my-generals`(MyGeneralSummary) | 헬퍼 다수 이식(officerLevelText·honor·getDedLevelText·getBillByLevel·personalityNameOf·SpecialityHelper·calcLeadershipBonus) | N | 15컬럼+15종 정렬 셀렉터 | 🔴 25%(BE 9필드만) | a_genList과 쌍. BE `MyGeneralSummary` 9필드→봉록/명성/성격/특기/얼굴/계급한글/사관/벌점/통솔보너스/부상 확장. 거의 전 소스 존재. 벌점만 BLOCKED. |
| a_npcList.php | php-page read (fid 35) | `hwe/a_npcList.php` | FE `web/game/app/game/rankings/npcs/page.tsx` · read `RankingController.kt` | 소스 존재 | N | 12컬럼(희생장수NPC색/악령이름owner_name/Lv/국가/성격/특기2/종능sum/통무지/명성/계급) + 8단 정렬 | 🔴 35% | 누락 5컬럼(악령이름·Lv·성격·특기2·종능) 추가, 신설 2컬럼(병력/도시) 제거, 라벨 정정(명성/계급). |
| a_kingdomList.php | php-page read (fid 15) | `hwe/a_kingdomList.php` | FE `web/game/app/game/rankings/kingdoms/page.tsx`(또는 별도 '세력일람' 라우트) · read `RankingController.kt` | 소스 대체로 존재(작위 lv0-7 byte-identical) | N | ROSTER(국가별 색상헤더+성향/작위/국력+officer_level 12~5 수뇌직책표+외교권자/조언자+속령전체일람(수도cyan)+장수전체일람dedication DESC+재야섹션) | 🔴 15%(현재는 leaderboard, 의미 별개) | 현 impl은 '세력 순위' leaderboard로 **별개 화면**. legacy는 '세력일람' roster. 두 화면 공존 or roster 신규. 공통필드(국가명/색/작위/장수수/도시수/수도) 패러티 OK. lv8/9는 의도 divergence. |
| b_myKingdomInfo.php | php-page read (fid 22) | `hwe/b_myKingdomInfo.php` | FE `web/game/app/game/my-nation/page.tsx` · read `MyController.kt` `/my-nation-detail` | **계약 버그** 우선 | N | 8열 19필드(총주민/총병사/국력/국고/병량/세율/세금·세곡/지급률/수입지출금미/속령수/장수수/예산/기술력/작위/속령일람/국가열전) | 🔴 22%(계약 불일치 버그) | **먼저 계약 버그 수정** — FE는 {nation,generals,cities} 가정하나 BE는 {result,hasNation,nation:FrontNationInfo,cityCount,generalCount}만 반환 → 두 표 공백+pop/genNum/power 0/undefined. legacy엔 없는 장수표/도시표 제거 + 19필드 단일표로. |
| b_myCityInfo.php | php-page read (fid 22) | `hwe/b_myCityInfo.php` | FE `web/game/app/game/my-cities/page.tsx` · read `MyController.kt` `/my-cities` | 도시당 21 데이터포인트 | N | 도시별 카드(5행×최대10열): 헤더【지역|등급】도시명(국가색,수도cyan) + 주민/인구율/자금·군량·둔전수입 + 농업/상업/치안/수비/성벽 + 민심/시세/태수·군사·종사 | 🔴 22%(평면9컬럼) | 평면표→도시별 카드. number_format/소수자릿수/시세 null→"- " verbatim. BE 필드 확장. |
| b_currentCity.php | php-page read (fid 22) | `hwe/b_currentCity.php` + `cityGeneral.php` | FE `web/game/app/game/city/page.tsx` · read `web/CityDetailController.kt` `CityDetailResponse` | **BE 미emit** → 다수 blocked | N | 장수 상세 테이블+군사집계행+관직자행+도시선택 셀렉터+갱신시각+도시명행+장수명 CSV | 🔴 22%(헤더+게이지만) | 근본원인=`CityDetailResponse`가 장수리스트/관직자명/군사집계/셀렉터 데이터 미emit → **FE 단독 불가, BLOCKED 다수**. BE DTO 확장 선결. fog 마스킹은 visible=false 의도. |
| b_myPage.php | php-page read (fid 20) | `hwe/b_myPage.php` | FE 신규/보강 — 컨트롤바 18번('내 정보&설정')이 `/game`(GameChrome)로 잘못 라우팅(`control-bar-config.ts:64`) → 전용 페이지 필요 · read `MyController.kt` `/my-page` | C1-β set_my_setting/vacation(설정 패널 write) | N | 좌 정보카드(generalInfo+generalInfo2)+우상단 설정패널(토너먼트/환약/자동사령턴/수비/저장/휴가/즉시행동/화면모드/아이템파기/CSS)+4 기록섹션(개인/전투/장수열전/전투결과) | 🔴 20%(라우팅 오류) | (1) `/game/my-page` 전용 라우트 신설 + control-bar-config 18번 재배선. (2) generalInfo2(명성/계급/전투/계략/사관/승률/승리/패배/살상률/사살/피살)+숙련도 표시. (3) 설정 패널=C1-β write 소비. (4) 4 기록섹션(GetGeneralLog read 필요). |
| v_history.php | vue-page read (fid 35) | `hwe/PageHistory.vue` | FE `web/game/app/game/history/page.tsx` · read `HistoryController.kt` | **wire shape 불일치** + `utilGame.formatLog`(✅) | N | 4섹션(MapViewer 스냅샷+SimpleNationList 국가표+중원정세 로그+장수동향 로그) | 🔴 35%(2섹션, 색 깨짐) | (1) **wire shape 정합** — BE {result,months:[{year,month,profileName,map,nations}]} ↔ FE 기대 {firstYearMonth/.../record:{globalHistory,globalAction}} → record 항상 null. (2) `formatLog()` 적용(현재 raw `<R><B><1>` 노출). (3) MapViewer+SimpleNationList 2섹션 추가. SimpleNationList=신규 컴포넌트. |
| v_auction.php | vue-page read (fid 22) | `hwe/PageAuction.vue` | FE `web/game/app/game/auction/page.tsx` | C1 AuctionResource/AuctionUniqueItem 컴포넌트 | N | '금/쌀↔유니크' 모드 토글 두 화면 | 🔴 22% | C1 컴포넌트 2종 조립 + 모드 토글 셸. |
| a_hallOfFame.php | php-page read (fid 12) | `hwe/a_hallOfFame.php` | FE `web/game/app/game/rankings/hall-of-fame/page.tsx` · read `RankingController.kt` `RankReadService.hallOfFame()` | **`hall` 테이블 read 부재** | N | 24분류 상위10 카드(순위·64초상·국가명대비색·printValue int/percent)+시즌/시나리오 셀렉터 | 🔴 12%(emptyList 하드리턴) | **높은 blocked** — `RankReadService.hallOfFame()`=emptyList() 하드리턴, hall read entity/repository 부재(`hall` 테이블 schema.sql:257). read 시임부터 신규. 24 type 섹션·한글라벨·초상·대비색·ng_games 시즌 셀렉터. C3 후순위. |
| a_emperior.php / a_emperior_detail.php | php-page read (fid 25/8) | `hwe/a_emperior.php` / `a_emperior_detail.php` | FE `rankings/emperor/page.tsx` + `rankings/emperor/[id]/page.tsx` · read `RankingController.kt` emperor | **emperor 테이블 부재**(항상 404) | N | 왕조 일람 + 상세 | 🔴 8-25%(404) | **높은 blocked** — emperor 테이블 부재로 detail 항상 404. read 시임+테이블 선결. C3 최후순위. |

> C3 보조 read-api 갭(동일 컨트롤러 보강 흐름): `Global/GetRecentRecord`(메인 3피드, fid 10), `Global/GetHistory/GetCurrentHistory`(fid 15-25), `Global/GeneralList`(fid 45), `Auction/Get*AuctionList/Detail`(fid 15-35), `bestGeneral`/`a_bestGeneral`(fid 8), `a_traffic`(fid 15). 각 controller+DTO 보강으로 FE page와 동반 정합.

---

## C4 — 시뮬레이터 (날조 스텁 → 실 전투엔진)

> ctrl-simulator: `SimulatorController` 난수 스텁을 logic `war/*` 실 전투엔진에 연결. **BE 선결 필수**(FE는 BE 의존).

| id | kind | legacy(file) | 대상 파일 (opensamguk) | 의존 | 골든(RNG) | 게이트 | 상태 | 핵심 fixSpec/서브태스크 |
|---|---|---|---|---|---|---|---|---|
| j_simulate_battle (BE) | php-ajax write/계산 | `hwe/battle_simulator.php` 계산부 + `logic war/*` | `app/game-api/.../controller/SimulatorController.kt`(스텁 교체) → `logic` `war/*` 전투엔진(`processWar` 재사용) · 신규 DTO 입출력 | `logic/war/*`(P4 전투엔진, G1 draw byte-match), `common/rng RandUtil(LiteHashDrbg(warSeed))` | **Y (전투엔진 = ONE RandUtil(warSeed) draw-for-draw)** | 시드 재현 + 1000회 반복 요약(준/받은 피해 min~max, 군량소모, 스킬) draw-for-draw vs PHP 골든 | 🔴 난수 스텁 + FE 키 불일치 | (1) **스텁 제거** — 현 `(100..500).random()` → 실 전투엔진. 전투 전체가 `processWar()`서 1회 생성한 `RandUtil(warSeed)` 1개로 진행(재시드 금지). (2) **FE↔BE 키 정합** — FE `{attackerGeneralId,defenderGeneralId}` ↔ 컨트롤러 `body["attackerId"]`(항상 에러 분기). 응답 필드도 전부 불일치(FE attackerWon/Damage… ↔ BE winner/damageDealt…). (3) 입력=수동 파라미터(국가성향/기술등급/규모/도시/수도/장수 전항목). (4) **`j_export_simulator_object`(read)** 동반 — 동국 장수 raw stat 객체 반환(타국=더미). |
| battle_simulator.ts / .php (FE) | fe-ts (8) / php-page (6) | `hwe/battle_simulator.php` + `hwe/ts/battle_simulator.ts` | FE `web/game/app/game/simulator/page.tsx`(전면 재작성) | j_simulate_battle(BE) + j_export_simulator_object | N(FE) / 라벨 BLOCKED 일부 | 전역설정+출병국/수비국 설정+장수 상세폼+요약테이블+로그 2분할 | 🔴 6-8%(최소 화면) | (1) **전역 설정 카드**(시작년 disabled/년/월/시드/반복횟수[1회 로그·1000회 요약]/전투/저장·불러오기 .json). (2) **출병국·수비국 설정**(성향+장단점·기술등급1~12·국가규모·도시규모·수도Y/N·수비/성벽). (3) **장수 상세 폼**(이름/직위/Level/통무지/명마·무기·서적/부상%·군량·도구/병종·병사·성격/훈련·사기·전특/숙련5종/수비여부/전투·승리·사살수/회피·필살·계략시도 확률). (4) **수비자 다중 add/정렬/복제/제거**. (5) **요약 테이블**(일시/횟수/페이즈/준·받은피해 min~max/양측 군량·스킬). (6) **마지막 전투 로그 + 상세 로그 2분할**. (7) 서버에서 가져오기 모달. **BLOCKED 라벨**: 성향/전특/성격/아이템 한글명(`iActionBundle` name=null), dex 라벨/색상(`getDexLevelList` 미노출) — 소스 노출 선결. 값 소스 대부분 존재(GameConst availableNationType/SpecialWar/Personality/allItems, GameUnitConst 병종, maxTrainByWar=110/maxAtmosByWar=150). |
| v_battleCenter.php / PageBattleCenter.vue | vue-page read | `hwe/v_battleCenter.php` + `hwe/PageBattleCenter.vue` + `ts/battleCenter.ts` | FE 신규 `web/game/app/game/battle-center/page.tsx` · read 신규 `BattleCenterController.kt` | general_record read seam | N | 감찰부(장수 행동 로그 뷰어, 전투 참가 장수 실시간 목록) | 🔴 현재 /coming-soon 리다이렉트 | (별개 단위지만 시뮬레이터 인접) read 컨트롤러+DTO 신규. legacy 타이틀 '감찰부'. `j_general_log_old`(generalAction/battleResult/battleDetail 페이지네이션 로그) read 동반. |

---

## 실행 순서 권고 (의존 위상)

1. **C1-α** (외교3+게시판2) — wire 기존, FE submit+DTO 정합만. **가장 빠른 패러티 회수**. 단 BoardArticle은 TipTap 컴포넌트 + author_icon 결정 선행.
2. **C3 ①②** (a_genList/b_myGenInfo → a_npcList/a_kingdomList) — 헬퍼 이미 이식·미사용, blocked 최저. raw 코드 노출 제거가 즉효.
3. **C2 즉시조치분** (select_npc.ts/j_select_npc 카드 special/personal 3필드 + officerLevel 제거) — 소스 존재, blocked 아님.
4. **C1-β** (set_my_setting/vacation/set_npc_control/myBoss) — 신규 wire+logic+handler+dispatcher 풀빌드. b_myPage(C3) 설정패널과 동반.
5. **C1 컴포넌트** (AuctionResource/UniqueItem·BettingDetail) + **C3 v_auction** — 묶어서.
6. **C3 ③** (b_my* 카드: myKingdom 계약버그→myCity→currentCity[BE DTO 확장]) + **v_history**(wire shape 정합).
7. **C2 픽 경로** (j_select_picked_general) — **`/parity-wave` 이관**(RNG 골든). update/claim은 결정론으로 일반 close.
8. **C4** (j_simulate_battle BE → battle_simulator FE → v_battleCenter) — 전투엔진 골든(RNG).
9. **C3 최후** (a_hallOfFame/a_emperior) — read 시임+테이블 부재로 blocked 최고.

## BLOCKED/Quarantine 누적 (소스 부재, 날조 금지)

- **iAction 한글 표시명** (`specialDomesticName/Info`·`specialWarName/Info`·nationType name·specialWar/personality/item name) — `:logic` iAction getName/getInfo 인스턴스화 부재. GetConstController에 이미 BLOCKED 명시. C2/C4 다수 라벨 차단.
- **select_npc_token 테이블** — V1~V10 전무(grep 0). C2 NPC 가중추첨/keep/valid_until 차단.
- **game_env (npcmode/maxgeneral)** — world_state.config 미기재. C2 npcmode 차단/정원 가드 차단(IdentityDto §2와 동일 원인).
- **general_access_log** — refresh_score_total(벌점 컬럼)·checkLimit·increaseRefresh 차단(P8 백로그). C3 a_genList/b_myGenInfo 벌점.
- **hall / emperor 테이블** — read entity/repository 부재. C3 a_hallOfFame(emptyList 하드리턴)/a_emperior(404).
- **zeroPolicy 파생** (`GameUnitConst.costWithTech/develcost` 포팅 미확인) — C1-β set_npc_control read 일부.
- **getDexLevelList(임계값+색상+이름)** 상수 미노출 — C4 시뮬레이터 숙련 라벨.

각 BLOCKED는 해당 행에 quarantine + 페이즈 백로그 기록, 인접 비-blocked 필드는 정상 close.


---


<a id="section-groupD"></a>

> ── 인라인: `docs/superpowers/gap/exec/04-groupD-readdto.md` (그룹 worklist 전문) ──

# 그룹 D — read DTO 형상 (auction / betting / message / vote / diplomacy)

> 실행계획. PHP legacy(`legacy/devsam-core/hwe/sammo/API/...`) = grand truth. 모든 행은 raw 전수 감사
> (`docs/superpowers/gap/_full_audit_2026-06-07.raw.json`)의 `comparisons[].gaps/parityViolations` + 실제 파일 grep으로 근거.
> 빌드 금지(읽기전용 계획). 날조 금지 — 원천 미확정 항목은 **BLOCKED**로 명시(값 채우기 금지).

## 0. 스코프 / 원칙

- **무엇**: read API 응답 DTO를 legacy envelope 형상에 1:1 정렬. auction/betting/message/vote/diplomacy read-api 단위.
- **모듈 매핑(read 규칙)**: `app/game-api` controller + DTO 보강. 집계/필터는 기존 `infra/.../read/*Repository` 재사용(없으면 신규 메서드). FE 계약(`web/game`)은 BE 형상 확정 후 정렬(별도 그룹이지만 BE-FE 동일 repo 모순이라 본 계획에 동반 노트).
- **컨트롤러 disjoint**: 각 행이 건드리는 controller/DTO는 서로 겹치지 않음 → 병렬 worktree 안전. 단 `F4Dto.kt`는 vote+diplomacy가 공유 → **이 둘은 같은 파일을 co-widen하므로 순차**(또는 vote는 신규 `VoteDto.kt`로 분리해 disjoint화 권장 — 아래 행에 명시).
- **인증 divergence**: 세션→장수 해석은 `@AuthenticationPrincipal userId` + `GeneralResolver`(예: `InheritPointController.kt:63-67`) 패턴. 미인증 graceful 정책(빈/0)은 의도이식으로 허용 — parityViolation 아님. **데이터/동작(필터·정렬·마스킹·필드셋)** 만 패러티 게이트.
- **골든**: read DTO 형상 자체는 RNG 무관(N). 단 GetDiplomacy의 **conflict % 정규화(`round(100*killnum/sum,1)`)** 는 PhpRound 수치 패러티라 캡처 골든 픽스처 권장(아래 표 D9 골든=Y).

## 1. 단위 표

| id | kind | legacy(file) | 대상 파일(opensamguk) | 의존 | 골든필요(RNG) | 게이트(테스트) | 상태 | 핵심 fixSpec / 서브태스크 |
|----|------|--------------|----------------------|------|----------------|-----------------|------|---------------------------|
| **D1** Auction/GetActiveResourceAuctionList | read-api | `hwe/sammo/API/Auction/GetActiveResourceAuctionList.php:40-122` | `app/game-api/.../controller/AuctionController.kt:44-99` · `dto/AuctionDto.kt:20-53` | `AuctionRepository.findByFinishedFalseAndType`(AuctionRepository.kt:27 존재) · `AuctionType.value`(logic AuctionDto.kt:10-13 존재) | N | game-api `@WebMvcTest` AuctionController IT: ① type IN(buyRice,sellRice) 필터로 uniqueItem 제외 ② envelope `{buyRice[],sellRice[],recentLogs[],generalID}` ③ `type==buyRice`(소문자) ④ highestBid object 4필드 | 부분(fid 35) | (a) `listActive()` `findByFinishedFalse()`→`findByFinishedFalseAndType(BUY_RICE)+SELL_RICE`(또는 in-필터). (b) flat List→`{buyRice,sellRice,recentLogs,generalID}` 봉투 DTO 신설. (c) `type = type.name`(L85)→`type = type.value`. (d) `AuctionResponse.detail`(raw jsonb, L40) 제거 — 필요필드만 디코드. (e) startBidAmount/finishBidAmount는 BE 이미 제공(L94-95) → FE 노출. **recentLogs = BLOCKED**(legacy는 파일로그 `_auctionlog.txt`, func_history.php:89-95 원천 — opensamguk 동치 경매로그 소스 미확정, 날조 금지. 빈 배열로 두고 원천 확정 시 채움). FE 동반: highestBid number→object(types/game.ts:59-60), amountMin=1→startBidAmount(page.tsx:162). |
| **D2** Auction/GetUniqueItemAuctionList | read-api | `hwe/sammo/API/Auction/GetUniqueItemAuctionList.php:35-103` | **신규** `AuctionController.kt` `@GetMapping("/unique")` + 신규 DTO `UniqueAuctionListResponse`(AuctionDto.kt 또는 분리) | `AuctionRepository.findByTypeOrderByCloseDateAsc(UniqueItem)`(신규) · `ObfuscatedNamePool.genObfuscatedName`(logic ObfuscatedNamePool.kt:76-81 존재, byte-faithful) · `findTopByAuctionIdOrderByAmountDesc` | N | IT: type=UniqueItem만, finished 포함, close_date ASC, highestBid null 항목 제외, 봉투 `{result,list,obfuscatedName}` | 미존재(fid 15) | (a) 신규 read 엔드포인트(`GET /api/auctions/unique`) — `listActive`의 finished=false 재사용 금지. (b) repo `findByTypeOrderByCloseDateAsc(AuctionType.UniqueItem)` 추가(finished 무필터). (c) 항목 필드셋 = `{id,finished,title(detail.title),target,isCallerHost,hostName(난독),closeDate,remainCloseDateExtensionCnt,availableLatestBidCloseDate,highestBid}`. raw `hostGeneralId`/raw detail jsonb 제외. (d) highestBid = `{generalName,amount,isCallerHighestBidder(=generalId==viewerId),date}` — raw generalId 제거. (e) highestBid==null 경매 skip(L76-79). (f) top-level `obfuscatedName`(viewerGeneralId 기준). (g) closeDate/availableLatestBidCloseDate = `yyyy-MM-dd HH:mm:ss`(TimeUtil.format 동치, withFraction=false). |
| **D3** Auction/GetUniqueItemAuctionDetail | read-api | `hwe/sammo/API/Auction/GetUniqueItemAuctionDetail.php:42-103` | **신규** `AuctionController.kt` `@GetMapping("/{id}/unique-detail")` + 신규 DTO `UniqueAuctionDetailResponse` | `AuctionInfoDetail.fromArray`(logic AuctionDto.kt:48-129) · `ObfuscatedNamePool.decode`(AuctionOpenHandler.kt:425-428 경로) · `game_kv` hiddenSeed read(RehydrateService.kt:30) · inheritance KV previous(InheritPointController.kt:70-78 패턴) | N | IT: type=UNIQUE_ITEM AND id 조회, 부재 시 '선택한 경매가 없습니다.', 중첩 `{result,auction,bidList,obfuscatedName,remainPoint}` | 미존재(fid 15) | (a) 신규 핸들러 — type=UNIQUE_ITEM 한정 조회(없으면 legacy 한글 메시지). (b) `auction.title/hostName(난독)/remainCloseDateExtensionCnt/availableLatestBidCloseDate` = `AuctionInfoDetail.fromArray(detail)` 명시필드(raw jsonb 노출 금지). hostName **live general JOIN 폴백 금지**(난독이 grand truth; AuctionController.kt:80 폴백이 오염원). (c) `isCallerHost = hostGeneralId==callerGeneralId`. (d) `bidList[]` = `{generalName(aux 디코드),amount,isCallerHighestBidder,date(포맷)}` amount DESC, raw aux/no/owner/generalId 제거. (e) top-level `obfuscatedName`(caller 난독, ObfuscatedNamePool.decode) + `remainPoint`(inheritance_{ownerId} KV previous, 부재 0). (f) `result:true` 래퍼. |
| **D4** Betting/GetBettingList | read-api | `hwe/sammo/API/Betting/GetBettingList.php:33-95` | **신규** `BettingController.kt` `@GetMapping` 루트(`/api/bettings`) + 신규 DTO `BettingListItem` | `GameKvReadRepository`(table='betting', BettingController.loadBettingMaster:81-92 패턴) · `BettingRepository` SUM(amount) GROUP BY betting_id(신규) · `WorldStateReadRepository.currentYear/currentMonth`(FrontInfoController.kt:342-343) · logic `BettingInfo`(BettingInfo.kt:38-50) | N | IT: req 필터(bettingNation\|tournament), candidates 제거, totalAmount 집계, `{bettingList(Map<id,item>),year,month}` | 미존재(fid 10) | (a) 목록 라우트 신설(FE api.ts:106이 `GET /api/bettings` 호출하나 BE 없음). (b) game_kv(betting) 전체 디코드→`BettingInfo` 목록, `type==req` 필터(req null이면 전체). (c) 항목 = `{id,type,name,finished,selectCnt,isExclusive?,reqInheritancePoint,openYearMonth,closeYearMonth,winner(List<Int>?),totalAmount}` — **candidates 제외**(legacy unset). (d) repo `SUM(amount) GROUP BY betting_id`(신규) → `totalAmount`(행 없으면 0). (e) `year/month` = world current. **checkLimit/increaseRefresh = BLOCKED**(general_access_log/refresh_score 미영속, IdentityDto.kt:184,195 — 의도이식 보류). FE 동반: betting/page.tsx 날조필드(odds/targetNations/totalPool/status) 제거. |
| **D5** Betting/GetBettingDetail | read-api | `hwe/sammo/API/Betting/GetBettingDetail.php:46-98` | `BettingController.kt:61-74`(detail) · `dto/BettingDto.kt:26-65` · `decodeMaster(BettingController.kt:95-123)` | `BettingRepository.aggregateAmountByTypeForUser`(BettingRepository.kt:46-54 **존재·미사용**) · inheritance previous KV / `GeneralReadRepository.gold` · `WorldStateReadRepository` · logic `BettingInfo.winner`(BettingInfo.kt:49) + `SelectItem.isHtml`(SelectItem.kt:69) | N | IT: 응답 7키(result,bettingInfo,bettingDetail,myBetting,remainPoint,year,month), winner/isHtml 디코드, myBetting 튜플 | 부분(fid 35) | (a) `detail()`에 `@AuthenticationPrincipal userId` 추가 → resolver. (b) **누락 4필드 추가**: `myBetting`(aggregateAmountByTypeForUser, [bettingType,amount] 튜플), `remainPoint`(reqInheritancePoint? inheritance previous[0] : general.gold, 부재 0), `year`,`month`(world). (c) `decodeMaster`에 `winner: List<Int>?`(map["winner"]) + `candidate.isHtml: Boolean?`(item["isHtml"]) 디코드 — 종료 베팅 하이라이트/HTML info 렌더용. (d) 마스터 부재 시 '해당 베팅이 없습니다' 에러(데이터 패러티 우선). 모든 소스 존재 — blocked 없음. |
| **D6** Message/GetContactList | read-api | `hwe/sammo/API/Message/GetContactList.php:24-28` · `func.php:390-434`(checkSecretPermission) · `func_message.php:17,27-29` | `app/game-api/.../controller/ContactController.kt:34-81`(secretPermission 버그 :79) | `GeneralReadEntity`(nationId/officerLevel/penalty/meta) · `PenaltyKey` enum(해석소스 확인 필요) | N | IT: auditor→flags 0x4 미설정(secretMin=3), nation==0/officer==0→0x4 off, NoChief/NoTopSecret/NoAmbassador 클램프 | 부분(fid 62) | (a) **버그**: `secretPermission()`(ContactController.kt:79)이 'auditor'→4로 매핑해 0x4 오설정. legacy `checkSecretPermission(checkSecretLimit=false)` 포팅: nationId==0\|\|officerLevel==0→-1; NoChief penalty→0; secretMax=(NoTopSecret\|\|NoChief?1:NoAmbassador?2:4); secretMin=(level12→4,permission=='ambassador'→4,=='auditor'→3,level>=5→2,level>1→1,else 0); **0x4는 min(secretMin,secretMax)==4 일 때만 set**. (b) PenaltyKey 문자열 키는 legacy enum에서 **정확 확인 후 사용(추측 금지)**. (c) 미인증 시 `{nation:[]}`(의도이식 시 면제 판정). FE 동반: mailbox raw 숫자입력→contacts 기반 색상그룹 선택기(MessagePanel.vue:556-674). |
| **D7** Message/GetRecentMessage | read-api | `hwe/sammo/API/Message/GetRecentMessage.php:67-160` | **신규** `MailboxController.kt` `@GetMapping("/recent")` + `dto/MessageDto.kt` 봉투 | `GeneralResolver` · `secretPermission`(ContactController 재사용) · `MessageRepository.findByMailboxAndType`(존재) + valid_until>now 필터(신규 메서드) · `MessageType.value`(MessageType.kt:7-11) | N | IT: 4섹션(private/public/national/diplomacy), id DESC limit 15, valid_until>now, sequence 커서, diplomacy 마스킹, type 소문자 | 미존재(fid 25) | (a) `GET /api/messages/recent?sequence` 신설 — 봉투 `{result,private[],public[],national[],diplomacy[],sequence,nationID,generalName,latestRead}`. (b) 섹션 라우팅: private=PRIVATE@generalID, public=PUBLIC@9999, national=NATIONAL@9000+nationID, diplomacy=DIPLOMACY@9000+nationID. (c) 각 섹션 `valid_until>now` + id DESC + limit 15(신규 repo 메서드). (d) sequence/nextSequence/마지막섹션 array_pop 페이지네이션(L84-143,L151). (e) **diplomacy 마스킹**: dest.nationId!=0 && permission<3 → text='(외교 메시지입니다)', option.invalid=true(L125-139). (f) `type=type.name`→`type=type.value`(MailboxController.kt:67). (g) raw message jsonb/mailbox/validUntil/Int src·dest 제거 → toArray 필드셋(public이면 dest=null). **latestRead = BLOCKED**(GeneralStorKey latestReadPrivateMsg/latestReadDiplomacyMsg KV 미포팅, 날조 금지). **delayFrequentCall = BLOCKED**(세션 모델 미정). |
| **D8** Message/GetOldMessage | read-api | `hwe/sammo/API/Message/GetOldMessage.php:20-142` · `Message.php:202-223` | `MailboxController.kt:29-36,67`(기존 평면 read) + 봉투 재구성 + `dto/MessageDto.kt:18-36` | `MessageRepository.findByMailboxAndType`(존재) + valid_until>now + id DESC limit 15 + id<to(신규) · `secretPermission` · `GeneralResolver` | N | IT: to/type 페이징 LIMIT 15, id DESC, valid_until>now, diplomacy 마스킹, 봉투 `{타입별[],result,keepRecent,sequence,nationID,generalName}` | 부분(fid 25) | (a) 평면 List→봉투 `{private[],public[],national[],diplomacy[],result,keepRecent,sequence,nationID,generalName}`. (b) `to(id<to)+type` @RequestParam + LIMIT 15. (c) type→mailbox 라우팅(private=generalID, public=9999, national/diplomacy=9000+nationID). (d) **valid_until>now 만료 필터**(현재 findByMailboxOrderById 무필터 → invalidate된 '2000-12-31' 메시지 노출 버그). (e) 정렬 id DESC. (f) `type=type.name`→`type.value`. (g) time/validUntil Instant→`yyyy-MM-dd HH:mm:ss` 문자열(FE 사전식 '9999-12-31' 비교 정합). (h) **diplomacy 마스킹**(D7 동일). sequence=max(반환 id, reqTo). raw message jsonb 제거. **delayFrequentCall = BLOCKED**. |
| **D9** Vote/GetVoteList | read-api | `hwe/sammo/API/Vote/GetVoteList.php:40-43` · `DTO/VoteInfo.php:10-17` | `app/game-api/.../controller/VoteController.kt:37-51` · `dto/F4Dto.kt:367-375`(VoteSummary) → **신규 `VoteDto.kt`로 분리 권장**(diplomacy와 F4Dto co-widen 회피) | `VoteReadRepository.findAllByOrderByIdDesc`(VoteReadRepository.kt:47-54) · `VotePollReadEntity.options`(jsonb LinkedHashMap, 삽입순서) · 공통 turnTime 포맷터 | N | IT: 봉투 `{result:true,votes:Map<voteID,VoteInfo>}`, VoteInfo 7필드, options 배열, opener nullable, startDate/endDate 'YYYY-MM-DD HH:MM:SS' | 부분(fid 30) | (a) 평면 List<VoteSummary>→`VoteListResponse{result:true, votes:LinkedHashMap<Int,VoteInfo>}`(voteID 키). (b) VoteInfo = `{id,title,multipleOptions,opener(String?),startDate,endDate,options(List<String>)}` — legacy 이름. (c) **VoteSummary 폐기**(openerName→opener, startAt→startDate, endAt→endDate, options 누락, closed 추가됨). (d) opener: opener_name 빈문자열→null(FE `?? '[SYSTEM]'` 폴백; 0=시스템 매핑은 NewVote.php 저장규칙 확인 후, 그 전엔 빈→null만 안전). (e) options: poll.options.values 삽입순서 String 리스트(키=인덱스, e.value?.toString()?:e.key). (f) startDate/endDate = 공통 turnTime 포맷('YYYY-MM-DD HH:MM:SS') — ISO면 FE 사전식 비교 깨짐. closed 필드 제거. |
| **D10** Vote/GetVoteDetail | read-api | `hwe/sammo/API/Vote/GetVoteDetail.php:47-73` · `DTO/VoteComment.php:11-31` | `VoteController.kt:94-110` · `dto/F4Dto.kt:391-406`(VoteDetailResponse) → **신규 `VoteDto.kt`** | `VoteReadRepository`(selection jsonb GROUP BY 집계 신규) · `GeneralRepository.countByNpcLessThan(2)`(신규) · 공통 turnTime 포맷터 | N | IT: 최상위 `{result,voteInfo,votes,comments,myVote,userCnt}`, votes=[[sel[],cnt]], userCnt=general(npc<2) count, myVote nullable | 부분(fid 55) | (a) 평탄→중첩: 최상위 `{result, voteInfo:VoteInfo, votes, comments, myVote, userCnt}`. VoteInfo = D9와 동일 중첩 DTO. (b) `votes` = `List<Pair<List<Int>,Int>>`(selection 조합 GROUP BY count) — 현 VoteOptionResult(옵션별 평탄)를 폐기, 옵션 텍스트는 voteInfo.options로. (c) **userCnt = general npc<2 count**(현 distinct-voter 집계는 투표율 분모 오류→항상 ~100%). 신규 read 쿼리. (d) `myVote` **nullable**(미투표/익명/principal 없음→null, emptyList 금지 — FE canVote 게이트). (e) opener nullable, startDate/endDate/comment.date = turnTime 문자열. (f) **body 제거**(legacy 미존재, F4Dto.kt:398 raw 컬럼 누설), **closed 제거**(F4Dto.kt:400). (g) comments에 voteID/generalID 추가(VoteComment 계약). |
| **D11** Global/GetDiplomacy | read-api | `hwe/sammo/API/Global/GetDiplomacy.php:35-104` · `func.php:38-82`(getNationStaticInfo) | `app/game-api/.../controller/DiplomacyController.kt:105-129`(conflict) :42,:119-126(matrix) · `dto/F4Dto.kt:92-96,121-133` | `NationReadEntity`(power:55/level:46/capitalCityId:34/typeCode:49/meta.gennum:74) · `CityReadRepository.findAll`(도시명 집계) · `PhpRound`(소수1자리 half-away) · `GeneralResolver`(myNationID) | **Y**(conflict % 정규화 수치 패러티 — 캡처 골든 픽스처 권장) | GoldenTest(GetDiplomacy.php JSON 캡처): nations(power DESC+cities), conflict(round(100*killnum/sum,1) 튜플), diplomacyList(viewer-conditional 마스킹), myNationID. **현 F4ReadControllersTest.kt:218-238은 diverged shape를 고정 중 → 교체** | 부분(fid 45/55) | (a) conflict 응답 reshape: `{result, nations[], conflict[[cityId,{nationId:pct}]], diplomacyList{me:{you:state}}, myNationID}`(현 `{cities,matrix}` 폐기). (b) **nations** = SimpleNationObj 동형 `{nation(id),name,color,type,level,capital,gennum,cities(List<String>),power}`, level>0 필터, **power DESC 정렬**, cities=cities.findAll() nationId 그룹 도시명(삽입순서). (c) **conflict %**: city.conflict 빈맵/key<2 도시 제외, sum=Σkillnum, `round(100*killnum/sum,1)` PhpRound — DTO를 Int→Double(소수1자리). 현 `.toInt()` 절삭(L113-115) 제거. (d) **viewer-conditional 마스킹**: myNationID resolve, me/you 중 하나가 myNation이면 원 state, 둘 다 아니면 3~7→2(현 무조건 마스킹 L42,L119-126 버그). (e) `myNationID` 필드 추가(resolver.resolve(userId)?.nationId?:0, letters() L67 패턴). (f) dead `DiplomacyMonthProcessor.kt`(prod caller 없음) 정리 또는 non-prod 주석 — PostUpdateMonthly가 단일 진실. |

## 2. BLOCKED(원천 미확정 — 값 날조 금지) 요약

| 행 | 항목 | 원천 미확정 사유 | 해소 조건 |
|----|------|------------------|-----------|
| D1 | `recentLogs`(최근 경매 20건) | legacy는 파일로그 `logs/{serverID}/_auctionlog.txt` 역순 20줄(func_history.php:89-95). opensamguk 동치 경매로그 테이블/컬렉션 미확정 | 경매 로그 원천(log_entry 테이블 또는 컬렉션) 확정 후 추가. 그 전 빈 배열. |
| D4 | `checkLimit`/`increaseRefresh` 접속제한 | general_access_log/refresh_score 미영속(IdentityDto.kt:184,195) | refresh_score 소스 확정 또는 인증 divergence로 명시적 제외. |
| D7/D8 | `latestRead{diplomacy,private}` | GeneralStorKey latestReadPrivateMsg/latestReadDiplomacyMsg KV 저장 컬럼/테이블 미포팅 | 저장 모델 확정 후 KV read. |
| D7/D8 | `delayFrequentCall`(폴링 스로틀) | 세션 상태(lastMsgGet) 저장소 미정의 | 세션 모델 확정 또는 의도이식 divergence 명시 제외. |

## 3. 실행 순서 / 병렬

- **disjoint 병렬 가능(컨트롤러/DTO 독립)**: D1(Auction Resource), D4(Betting List)+D5(Betting Detail 동일 controller지만 한 worktree), D6(Contact), D7+D8(Mailbox 동일 controller — 한 worktree, 봉투+마스킹 공유), D11(Diplomacy).
- **Auction 행 묶음**: D1·D2·D3 모두 `AuctionController.kt`+`AuctionDto.kt` co-widen → **한 worktree 순차**(D1 envelope→D2 unique list→D3 unique detail). 또는 D2/D3는 신규 핸들러라 메서드 추가만이면 분리 가능하나 DTO 파일 공유로 충돌 위험 → 묶는 편이 안전.
- **Vote 행 묶음**: D9·D10 → **신규 `VoteDto.kt`로 분리**해 `F4Dto.kt`(diplomacy 공유)와 disjoint화 권장. 분리하면 D9+D10 worktree와 D11 worktree 병렬.
- **공통 의존(먼저)**: turnTime 'YYYY-MM-DD HH:MM:SS' 포맷터(D2/D3/D7/D8/D9/D10 공용), valid_until>now repo 메서드(D7/D8 공용), `secretPermission` legacy 포팅(D6→D7/D8 재사용) — 이들은 creator-then-consumer 순서.

## 4. 게이트 / 검증

- 각 행 = `app/game-api` `@WebMvcTest`(또는 슬라이스 IT)로 응답 JSON 필드셋/필터/정렬/마스킹 assert. 빌드: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test`(출력 tail + test XML 검증).
- **D11만 골든(Y)**: 실 PHP install에서 `GetDiplomacy.php` 응답 JSON 캡처 → `logic`/`app:game-api` 골든 픽스처 → conflict % 정규화·power DESC·viewer 마스킹 byte/field 패러티. 현 `F4ReadControllersTest.kt:218-238`(diverged shape 고정)은 교체.
- FE 정렬(별도 그룹이나 동일 repo 모순): D1/D4/D9/D10/D11은 BE 형상 변경 시 `web/game` 소비자(api.ts/types/game.ts/page.tsx)를 동반 수정해야 컴파일/렌더 정합(BE 출력만 바꾸면 FE 깨짐). 각 행 fixSpec 말미 FE 동반 노트 참조.


---

## 4. 진척 트래커 (마스터 체크리스트)

> 상태 범례: ✅ 완료 · 🔄 진행중 · 🔴 미착수 · 🟡 부분(스텁/일부). 단위 세부 fixSpec은 §3 인라인 표 참조.

### 4.1 완료 (✅)

- [x] **Tier-0 Area1 utilGame 16종** — `web/game/lib/utilGame/` 포팅+커밋(c2293ba). (잔여=보류 3종 배선만)
- [x] **맵 정합** — 두 맵뷰어 데이터/마커 정합(b3d0ff1).
- [x] **감사 docs** — 전수 감사 raw(`_full_audit_2026-06-07.raw.json`) + MASTER_GAP.md + HARDCODE_INVENTORY.md + 본 EXECUTION_PLAN.md.
- [x] **그룹 C 부분 선행** — `BoardHandler.handleComment`(엔진+DTO, parityViolation 0) · diplo 3종 + board 2종 + auction/betting wire **기존 등록**(CommandWireMapper).

### 4.2 진행중 (🔄)

- [ ] **Tier-0 Area2 Scenario 도메인** — B1/B2/B3 (★ 하드선행, 미착수지만 다음 진입점).

### 4.3 미착수 (🔴) — 그룹별 체크리스트

**Tier-0 (잔여)**
- [ ] Area1 보류: formatCityName 배선(GetConst cityConst→룩업 adapter) · postFilterNationCommandGen(formatCityName+Josa 선결) · getNewMsgToast→그룹 C 메시지 패밀리 이관
- [ ] Area2 **B1 GeneralBuilder**(737L, 13 draw, 골든Y+flush IT) ★
- [ ] Area2 **B3 GeneralPool/RandomNameGeneral**(212L, 3 draw, 골든Y) — B1 위임
- [ ] Area2 **B2 Scenario/Nation**(200L, type choice 골든Y) — B1 카운트 의존, ScenarioImporter 책임경계 확인
- [ ] Area3 GeneralTriggerCaller+BaseGeneralTrigger(각 9L) — 첫 구체 트리거(che_도시치료 등)와 단일 PR
- [ ] Area4 공유 stub widening 1회(CommandRegistry/CommandWireMapper/Dispatcher 빈 케이스) — 그룹 A 진입 직전

**그룹 A — 명령 + 이벤트 (골든 Y 15건)**
- [ ] A1 계략 5종(골든Y): che_화계 · che_파괴 · che_탈취 · che_선동 · che_첩보
- [ ] A1 골든Y: che_단련 · che_접경귀환
- [ ] A1 deterministic: che_강행 · che_숙련전환 · che_전투태세 · che_모반시도 · che_전투특기초기화 · che_내정특기초기화 · che_등용수락(A2 accept-intake 의존) · cr_인구이동
- [ ] A1 Area4 흡수: InstantRetreat(골든Y) · ResetStat(골든Y) · DieOnPrestart · DropItem · CheckOwner
- [ ] A2 공통 인프라: message:send effect · instant-nation registry/loader · message-accept intake · StaticEventHandler 외교훅
- [ ] A2 부분포팅 본체: che_견문(골든Y) · che_해산 · che_인재탐색(골든Y, B1/B3 의존) · che_종전제의 · che_불가침제의 · che_불가침파기제의 · che_불가침수락 · che_불가침파기수락 · che_종전수락
- [ ] A3 이벤트(골든Y): RaiseInvader · RaiseNPCNation · CreateManyNPC · LostUniqueItem (B1/B3 + Area2 seam 의존)
- [ ] A3 이벤트(det): InvaderEnding · AutoDeleteInvader · RegNPC · RegNeutralNPC · CreateAdminNPC(PHP NYI=제외) · BlockScoutAction · UnblockScoutAction · ChangeCity

**그룹 B — 어드민 (대부분 골든 N)**
- [ ] B0-AUTH(self/peer 보호 규칙) · B0-DATA(UserEntity 확장+Flyway) · B0-GAMEENV(game_env read 노출)
- [ ] B6-INTAKE(forced-mutation 골격, one-daemon-write 준수)
- [ ] B1a~d(게임환경 BE: 시간시프트/락/봉급/env-set) · B1e(게임환경 탭 FE)
- [ ] B2a~e(회원관리 BE, 루트DB) · B2f(회원관리 탭 FE)
- [ ] B3a(국가통계 read) · B3b(국가변경 intake) · B3c(FE)
- [ ] B4a(장수로그 read) · B4b(외교전체 read, 마스킹無) · B4c(FE)
- [ ] B5-force-rehall(골든 검토: CheckHall)
- [ ] B6a~f(회원 강제뮤테이션 intake) · B6g(FE)
- [ ] (백로그/divergence) B-AUTH-EXT(grade 0–9) · B2b system_flag · B2e banned_member · system.NOTICE(POST /admin/notice) · 서버개폐 docker화

**그룹 C — FE 미포팅**
- [ ] C1-α(wire 기존, FE submit+DTO): j_diplomacy_{send,rollback,destroy}_letter · j_board_article_add(+TipTap, author_icon 결정) · j_board_comment_add(BE 완료, FE 폼만)
- [ ] C1-β(wire 신규 풀빌드): j_set_my_setting · j_vacation · j_set_npc_control · j_myBossInfo(+generalSetPermission)
- [ ] C1 컴포넌트: TipTap · BoardArticle · BoardComment · AuctionResource · AuctionUniqueItem · BettingDetail
- [ ] C2 즉시조치: select_npc/claim 카드 special/personal 3필드 추가 + officerLevel 제거
- [ ] C2 read/write: j_get_select_pool(read 신규) · select_general_from_pool(FE) · j_update_picked_general(det close)
- [ ] C2 **`/parity-wave` 이관**: j_select_picked_general(골든Y `allStat^1.5`)
- [ ] C3 ①: a_genList · b_myGenInfo (헬퍼 이미 이식·미사용 → 즉효)
- [ ] C3 ②: a_npcList · a_kingdomList(roster 신규)
- [ ] C3 ③: b_myKingdomInfo(계약버그) · b_myCityInfo · b_currentCity(BE DTO 확장) · b_myPage(라우팅 오류) · v_history(wire shape) · v_auction
- [ ] C3 최후(높은 blocked): a_hallOfFame(hall 테이블 부재) · a_emperior(emperor 테이블 부재)
- [ ] C4: j_simulate_battle(BE, 골든Y 전투엔진) → battle_simulator(FE 재작성) → v_battleCenter

**그룹 D — read DTO 형상**
- [ ] D1 Auction/GetActiveResourceAuctionList(envelope) · D2 GetUniqueItemAuctionList(신규) · D3 GetUniqueItemAuctionDetail(신규)
- [ ] D4 Betting/GetBettingList(신규 라우트) · D5 GetBettingDetail(4필드 추가)
- [ ] D6 Message/GetContactList(secretPermission 버그) · D7 GetRecentMessage(신규) · D8 GetOldMessage(봉투+만료필터)
- [ ] D9 Vote/GetVoteList(신규 VoteDto) · D10 GetVoteDetail(userCnt 분모 수정)
- [ ] D11 Global/GetDiplomacy(**골든Y** conflict % + power DESC + viewer 마스킹)

### 4.4 BLOCKED 누적 (원천 부재 — 날조 금지, 백로그)

- iAction 한글 표시명(specialDomestic/War Name·Info, nationType/personality/item name) — `:logic` getName/getInfo 인스턴스화 부재
- select_npc_token 테이블(V1~V10 전무) · game_env(npcmode/maxgeneral, world_state.config 미기재)
- general_access_log(refresh_score 벌점·checkLimit·increaseRefresh, P8)
- hall / emperor 테이블(read entity/repository 부재)
- D1 recentLogs(경매 파일로그 원천 미확정) · D7/D8 latestRead/delayFrequentCall · D4 checkLimit
- zeroPolicy 파생(GameUnitConst.costWithTech/develcost 포팅 미확인) · getDexLevelList 상수 미노출

---

## 5. 참조

- **마스터 갭 요약 + §5 실행 우선순위**: `docs/superpowers/gap/MASTER_GAP.md` (HEAD b58f99a 시점, 768/202/100/72, 충실도 분포·blocked·parityViolation 집계).
- **per-unit fixSpec/근거(file:line)**: raw JSON — `docs/superpowers/gap/_full_audit_2026-06-07.raw.json` (5.1MB, **통째 Read 금지**, jq 슬라이스만). 예: `jq '.comparisons[]|select(.unit|test("che_견문"))' ...` / `jq '.missingPages[]|select(.kind=="command")' ...` / `jq '.partialPorts[]|select(.id|test("Auction"))' ...`. 스키마: `.missingPages[]{id,kind,legacy,note}` · `.partialPorts[]{id,kind,legacy,current,note}` · `.comparisons[]{unit,kind,fidelity,summary,gaps[],parityViolations[]}`.
- **하드코딩/스텁/날조값 대장**: `docs/superpowers/gap/HARDCODE_INVENTORY.md` (file:line 근거, 풀-패러티 진입 게이트용).
- **그룹별 worklist(섹션 원본, 유지)**: `exec/00-tier0.md`(#section-tier0) · `exec/01-groupA-commands-events.md`(#section-groupA) · `exec/02-groupB-admin.md`(#section-groupB) · `exec/03-groupC-fe-missing.md`(#section-groupC) · `exec/04-groupD-readdto.md`(#section-groupD).
- **보조 정본**: `gap/PARITY_RECONCILED.md`, `gap/WAVE_COVERAGE_REVIEW.md`, `gap/{API,LOGIC,FE_*,READ_DTO,FOUNDING_SEAM}_GAP.md`.
- **실행 도구**: `/parity-close <code>`(단일 명령 end-to-end) · `/parity-wave [codes]`(N개 fan-out, foundation-first) · `/parity-ship`(게이트+머지+배포). 골든 캡처 = `tools/php-golden/`(Docker, MariaDB 11.4 + php:8.3, 시나리오 1010).

> *문서 한계: 충실도 비교는 457/768 단위만 커버(13 에이전트 실패 → ~311 미비교). 미비교 단위의 패러티는 본 문서로 확정되지 않으며 후속 감사로 보완. 모든 수치·근거는 raw(HEAD b58f99a 캡처) 기준, 계획 조립 HEAD = 426ae33.*
