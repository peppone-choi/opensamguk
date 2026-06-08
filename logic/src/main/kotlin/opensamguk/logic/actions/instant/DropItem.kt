package opensamguk.logic.actions.instant

import opensamguk.common.josa.JosaUtil

/**
 * 아이템 버리기 instant-action — PHP `sammo/API/General/DropItem.php`(`extends BaseAPI`)의 `launch()`
 * 순수 포팅. **예약 커맨드(che_*)가 아니라** instant API 핸들러이므로 호출 즉시 1회 실행된다([InstantActionRegistry]
 * 분류: `DropItem` ∈ `INSTANT_ACTION_CODES`). dispatch는 Model B — `:common`의
 * [opensamguk.common.wire.TurnDaemonCommand.DropItem] variant → 엔진 dispatcher → 핸들러 →
 * ChangeRecorder → JDBC flush (one-daemon-write-rule 준수, game-api는 인테이크만).
 *
 * ## 패러티 모델 = InheritResets 패턴 (world/DB 없이 입력→Outcome)
 * [InheritResets]와 동형으로, 본체는 **순수 effect 산출기**다. 엔진 핸들러가 PHP `launch()`가 읽는 입력
 * (`getItem(itemType)`의 클래스명/표시명/원명/구매가능 여부, 장수명, 국가명)을 주입하고, 본체는
 * [DropItemOutcome]을 반환한다. 핸들러가 그 Outcome으로 장수 item 슬롯 clear 델타 + 로그를 적재한다.
 *
 * ## draw 없음 (0-draw)
 * PHP `launch()`는 RNG를 전혀 소비하지 않는다. RandUtil을 받지 않으며 [DropItemGoldenTest]가 0-draw를
 * 명시 검증한다.
 *
 * ## PHP launch() 정본 (DropItem.php:37-71) — 실행/로그 순서 byte-exact
 *  1. `$item = $me->getItem($itemType)`. `$item->getRawClassName() === 'None'`이면(슬롯 비어있음)
 *     → `'아이템을 가지고 있지 않습니다.'` deny([DropItemOutcome.Denied]). 그 외 effect 없음.
 *  2. `$me->setItem($itemType, 'None')` — 해당 슬롯을 'None'으로 clear.
 *  3. 장수 action 로그(`pushGeneralActionLog`, MONTH 포맷):
 *       `<C>{itemName}</>{josaUl} 버렸습니다.`
 *     - `itemName` = `$item->getName()`(표시명, +stat 장식 포함, 예: `청룡언월도(+5)`).
 *     - `josaUl` = `JosaUtil::pick($item->getRawName(), '을')` — josa는 **원명**(`getRawName`)으로 고른다.
 *  4. `if (!$item->isBuyable())`(진귀/비구매 아이템 = 망실 broadcast):
 *     - global action 로그(`pushGlobalActionLog`, MONTH):
 *         `<Y>{generalName}</>{josaYi} <C>{itemName}</>{josaUl} 잃었습니다!`
 *     - global history 로그(`pushGlobalHistoryLog`, YEAR_MONTH):
 *         `<R><b>【망실】</b></><D><b>{nationName}</b></>의 <Y>{generalName}</>{josaYi} <C>{itemName}</>{josaUl} 잃었습니다!`
 *     - `josaYi` = `JosaUtil::pick($generalName, '이')`.
 *  5. `$me->applyDB($db)` — 본체 밖(핸들러 flush).
 *
 * 로그 본문(BODY)만 산출한다 — `<C>●</>{month}월:` / `<C>●</>{year}년 {month}월:` 프리픽스는 로거 경계
 * (엔진 핸들러)가 붙인다([opensamguk.logic.actions.GeneralActionResolveContext.addLog] 등과 동일 규약).
 */
object DropItem {

