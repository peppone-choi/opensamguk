# WAVE 5 — mutation surface: chief-center 예약 편집기 + finance/npc/inherit 세터 노출

## 목표

백엔드가 이미 ported·registered·ring-intake·golden-green인 21개 chief/nation-internal 명령과 backend-ready 세터(finance setRate/setBill/setSecretLimit · npc-control · inherit buy/reset/get-more · diplomacy 서신 lifecycle)를 **player-reachable mutation surface로 노출**한다. 핵심 신규 빌드는 단 하나의 빠진 seam — game-api가 chief 명령을 `nation_turn` 링에 예약하는 경로 — 이며 나머지는 FE 노출이다.

## 출처

- 인벤토리: `docs/superpowers/gap/FE_OUTPUT_ACTION_GAP.md` (§1 chief-center / §2 diplomacy letter / §8 inherit / Adjacent: nation-finance·npc-control), `docs/superpowers/gap/FE_STRUCTURE_GAP.md` (§3.2 PARTIAL=read-only), `docs/superpowers/PARITY_LEDGER.md` (FE_MISSING 21 + WIRING_MISSING).
- GAP_AUDIT 섹션: `docs/superpowers/GAP_AUDIT.md` §3 WAVE 5 (5a~5d), §2.5.
- PHP grand truth: `legacy/devsam-core/hwe/sammo/API/NationCommand/{ReserveCommand,ReserveBulkCommand,PushCommand,RepeatCommand,GetReservedCommand}.php`, `legacy/devsam-core/hwe/func_command.php:402-497 setNationCommand` / `:109 pushNationCommand` / `:171 repeatNationCommand`, `legacy/devsam-core/hwe/func.php:481-513 getChiefCommandTable`, `legacy/devsam-core/hwe/sammo/GameConstBase.php:378-415 $availableChiefCommand`, `legacy/devsam-core/hwe/j_diplomacy_send_letter.php` (+ destroy/rollback 형제), `legacy/devsam-core/hwe/ts/components/ChiefReservedCommand.vue`.

## 완료/제외 (코드로 검증 — 스펙에서 제외)

WAVE 2(silent-no-op intake) 및 P6 chief/diplomacy 작업이 이미 머지됨. 다음은 **이미 닫혀 있어 W5에서 다시 만들지 않는다**:

