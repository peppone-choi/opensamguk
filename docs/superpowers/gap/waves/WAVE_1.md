# WAVE 1 — daemon-seam correctness 실행 스펙

## 목표
golden-green / prod-broken 데몬 seam 4종(군주 후계, 외교 월간 만료, checkStatistic, 아이템 special-effect 훅)을 PHP grand truth 기준으로 닫아 라이브 데몬이 상태를 조용히 손상시키지 않게 한다.

## 출처
- 인벤토리: `docs/superpowers/gap/LOGIC_GAP.md` (§2 checkStatistic, §5 diplomacy month, §8b ruler succession, §12 item/specialty)
- GAP_AUDIT 섹션: `docs/superpowers/GAP_AUDIT.md` WAVE 1 (1a~1d, lines 165-170)
- PHP grand truth: `legacy/devsam-core/hwe/func.php` (nextRuler 1807, deleteNation 1713), `legacy/devsam-core/hwe/sammo/General.php:515-600` (kill), `legacy/devsam-core/hwe/sammo/TextDecoration/DyingMessage.php`, `legacy/devsam-core/hwe/func_gamerule.php:336-406`(diplomacy month) / `:469-651`(checkStatistic), `legacy/devsam-core/hwe/sammo/GameConstBase.php:258-313`($allItems), `legacy/devsam-core/hwe/sammo/ActionItem/*`, `legacy/devsam-core/hwe/sammo/BaseItem.php`

## 완료 / 제외 (코드로 검증, W1 스펙에서 제외)

### W0 founding seam — 이미 머지됨 (PR #26)
- `che_거병` created-set 드레인이 라이브 데몬에 배선 완료. 근거: `app/game-engine/.../turn/InMemoryTurnWorld.kt:197`(`createNation`), `:209`(`createDiplomacy`), `:222`(`createNationTurn`); `app/game-engine/.../turn/ReservedTurnHandler.kt:304-306`(created-set 드레인). 커밋 `a95efdd` / merge `ff2f12d`. **W1 범위가 아니다.**

### 1b DiplomacyMonthProcessor 배선 — 실질적으로 이미 닫혀 있음 (진단 오판 정정)
- LOGIC_GAP §5 / GAP_AUDIT 1b의 진단("`DiplomacyMonthProcessor.kt`가 tick caller가 없어 term countdown/auto-expiry가 라이브에서 안 돈다")은 **부정확**하다.
- 진짜 라이브 월간 외교 처리는 `app/game-engine/.../run/MonthlyPostUpdateHook.kt:99-150`이 `postUpdateMonthlyDiplomacy`(POST2 Q5-Q10)를 호출하여 이미 수행한다. 근거: `logic/.../world/PostUpdateMonthly.kt:194-327`(POST2)가 PHP `func_gamerule.php:336-406`을 충실 포팅 — 전쟁기한(Q5 `:345-348`), 개전 로그(Q6 `:352-360`), 종전(Q7 `:364-388`), term-1+불가침→통상(state 7→2, `PostUpdateMonthly.kt:299-300` = PHP `:399-401`)+선포→교전(state 1→0,term=6, `:301-303` = PHP `:403-406`). 이 hook은 `DaemonLoopConfig.kt:184`에서 `MonthlyPipeline`에 배선되어 매 월경계에 실행된다.
- 즉 `logic/.../diplomacy/DiplomacyMonthProcessor.kt`(legacy_core2026 TS 오라클 포트)는 **orphan dead-code**다. caller 0개(grep으로 확인), state enum 매핑/순회순서/로그가 PHP와 다르며(PHP `me desc, you desc` + stopWarList dedup vs TS `me<you` pair, TS는 로그 미푸시), PHP가 이긴다.
- **W1에서 1b로 남는 작업은 단 하나(태스크 T-1B): orphan `DiplomacyMonthProcessor.kt` + `DiplomacyMonthProcessorTest.kt` 제거(또는 deprecated 주석으로 audit 정합화).** 새 로직/골든 불필요. POST2 자체는 손대지 않는다.

