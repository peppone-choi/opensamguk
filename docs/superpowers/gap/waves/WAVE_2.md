# WAVE 2 — silent-no-op intake fixes (buttons that lie)

## 목표
거짓 202를 반환하는 mutation 버튼 3계열(auction `auction_bid`, betting `bet`+`bettingType`, inherit `BuyHiddenBuff`/`BuyRandomUnique`)을 실제 동작하게 배선하고, 미구현인 tournament-admin 3버튼을 silent 실패에서 **명시적 비활성**으로 전환한다.

## 출처
- 인벤토리: `docs/superpowers/gap/FE_OUTPUT_ACTION_GAP.md` §5(auction) / §6(betting) / §8(inherit), 그리고 SUMMARY 의 "3 SILENT-NO-OP bugs".
- `docs/superpowers/PARITY_LEDGER.md` 라인 35-38, 192-196 (cross-cutting silent-no-op 목록).
- `docs/superpowers/GAP_AUDIT.md` WAVE 2 섹션 (라인 172-176): 2a/2b/2c.
- PHP grand truth: `legacy/devsam-core/hwe/sammo/API/InheritAction/BuyHiddenBuff.php`(93줄), `…/BuyRandomUnique.php`(65줄), `legacy/devsam-core/hwe/sammo/Betting.php`(`bet`/`purifyBettingKey`/`convertBettingKey` 라인 51-131).

## 완료/제외 (코드로 검증 — 스펙에서 제외)

이 웨이브를 작성하며 실제 코드를 읽어 **이미 닫힌 부분**을 식별했다. 아래는 W2 스펙에서 제외한다.

1. **백엔드 intake `auctionBid`/`placeBet` 는 이미 존재.** `CommandWireMapper.kt:43-71` 의 `intakeCodes` 에 `"placeBet"`, `"auctionBid"` 가 등록돼 있고, `toCommand` 가 각각 `TurnDaemonCommand.AuctionBid`(`CommandWireMapper.kt:112-118`, `tryExtendCloseDate` 포함), `TurnDaemonCommand.PlaceBet`(`CommandWireMapper.kt:105-111`, `bettingType = args.intList("bettingType")` 포함)로 매핑한다. 엔진 dispatch 도 완료 — `TurnDaemonCommandDispatcher.kt:105`(`AuctionBid`)/`:107`(`PlaceBet`). 즉 **2a 는 순수 FE 버그**(FE 가 잘못된 코드 문자열을 post)이며 백엔드 변경 불필요.

2. **`bettingType` intList 파서·wire 필드는 이미 존재.** `TurnDaemonCommand.PlaceBet.bettingType: List<Int>`(`TurnDaemonCommand.kt:427`), `CommandWireMapper.intList("bettingType")`(`CommandWireMapper.kt:235-243`, 스칼라 단일값도 래핑). 빠진 것은 **FE 가 이 필드를 채우지 않는 것**뿐 — FE 가 `nationId` 만 보내고 `bettingType` 을 omit (`betting/page.tsx:227` `extraArgs={{ bettingId, nationId }}`).

3. **inherit `BuyHiddenBuff`/`BuyRandomUnique` 의 FE 는 이미 정확히 배선됨.** `inherit/page.tsx:342-345`(`command:'BuyHiddenBuff', extraArgs:{buffKey, level:level+1, prevLevel:level}`), `:292`(`command:'BuyRandomUnique', extraArgs:{}`), `:415-420`(CommandModal `pinnedCommand={buyModal.command}`, `pinnedArgType={null}`). FE 변경 불필요 — verify-only. **빠진 것은 100% 백엔드**(wire variant + intake + dispatch + handler 부재).

4. **logic 의 `BuyHiddenBuffAction`/`BuyRandomUniqueAction` 순수 로직은 이미 존재**하나 W2 와 별개. `logic/.../inheritance/BuyHiddenBuffAction.kt`(cumulative-diff cost, `BuyHiddenBuff.php:68` 핀), `BuyRandomUniqueAction.kt`, `InheritActionRegistry.kt:45-46`. 단, 이들은 `InheritActionRegistry`(read-도메인/계산용) 경로로만 호출되며 **데몬 intake 경로(`ChangeRecorder`→flush)에 배선돼 있지 않다**. W2 의 엔진 핸들러는 이 logic 액션의 cost/log 공식을 재사용하되, side-effect 적용은 `InheritResetHandler` 패턴(델타 레코딩)을 따른다. (`BuyHiddenBuffAction.execute` 의 `onPurchase` 콜백/`InheritancePointManager` 직접쓰기 경로는 데몬에서 쓰지 않는다 — one-daemon-write-rule.)

