package opensamguk.logic.actions.trade

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port-faithful test for che_장비매매 (PHP che_장비매매.php:27-205 — Research Unit 6).
 *
 * 장비매매 (CheJangbiMaemae): a reqArg equip-slot trade command driven by the item registry (ITEMS).
 *   - BUY (itemCode != 'None'): pay the item `getCost()` in gold (increaseVarWithLimit('gold', -cost, 0)),
 *     equip it (setItem(type, code)), push a MONTH log "<C>{itemName}</>{josa-을} 구입했습니다. <1>date</>".
 *   - SELL (itemCode === 'None'): the sold item is the general's CURRENT slot item; refund cost/2 gold
 *     (increaseVarWithLimit('gold', cost/2) — no zero floor), push a MONTH log
 *     "<C>{itemName}</>{josa-을} 판매했습니다. <1>date</>", then clear the slot (setItem(type, null) → 'None').
 *     When the sold item is NON-buyable (a rare item), ALSO broadcast a global action log
 *     "<Y>{generalName}</>{josa-이} <C>{itemName}</>{josa-을} 판매했습니다!" (the global HISTORY log is a
 *     GATE-RUNTIME history-bucket seam, not modeled here).
 *   - 도기 (che_보물_도기) onArbitraryAction hook on SELL: draws ONE rng `choice([금/gold, 쌀/쌀])`, computes
 *     score=10000+5000*valueFit(intdiv(relYear,2),0), adds toInt(score/2) to nation treasury + the
 *     remainder to the general, and pushes a general action log "재산과 국고에 총 {resName} <C>{n}</>을 보충합니다.".
 *   - Always: addExperience(10) (fixed), setResultTurn(LastTurn(name, arg)), checkStatChange (PLAIN logs),
 *     then the trailing unique-item lottery on a SEPARATE rng (not drawn here).
 *
 * The item trade metadata (getCost/isBuyable/getReqSecu/getName/getRawName) is ported faithfully from the
 * PHP ActionItem classes; the stat-fold +stat behaviour is imported from ITEMS (BaseStatItemModule), not
 * re-created. Names ride meta["name"] (the logic General has no name column).
 */
class JangbiMaemaeTest {

    private val pipeline = GeneralActionPipeline()
    private val HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 6
    private val YEAR = 200
    private val date = "11:45"
    private val env = WorldEnv(year = YEAR, startYear = 184, develCost = 152)

    // --- fixtures -----------------------------------------------------------

    private fun actor(
        gold: Int = 50000,
        horse: String = "None",
        weapon: String = "None",
        book: String = "None",
        item: String = "None",
        arg: Map<String, Any?>? = null,
    ) = General(
        id = 10, nationId = 3, cityId = 7,
        leadership = 70, strength = 60, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5,
        gold = gold, rice = 5000,
        horse = horse, weapon = weapon, book = book, item = item,
        lastTurn = LastTurn(command = "장비매매", arg = arg),
        meta = linkedMapOf("name" to "최강자", "explevel" to 0, "dedlevel" to 0),
    )

    private fun city(secu: Int = 9999, trade: Int? = 100) = City(
        id = 7, nationId = 3, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 80.0,
        security = secu, securityMax = 10000, trade = trade,
        meta = linkedMapOf(),
    )

    private fun nation(gold: Int = 1000, rice: Int = 1000) =
        Nation(id = 3, level = 2, capitalCityId = 7, gold = gold, rice = rice, name = "촉")

    private fun rng() = RandUtil(LiteHashDrbg(serializeSeed(HIDDEN_SEED, "generalCommand", YEAR, MONTH, 10, "che_장비매매")))

    private fun cmd(arg: Map<String, Any?>) = CheJangbiMaemae(pipeline).withArg(arg)

    // --- registry metadata --------------------------------------------------

    @Test
    fun `keys names categories and tokens`() {
        val c = CheJangbiMaemae(pipeline)
        assertEquals("che_장비매매", c.key)
        assertEquals("장비매매", c.name)
        assertEquals("자원교역", c.category)
        assertEquals("che_장비매매", c.rawClassName)
        assertEquals("장비매매", c.lotteryActionName)
        assertEquals(mapOf("itemType" to "string", "itemCode" to "string"), c.argsSchema)
    }

    // --- item trade metadata (ported PHP per-item cost/buyable/reqSecu/name) -