### 1a 부분 인프라 — 이미 존재 (재구현 금지)
- `app/game-engine/.../turn/ChangeRecorder.kt:605`(`markNationDeleted`)가 국가 tombstone + 도시 공백지화(nationId=0, frontState=0, conflict='{}') cascade를 이미 수행 + `world.removeNation`(`InMemoryTurnWorld.kt:264`) 호출. `ng_old_nations` 테이블은 `V1__baseline.sql`에 존재.
- `kill()`의 후계 hook 골격(`ReservedTurnHandler.kt:486-489`)과 dyingMessage hook(`:85`, `:500`)은 이미 plug-in 형태. `KillTombstoneTest.kt:187` 이미 `nextRuler` hook 호출을 검증.
- **남은 갭(1a 실작업):** `:logic`에 `nextRuler`/`deleteNation` 포트가 0개(grep 확인); `DaemonLoopConfig.kt:157-164`의 `ReservedTurnHandler` ctor가 `nextRuler`/`dyingMessage`를 넘기지 않아 no-op default(`ReservedTurnHandler.kt:79`, `:85`) 사용 중. `markNationDeleted`는 장수 재야화/부대삭제/diplomacy삭제/멸망로그를 안 한다(아래 1a 참조).

## foundation-first 빌드 순서 (Tier-0 공유 확장점 먼저)

1. **Tier-0 (foundation, 순차·creator-first):**
   - **T-0A** `statistic` 테이블 마이그레이션 `V11__p_statistic.sql` (1c의 INSERT 타겟; 현재 어느 마이그레이션에도 없음) + flush row mapper/op. → 1c의 모든 consumer가 의존.
   - **T-0B** `ChangeRecorder`에 `markNationDeleted` 확장(장수 재야화 패치 + 부대 삭제 + diplomacy 삭제 cascade) + `world.listGenerals`/`applyGeneralDirtyFree` 재사용. → 1a deleteNation이 소비.
   - **T-0C** `ItemRegistry.extraHookBuilders` 등록 컨벤션은 이미 존재(`ItemHooks.kt:192`) — 신규 foundation 불필요. 1d는 이 기존 확장점에 항목만 추가(co-widen 1파일이므로 1d 내부는 순차 또는 단일 태스크).
2. **Tier-1 (consumer, T-0 위에서 병렬):** 1a 로직 포트(`:logic`), 1c 계산 로직, 1d 훅 클래스 + 등록.
3. **Tier-2:** 데몬 배선(`DaemonLoopConfig`) + 게이트.

## 태스크 분해 표

PHP 출처 file:line은 `legacy/devsam-core/` 상대. 게이트 골든 Y = 실제 PHP 캡처 재생 필요.