    /**
     * DropItem.php:39-70 순수 포팅. 모든 입력은 핸들러가 PHP `$me->getItem(itemType)` / 장수·국가
     * read로 주입한다(world/DB 미접근).
     *
     * @param itemType 버릴 슬롯 키(`horse`/`weapon`/`book`/`item` 중 하나, PHP `array_keys(allItems)`).
     *                 argTest는 핸들러/인테이크가 수행(여기선 슬롯 비어있음 가드만 PHP와 동일).
     * @param itemRawClassName `$item->getRawClassName()` — 슬롯에 든 아이템 클래스 단축명. `'None'`이면
     *        슬롯이 비어 있음 → deny. 'None' 외에는 실제 아이템.
     * @param itemName `$item->getName()` — 표시명(+stat 장식 포함). 로그 `<C>…</>`에 들어간다.
     * @param itemRawName `$item->getRawName()` — 원명. josa(`을`) 선택에 쓴다(표시명이 아니라 원명).
     * @param itemBuyable `$item->isBuyable()` — 구매가능 여부. false(진귀/비구매)면 망실 broadcast.
     * @param generalName 장수명 — broadcast 로그의 `<Y>…</>` 토큰.
     * @param nationName `$me->getStaticNation()['name']` — global history의 `<D><b>…</b></>` 토큰.
     */
    fun drop(
        itemType: String,
        itemRawClassName: String,
        itemName: String,
        itemRawName: String,
        itemBuyable: Boolean,
        generalName: String,
        nationName: String,
    ): DropItemOutcome {
        // DropItem.php:45-47 — 슬롯이 비어 있으면(None 클래스) deny. effect 없음.
        if (itemRawClassName == "None") {
            return DropItemOutcome.Denied("아이템을 가지고 있지 않습니다.")
        }

        // DropItem.php:53,58 — josa는 장수명(이)/아이템 원명(을)으로 고른다.
        val josaYi = JosaUtil.pick(generalName, "이")
        val josaUl = JosaUtil.pick(itemRawName, "을")

        // DropItem.php:59 — 장수 action 로그 본문(MONTH). 표시명(itemName) 사용.
        val actionLog = "<C>$itemName</>$josaUl 버렸습니다."

        // DropItem.php:63-66 — 비구매(진귀) 아이템만 망실 broadcast 2건.
        val globalActionLog: String?
        val globalHistoryLog: String?
        if (!itemBuyable) {
            globalActionLog = "<Y>$generalName</>$josaYi <C>$itemName</>$josaUl 잃었습니다!"
            globalHistoryLog =
                "<R><b>【망실】</b></><D><b>$nationName</b></>의 <Y>$generalName</>$josaYi <C>$itemName</>$josaUl 잃었습니다!"
        } else {
            globalActionLog = null
            globalHistoryLog = null
        }

        return DropItemOutcome.Applied(
            clearedSlot = itemType,
            actionLog = actionLog,
            globalActionLog = globalActionLog,
            globalHistoryLog = globalHistoryLog,
        )
    }
}

/** DropItem instant-action이 산출하는 effect(엔진 핸들러가 적용). */
sealed interface DropItemOutcome {
    /** 슬롯이 비어 있어 deny — PHP `launch()` 반환 문자열(byte-exact). effect 없음. */
    data class Denied(val reason: String) : DropItemOutcome

    /**
     * 성공: [clearedSlot] 슬롯을 'None'으로 비우고, 장수 action 로그 [actionLog]를 적재한다(MONTH 포맷,
     * `<C>●</>{month}월:` 프리픽스는 핸들러가 부여). 진귀(비구매) 아이템이면 [globalActionLog](MONTH,
     * global) + [globalHistoryLog](YEAR_MONTH, global history) 망실 broadcast 2건도 적재한다 — 구매가능
     * 아이템이면 둘 다 null.
     *
     * global-history sink은 현 일반-패스 로그 라우팅에 부재하다([CheJongjeonSuak] 동일 갭) — 핸들러가
     * global-history 채널을 배선하면 [globalHistoryLog]를 그대로 flush하면 된다(byte-exact 보존, 날조 아님).
     */
    data class Applied(
        val clearedSlot: String,
        val actionLog: String,
        val globalActionLog: String?,
        val globalHistoryLog: String?,
    ) : DropItemOutcome
}