    @Test
    fun `item trade metadata matches the PHP ActionItem declarations`() {
        // tier 01 stat item: cost 1000, reqSecu 1000, buyable, name "노기(+1)" rawName "노기"
        val nogi = itemTradeInfo("che_명마_01_노기")!!
        assertEquals(1000, nogi.cost)
        assertEquals(1000, nogi.reqSecu)
        assertTrue(nogi.buyable)
        assertEquals("노기(+1)", nogi.name)
        assertEquals("노기", nogi.rawName)
        // tier 06: cost 21000, reqSecu 6000, buyable
        val sobu = itemTradeInfo("che_무기_06_소부")!!
        assertEquals(21000, sobu.cost)
        assertEquals(6000, sobu.reqSecu)
        assertTrue(sobu.buyable)
        // tier 15 rare horse: cost 200, reqSecu 0, NOT buyable
        val jeokto = itemTradeInfo("che_명마_15_적토마")!!
        assertEquals(200, jeokto.cost)
        assertEquals(0, jeokto.reqSecu)
        assertTrue(!jeokto.buyable)
        // 도기 (도구 slot, BaseItem): cost 200, NOT buyable, name "도기(보물)" rawName "도기"
        val dogi = itemTradeInfo("che_보물_도기")!!
        assertEquals(200, dogi.cost)
        assertTrue(!dogi.buyable)
        assertEquals("도기(보물)", dogi.name)
        assertEquals("도기", dogi.rawName)
    }

    // --- argTest: itemType in itemMap, itemCode in allItems, buyable --------

    @Test
    fun `argTest accepts a buyable item and the None sell sentinel and rejects the rest`() {
        val c = CheJangbiMaemae(pipeline)
        // a buyable item: normalized to {itemType, itemCode} insertion order
        assertEquals(
            linkedMapOf("itemType" to "weapon", "itemCode" to "che_무기_01_단도"),
            c.parseArgs(mapOf("itemType" to "weapon", "itemCode" to "che_무기_01_단도")),
        )
        // 'None' (sell): the itemClass of 'None' is buyable=true (BaseItem default is false, but the None
        // class is special-cased buyable in PHP) — argTest passes the sell sentinel through.
        assertEquals(
            linkedMapOf("itemType" to "book", "itemCode" to "None"),
            c.parseArgs(mapOf("itemType" to "book", "itemCode" to "None")),
        )
        // unknown itemType → reject
        assertEquals(emptyMap(), c.parseArgs(mapOf("itemType" to "ring", "itemCode" to "che_무기_01_단도")))
        // itemCode not in allItems[type] → reject
        assertEquals(emptyMap(), c.parseArgs(mapOf("itemType" to "weapon", "itemCode" to "che_없는무기")))
        // a NON-buyable item cannot be the buy target → reject (che_장비매매.php:52-55)
        assertEquals(emptyMap(), c.parseArgs(mapOf("itemType" to "horse", "itemCode" to "che_명마_15_적토마")))
        // null/missing → reject
        assertEquals(emptyMap(), c.parseArgs(mapOf("itemType" to "weapon")))
    }

    // --- BUY: gold -= cost, equip slot, MONTH log, addExperience(10) ---------

    @Test
    fun `buy pays the cost equips the slot and logs`() {
        val a = actor(gold = 50000, arg = mapOf("itemType" to "horse", "itemCode" to "che_명마_02_조랑"))
        val draft = GeneralActionDraft(a, city(), nation())
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        cmd(mapOf("itemType" to "horse", "itemCode" to "che_명마_02_조랑")).resolve(ctx)

        // 조랑 cost 3000: gold 50000 - 3000
        assertEquals(47000, draft.general.gold)
        // equipped the horse slot
        assertEquals("che_명마_02_조랑", draft.general.horse)
        // fixed addExperience(10)
        assertEquals(10.0, draft.general.experience, 1e-9)
        // MONTH log with the item DISPLAY name 조랑(+2) + josa-을 on the RAW name 조랑
        assertEquals("<C>●</>${MONTH}월:<C>조랑(+2)</>을 구입했습니다. <1>$date</>", ctx.logs().single())
        // setResultTurn carries the normalized arg
        assertEquals("장비매매", draft.general.lastTurn.command)
        assertEquals(mapOf("itemType" to "horse", "itemCode" to "che_명마_02_조랑"), draft.general.lastTurn.arg)
        // no global broadcast on a buy
        assertTrue(ctx.globalActionLogs().isEmpty())
    }

    // --- SELL (buyable item): refund cost/2, clear slot, MONTH log, no broadcast

    @Test
    fun `sell of a buyable item refunds half and clears the slot without broadcasting`() {
        // selling the equipped 갈색마 (che_명마_05_갈색마, cost 15000 → refund 7500)
        val a = actor(gold = 1000, horse = "che_명마_05_갈색마", arg = mapOf("itemType" to "horse", "itemCode" to "None"))
        val draft = GeneralActionDraft(a, city(), nation())
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        cmd(mapOf("itemType" to "horse", "itemCode" to "None")).resolve(ctx)

        assertEquals(8500, draft.general.gold, "gold 1000 + 15000/2")
        assertEquals("None", draft.general.horse, "slot cleared")
        assertEquals(10.0, draft.general.experience, 1e-9)
        // sell MONTH log with the DISPLAY name 갈색마(+5); josa-를 picked on the RAW name 갈색마 (open syllable)
        assertEquals("<C>●</>${MONTH}월:<C>갈색마(+5)</>를 판매했습니다. <1>$date</>", ctx.logs().single())
        // 갈색마 IS buyable → no global broadcast
        assertTrue(ctx.globalActionLogs().isEmpty())
    }