### 1a — ruler succession (nextRuler / deleteNation)

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 클래스 / 골든 Y/N) | 의존성 |
|---|---|---|---|---|
| **T-1A-1** | `logic/.../succession/NextRuler.kt` (신규) | `nextRuler` 후계 선택 순수 로직: (a) NPC-ruler(`npc>0` & `!fiction`) → `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'NextNPCRuler',year,month,generalId)))`로 `npcmatch2 asc`(affinity 거리, `IF(ABS(affinity-X)>75,150-ABS,ABS)`) 동점-최소 풀에서 `rng.choice` 1 draw. (b) fallback1: `officer_level!=12 AND >=9 AND npc!=5 ORDER BY officer_level DESC LIMIT 1`. (c) fallback2: `... ORDER BY dedication DESC LIMIT 1`. (d) 후보 없음 → deleteNation 위임. 후계 promote: dying ruler `officer_level=1,officer_city=0`; heir `officer_level=12,officer_city=0` + 유지 로그(`hwe/func.php:1807-1887`) | `NextRulerTest` (logic) / **골든 Y** (1010 캡처: NPC 군주 사망 시드별 후계 선택 draw-for-draw) | T-0B |
| **T-1A-2** | `logic/.../succession/DeleteNation.kt` (신규) | `deleteNation` 순수 로직 산출물(side-effect 명세): DeleteConflict, 【멸망】글로벌 히스토리 로그(`<R><b>【멸망】...멸망했습니다.`), 전 장수 재야화(belong=0/troop=0/officer_level=0/officer_city=0/nation=0/permission='normal' + npc<2면 max_belong 유산 갱신 + 개별 멸망 로그 PLAIN+history), 도시 공백지(nation=0,front=0), 부대 삭제, ng_old_nations INSERT 페이로드, nation/nation_turn/diplomacy 삭제, refreshNationStaticInfo (`hwe/func.php:1713-1805`) | `DeleteNationTest` (logic) / **골든 Y** (1010 캡처: 후계 없는 군주 사망 → 멸망 cascade 로그/행 byte-match) | T-0B |
| **T-1A-3** | `app/game-engine/.../turn/ChangeRecorder.kt` (T-0B 확장) | `markNationDeleted` cascade에 장수 재야화 패치(`diffGeneral`로 belong/troop/officer_*/nation/permission) + 부대 삭제 + diplomacy 삭제 기록 추가(현재 `:605-626`는 도시 공백지화만). diplomacy 삭제 채널/op 필요 시 추가 | `KillTombstoneTest`, `MarkNationDeletedCascadeTest` (engine) / 골든 N (구조 단위) | T-1A-2 |
| **T-1A-4** | `app/game-engine/.../config/DaemonLoopConfig.kt` (`ReservedTurnHandler` ctor 구역만) | `:157-164` ctor에 `nextRuler = { gid, env -> nextRulerAdapter(gid, env) }` + `dyingMessage = { g -> dyingMessageProvider(g) }` 배선(no-op default 제거). nextRulerAdapter는 T-1A-1 로직을 world read + recorder write로 thread; heir promote는 `world.applyGeneralDirtyFree`+`recorder.diffGeneral`; 후보 없음 → T-1A-2(world.markNationDeleted 확장 경유) | `DaemonRulerSuccessionWiringTest` (engine) / 골든 N | T-1A-1, T-1A-2, T-1A-3 |
| **T-1A-5** | `app/game-engine/.../turn/<DyingMessageProvider>.kt` (신규, engine) | `DyingMessage.getText()` 분기 포트: npc==0 → 42-msg pool, npc∈{2,6} → defaultMessage pool(1), npc∈{3,4} → 10-msg utilNPC pool("떠났습니다"), else → defaultMessage. `;name;` 치환(owner & age-startage>1 → `(realName)` 부가) + `JosaUtil::batch`. **선택은 `Util::choiceRandom`=`array_rand`=PHP 전역 mt_rand → 비결정론**(`src/sammo/Util.php:648`) | `DyingMessageProviderTest` (engine) / **골든 N + quarantine 증명** (아래 패러티 주의 참조) | T-1A-4 |

### 1b — DiplomacyMonthProcessor (orphan 정리만)

| id | 변경 파일 (disjoint) | 무엇을 | 게이트 / 골든 | 의존성 |
|---|---|---|---|---|
| **T-1B** | `logic/.../diplomacy/DiplomacyMonthProcessor.kt`, `logic/.../test/.../diplomacy/DiplomacyMonthProcessorTest.kt` (삭제 또는 deprecated 표기) | orphan TS-port 제거(POST2가 이미 라이브 처리; 위 "완료/제외" 참조). 라이브 path `PostUpdateMonthlyDiplomacyTest`는 그대로 유지·green 확인 | `PostUpdateMonthlyDiplomacyTest` 회귀 green / 골든 N | (없음) |

### 1c — checkStatistic

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 / 골든 | 의존성 |
|---|---|---|---|---|
| **T-1C-1** | `infra/src/main/resources/db/migration/V11__p_statistic.sql` (신규) | `statistic` 테이블 스키마(year,month,nation_count,nation_name,nation_hist,gen_count,personal_hist,special_hist,power_hist,crewtype,etc,aux) — 현재 부재(검증됨) | `StatisticMigrationIT` (infra, Docker-gated) / 골든 N | (없음) |
| **T-1C-2** | `logic/.../world/CheckStatistic.kt` (신규, 순수 계산) | `checkStatistic` 집계 포트: 평균 금/쌀/숙련/경험공헌(`Util::round`), 국가 min/avg/max tech·power(`floor`/`round`), 국가 type 히스토그램(`$availableNationType` 순서), powerHist 문자열(name(power/gennum/cnt/pop/pop_max/goldrice/...)), personal/special/special2/crewtype 히스토그램(getGenChar/getGeneralSpecialDomesticName/getGeneralSpecialWarName/getShortName), gen_count `total(user+npc)`, etc 문자열 — 전부 byte 정확(`hwe/func_gamerule.php:469-651`) | `CheckStatisticTest` (logic) / **골든 Y** (1010 month==1 경계 statistic 행 byte-match) | (없음) |
| **T-1C-3** | `app/game-engine/.../run/<EngineCheckStatistic>.kt` (신규) + flush row op | world 집계 → T-1C-2 호출 → `statistic` INSERT를 ChangeRecorder/JdbcFlushExecutor 채널로 flush(데몬 쓰기는 JDBC delta only — one-daemon-write-rule) | `EngineCheckStatisticTest`, flush IT (engine/infra) / 골든 N | T-1C-1, T-1C-2 |
| **T-1C-4** | `app/game-engine/.../config/DaemonLoopConfig.kt` (`checkStatistic =` 한 줄, `:183`) | `CheckStatistic { }` 빈 람다 → `CheckStatistic { engineCheckStatistic.run() }` 교체. `MonthlyPipeline.kt:116`이 month==1에 호출(배선은 이미 존재; impl만 교체) | `DaemonCheckStatisticWiringTest` (engine) / 골든 N | T-1C-3 |