1. **WAVE 2a/2b silent-no-op intake — DONE.** `auctionBid`·`placeBet`가 `CommandWireMapper.kt:43-71 intakeCodes`에 등록됨(`auctionBid`는 `tryExtendCloseDate` 인자, `placeBet`는 `bettingType: List<Int>` 인자까지 배선). `BuyHiddenBuff`/`BuyRandomUnique`만 잔존 — W5 범위 밖(GAP_AUDIT 5c가 "after WAVE 2b"로 분리). 근거: `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt:43-71`.
2. **finance 세터 intake — DONE (backend).** `setRate`/`setBill`/`setSecretLimit`/`setBlockWar`/`setBlockScout`/`setNotice`/`setScoutMsg` 7종 모두 intakeCodes + typed `TurnDaemonCommand` 변형 + 디스패처 배선 완료. 근거: `CommandWireMapper.kt:45-51,119-139`. **잔여 = FE 노출만**(nation-finance/page.tsx 정책 블록 read-only).
3. **chief 12종 + nation-internal 9종 logic+golden — DONE.** 21개 모두 `logic/.../actions/nation/*.kt` ported + `Che*GoldenTest.kt`/`NationGoldenTest.kt` gate-closed. `turnReservedC3Codes` 12종은 의도적으로 intakeCodes에서 제외(링 경로 사용). 근거: `CommandWireMapper.kt:84-87`, `PARITY_LEDGER.md` FE_MISSING(21).
4. **daemon nation_turn 드레인 — DONE.** 데몬이 `nation_turn` 링을 읽어 chief 명령을 resolve하는 경로 존재: `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt:192 reservedTurnRepository.readReservedNationTurn(nationId, officerLevel, 0)` + `ProcessNationCommand.kt`(nation pass, general pass보다 먼저). flush: `DatabaseHooks.kt:98-101`.
5. **nation_turn 링 write/read 인프라 — DONE.** `infra/.../ReservedTurnRepository.kt:147-201 reserveNationTurn / readReservedNationTurn`(officer_level 키, MAX_CHIEF_TURNS=12, ON CONFLICT upsert) + `pullNationTurn:213-239` 이미 존재. **호출자(game-api)만 부재** — 이게 5a 단일 신규 seam.
6. **chief-reserved READ 엔드포인트 — 부분 DONE.** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ChiefCenterController.kt`가 `GET /api/nation/chief-reserved` 제공. **단, DTO shape이 legacy/FE와 불일치** — 아래 5a-Found 참고(완료 아님).
7. **diplomacy 제의 4종(che_종전제의/불가침제의/불가침파기제의/선전포고) + 수락 트리거 3종 — DONE.** `diplomacy/page.tsx` 빠른 명령 + mailbox accept. 근거: `PARITY_LEDGER.md` Nation DONE(8), `web/game/app/game/diplomacy/page.tsx:17-23`. **잔여 = 서신 send/destroy/rollback lifecycle**(5d).
8. **reserved-command READ(general ring) — DONE.** `app/game-api/.../web/ReservedCommandsController.kt GET /api/reserved-commands` + FE `PartialReservedCommand.tsx` scaffold. (chief ring은 별도 — ChiefCenterController.)

## foundation-first 빌드 순서

Tier-0 공유 확장점(여러 family가 consume) → consumer 순. **같은 파일을 두 family가 co-widen하면 안 됨.**

- **Tier-0 (foundation, 순차·creator-first):**
  - **F1** `CommandReserveService`에 `nation_turn` 예약 경로 추가(chief code → `reserveNationTurn` + poke). 5a/5b의 모든 chief 명령 제출이 의존. **이 wave의 유일한 핵심 신규 seam.**
  - **F2** chief 명령 카탈로그를 game-api에 단일 정의(`CHIEF_COMMAND_CODES` + 카테고리 grouping = `GameConstBase.php:378-415 $availableChiefCommand`). 5a 편집기 modal + 5b 카탈로그 노출이 consume.
  - **F3** `ChiefReservedResponse`/`ChiefPost` DTO를 legacy `GetReservedCommand` shape으로 enrich(name/turnTime/npcType/officerLevelText/turn[]/arg + year/month/officerLevel/troopList/chiefList map) + FE type 정렬. 5a 편집기가 consume.
- **Tier-1 (consumer, F1~F3 이후 병렬):** 5a 편집기 FE · 5b 카탈로그 노출 · 5c finance/npc/inherit 세터 FE · 5d diplomacy 서신 lifecycle.

## 태스크 분해 표

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 클래스 · 골든 Y/N) | 의존 |
|---|---|---|---|---|
| **T1** (F1) | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt` · `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt` (`turnReservedC3Codes`→`chiefRingCodes`로 확장, nation-internal 9 추가; intakeCodes 불변) | chief 명령 코드면 `reservedTurns.reserveNationTurn(nationId, officerLevel, turnIdx, code, argJson)` + `Run(POKE)`. general ring(`reserve`)도 intake도 아님. `setNationCommand`(`func_command.php:402`)의 turnList·officer_level>=5·brief 시맨틱. nationId/officerLevel은 RESOLVED principal에서(body 신뢰 금지). | `CommandReserveServiceIT.kt`(chief code → nation_turn upsert, NOT general_turn) · `CommandWireMapperTest.kt`(chief code → toCommand null + isChiefRingCommand true). 골든 N | — |
| **T2** (F1) | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt` (or 신규 `NationCommandController.kt` — disjoint) | chief 예약 요청에 nationId/officerLevel 해석 + precheck(예약-가능 게이트). `setNationCommand`의 officer_level<5 deny('수뇌가 아닙니다'), NoChiefTurnInput penalty deny, turnList 범위(0..maxChiefTurn-1) 검증. `ReserveCommand.php`/`ReserveBulkCommand.php` argTest. | `CommandControllerIT.kt`(chief reserve 202 + nation_turn 적재) · `CommandControllerSecurityTest.kt`(타국/비수뇌 403/200-deny). 골든 N | T1 |
| **T3** (F2) | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/AvailableCommandsController.kt` (`CHIEF_COMMAND_CODES` companion + chief 카탈로그 분기; `GENERAL_COMMAND_CODES`는 불변) | `GameConstBase.php:378-415 $availableChiefCommand` 6 카테고리(휴식/인사/외교/특수/전략/기타) verbatim 순서 + `getChiefCommandTable`(`func.php:481`)의 `{value,compensation,possible,title,simpleName,reqArg}` shape. 외교 4종은 이미 GENERAL_COMMAND_CODES에도 있으나 chief 카탈로그에서는 nation 카테고리로 노출. | `AvailableCommandsControllerTest.kt`(chief 카탈로그 21 code + 카테고리 순서/insertion-order). 골든 N | — |
| **T4** (F3) | `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/F4Dto.kt` (ChiefReserved* enrich) · `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ChiefCenterController.kt` (occupant join + chiefList map + year/month/officerLevel/troopList) | `GetReservedCommand.php:35-169` 응답 shape: `chiefList`(officer_level 키 map, name/turnTime/officerLevelText/npcType/turn[] {action,brief,arg}), `officerLevel`/`year`/`month`/`maxChiefTurn`/`isChief`/`troopList`. officer_level>=5 장수 join, getOfficerLevelText, getNPCType. | `F4ReadControllersTest.kt`(chiefList map keyed-by-level + occupant name/npcType + arg decode). 골든 N | — |
| **T5** (5a) | `web/game/app/game/chief-center/page.tsx` · `web/game/components/ChiefReservedCommand.tsx`(신규) · `web/game/lib/api.ts`(chiefReserve POST 추가) | `ChiefReservedCommand.vue`: edit-mode toggle, multi-turn 선택(해제/모든턴/홀수턴/짝수턴/span), 명령 선택 ▾ modal(F2 카탈로그), 반복(RepeatCommand fill N), 당기기/미루기(PushCommand ±turnIdx), bottom post selector. ReserveCommand/ReserveBulkCommand 제출 → T1 seam. | (FE: 게이트 없음 — IT는 T1/T2가 커버; 빌드 typecheck) 골든 N | T1, T2, T3, T4 |
| **T6** (5b) | `web/game/types/game.ts`(chief 카탈로그 타입) · `web/game/components/CommandModal.tsx`(chief 카탈로그 source 분기) | T3 chief 카탈로그를 modal이 렌더 가능하게(21 code). nation 카테고리 명령은 chief ring 제출 경로(T5/T1). | `CommandModal` 렌더 typecheck. 골든 N | T3, T5 |
| **T7** (5c-finance) | `web/game/app/game/nation-finance/page.tsx` | `PageNationStratFinan.vue`: 세율(setBill)/지급률(setRate)/기밀 권한(setSecretLimit) edit 컨트롤. Validator min/max 범위(`SetBill`/`SetRate`/`SetSecretLimit` PHP). 이미 intakeCodes·typed command 존재(완료/제외 #2) → CommandModal extraArgs만. | (FE typecheck) 골든 N | — |
| **T8** (5c-npc) | `web/game/app/game/npc-control/page.tsx` · `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt` (npc setter intakeCodes — T1과 같은 파일! → T1 이후 순차) · `common/.../wire/TurnDaemonCommand.kt`(신규 NpcPolicy* 변형) · 엔진 dispatcher | `PageNPCControl.vue` + `j_set_npc_control`: 국가 정책 setter + 사령턴/장수턴 우선순위 drag-reorder + 초깃값으로/이전값으로/설정(resetPolicy/rollbackPolicy/submitPolicy). `AutorunNationPolicy::$defaultPolicy`. **PHP 골든 capture 가능성** — OQ 참조. | `CommandWireMapperTest.kt` + 신규 `NpcPolicyIntakeIT.kt`. 골든 **조건부 Y**(OQ-2) | T1 (CommandWireMapper 공유) |
| **T9** (5c-inherit) | `web/game/app/game/inherit/page.tsx` · `app/game-api/.../reserve/CommandWireMapper.kt`(BuyHiddenBuff/BuyRandomUnique intakeCodes — T1·T8과 같은 파일! 순차) · `common/.../wire/TurnDaemonCommand.kt`(신규 변형) · 엔진 dispatcher + handler | `PageInheritPoint.vue`: BuyHiddenBuff(fibonacci cost, prev-level revert)/BuyRandomUnique store buttons. WIRING_MISSING(neither intakeCode nor che_). GetMoreLog(더 가져오기). | 신규 `InheritStoreIntakeIT.kt` + `CommandWireMapperTest.kt`. 골든 **조건부 Y**(cost diff byte parity, OQ-4) | T1, T8 (CommandWireMapper 공유) |
| **T10** (5d) | `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomacyController.kt`(send/destroy/rollback POST) · `common/.../wire/TurnDaemonCommand.kt`(신규 DiplomacyLetter* 변형 — T8/T9와 같은 파일! 순차) · 엔진 dispatcher + handler + `logic`(letter lifecycle) · `web/game/app/game/diplomacy/page.tsx` | `j_diplomacy_send_letter.php`(ng_diplomacy INSERT, state=proposed, prev_no replaced, Message diplomacy 발송 — JosaUtil 로그 byte parity) + destroy/rollback 형제. permission<4 deny('권한이 부족합니다. 수뇌부가 아닙니다.'). | 신규 `DiplomacyLetterIT.kt` + logic `DiplomacyLetterLifecycleTest.kt`. 골든 **조건부 Y**(서신 메시지 로그 문자열, OQ-5) | T1 |

## 병렬화 그룹 (disjoint worktree family)

`common/.../wire/TurnDaemonCommand.kt`와 `CommandWireMapper.kt`는 **cross-area 공유 아티팩트** — 동일 파일을 여러 family가 co-widen하면 merge conflict. 따라서:

- **Group 0 (foundation, 순차):** T1 → T2(별 파일이면 T1과 동시 가능하나 T1 seam 의존) → T3, T4(서로 disjoint, T1과도 disjoint → T1과 병렬 가능). 실무: **{T1}** 먼저(CommandReserveService+CommandWireMapper 생성), 그 다음 **{T3}·{T4}** 병렬(AvailableCommandsController / F4Dto+ChiefCenterController — 서로 disjoint), **{T2}**(CommandController, T1 의존).
- **Group A (chief FE):** T5 → T6. (Group 0 전부 이후. T5는 web/game chief-center+신규 컴포넌트+api.ts; T6은 types+CommandModal — disjoint.)
- **Group B (finance FE):** T7. (nation-finance/page.tsx 단독 — Group 0와 disjoint, 즉시 병렬 가능.)
- **Group C (npc + inherit + diplomacy intake):** T8 → T9 → T10. **이 셋은 `common/.../wire/TurnDaemonCommand.kt` + `CommandWireMapper.kt`를 공유 → 반드시 순차**(creator-then-consumer; 각자 새 변형 추가). FE 파일(npc-control/inherit/diplomacy page.tsx)은 disjoint이므로 wire 변형 추가만 순차로 직렬화하고 FE 렌더는 병렬 가능.

→ **disjoint 병렬 family 수 = 3** (A=chief FE, B=finance FE, C=npc/inherit/diplomacy intake-chain). Group 0는 모든 family의 선행 foundation.

## 패러티 주의점

- **insertion-order (chief 카탈로그·chiefList map):** `$availableChiefCommand`의 6 카테고리 순서(휴식/인사/외교/특수/전략/기타)와 각 카테고리 내 명령 순서를 verbatim 보존 — `LinkedHashMap`/순서 보존 list. `GetReservedCommand`의 `chiefList`는 officer_level 키 map(`nationTurnList` ORDER BY officer_level DESC, turn_idx ASC) → 재키잉 금지. nation_turn arg는 `Json::decode` insertion-order 보존.
- **로그 byte-parity (5d):** `j_diplomacy_send_letter.php:174-180`의 서신 메시지("새로운 외교 문서 #{N}{josaYi} 준비되었습니다. 외교부에서 확인해주세요." / prev_no 변형)는 `JosaUtil.pick(N, '이')` 조사 + `<Y1>`/태그 마크업까지 byte-동일. `setNationCommand` deny 문자열('수뇌가 아닙니다'/'수뇌 턴 입력 불가능'/'올바른 턴이 아닙니다. : '+turnIdx)도 verbatim.
- **flush-delta (one-daemon-write):** game-api는 publish만(POKE/typed). chief 예약은 `reserveNationTurn`(JDBC, `:infra` — JPA EntityManager 아님). 실제 nation_turn state-mutation/링 회전은 데몬(`ProcessNationCommand`/`pullNationTurn`)이 `ChangeRecorder`→`JdbcFlushExecutor`로 flush. T8/T9/T10의 신규 intake도 데몬 핸들러에서만 mutation(ChangeRecorder 경유) — game-api 인라인 쓰기 금지.
- **rounding (5c-inherit):** BuyHiddenBuff fibonacci cumulative-diff cost는 `PhpRound` half-away가 아니라 정수 누적(P6 BuyHiddenBuff cumulative-diff 패턴 — 기존 logic 참조). 새 cost 계산 금지, ported logic 재사용.
- **RNG:** chief 명령 resolve 자체는 데몬의 `'nationCommand'` 6-component seed로 이미 골든-green(W5는 노출만) — game-api 예약 경로는 RNG draw 없음. 5d 서신/5c npc도 reserve 시점 RNG 없음(메시지 발송은 결정적).
- **officer ranks 0-9 divergence:** 국가 레벨 0-9 확장(`getOfficerLevelText`)은 legacy 7단계에서 의도적 divergence — 패러티 위반 아님(MEMORY 참조). chief post 8개(lv 12/11/10/9/8/7/6/5)는 유지.

## 오픈 질문

- **OQ-1 (chief 예약 turnIdx 의미):** general ring(`general_turn`)은 `turn_idx mod 30` 절대-카운터, nation ring은 `turn_idx mod 12` + officer_level 키. FE 편집기가 보내는 `turnList`(0..maxChiefTurn-1 다중)는 `setNationCommand`처럼 슬롯 직접 지정 — `CommandReserveService`의 단일 `turnIdx` 시그니처를 turnList(다중)로 확장할지, 슬롯별 N회 호출할지 결정 필요(ReserveBulkCommand 의미와 정합).
- **OQ-2 (npc-control 골든 필요여부):** `j_set_npc_control` 정책 저장/우선순위 reorder는 결정적(RNG 없음)이라 보이나, `AutorunNationPolicy::$defaultPolicy` 초깃값/계산-derived 필드(zeroPolicy)가 PHP capture 없이 재현 가능한지 확인 필요. 가능하면 unit test로 충분(골든 N), 불확실하면 정책 저장 라운드트립 1건 capture.
- **OQ-3 (TipTap vs plaintext):** nation-finance의 nationMsg/scoutMsg는 현재 plaintext(TipTap deferred). 5c가 세율/지급률/기밀만 노출하고 메시지 에디터는 계속 deferred로 둘지(README F-시리즈와 정합) 확인.
- **OQ-4 (inherit store 골든):** BuyHiddenBuff/BuyRandomUnique는 point 차감 + 효과 적용이 데몬 핸들러. cost diff byte parity는 P6에서 일부 닫혔다고 ledger에 있음(`BuyHiddenBuff cumulative-diff cost`) — 신규 capture가 필요한지 아니면 기존 logic+test로 충분한지 P6_STATUS 대조 필요.
- **OQ-5 (diplomacy 서신 lifecycle 범위):** send/destroy/rollback 3종 전부를 W5에서 닫을지, 아니면 send(가장 가시적)만 닫고 destroy/rollback은 W6 domain REST로 미룰지. PHP는 `ng_diplomacy` INSERT + Message 발송 + prev_no state 전이까지 한 트랜잭션 — logic 포트 규모가 큼(서신 lifecycle을 logic에 새로 포팅해야 하면 별도 wave 분리 후보).
- **OQ-6 (che_부대탈퇴지시 vs troopExit 혼동):** `che_부대탈퇴지시`(chief-issued reserved nation 명령)는 즉시-intake `troopExit`와 다른 메커니즘(PARITY_LEDGER 명시). 5a/5b에서 chief ring으로만 노출하고 troopExit과 코드 분리 유지 확인(intakeCodes 오염 금지).