    // --- SELL (non-buyable rare item): global broadcast ---------------------

    @Test
    fun `sell of a non-buyable rare item broadcasts a global action log`() {
        // selling the equipped 적토마 (che_명마_15_적토마, cost 200 → refund 100, NOT buyable)
        val a = actor(gold = 1000, horse = "che_명마_15_적토마", arg = mapOf("itemType" to "horse", "itemCode" to "None"))
        val draft = GeneralActionDraft(a, city(), nation())
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        cmd(mapOf("itemType" to "horse", "itemCode" to "None")).resolve(ctx)

        assertEquals(1100, draft.general.gold, "gold 1000 + 200/2")
        assertEquals("None", draft.general.horse)
        // own MONTH log — 적토마 is statValue 15 (token[-2]); josa-를 on the open-syllable raw name 적토마
        assertEquals("<C>●</>${MONTH}월:<C>적토마(+15)</>를 판매했습니다. <1>$date</>", ctx.logs().single())
        // global broadcast (general name 최강자 + josa-가 [open syllable], item display name 적토마(+15), josa-를 on raw 적토마)
        assertEquals(
            "<C>●</>${MONTH}월:<Y>최강자</>가 <C>적토마(+15)</>를 판매했습니다!",
            ctx.globalActionLogs().single(),
        )
    }

    // --- 도기 onArbitraryAction on SELL: rng draw + nation/general split + log

    @Test
    fun `selling 도기 fires its onArbitraryAction hook splitting the bonus to nation and general`() {
        // 도기 is a 도구 slot item (item slot). relYear = 200-184 = 16 → intdiv(16,2)=8 → score=10000+5000*8=50000.
        val a = actor(gold = 1000, item = "che_보물_도기", arg = mapOf("itemType" to "item", "itemCode" to "None"))
        val draft = GeneralActionDraft(a, city(), nation(gold = 1000, rice = 1000))
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        cmd(mapOf("itemType" to "item", "itemCode" to "None")).resolve(ctx)

        // score = 50000; toInt(score/2)=25000 → nation += 25000 ; general += 50000-25000=25000 (on the chosen res).
        // Plus the base sell refund cost/2 = 200/2 = 100 gold.
        // The chosen resource is rng->choice([금/gold, 쌀/쌀]) drawn from the action rng (deterministic seed).
        val drng = rng()
        val choice = drng.choice(listOf("금" to "gold", "쌀" to "rice"))
        val resKey = choice.second
        val resName = choice.first

        // base general fixtures: gold 1000 (override) + rice 5000 (fixed). base refund (cost/2=100) lands
        // in gold; the 도기 bonus (25000) lands in the chosen resource.
        if (resKey == "gold") {
            assertEquals(1000 + 100 + 25000, draft.general.gold, "gold = base + refund + 도기 bonus")
            assertEquals(5000, draft.general.rice)
            assertEquals(1000 + 25000, draft.nation!!.gold, "nation gold += 25000")
            assertEquals(1000, draft.nation!!.rice)
        } else {
            assertEquals(1000 + 100, draft.general.gold, "gold = base + refund only")
            assertEquals(5000 + 25000, draft.general.rice, "rice += 도기 bonus")
            assertEquals(1000, draft.nation!!.gold)
            assertEquals(1000 + 25000, draft.nation!!.rice, "nation rice += 25000")
        }
        assertEquals("None", draft.general.item, "도기 slot cleared")

        // the 도기 hook log + the sell log (도기 is NON-buyable → also a global broadcast).
        val ownLogs = ctx.logs()
        // sell line first (run order: refund/log → onArbitraryAction → setItem). josa-를 on raw name 도기.
        // The 도기 hook log is a general action log (no <1>date</>): "재산과 국고에 총 {resName} <C>50,000</>을 보충합니다.".
        assertTrue(ownLogs.any { it == "<C>●</>${MONTH}월:<C>도기(보물)</>를 판매했습니다. <1>$date</>" }, "sell log")
        assertTrue(ownLogs.any { it == "<C>●</>${MONTH}월:재산과 국고에 총 $resName <C>50,000</>을 보충합니다." }, "도기 bonus log")
        // 도기 is NOT buyable → global broadcast fires too (josa-가 on name 최강자, josa-를 on raw name 도기)
        assertEquals(
            "<C>●</>${MONTH}월:<Y>최강자</>가 <C>도기(보물)</>를 판매했습니다!",
            ctx.globalActionLogs().single(),
        )
    }

    // --- unbound (no arg) → resolve is a no-op (argTest would have failed) ---

    @Test
    fun `unbound command does not run`() {
        val a = actor(gold = 50000)
        val draft = GeneralActionDraft(a, city(), nation())
        val ctx = GeneralActionResolveContext(draft, rng(), env, MONTH, date)
        CheJangbiMaemae(pipeline).resolve(ctx)  // no withArg → no-op
        assertEquals(50000, draft.general.gold)
        assertEquals(0.0, draft.general.experience, 1e-9)
        assertTrue(ctx.logs().isEmpty())
    }
}