### 1d — item special-effect hooks (scenario_1010 reachable non-stat specials)

reachability 권위 = `GameConstBase.php:289-312` `$allItems['item']`(값 0=상점 buyable, 1=유니크/유산 풀). 이 풀의 비-스탯 special 중 `ItemHooks.kt:194-222` 미등록 35종을 hook 유형별로 등록. (능력치 3종·변도론·백상·기주마·비도·맥궁·태현청생부·구정신단경·충차·event_전투특기_20·납금박산로·주판·삼략·동작·평만지장도는 등록 완료 — 제외.)

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처: `hwe/sammo/ActionItem/`) | 게이트 / 골든 | 의존성 |
|---|---|---|---|---|
| **T-1D-1** | `logic/.../items/ItemHooks.kt` (스탯/내정 폴드 그룹) + `ItemModules.kt` (필요 시 모듈 클래스) | onCalcStat/onCalcOpposeStat/onCalcDomestic/onCalcStrategic 폴드 17종: 명성_구석, 징병_낙주(stat+domestic), 집중_전국책, 환술_논어집해, 행동_서촉지형도, 간파_노군입산부(opposeStat), 농성_주서음부·위공자병법(stat+opposeStat), 계략_이추·향낭·육도(domestic; 육도 stat도), 훈련_철벽서·단결도, 사기_춘화첩·초선화, 회피_태평요술, 필살_둔갑천서 — 각 `che_*.php` onCalc* 본문 byte-faithful | `ItemHooksTest` (logic) / 골든 N (기존 ItemHooksTest 패턴; 폴드 값 단위검증) | T-0C(기존) |
| **T-1D-2** | `logic/.../war/specialty/<신규 trigger modules>.kt` + `ItemHooks.kt` (전투 트리거 그룹) | getBattlePhaseSkillTriggerList/getBattleInitSkillTriggerList/getWarPowerMultiplier 16종: 저격_수극·매화수전, 의술_정력견혈산·청낭서·태평청령·상한잡병론(phase+preTurn), 위압_조목삭, 진압_박혁론, 저지_삼황내문, 불굴_상편, 약탈_옥벽, 상성보정_과실주, 사기_탁주·훈련_청주(battleInit), 척사_오악진형도·공성_묵자(warPowerMultiplier) — `TYPE_ITEM(+DEDUP*N)` 트리거 타입/우선순위 정확 | `WarItemHooksTest`, `WarItemModulesTest` (logic) / **골든 Y** (전투 페이즈 트리거 draw-for-draw: 1 RandUtil(warSeed) 위 draw 순서/개수 일치) | T-0C(기존), T-1D-1 (같은 `ItemHooks.kt` co-widen → 순차) |
| **T-1D-3** | `logic/.../items/ItemHooks.kt` (preTurn/onArbitrary 그룹) + 등록 finalize | getPreTurnExecuteTriggerList/onArbitraryAction 잔여: 치료_환약(preTurn+arbitrary), 보물_도기(arbitrary), 의술 4종 preTurn(che_도시치료 류) — `getPreTurnExecuteTriggerList` GeneralTrigger 본문 byte-faithful + 79개 전수 equip-reachability 감사 문서화(allItems 풀 대조 표) | `ItemHooksTest`, `ItemReachabilityAuditTest` (logic) / 골든 N (감사) | T-1D-1, T-1D-2 (`ItemHooks.kt` 동일 파일 → 순차) |

