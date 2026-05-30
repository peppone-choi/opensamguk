package opensamguk.logic.ai

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.LastTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task FD2 — [GeneralAI.chooseNationTurn] (the nation decision spine) + the QUARANTINED
 * [GeneralAI.chooseInstantNationTurn] structural stub.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:3616-3683` (chooseNationTurn) and
 * `:3685-3707` (chooseInstantNationTurn — QUARANTINED, decision #3, R-SEAM §3; NOT wired, off the gate).
 * GRAND TRUTH, read in full.
 *
 * The nation spine VERBATIM (PHP source-line order — the order IS the draw/log order):
 *  - `:3618` `updateInstance()` prologue.
 *  - `:3621` `$lastTurn = $reservedCommand->getLastTurn()` (threaded into the do{X}($lastTurn) loop).
 *  - `:3625` `categorizeNationGeneral()` THEN `:3626` `categorizeNationCities()` (the literal call order;
 *    F-FACADE owns the by-reference cities-before-generals invariant).
 *  - `:3628-3648` npcType>=2 step-1 side-effects: `use_auto_nation_turn` reset (`:3630-3632`);
 *    officer_level==12 → month-gated `choosePromotion`(3,6,9,12) / `chooseTexRate`+`chooseGoldBillRate`(12)
 *    / `chooseTexRate`+`chooseRiceBillRate`(6); else (npcType>=2 non-ruler) `chooseNonLordPromotion`(3,6,9,12).
 *  - `:3650-3659` the GATED reserved-honor (decision #4, DISTINCT from the general NO-gate path): a non-휴식
 *    reserved command is gated via `hasFullConditionMet()`; on PASS return it (reason 'reserved'); on DENY
 *    emit a `getFailString()` fail-log `"{failString} <1>{date}</>"` THEN FALL THROUGH to the loop.
 *  - `:3661-3679` the 4-guard priority loop: guard-A `property_exists(...,'can'+name)` else trigger_error+continue;
 *    guard-B `nationPolicy.{'can'+name}` false → continue; guard-C `npcType<2 && !(availableInstantTurn[name] ?? false)`
 *    → continue; then `do{X}($lastTurn)` first-non-null wins (reason 'do'+name).
 *  - `:3680-3682` the neutral fallback (never null, reason 'neutral').
 */
class ChooseNationTurnTest {

    private val restNation = ChosenCommand("휴식", emptyMap(), reason = "")
    private val reservedNonRest = ChosenCommand("che_포상", linkedMapOf("destGeneralID" to 9), reason = "")
    private val lastTurn = LastTurn("휴식")

    private fun rng() = RandUtil(opensamguk.common.rng.LiteHashDrbg("ChooseNationTurnTest"))

    private fun input(
        npcType: Int = 2,
        officerLevel: Int = 12,
        month: Int = 1,
        rng: RandUtil = rng(),
    ) = NationAiInput(npcType = npcType, officerLevel = officerLevel, month = month, rng = rng)

    /** A neutral-fallback builder used by every AI instance (PHP `buildNationCommandClass(null,...)` :3680). */
    private fun neutral() = ChosenCommand("che_휴식", emptyMap(), "")

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (1) the dispatch order EQUALS nationPolicy.priority after the merge (B1 valid-name override REPLACES);
    //     first-non-null wins.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `nation dispatch order equals nationPolicy priority, first non-null wins`() {
        val fired = mutableListOf<String>()
        // npcType>=2 bypasses guard-C → the whole override list is eligible; valid bare-action names.
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC포상", "NPC몰수")),
        )
        assertEquals(listOf("NPC포상", "NPC몰수"), policy.priority)

        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
            "NPC포상" to { fired += "NPC포상"; null }, // null → fall through to NPC몰수
        )
        val ai = nationAi(policy, dispatch)
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))

        assertEquals(listOf("NPC포상", "NPC몰수"), fired, "dispatch order = merged priority order")
        assertEquals("doNPC몰수", chosen.reason)
        assertEquals("che_몰수", chosen.actionCode)
    }

    @Test fun `first non-null short-circuits the nation loop`() {
        val fired = mutableListOf<String>()
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC포상", "NPC몰수")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC포상" to { fired += "NPC포상"; ChosenCommand("che_포상", emptyMap(), "") },
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch)
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertEquals(listOf("NPC포상"), fired, "first non-null short-circuits → NPC몰수 never fires")
        assertEquals("doNPC포상", chosen.reason)
    }

    @Test fun `lastTurn is threaded into the do bodies, not null`() {
        var seen: LastTurn? = LastTurn("sentinel-not-overwritten")
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC포상")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC포상" to { lt -> seen = lt; ChosenCommand("che_포상", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch)
        ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertSame(lastTurn, seen, "the nation loop passes reservedCommand.getLastTurn() (NOT null) into do{X}")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (2) the 4 guards.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `guard-A drops a typo or unknown priority name (no can-flag prop)`() {
        val fired = mutableListOf<String>()
        // '귀한' is a typo (no can귀한 prop) → guard-A trigger_error + continue; 'NPC포상' is valid.
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("귀한", "NPC포상")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "귀한" to { fired += "귀한"; ChosenCommand("che_X", emptyMap(), "") },
            "NPC포상" to { fired += "NPC포상"; ChosenCommand("che_포상", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch)
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertFalse(fired.contains("귀한"), "guard-A drops the typo before any do call")
        assertEquals("doNPC포상", chosen.reason)
    }

    @Test fun `guard-B drops a priority name whose can-flag is false`() {
        val fired = mutableListOf<String>()
        // npcType<2 chief: keep can* true via the chief aiOption; explicitly turn canNPC포상 false.
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC포상", "NPC몰수")),
        )
        policy.canNPC포상 = false // guard-B :3667
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC포상" to { fired += "NPC포상"; ChosenCommand("che_포상", emptyMap(), "") },
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch)
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertFalse(fired.contains("NPC포상"), "guard-B skips the can-flag-false action")
        assertEquals("doNPC몰수", chosen.reason)
    }

    @Test fun `guard-C restricts npcType under 2 to the availableInstantTurn whitelist`() {
        val fired = mutableListOf<String>()
        // npcType<2 (human chief). priority = [NPC몰수 (NOT instant), NPC포상 (instant)].
        // guard-C drops NPC몰수 (not in availableInstantTurn); NPC포상 survives.
        val policy = AutorunNationPolicy(
            npcType = 0, tech = 0, develcost = 100,
            aiOptions = mapOf("chief" to true), // chief → can* stay true (R-NATIONPOL §4)
            serverPolicy = mapOf("priority" to listOf("NPC몰수", "NPC포상")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
            "NPC포상" to { fired += "NPC포상"; ChosenCommand("che_포상", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch)
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 0, officerLevel = 5))
        assertEquals(listOf("NPC포상"), fired, "guard-C drops the non-instant NPC몰수 for npcType<2")
        assertEquals("doNPC포상", chosen.reason)
    }

    @Test fun `npcType at or above 2 bypasses guard-C — the full priority is eligible`() {
        val fired = mutableListOf<String>()
        // npcType>=2: guard-C is bypassed → NPC몰수 (NOT in availableInstantTurn) is still eligible.
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC몰수")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch)
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertEquals(listOf("NPC몰수"), fired, "npcType>=2 bypasses guard-C → NPC몰수 fires")
        assertEquals("doNPC몰수", chosen.reason)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (3) the GATED reserved-honor (decision #4 — DISTINCT from the general NO-gate path).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a non-rest reserved command that passes the full-condition gate is honored as reserved`() {
        val logCalls = mutableListOf<String>()
        val policy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100)
        val ai = nationAi(
            policy, linkedMapOf(),
            hasFullConditionMet = { true }, // gate PASS
            logFailString = { logCalls += it },
        )
        val chosen = ai.chooseNationTurn(reservedNonRest, lastTurn, input(npcType = 2, officerLevel = 5))
        assertEquals("reserved", chosen.reason, "the GATED reserved-honor returns reason 'reserved' (NOT 'do예약턴')")
        assertSame(reservedNonRest.actionCode, chosen.actionCode)
        assertTrue(logCalls.isEmpty(), "a PASSED gate does NOT fail-log")
    }

    @Test fun `a non-rest reserved command that fails the gate fail-logs then falls through to the loop`() {
        val logCalls = mutableListOf<String>()
        val fired = mutableListOf<String>()
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC포상")),
        )
        val ai = nationAi(
            policy,
            linkedMapOf("NPC포상" to { _: LastTurn? -> fired += "NPC포상"; ChosenCommand("che_포상", emptyMap(), "") }),
            hasFullConditionMet = { false }, // gate DENY
            getFailString = { "조건이 맞지 않습니다. 포상 실패." },
            logFailString = { logCalls += it },
        )
        val chosen = ai.chooseNationTurn(reservedNonRest, lastTurn, input(npcType = 2, officerLevel = 5))
        // the deny path fail-logs "{failString} <1>{date}</>" THEN falls through (decision #4).
        assertEquals(1, logCalls.size, "a DENIED non-rest reservation fail-logs exactly once")
        assertTrue(logCalls[0].startsWith("조건이 맞지 않습니다. 포상 실패."), "the fail-log carries the getFailString text")
        assertEquals(listOf("NPC포상"), fired, "the deny path FALLS THROUGH to the priority loop")
        assertEquals("doNPC포상", chosen.reason)
    }

    @Test fun `a rest reserved command skips the gate entirely and falls through to the loop`() {
        val gateCalls = mutableListOf<Boolean>()
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC포상")),
        )
        val ai = nationAi(
            policy,
            linkedMapOf("NPC포상" to { _: LastTurn? -> ChosenCommand("che_포상", emptyMap(), "") }),
            hasFullConditionMet = { gateCalls += true; true },
        )
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertTrue(gateCalls.isEmpty(), "a 휴식 reservation bypasses the full-condition gate (PHP :3650 instanceof 휴식)")
        assertEquals("doNPC포상", chosen.reason)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (4) the npcType>=2 step-1 promotion/tax/bill side-effects fire only on month gates.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `choosePromotion fires for an officer_level 12 NPC only on months 3 6 9 12`() {
        for (month in 1..12) {
            val fired = mutableListOf<String>()
            val ai = nationAi(
                AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
                choosePromotion = { fired += "promotion" },
                buildNeutral = { neutral() },
            )
            ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 12, month = month))
            val expected = month in listOf(3, 6, 9, 12)
            assertEquals(expected, fired.contains("promotion"), "choosePromotion month=$month expected=$expected")
        }
    }

    @Test fun `month 12 fires chooseTexRate then chooseGoldBillRate, month 6 fires chooseTexRate then chooseRiceBillRate`() {
        val firedDec = mutableListOf<String>()
        nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            chooseTexRate = { firedDec += "tex" },
            chooseGoldBillRate = { firedDec += "gold" },
            chooseRiceBillRate = { firedDec += "rice" },
            buildNeutral = { neutral() },
        ).chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 12, month = 12))
        assertEquals(listOf("tex", "gold"), firedDec, "month 12: chooseTexRate then chooseGoldBillRate (no rice)")

        val firedJun = mutableListOf<String>()
        nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            chooseTexRate = { firedJun += "tex" },
            chooseGoldBillRate = { firedJun += "gold" },
            chooseRiceBillRate = { firedJun += "rice" },
            buildNeutral = { neutral() },
        ).chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 12, month = 6))
        assertEquals(listOf("tex", "rice"), firedJun, "month 6: chooseTexRate then chooseRiceBillRate (no gold)")
    }

    @Test fun `a non-ruler NPC fires chooseNonLordPromotion on months 3 6 9 12`() {
        val fired = mutableListOf<String>()
        nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            choosePromotion = { fired += "lordPromotion" },
            chooseNonLordPromotion = { fired += "nonLordPromotion" },
            buildNeutral = { neutral() },
        ).chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5, month = 9))
        assertEquals(listOf("nonLordPromotion"), fired, "non-ruler NPC fires chooseNonLordPromotion, not the lord branch")
    }

    @Test fun `npcType under 2 fires NO step-1 promotion side-effects`() {
        val fired = mutableListOf<String>()
        nationAi(
            AutorunNationPolicy(npcType = 0, tech = 0, develcost = 100, aiOptions = mapOf("chief" to true)), linkedMapOf(),
            choosePromotion = { fired += "p" },
            chooseNonLordPromotion = { fired += "nlp" },
            chooseTexRate = { fired += "t" },
            buildNeutral = { neutral() },
        ).chooseNationTurn(restNation, lastTurn, input(npcType = 0, officerLevel = 12, month = 12))
        assertTrue(fired.isEmpty(), "the whole step-1 block is gated by npcType>=2")
    }

    @Test fun `use_auto_nation_turn is recorded as a ChangeRecorder delta when reset, for an npc-controlled general`() {
        val kv = mutableListOf<Triple<Int, String, Any?>>()
        nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            recordGeneralKv = { id, k, v -> kv += Triple(id, k, v) },
            buildNeutral = { neutral() },
            generalId = 11,
            useAutoNationTurn = false, // currently 0/false → reset to 1 (PHP :3630-3632)
        ).chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5, month = 1))
        val expected: List<Triple<Int, String, Any?>> = listOf(Triple(11, "use_auto_nation_turn", 1))
        assertEquals(expected, kv, "use_auto_nation_turn reset queues a KV delta")
    }

    @Test fun `use_auto_nation_turn already truthy writes no delta`() {
        val kv = mutableListOf<Triple<Int, String, Any?>>()
        nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            recordGeneralKv = { id, k, v -> kv += Triple(id, k, v) },
            buildNeutral = { neutral() },
            useAutoNationTurn = true,
        ).chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5, month = 1))
        assertTrue(kv.isEmpty(), "an already-truthy use_auto_nation_turn writes NO delta (PHP `?? 1` then `if(!...)`)")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (5) the neutral fallback.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the neutral fallback fires with reason neutral when nothing else does`() {
        val ai = nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            buildNeutral = { ChosenCommand("che_휴식", emptyMap(), "") },
        )
        val chosen = ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertEquals("neutral", chosen.reason)
        assertEquals("che_휴식", chosen.actionCode)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (6) updateInstance / categorize order (prologue).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `prologue runs updateInstance then categorizeNationGeneral then categorizeNationCities`() {
        val order = mutableListOf<String>()
        val ai = nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100), linkedMapOf(),
            updateNationInstance = { order += "update" },
            categorizeNationGeneral = { order += "generals" },
            categorizeNationCities = { order += "cities" },
            buildNeutral = { neutral() },
        )
        ai.chooseNationTurn(restNation, lastTurn, input(npcType = 2, officerLevel = 5))
        assertEquals(listOf("update", "generals", "cities"), order, "PHP :3618/:3625/:3626 literal call order")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (7) chooseInstantNationTurn — QUARANTINED structural stub (decision #3, R-SEAM §3).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `chooseInstantNationTurn is gate-FIRST — a passing reservation returns it before updateInstance`() {
        var updated = false
        val ai = nationAi(
            AutorunNationPolicy(npcType = 2, tech = 0, develcost = 100),
            linkedMapOf(),
            updateNationInstance = { updated = true },
            hasFullConditionMet = { true },
            buildNeutral = { neutral() },
        )
        val result = ai.chooseInstantNationTurn(reservedNonRest, lastTurn, input(npcType = 2, officerLevel = 5))
        assertSame(reservedNonRest, result, "the instant stub is gate-FIRST (no updateInstance before the gate)")
        assertFalse(updated, "the gate-pass returns before updateInstance (DISTINCT from chooseNationTurn)")
    }

    @Test fun `chooseInstantNationTurn uses key_exists (the 2-guard loop) and carries NO reason strings`() {
        val fired = mutableListOf<String>()
        // priority = [NPC몰수 (NOT in availableInstantTurn → dropped by key_exists), NPC포상 (in → kept)].
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC몰수", "NPC포상")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
            "NPC포상" to { fired += "NPC포상"; ChosenCommand("che_포상", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch, hasFullConditionMet = { false }, buildNeutral = { neutral() })
        val result = ai.chooseInstantNationTurn(reservedNonRest, lastTurn, input(npcType = 2, officerLevel = 5))
        assertEquals(listOf("NPC포상"), fired, "key_exists drops NPC몰수 (regardless of npcType — no guard-C)")
        assertEquals("", result?.reason, "chooseInstantNationTurn assigns NO reason (deliberately DIVERGENT from chooseNationTurn)")
        assertEquals("che_포상", result?.actionCode)
    }

    @Test fun `chooseInstantNationTurn key_exists is npcType-independent — even an NPC drops a non-instant name`() {
        val fired = mutableListOf<String>()
        // npcType>=2: chooseNationTurn would KEEP NPC몰수 (no guard-C), but chooseInstantNationTurn drops it
        // via key_exists — the two loops are DELIBERATELY DIVERGENT.
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 100,
            serverPolicy = mapOf("priority" to listOf("NPC몰수")),
        )
        val dispatch = linkedMapOf<String, (LastTurn?) -> ChosenCommand?>(
            "NPC몰수" to { fired += "NPC몰수"; ChosenCommand("che_몰수", emptyMap(), "") },
        )
        val ai = nationAi(policy, dispatch, hasFullConditionMet = { false }, buildNeutral = { ChosenCommand("che_휴식", emptyMap(), "") })
        val result = ai.chooseInstantNationTurn(reservedNonRest, lastTurn, input(npcType = 2, officerLevel = 5))
        assertTrue(fired.isEmpty(), "key_exists drops NPC몰수 even for an NPC — divergent from chooseNationTurn's guard-C bypass")
        assertEquals("che_휴식", result?.actionCode, "the instant stub falls back to the neutral build")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun nationAi(
        policy: AutorunNationPolicy,
        nationDispatch: LinkedHashMap<String, (LastTurn?) -> ChosenCommand?>,
        updateNationInstance: () -> Unit = {},
        categorizeNationGeneral: () -> Unit = {},
        categorizeNationCities: () -> Unit = {},
        choosePromotion: () -> Unit = {},
        chooseNonLordPromotion: () -> Unit = {},
        chooseTexRate: () -> Unit = {},
        chooseGoldBillRate: () -> Unit = {},
        chooseRiceBillRate: () -> Unit = {},
        recordGeneralKv: (Int, String, Any?) -> Unit = { _, _, _ -> },
        hasFullConditionMet: (ChosenCommand) -> Boolean = { true },
        getFailString: (ChosenCommand) -> String = { "" },
        logFailString: (String) -> Unit = {},
        buildNeutral: () -> ChosenCommand = { ChosenCommand("che_휴식", emptyMap(), "") },
        generalId: Int = 0,
        useAutoNationTurn: Boolean = true,
        turnTimeHm: String = "01-01",
    ): GeneralAI = GeneralAI(
        generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
        dispatch = linkedMapOf(),
        nationPolicy = policy,
        nationDispatch = nationDispatch,
        updateNationInstance = updateNationInstance,
        categorizeNationGeneral = categorizeNationGeneral,
        categorizeNationCities = categorizeNationCities,
        choosePromotion = choosePromotion,
        chooseNonLordPromotion = chooseNonLordPromotion,
        chooseTexRate = chooseTexRate,
        chooseGoldBillRate = chooseGoldBillRate,
        chooseRiceBillRate = chooseRiceBillRate,
        recordGeneralKv = recordGeneralKv,
        hasFullConditionMet = hasFullConditionMet,
        getFailString = getFailString,
        logFailString = logFailString,
        buildNeutralNationCommand = buildNeutral,
        nationGeneralId = generalId,
        useAutoNationTurn = useAutoNationTurn,
        nationTurnTimeHm = turnTimeHm,
    )
}
