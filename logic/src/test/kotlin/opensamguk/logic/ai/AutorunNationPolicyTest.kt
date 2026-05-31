package opensamguk.logic.ai

import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FP2 — [AutorunNationPolicy] parity tests.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/AutorunNationPolicy.php` (291 lines, read in full;
 * R-NATIONPOL.md). The merged `priority` array ORDER IS the `do<한글>` dispatch order in
 * `chooseNationTurn`, the log order, AND the per-decision RNG draw order.
 *
 * B1 (decision #5, INVERTED from the old "dead-KV" draft; R-NATIONPOL §1): the KV `priority` override
 * is LIVE for the nation policy too — but via a DIFFERENT mechanism than the general policy. The
 * nation ctor does a **whole-array replace** at construction (NO per-item filter, :197-199 server /
 * :212-214 nation), then `if(!priority) priority=defaultPriority` (:218-220). The per-name validation
 * happens at the CONSUMER loop in `chooseNationTurn` via `property_exists($this,'can'.$name)`
 * (:3663-3666) — a typo/unknown name has no `can<name>` prop → dropped there. The nation KV
 * (applied SECOND) wins over the server KV.
 */
class AutorunNationPolicyTest {

    // env develcost feeds reqNPCDevelGold = develcost*30; tech feeds the war-cost derived defaults.
    private val ENV_DEVELCOST = 100

    private fun policy(
        npcType: Int = 3,
        aiOptions: Map<String, Any?>? = mapOf("chief" to true),
        nationPolicy: Map<String, Any?>? = null,
        serverPolicy: Map<String, Any?>? = null,
        tech: Int = 0,
        develcost: Int = ENV_DEVELCOST,
    ) = AutorunNationPolicy(
        npcType = npcType,
        aiOptions = aiOptions,
        nationPolicy = nationPolicy,
        serverPolicy = serverPolicy,
        tech = tech,
        develcost = develcost,
    )

    // --- (1) defaultPriority — the EXACT ordered 20-list, NO 유저장몰수 (AutorunNationPolicy.php:38-68) ---

    @Test
    fun `defaultPriority is the exact ordered 20-list with no 유저장몰수`() {
        val expected = listOf(
            "불가침제의",
            "선전포고",
            "천도",
            "유저장긴급포상",
            "부대전방발령",
            "유저장구출발령",
            "유저장후방발령",
            "부대유저장후방발령",
            "유저장전방발령",
            "유저장포상",
            // '유저장몰수' (:53) commented out — NOT in array
            "부대구출발령",
            "부대후방발령",
            "NPC긴급포상",
            "NPC구출발령",
            "NPC후방발령",
            "NPC포상",
            "NPC전방발령",
            "유저장내정발령",
            "NPC내정발령",
            "NPC몰수",
        )
        assertEquals(expected, AutorunNationPolicy.DEFAULT_PRIORITY)
        assertEquals(20, AutorunNationPolicy.DEFAULT_PRIORITY.size)
        assertFalse(AutorunNationPolicy.DEFAULT_PRIORITY.contains("유저장몰수"))
    }

    // --- (2) availableInstantTurn — the 12-key set (AutorunNationPolicy.php:71-84) ---

    @Test
    fun `availableInstantTurn is the exact 12-key set`() {
        val expected = setOf(
            "유저장긴급포상",
            "유저장구출발령",
            "유저장후방발령",
            "유저장전방발령",
            "유저장내정발령",
            "유저장포상",
            "NPC긴급포상",
            "NPC구출발령",
            "NPC후방발령",
            "NPC내정발령",
            "NPC포상",
            "NPC전방발령",
        )
        assertEquals(expected, AutorunNationPolicy.AVAILABLE_INSTANT_TURN)
        assertEquals(12, AutorunNationPolicy.AVAILABLE_INSTANT_TURN.size)
        // the 8 EXCLUDED priority entries (군주 + 부대* + NPC몰수)
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("불가침제의"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("선전포고"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("천도"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("부대전방발령"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("부대유저장후방발령"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("부대구출발령"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("부대후방발령"))
        assertFalse(AutorunNationPolicy.AVAILABLE_INSTANT_TURN.contains("NPC몰수"))
    }

    // --- (3) the FULL value-key set + numeric defaults (AutorunNationPolicy.php:152-180) ---

    @Test
    fun `plain numeric defaults are the defaultPolicy values`() {
        val p = policy()
        assertEquals(10000, p.reqNationGold)
        assertEquals(12000, p.reqNationRice)
        assertEquals(10000, p.reqHumanDevelGold)
        assertEquals(10000, p.reqHumanDevelRice)
        assertEquals(500, p.reqNPCDevelRice) // default 500, NOT 0 → never derives
        assertEquals(1000, p.minimumResourceActionAmount)
        assertEquals(10000, p.maximumResourceActionAmount)
        assertEquals(40, p.minNPCWarLeadership)
        assertEquals(1500, p.minWarCrew)
        assertEquals(50000, p.minNPCRecruitCityPopulation)
        assertEquals(0.5, p.safeRecruitCityPopulationRatio)
        assertEquals(90, p.properWarTrainAtmos)
        assertEquals(10, p.cureThreshold)
        assertTrue(p.combatForce.isEmpty())
        assertTrue(p.supportForce.isEmpty())
        assertTrue(p.developForce.isEmpty())
    }

    // --- (3b) the derived defaults under === 0 guards, in ORDER (R-NATIONPOL §3) ---

    @Test
    fun `reqNPCDevelGold is develcost times 30 with NO round`() {
        val p = policy(develcost = 137)
        assertEquals(137 * 30, p.reqNPCDevelGold) // 4110, raw multiply
    }

    @Test
    fun `reqNPCWarGold uses PhpRound minus2 of reqGold times 4 at defaultStatNPCMax`() {
        val tech = 0
        val crew = AutorunNationPolicy.DEFAULT_STAT_NPC_MAX * 100
        val unit = UnitSetTable.byId(UnitSetTable.DEFAULT_CREWTYPE)!!
        val expectedGold = phpRound(unit.costWithTech(tech, crew) * 4, -2)
        val expectedRice = phpRound(unit.riceWithTech(tech, crew) * 4, -2)
        val p = policy(tech = tech)
        assertEquals(expectedGold, p.reqNPCWarGold)
        assertEquals(expectedRice, p.reqNPCWarRice)
    }

    @Test
    fun `reqHumanWarUrgent uses PhpRound minus2 of reqGold times 3 times 2 at defaultStatMax`() {
        val tech = 0
        val crew = AutorunNationPolicy.DEFAULT_STAT_MAX * 100 // defaultStatMax NOT NPC
        val unit = UnitSetTable.byId(UnitSetTable.DEFAULT_CREWTYPE)!!
        val expectedGold = phpRound(unit.costWithTech(tech, crew) * 3 * 2, -2)
        val expectedRice = phpRound(unit.riceWithTech(tech, crew) * 3 * 2, -2)
        val p = policy(tech = tech)
        assertEquals(expectedGold, p.reqHumanWarUrgentGold)
        assertEquals(expectedRice, p.reqHumanWarUrgentRice)
    }

    @Test
    fun `reqHumanWarRecommand derives from reqHumanWarUrgent AFTER its default applied`() {
        val p = policy(tech = 0)
        // #5/#6 = PhpRound(urgent*2, -2); urgent already derived (#3/#4 ran first)
        assertEquals(phpRound(p.reqHumanWarUrgentGold * 2.0, -2), p.reqHumanWarRecommandGold)
        assertEquals(phpRound(p.reqHumanWarUrgentRice * 2.0, -2), p.reqHumanWarRecommandRice)
    }

    @Test
    fun `a non-zero policy override of a war-cost field SUPPRESSES the derivation`() {
        // strict === 0 guard: a non-zero provided value is kept, the derived default is skipped.
        val p = policy(
            tech = 0,
            nationPolicy = mapOf("values" to mapOf("reqNPCWarGold" to 99999)),
        )
        assertEquals(99999, p.reqNPCWarGold)
        // the rice side is still 0-guarded → still derives (outer || guard re-enters; inner if(rice===0))
        val crew = AutorunNationPolicy.DEFAULT_STAT_NPC_MAX * 100
        val unit = UnitSetTable.byId(UnitSetTable.DEFAULT_CREWTYPE)!!
        assertEquals(phpRound(unit.riceWithTech(0, crew) * 4, -2), p.reqNPCWarRice)
    }

    @Test
    fun `a zero override still re-triggers derivation`() {
        // explicit 0 from policy still === 0 → derives.
        val p = policy(tech = 0, develcost = 200, nationPolicy = mapOf("values" to mapOf("reqNPCDevelGold" to 0)))
        assertEquals(200 * 30, p.reqNPCDevelGold)
    }

    // --- (4) chief-gate (AutorunNationPolicy.php:259-288) ---

    @Test
    fun `npcType greater-equal 2 early-return keeps all can-flags true`() {
        val p = policy(npcType = 2, aiOptions = emptyMap())
        assertTrue(p.can부대전방발령)
        assertTrue(p.can부대후방발령)
        assertTrue(p.can부대구출발령)
        assertTrue(p.can부대유저장후방발령)
        assertTrue(p.can유저장후방발령)
        assertTrue(p.can유저장전방발령)
        assertTrue(p.can유저장구출발령)
        assertTrue(p.can유저장내정발령)
        assertTrue(p.canNPC후방발령)
        assertTrue(p.canNPC전방발령)
        assertTrue(p.canNPC구출발령)
        assertTrue(p.canNPC내정발령)
        assertTrue(p.can유저장긴급포상)
        assertTrue(p.can유저장포상)
        assertTrue(p.canNPC긴급포상)
        assertTrue(p.canNPC포상)
        assertTrue(p.canNPC몰수)
        assertTrue(p.can불가침제의)
        assertTrue(p.can선전포고)
        assertTrue(p.can천도)
    }

    @Test
    fun `npcType less-than 2 with chief option keeps all can-flags true`() {
        val p = policy(npcType = 1, aiOptions = mapOf("chief" to true))
        assertTrue(p.can부대전방발령)
        assertTrue(p.can선전포고)
        assertTrue(p.can천도)
        assertTrue(p.canNPC몰수)
    }

    @Test
    fun `npcType less-than 2 with NO chief disables the 18 flags but NOT 부대구출발령 nor 불가침제의`() {
        val p = policy(npcType = 1, aiOptions = emptyMap())
        // the 18 disabled flags (:264-287)
        assertFalse(p.can부대전방발령)
        assertFalse(p.can부대후방발령)
        assertFalse(p.can부대유저장후방발령)
        assertFalse(p.can유저장후방발령)
        assertFalse(p.can유저장전방발령)
        assertFalse(p.can유저장구출발령)
        assertFalse(p.can유저장내정발령)
        assertFalse(p.canNPC후방발령)
        assertFalse(p.canNPC전방발령)
        assertFalse(p.canNPC구출발령)
        assertFalse(p.canNPC내정발령)
        assertFalse(p.can유저장긴급포상)
        assertFalse(p.can유저장포상)
        assertFalse(p.canNPC긴급포상)
        assertFalse(p.canNPC포상)
        assertFalse(p.canNPC몰수)
        assertFalse(p.can선전포고)
        assertFalse(p.can천도)
        // NOT disabled (R-NATIONPOL §4)
        assertTrue(p.can부대구출발령)
        assertTrue(p.can불가침제의)
    }

    // --- (5) LIVE KV `priority` override (B1, decision #5; R-NATIONPOL §1) ---

    @Test
    fun `valid-name nation-KV priority override REPLACES the default`() {
        val override = listOf("선전포고", "천도", "NPC포상")
        val p = policy(nationPolicy = mapOf("priority" to override))
        assertEquals(override, p.priority)
        assertTrue(p.priority != AutorunNationPolicy.DEFAULT_PRIORITY)
    }

    @Test
    fun `valid-name server-KV priority override REPLACES the default`() {
        val override = listOf("불가침제의", "NPC몰수")
        val p = policy(serverPolicy = mapOf("priority" to override))
        assertEquals(override, p.priority)
    }

    @Test
    fun `nation-KV priority override WINS over server-KV priority override`() {
        val serverOverride = listOf("NPC포상", "NPC몰수")
        val nationOverride = listOf("불가침제의", "선전포고", "천도")
        val p = policy(
            nationPolicy = mapOf("priority" to nationOverride),
            serverPolicy = mapOf("priority" to serverOverride),
        )
        assertEquals(nationOverride, p.priority)
    }

    @Test
    fun `whole-array replace is NOT filtered at construction — a typo survives the ctor`() {
        // R-NATIONPOL §1: the nation ctor does a WHOLE-ARRAY replace (no per-item filter), unlike the
        // general policy. A typo is kept in `priority` at construction; it is the consumer loop that drops it.
        val override = listOf("선전포고", "선전포고_TYPO", "천도")
        val p = policy(nationPolicy = mapOf("priority" to override))
        assertEquals(override, p.priority)
    }

    @Test
    fun `the consumer-loop validity check accepts valid names and DROPS a typo via the can-flag prop`() {
        // chooseNationTurn :3663 `property_exists($this->nationPolicy,'can'.$name)` — a valid bare-action
        // name has a matching can<name> prop; a typo does not. F-DISPATCH consumes this predicate.
        val p = policy()
        assertTrue(p.hasCanFlagFor("선전포고"))
        assertTrue(p.hasCanFlagFor("NPC몰수"))
        assertTrue(p.hasCanFlagFor("부대구출발령"))
        assertFalse(p.hasCanFlagFor("선전포고_TYPO"))
        assertFalse(p.hasCanFlagFor("유저장몰수")) // commented-out → no can prop
    }

    @Test
    fun `an absent priority KV leaves the default`() {
        val p = policy(nationPolicy = mapOf("values" to mapOf("reqNationGold" to 5000)))
        assertEquals(AutorunNationPolicy.DEFAULT_PRIORITY, p.priority)
    }

    // --- (6) values per-key merge with property_exists skip-unknown (AutorunNationPolicy.php:189-195) ---

    @Test
    fun `a values override sets a known key and skips an unknown key`() {
        val p = policy(
            nationPolicy = mapOf(
                "values" to mapOf(
                    "reqNationGold" to 7777,
                    "minWarCrew" to 2222,
                    "totallyUnknownKey" to 1, // skipped (property_exists false)
                ),
            ),
        )
        assertEquals(7777, p.reqNationGold)
        assertEquals(2222, p.minWarCrew)
    }

    @Test
    fun `nation values win over server values`() {
        val p = policy(
            serverPolicy = mapOf("values" to mapOf("reqNationGold" to 1111)),
            nationPolicy = mapOf("values" to mapOf("reqNationGold" to 2222)),
        )
        assertEquals(2222, p.reqNationGold)
    }
}