## 병렬화 그룹 (disjoint worktree family)

같은 파일 co-widen 금지 규칙 적용. `ItemHooks.kt`는 1d 전체가 공유 → 1d는 **단일 family 내부 순차**.

- **그룹 A (1a succession):** T-1A-1, T-1A-2 (신규 `:logic` 파일, 서로 disjoint이나 T-1A-2가 T-1A-1의 deleteNation 위임을 받으므로 creator-first 순차) → T-1A-3(`ChangeRecorder.kt`) → T-1A-4(`DaemonLoopConfig.kt` ctor 구역) → T-1A-5(신규 DyingMessageProvider).  *주의:* T-1A-4와 T-1C-4는 **둘 다 `DaemonLoopConfig.kt`를 수정** → 그룹 A와 그룹 C의 `DaemonLoopConfig` 편집은 **순차**(같은 파일 co-widen 충돌). 권장: foundation 단계에서 `DaemonLoopConfig` ctor/checkStatistic 와이어링을 한 worktree에서 일괄.
- **그룹 B (1b cleanup):** T-1B — 완전 disjoint, 어느 그룹과도 무충돌. 즉시 병렬 가능.
- **그룹 C (1c statistic):** T-1C-1(신규 SQL) → T-1C-2(신규 `:logic`) → T-1C-3(신규 engine) → T-1C-4(`DaemonLoopConfig.kt` 한 줄 — 그룹 A의 `DaemonLoopConfig` 편집과 순차).
- **그룹 D (1d items):** T-1D-1 → T-1D-2 → T-1D-3 — `ItemHooks.kt` 공유로 family 내부 순차이나, 그룹 A/B/C와는 완전 disjoint → 그룹 D는 A/B/C와 병렬.

**disjoint 병렬 family 수 = 3** (A+C는 `DaemonLoopConfig.kt` 공유로 배선 시점에 합류 → 사실상 {A∪C-배선}, {B}, {D} 3 family. logic-port 단계에서는 A/C/D/B 4-way 병렬 가능하나 배선 충돌 회피 위해 3으로 카운트.)

## 패러티 주의점

- **RNG (1a NextNPCRuler):** 후계 선택은 `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'NextNPCRuler',year,month,generalId)))`로 새 스트림을 만들고 `rng.choice(candidates)` **정확히 1 draw**. 후보 풀은 `npcmatch2 asc`(affinity 거리 변환)로 정렬한 뒤 **최소 npcmatch2 동점 그룹만** 후보로 자름(PHP `:1838-1843`의 break 로직 — `!$candidate['npcmatch2'] == $minNPCMatch` 버그성 비교 포함, byte-faithful 재현 필요). 결정론적 → **골든 캡처 가능(Y)**.
- **RNG (1a dyingMessage):** `Util::choiceRandom`=`array_rand`=**PHP 전역 Mersenne Twister(mt_rand)** — RandUtil/LiteHashDRBG 스트림이 **아님**. 시드 없이 비결정론적 → **draw-for-draw 골든 캡처 불가**. → **quarantine**: 라이브에서는 결정론 대체(예: defaultMessage 또는 고정 인덱스)를 쓰되, sibling-code-path byte-match(메시지 풀 문자열/Josa 치환/`(realName)` 부가 규칙은 byte 정확)로 증명하고 phase backlog에 기록. **메시지 풀의 인덱스 선택만 비결정 — 풀 내용·치환·조사는 패러티 대상.**
- **Rounding (1c):** `Util::round`=half-away → `PhpRound`. avg 금/쌀/숙련/경험공헌/avgtech/avgpower는 `Util::round`, mintech/maxtech는 `floor`(distinct). dex 합은 `(dex1+dex2+dex3+dex4)` (PHP `:490`은 dex5 제외 — Kotlin DEX_KEYS 5개와 대조해 **dex 범위를 PHP대로 4개로** 맞출 것).
- **로그 byte-parity (1a):** 【멸망】(`<R><b>【멸망】</b></><D><b>{name}</b></>{josaUn} <R>멸망</>했습니다.`), 개별 장수 멸망 로그(`<D><b>{name}</b></>{josaYi} <R>멸망</>했습니다.`), 유지(`<C><b>【유지】</b></><Y>{nextRulerName}</>{josaYi} <D><b>{nationName}</b></>의 유지를 이어 받았습니다`) — Josa 픽 + 색/태그 정확. **로그 순서 = 실행 순서**(deleteNation: 멸망 글로벌 → 장수별 재야 로그 → 도시/부대/국가/외교 삭제). dyingMessage는 `pushGlobalActionLog`(현재 Kotlin `globalLog`/pushLog — scope/category 확인).
- **Flush delta (전 태스크):** 데몬 쓰기는 ChangeRecorder→JdbcFlushExecutor JDBC delta only. JPA EntityManager write 금지(one-daemon-write-rule, architecture-test 강제). 1c statistic INSERT, 1a 국가/장수/외교 삭제·재야화 모두 recorder 채널로. nextRuler heir promote = `recorder.diffGeneral`+`applyGeneralDirtyFree`.
- **Insertion-order (1c):** nation type 히스토그램은 `$availableNationType` 순서, nations 순회는 `power desc` 쿼리 순서, generals 순회는 DB 행 순서 — LinkedHashMap으로 보존. aux jsonb 키 순서 = PHP `Json::encode` 순서.
- **RNG (1d 전투 트리거):** 전투 페이즈 트리거 아이템은 전투 전체를 도는 **단일 `RandUtil(warSeed)`** 위에서 추가 draw를 낸다 → 트리거 타입(`TYPE_ITEM`)·DEDUP 우선순위·발동 확률 draw 개수/순서가 기존 specialty 트리거 사이에 정확히 끼어들어야 함(하나라도 어긋나면 하류 desync). → 1d 전투 그룹(T-1D-2)은 **골든 Y**.