5. **precheck 게이트는 모든 intake 코드를 통과시킨다 (변경 불필요).** `CommandPrecheckService.precheck`(`:45-48`)는 `registry.resolve(actionCode)` 를 평가하는데, 등록되지 않은 코드(`auctionBid`/`placeBet`/`BuyHiddenBuff`/`BuyRandomUnique`)는 `CommandRegistry.resolve` 의 `else -> RestAction`(`CommandRegistry.kt:162`)로 떨어진다. `RestAction.buildConstraints = emptyList()`(`CommandRegistry.kt:75`) → `evaluateConstraints([]) = Allow` → `PrecheckResult.Available`. 따라서 신규 `BuyHiddenBuff`/`BuyRandomUnique` 도 기존 inherit reset 들과 동일하게 precheck 를 통과한다. **CommandController/precheck/CommandReserveService 는 W2 에서 손대지 않는다.**

6. **tournament 엔진(start/advance/reset 의 실제 동작)은 WAVE 8.** PHP 에는 `tournament_start/advance/reset` 라는 player API 가 존재하지 않는다 — 토너먼트는 `func_tournament.php` 로직이 tick 꼬리에서 자동 진행된다(GAP_AUDIT WAVE 8). 그러므로 W2c 는 **등록이 아니라 명시적 비활성**(GAP_AUDIT 라인 176: "register (gated on WAVE 8 …); until then make the FE no-op explicit, not silent").

## 현재 실패 메커니즘 (왜 거짓 202인가)

- **auction (`auction_bid`)**: `auction/page.tsx:159` 가 `pinnedCommand="auction_bid"` post. `auction_bid ∉ intakeCodes` → `toCommand`(`CommandWireMapper.kt:102`) `null` 반환 → `CommandReserveService.reserve`(`:82-92`)가 Model A 로 폴백 → 미등록 코드를 `general_turn` 링에 reserve + `Run(POKE)` publish → 컨트롤러는 202 반환(거짓) → 데몬이 턴 해소 시 `CommandRegistry.resolve("auction_bid") = RestAction` → 휴식(no-op). 입찰 유실.
- **betting (`bet`)**: `betting/page.tsx:223` `pinnedCommand="bet"`. 동일 메커니즘으로 `bet ∉ intakeCodes` → Model A 링 → RestAction no-op. 추가로 정상 코드 `placeBet` 으로 고쳐도 **`bettingType` 이 비어** PlaceBet 의 베팅 대상이 미지정.
- **inherit (`BuyHiddenBuff`/`BuyRandomUnique`)**: `inherit/page.tsx` 가 정확한 코드+args 를 post 하나 `intakeCodes` 에 없고 wire variant 도 없음 → Model A 링 → 미등록 → RestAction no-op. 구매 유실.
- **tournament-admin**: `tournament-admin/page.tsx:87/98/110` 가 `api.command('tournament_start', {})` 를 **`generalId` 없이** 호출(`api.command(code, args)` 시 `generalId == null` → `/api/command/{code}` 쿼리 무 generalId). 컨트롤러 `command(@RequestParam generalId: Int)` 는 generalId 필수 → 400. 즉 silent 가 아니라 네트워크 오류 토스트로 떨어지나, **존재하지 않는 기능을 동작하는 것처럼 노출**한다 — 명시적 비활성으로 정리.

## Foundation-first 빌드 순서

W2 는 작고 대부분 disjoint 하나, **2b 의 공유 확장점**(`:common` wire variant → `:common` result variant → intake mapper → engine dispatch → engine handler)은 creator→consumer 순서가 강제된다. 2a/2c 는 2b 와 완전 disjoint.

Tier-0 (공유 확장점, 2b 내부 순차):
- **T0-a `:common`** — `TurnDaemonCommand.BuyHiddenBuff`/`BuyRandomUnique` wire variant + `TurnDaemonCommandResult.BuyHiddenBuffOk/Fail`/`BuyRandomUniqueOk/Fail` + result serializer 분기. (consumer: 인테이크 매퍼·엔진 핸들러.)
- **T0-b `:logic`** — 데몬-친화 순수 리졸버 `InheritBuys`(또는 기존 `BuyHiddenBuffAction`/`BuyRandomUniqueAction` 의 cost/log 공식을 `InheritResets` 형태의 `Denied/Applied` outcome 으로 재노출). 부작용 없이 cost·log·remaining 만 계산. (consumer: 엔진 핸들러.)

