package opensamguk.logic.actions.founding

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.personnel.CheBangrang
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FND2 — founding-area verification of the shared 방랑 cascade ([FoundingCascade]).
 *
 * 방랑 is the pure CASCADE founding variant (no created-set). It reverts a lord's whole nation back to
 * a wandering band in the load-bearing ORDER `che_방랑.php` run() lines 92-123, replayed by
 * [FoundingCascade.applyWanderingCascade]:
 *   1. nation revert  (name=lordName, color=#330000, level=0, type=None, tech=0, capital=0)
 *   2. lord self      (makelimit=12, officer_city=0; officer_level 12 untouched)
 *   3. members        (makelimit=12; officer_level=1 + officer_city=0 only when officer_level < 12)
 *   4. cities         (nation=0, front=0, conflict removed -> {})
 *   5. diplomacy      (state=2, term=0)
 *
 * FND2 CONSUMES [FoundingCascade] (CREATED by PR2 — it is NOT re-created here). The founding-area
 * invariant is: 거병/건국 push entities into the CREATED-set, while 방랑 leaves the created-set EMPTY
 * and populates only the CASCADE-set. This test pins both the order and that created-vs-cascade split.
 */
class BangrangCascadeTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 6
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 200, MONTH, 42, "che_방랑")))
    private val env = WorldEnv(year = 200, startYear = 184, develCost = 120)
    private val date = "08:00"

    private fun lord() = General(
        id = 42, nationId = 7, cityId = 500,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 0, rice = 0,
        meta = linkedMapOf("name" to "원소", "officer_city" to 500, "makelimit" to 0),
    )

    private fun member(id: Int, officerLevel: Int, cityId: Int) = General(
        id = id, nationId = 7, cityId = cityId,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel, gold = 0, rice = 0,
        meta = linkedMapOf("officer_city" to cityId, "makelimit" to 0),
    )

    private fun cityOf(id: Int, frontState: Int) = City(
        id = id, nationId = 7, level = 5,
        commerce = 1, commerceMax = 1, agriculture = 1, agricultureMax = 1,
        supplyState = 1, frontState = frontState, trust = 50.0,
        meta = linkedMapOf("conflict" to linkedMapOf("3" to 5)),
    )

    private fun realNation() = Nation(
        id = 7, level = 3, capitalCityId = 500, name = "위", color = "#abcdef",
        typeCode = "che_병가", tech = 1234.0,
    )

    @Test
    fun `applyWanderingCascade reverts nation lord members cities diplomacy in load-bearing order`() {
        val draft = GeneralActionDraft(lord(), cityOf(500, frontState = 1), realNation()).apply {
            cascadeGenerals.add(member(77, officerLevel = 5, cityId = 501))
            cascadeGenerals.add(member(78, officerLevel = 12, cityId = 502))   // a SECOND lord-rank member: officer_level untouched
            cascadeCities.add(cityOf(500, frontState = 1))
            cascadeCities.add(cityOf(501, frontState = 3))
            cascadeDiplomacy.add(Diplomacy(me = 7, you = 9, state = 0, term = 4))
            cascadeDiplomacy.add(Diplomacy(me = 5, you = 7, state = 1, term = 2))
        }

        FoundingCascade.applyWanderingCascade(draft, "원소")

        // 1. nation revert
        val n = draft.nation!!
        assertEquals("원소", n.name)
        assertEquals(FoundingCascade.WANDERING_COLOR, n.color)
        assertEquals("#330000", n.color)
        assertEquals(0, n.level)
        assertEquals(FoundingCascade.WANDERING_TYPE, n.typeCode)
        assertEquals("None", n.typeCode)
        assertEquals(0.0, n.tech)
        assertEquals(0, n.capitalCityId)

        // 2. lord self — makelimit 12, officer_city 0; officer_level 12 untouched
        assertEquals(12, metaInt(draft.general.meta, "makelimit"))
        assertEquals(0, metaInt(draft.general.meta, "officer_city"))
        assertEquals(12, draft.general.officerLevel)

        // 3. members — makelimit 12 always; officer_level=1/officer_city=0 only when < 12
        val m77 = draft.cascadeGenerals.first { it.id == 77 }
        assertEquals(12, metaInt(m77.meta, "makelimit"))
        assertEquals(1, m77.officerLevel, "officer_level<12 reset to 1")
        assertEquals(0, metaInt(m77.meta, "officer_city"))
        val m78 = draft.cascadeGenerals.first { it.id == 78 }
        assertEquals(12, metaInt(m78.meta, "makelimit"))
        assertEquals(12, m78.officerLevel, "officer_level==12 untouched")
        assertEquals(502, metaInt(m78.meta, "officer_city"), "officer_city untouched when officer_level==12")

        // 4. cities — nation 0, front 0, conflict removed ({})
        assertTrue(draft.cascadeCities.all { it.nationId == 0 })
        assertTrue(draft.cascadeCities.all { it.frontState == 0 })
        assertTrue(draft.cascadeCities.none { it.meta.containsKey("conflict") })

        // 5. diplomacy — state 2, term 0
        assertTrue(draft.cascadeDiplomacy.all { it.state == 2 && it.term == 0 })
    }

    @Test
    fun `방랑 populates the cascade-set and leaves the created-set EMPTY`() {
        val draft = GeneralActionDraft(lord(), cityOf(500, frontState = 0).copy(nationId = 7), realNation()).apply {
            cascadeGenerals.add(member(77, officerLevel = 5, cityId = 501))
            cascadeCities.add(cityOf(500, frontState = 0))
            cascadeDiplomacy.add(Diplomacy(me = 7, you = 9, state = 0, term = 4))
        }
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheBangrang(pipeline).resolve(ctx)

        // founding created-set invariant: 방랑 NEVER inserts a nation / diplomacy / nation_turn.
        assertTrue(draft.createdNations.isEmpty(), "방랑 created-nations empty")
        assertTrue(draft.createdDiplomacy.isEmpty(), "방랑 created-diplomacy empty")
        assertTrue(draft.createdNationTurns.isEmpty(), "방랑 created-nation_turn empty")

        // the cascade-set the ChangeRecorder diffs is populated + mutated.
        assertTrue(draft.cascadeGenerals.isNotEmpty(), "cascade-generals populated")
        assertTrue(draft.cascadeCities.all { it.nationId == 0 }, "cascade-cities neutralized")
        assertTrue(draft.cascadeDiplomacy.all { it.state == 2 }, "cascade-diplomacy reset")
    }

    @Test
    fun `방랑 has no lottery and emits exactly one action log`() {
        val draft = GeneralActionDraft(lord(), cityOf(500, frontState = 0).copy(nationId = 7), realNation())
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheBangrang(pipeline).resolve(ctx)

        val logs = ctx.logs()
        assertEquals(1, logs.size, "one action log")
        assertEquals("<C>●</>${MONTH}월:영토를 버리고 방랑의 길을 떠납니다. <1>$date</>", logs[0])
    }
}