## 오픈 질문

1. **dyingMessage 라이브 대체 정책:** array_rand 비결정 → 라이브에서 (a) defaultMessage 고정, (b) 고정 인덱스, (c) 시드 부여한 결정론 대체(패러티에서 의도적 divergence로 quarantine 문서화) 중 어느 것? 사용자/PARITY_LEDGER 정책 확인 필요. (권장: defaultMessage 고정 + quarantine 등재.)
2. **NextNPCRuler `!$candidate['npcmatch2'] == $minNPCMatch` 비교 버그:** PHP는 `!$x == $y`를 `(!$x) == $y`로 평가(연산자 우선순위). 풀 자름 경계가 사실상 깨져 있을 수 있음 — 1010 골든으로 실제 후보 풀 크기를 확인해 byte-faithful 재현(버그 포함)할지 결정. 캡처가 권위.
3. **checkStatistic flush 빈도/멱등성:** month==1마다 1행 INSERT. 재시작-재수화(restart-rehydrate, LOGIC_GAP §15) 시 중복 INSERT 방지 키(year,month unique?) 필요 여부 — PHP는 매번 INSERT(중복 가능). 마이그레이션에 unique 제약을 둘지 결정.
4. **diplomacy 삭제 op (부분 해소):** `markNationDeleted` docstring(`ChangeRecorder.kt:597-603`)은 nation cascade가 이미 diplomacy/nation_turn/nation DELETE + ng_old_nations를 처리하도록 설계되었다고 명시하며, `world.removeNation`(`InMemoryTurnWorld.kt:276`)이 diplomacy를 prune한다. 따라서 diplomacy/nation_turn 삭제 인프라는 markNationDeleted 경로에 이미 있을 가능성 높음 — T-1A-3은 이 cascade가 **flush까지 byte-faithful로 닫혀 있는지 검증**하고, 부재한 부분(장수 재야화 패치·부대 삭제·【멸망】+개별 멸망 로그·max_belong 유산 갱신)만 추가하는 것으로 한정. 신규 diplomacy delete op는 불필요할 수 있음(IT로 확인).
5. **1d 79개 전수 vs reachable-only:** allItems 풀 밖(시나리오/이벤트 전용, buyable·unique 둘 다 아님) 아이템(예: che_치트_*, che_의술 일부 변형)은 reachable 아님 → 등록 제외하고 감사 표에 "unreachable" 명시. 사용자가 "arbitrary item assignment까지 완전 패러티"를 원하면 범위 확장 필요(현재는 reachable-only로 스코프).