Tier-1 (consumer, T0 이후):
- **2b-intake** — `CommandWireMapper.intakeCodes` 에 `"BuyHiddenBuff"`,`"BuyRandomUnique"` 추가 + `toCommand` 분기(buffKey/level/prevLevel 또는 무인자).
- **2b-engine** — `InheritBuyHandler`(또는 `InheritResetHandler` 확장) + `TurnDaemonCommandDispatcher` 분기 2줄.

2a/2c 는 FE-only (또는 FE+verify), Tier-0 의존 없음 — 즉시 병렬 가능.

## 태스크 분해 표

| id | 변경 파일 (disjoint) | 무엇을 (PHP/근거 출처) | 게이트 (테스트 + 골든) | 의존성 |
|----|----------------------|-------------------------|-------------------------|--------|
| **T2a-1** | `web/game/app/game/auction/page.tsx` | `pinnedCommand="auction_bid"` → `"auctionBid"`. (백엔드는 이미 `auctionBid` intake — 제외근거 #1.) 유니크 경매면 `tryExtendCloseDate` 를 extraArgs 에 옵션 노출(`AuctionBid.tryExtendCloseDate`, `TurnDaemonCommand.kt:416`); 미노출 시 null 허용이므로 필수 아님. | `auction/page.tsx` 단위(코드 문자열 == intakeCode) — Next 빌드/타입체크. PHP 골든 N. | 없음 |
| **T2a-2** | `web/game/app/game/betting/page.tsx` | `pinnedCommand="bet"` → `"placeBet"`; `extraArgs={{ bettingId, bettingType: [selectedCandidateKey] }}` 로 교체(`nationId` 제거). `selectedCandidateKey` = `targetNations[]` 에서 라디오 선택된 후보의 `id`(= PHP candidate key — `Betting.php:66-68` `key_exists($bettingKey, candidates)`). `amountMin` 은 PHP `rule('min','amount',10)`(`Betting.php:30`) 에 맞춰 10 으로 재검토(현재 501 하드코드는 근거 불명 — open question Q1). | `betting/page.tsx` 단위 + 수동: 선택 후보 인덱스가 `bettingType` 배열로 전송됨을 확인. PHP 골든 N. | 없음 |
| **T2b-T0a** | `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`, `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt` | `BuyHiddenBuff(requestId, generalId, buffKey, level, prevLevel)` + `BuyRandomUnique(requestId, generalId)` wire variant; `BuyHiddenBuffOk/Fail`, `BuyRandomUniqueOk/Fail` result variant + serializer 분기(`TurnDaemonCommandResult.kt:459-461` 패턴). PHP arg: `type`(=buffKey), `level` (`BuyHiddenBuff.php:49-50`); BuyRandomUnique 무인자(`BuyRandomUnique.php:18-20`). | `TurnDaemonCommandWireTest`(신규 케이스) + `TurnDaemonCommandResultWireTest`(round-trip). 골든 N. | 없음 |
| **T2b-T0b** | `logic/src/main/kotlin/opensamguk/logic/actions/intake/InheritBuys.kt` (신규) | 데몬-친화 순수 리졸버: `buyHiddenBuff(buffKey, level, prevLevel, previousPoint, isUnited, inheritBuffPoints)` 와 `buyRandomUnique(previousPoint, isUnited, alreadyOrdered, reqAmount)` → `InheritResetOutcome.Denied(reason)` / `Applied(spent, remainingPrevious, log, auxUpdates, varUpdates)`. cost=`inheritBuffPoints[level]-inheritBuffPoints[prevLevel]`(`BuyHiddenBuff.php:68`), log=`"{reqAmount} 포인트로 {buffTypeText} {level} 단계 {moreText}구입"`(`:81-83`, moreText=prevLevel>0?"추가":""), random log=`"{reqAmount} 포인트로 랜덤 유니크 구입"`(`:56`). deny 순서/문자열 byte-parity: `이미 구입했습니다.`/`이미 더 높은 등급을 구입했습니다.`/`이미 천하가 통일되었습니다.`/`충분한 유산 포인트를 가지고 있지 않습니다.`/`이미 구입 명령을 내렸습니다. 다음 턴까지 기다려주세요.`. buffTypeText 는 `TriggerInheritBuff::BUFF_KEY_TEXT[type]`(display 문자열 — 미확보 시 open question Q2). | `InheritBuysTest`(cost diff·deny 순서·log 문자열). 골든 N (logic 단위; 풀 PHP 골든은 P8 backlog). | T2b-T0a |
| **T2b-1** | `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt` | `intakeCodes` 에 `"BuyHiddenBuff"`,`"BuyRandomUnique"` 추가; `toCommand` 분기 추가(`BuyHiddenBuff`→buffKey/level/prevLevel, `BuyRandomUnique`→무인자). FE 가 보내는 arg shape(`inherit/page.tsx:342-345`)와 일치: `buffKey`(str), `level`(int), `prevLevel`(int). | `CommandWireMapperTest`(신규: 두 코드 매핑). 골든 N. | T2b-T0a |
| **T2b-2** | `app/game-engine/src/main/kotlin/opensamguk/engine/intake/InheritBuyHandler.kt` (신규) + `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonCommandDispatcher.kt` | `InheritBuyHandler`: `InheritResetHandler` 패턴 복제 — owner=`me.meta["owner"]`(`:157`), previous=`previousPointReader`, isunited=state.meta, aux.inheritBuff/inheritRandomUnique 읽기. `InheritBuys` 호출 후 `apply` 로 델타: (1) `recordInheritanceLog(owner, log, "inheritPoint")`, (2) general aux 패치(`inheritBuff[type]=level` 또는 `inheritRandomUnique=now`)+`diffGeneral`, (3) `recordInheritancePointSet(owner,"previous",remaining,null)`, (4) `recordRankIncrease(id, INHERIT_SPENT_DYN, spent)` — PHP side-effect 순서(`BuyHiddenBuff.php:83-89`) byte-동일. dispatcher 에 `is TurnDaemonCommand.BuyHiddenBuff -> …`/`BuyRandomUnique -> …` 2분기 추가(`:117-119` 패턴). | `IntakeWaveC2SliceATest`(또는 신규 `InheritBuyIntakeTest`): Ok(spent/remaining/aux), Fail(deny 문자열), 미장수, 통일-게이트. 골든 N. | T2b-T0a, T2b-T0b, T2b-1 |
| **T2b-3 (verify)** | (없음 — 코드변경 0) | `inherit/page.tsx` 가 이미 `BuyHiddenBuff`/`BuyRandomUnique` 를 정확한 args 로 post 함을 확인(제외근거 #3). FE 변경 불필요. | 수동 verify: 버튼 → 202 → 다음 턴 aux.inheritBuff 반영. 골든 N. | T2b-1, T2b-2 |
| **T2c-1** | `web/game/app/game/tournament-admin/page.tsx` | start/advance/reset 3버튼을 `disabled` + "WAVE 8 토너먼트 엔진 미구현 — tick 자동 진행 예정" 안내문으로 교체. `api.command('tournament_*', …)` 호출 3개 제거(제외근거 #6: PHP 에 해당 player API 없음, 등록 금지). 참가자/대진 read 탭(`api.tournament()`)은 유지. | `tournament-admin/page.tsx` 단위 + Next 빌드. 골든 N. | 없음 |

## 병렬화 그룹 (disjoint worktree family)

- **Family A — 2a auction/betting FE** (`T2a-1`, `T2a-2`): `auction/page.tsx` + `betting/page.tsx`. 서로·타 family 와 disjoint. 즉시 병렬.
- **Family B — 2b inherit backend** (`T2b-T0a → {T2b-T0b, T2b-1} → T2b-2 → T2b-3`): `:common`(T0a) 가 creator, 나머지가 consumer 라 **family 내부는 부분 순차**. T2b-T0b(`:logic` 신규파일)와 T2b-1(`CommandWireMapper`)은 T0a 후 서로 disjoint 병렬 가능. T2b-2 는 둘 다 소비. Family B 는 Family A/C 와 완전 disjoint(파일 무교집합).
- **Family C — 2c tournament-admin FE** (`T2c-1`): `tournament-admin/page.tsx` 단일. 즉시 병렬.

→ **disjoint family 수 = 3**. 단, `CommandWireMapper.kt` 는 Family B 내부에서만 수정(T2b-1) — 다른 family 가 co-widen 하지 않음. `TurnDaemonCommandDispatcher.kt` 도 Family B 내부(T2b-2)에서만.

## 패러티 주의점

- **RNG**: 2a/2b 의 buy 경로에는 RNG draw 가 **없다**(BuyHiddenBuff/BuyRandomUnique 는 cost 차감 + flag set 만). `InheritResetTurnTime` 만 RandUtil draw 1회를 갖는데 W2 범위 밖. 신규 핸들러에서 RandUtil 을 새로 시드하지 말 것.
- **Rounding**: cost = `inheritBuffPoints[level] - inheritBuffPoints[prevLevel]` 는 정수 상수 차이 → 라운딩 무관. `previous` 잔액은 PHP 가 float `[remaining, null]` 로 저장(`BuyHiddenBuff.php:88`); 차감은 단순 뺄셈. `Math.round`/`kotlin.math.round` 사용 금지(불필요).
- **로그 byte-parity**: T2b-T0b 의 log 문자열은 정확히 `"{reqAmount} 포인트로 {buffTypeText} {level} 단계 {moreText}구입"`(공백/접미 "구입" 포함; prevLevel>0 이면 "추가구입", 아니면 "구입") 와 `"{reqAmount} 포인트로 랜덤 유니크 구입"`. tag 는 `"inheritPoint"`. `buffTypeText` 는 PHP `BUFF_KEY_TEXT` 의 한글 표시명 — 미확보 시 Q2.
- **flush-delta**: 신규 핸들러는 inline DB write 금지. `ChangeRecorder` 의 `recordInheritanceLog`/`diffGeneral`/`recordInheritancePointSet`/`recordRankIncrease` 만 사용(`InheritResetHandler.apply` 와 동일 채널·순서). one-daemon-write-rule 준수(JPA EntityManager 미사용).
- **insertion-order**: aux 패치 시 `LinkedHashMap` 유지(`InheritResetHandler.apply:135-138` 패턴). `inheritBuff` 맵 키 순서 보존 — 재키잉 금지.
- **side-effect 순서**: PHP `launch` 순서(logger push → flush → setAuxVar → previous set → increaseRankVar → applyDB) 를 핸들러 `apply` 가 동일 순서로 — 로그 순서 = 실행 순서 게이트.

## 오픈 질문
- **Q1 (2a betting amountMin)**: 현재 FE `amountMin={501}`(`betting/page.tsx:226`)인데 PHP `Bet.php:30` 은 `rule('min','amount',10)`. 501 의 근거(코멘트 "bet > 500원")가 PHP 의 어느 경로(예: `bettingHelper->bet` 내부 추가 검증)인지 미확인. 골든/PHP 추가 검증 확인 전까지 501 유지할지 10 으로 내릴지 — PARITY_LEDGER betting 항목 교차확인 필요.
- **Q2 (2b buffTypeText)**: `TriggerInheritBuff::BUFF_KEY_TEXT[$type]` 의 8개 한글 표시명을 PHP 에서 미추출. 로그 byte-parity 를 위해 이 맵을 `:common`/`:logic` 상수로 포팅해야 하며, 미확보 시 log 문자열이 깨진다. legacy `TriggerInheritBuff.php` 읽어 확정 필요(추측 금지).
- **Q3 (2a betting bettingType 키 의미)**: NATION_STRENGTH 베팅에서 candidate key 가 nation id 와 동일한지(= `targetNations[].id` 를 그대로 `bettingType` 키로 써도 되는지) 확정 필요. PHP `purifyBettingKey`(`Betting.php:56-73`)는 `info->candidates` 의 키만 허용 — read DTO 의 `targetNations[].id` 가 그 키 공간과 동일함을 read-DTO 측에서 보장하는지(WAVE 3e/6 의 candidates grid 가 본격 노출). W2 에서는 현 read 가 주는 `targetNations[].id` 를 키로 가정하되, 불일치 시 betting 후보 검증은 WAVE 6c 로 이월.
- **Q4 (2b PlaceBet/InheritBuy 게이트 미검증 분기)**: 엔진 `PlaceBetHandler` 는 현재 `bettingType` count==selectCnt/key 존재 검증을 하지 않는다(`PlaceBetHandler.kt:63` 단순 JSON 인코딩). 신규 `InheritBuyHandler` 도 deny 경로를 PHP 와 동일 순서로 구현하되, betting 의 후보 검증 보강은 W2 범위 밖(WAVE 6c). 명시만 하고 W2 게이트에서 제외.
