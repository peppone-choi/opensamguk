package opensamguk.logic.actions.nation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for diplomacy acceptance commands — [CheBulgachimSuak], [CheJongjeonSuak], [CheBulgachimPagiSuak].
 *
 * Verifies that each command:
 *  1. Correctly builds constraints (BeChief, NotBeNeutral, exists, diplomacy status checks).
 *  2. Correctly mutates cascadeDiplomacy in resolve().
 *  3. Produces expected log lines.
 */
class DiplomacyAcceptCommandsTest {

    private fun makeContext(
        args: Map<String, Any?> = emptyMap(),
        general: General = General(id = 1, nationId = 1, cityId = 1, officerLevel = 12),
        city: City = City(id = 1, nationId = 1),
        nation: Nation = Nation(id = 1, name = "테스트국"),
        generalName: String = "테스트장수",
        destGeneralName: String = "상대장수",
    ): GeneralActionResolveContext {
        val draft = GeneralActionDraft(general = general, city = city, nation = nation)
        val rng = RandUtil(LiteHashDrbg(ByteArray(0)))
        val env = WorldEnv(year = 2025, month = 6, startYear = 190)
        return GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = env,
            month = 6,
            date = "12:00",
            args = args,
            generalName = generalName,
            destGeneralName = destGeneralName,
        )
    }

    private fun makePipeline() = opensamguk.logic.stats.GeneralActionPipeline(emptyMap())

    // ========================================================================
    // CheBulgachimSuak (불가침 수락)
    // ========================================================================

    @Test
    fun `bulgachimSuak key and category`() {
        val cmd = CheBulgachimSuak(makePipeline())
        assertEquals("che_불가침수락", cmd.key)
        assertEquals("불가침 수락", cmd.name)
        assertEquals("외교", cmd.category)
        assertEquals(0, cmd.getPreReqTurn())
    }

    @Test
    fun `bulgachimSuak parseArgs valid`() {
        val cmd = CheBulgachimSuak(makePipeline())
        val result = cmd.parseArgs(mapOf("destNationID" to 5, "year" to 2026, "month" to 3))
        assertEquals(5, result["destNationID"])
        assertEquals(2026, result["year"])
        assertEquals(3, result["month"])
    }

    @Test
    fun `bulgachimSuak parseArgs invalid month returns empty`() {
        val cmd = CheBulgachimSuak(makePipeline())
        val result = cmd.parseArgs(mapOf("destNationID" to 5, "year" to 2026, "month" to 13))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `bulgachimSuak resolve creates both directional diplomacy rows`() {
        val cmd = CheBulgachimSuak(makePipeline())
        val ctx = makeContext(args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3))
        cmd.resolve(ctx)

        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)

        // Forward: me=1, you=5
        val forward = cascade.find { it.me == 1 && it.you == 5 }
        assertTrue(forward != null)
        assertEquals(DiplomacyState.NON_AGGRESSION, forward!!.state)
        assertTrue(forward.term >= 6) // at least 6 months

        // Reverse: me=5, you=1
        val reverse = cascade.find { it.me == 5 && it.you == 1 }
        assertTrue(reverse != null)
        assertEquals(DiplomacyState.NON_AGGRESSION, reverse!!.state)
        assertTrue(reverse.term >= 6)
    }

    @Test
    fun `bulgachimSuak resolve produces log`() {
        val cmd = CheBulgachimSuak(makePipeline())
        val ctx = makeContext(
            args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3),
            destGeneralName = "오국",
        )
        cmd.resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("오국"))
        assertTrue(logs[0].contains("2026년 3월까지 불가침에 성공했습니다"))
    }

    @Test
    fun `bulgachimSuak resolve rejects term less than 6 months`() {
        val cmd = CheBulgachimSuak(makePipeline())
        // Current yearMonth = 2025*12 + 6 - 1 = 24305
        // Request yearMonth = 2025*12 + 7 - 1 = 24306
        // term = 1 (< 6) → should return early
        val ctx = makeContext(args = mapOf("destNationID" to 5, "year" to 2025, "month" to 7))
        cmd.resolve(ctx)
        assertEquals(0, ctx.draft.cascadeDiplomacy.size)
    }

    @Test
    fun `bulgachimSuak min constraints include BeChief and NotBeNeutral`() {
        val cmd = CheBulgachimSuak(makePipeline())
        val ctx = ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = mapOf("year" to 2025, "month" to 6),
            args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3),
            destNationId = 5,
        )
        val constraints = cmd.buildMinConstraints(ctx)
        assertEquals(2, constraints.size)
        assertEquals("BeChief", constraints[0].name)
        assertEquals("NotBeNeutral", constraints[1].name)
    }

    // ========================================================================
    // CheJongjeonSuak (종전 수락)
    // ========================================================================

    @Test
    fun `jongjeonSuak key and category`() {
        val cmd = CheJongjeonSuak(makePipeline())
        assertEquals("che_종전수락", cmd.key)
        assertEquals("종전 수락", cmd.name)
        assertEquals("외교", cmd.category)
    }

    @Test
    fun `jongjeonSuak resolve sets both directions to trade`() {
        val cmd = CheJongjeonSuak(makePipeline())
        val ctx = makeContext(args = mapOf("destNationID" to 5))
        cmd.resolve(ctx)

        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)

        val forward = cascade.find { it.me == 1 && it.you == 5 }
        assertTrue(forward != null)
        assertEquals(DiplomacyState.TRADE, forward!!.state)
        assertEquals(0, forward.term)

        val reverse = cascade.find { it.me == 5 && it.you == 1 }
        assertTrue(reverse != null)
        assertEquals(DiplomacyState.TRADE, reverse!!.state)
        assertEquals(0, reverse.term)
    }

    @Test
    fun `jongjeonSuak resolve produces multiple logs`() {
        val cmd = CheJongjeonSuak(makePipeline())
        val ctx = makeContext(
            args = mapOf("destNationID" to 5),
            generalName = "아국",
            destGeneralName = "적국",
        )
        cmd.resolve(ctx)

        val logs = ctx.logs()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs[0].contains("적국"))
        assertTrue(logs[0].contains("종전에 성공했습니다"))

        val globalLogs = ctx.globalActionLogs()
        assertTrue(globalLogs.isNotEmpty())
    }

    @Test
    fun `jongjeonSuak constraints allow war and declaration only`() {
        val cmd = CheJongjeonSuak(makePipeline())
        val ctx = ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = emptyMap(),
            args = mapOf("destNationID" to 5),
            destNationId = 5,
        )
        val constraints = cmd.buildConstraints(ctx)
        val diplomacyConstraint = constraints.find { it.name == "AllowDiplomacyBetweenStatus" }
        assertTrue(diplomacyConstraint != null)
    }

    // ========================================================================
    // CheBulgachimPagiSuak (불가침 파기 수락)
    // ========================================================================

    @Test
    fun `bulgachimPagiSuak key and category`() {
        val cmd = CheBulgachimPagiSuak(makePipeline())
        assertEquals("che_불가침파기수락", cmd.key)
        assertEquals("불가침 파기 수락", cmd.name)
        assertEquals("외교", cmd.category)
    }

    @Test
    fun `bulgachimPagiSuak resolve sets both directions to trade`() {
        val cmd = CheBulgachimPagiSuak(makePipeline())
        val ctx = makeContext(args = mapOf("destNationID" to 5))
        cmd.resolve(ctx)

        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)

        val forward = cascade.find { it.me == 1 && it.you == 5 }
        assertTrue(forward != null)
        assertEquals(DiplomacyState.TRADE, forward!!.state)
        assertEquals(0, forward.term)

        val reverse = cascade.find { it.me == 5 && it.you == 1 }
        assertTrue(reverse != null)
        assertEquals(DiplomacyState.TRADE, reverse!!.state)
        assertEquals(0, reverse.term)
    }

    @Test
    fun `bulgachimPagiSuak resolve produces log`() {
        val cmd = CheBulgachimPagiSuak(makePipeline())
        val ctx = makeContext(
            args = mapOf("destNationID" to 5),
            destGeneralName = "오국",
        )
        cmd.resolve(ctx)

        val logs = ctx.logs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("오국"))
        assertTrue(logs[0].contains("불가침을 파기했습니다"))
    }

    @Test
    fun `bulgachimPagiSuak constraints allow non-aggression only`() {
        val cmd = CheBulgachimPagiSuak(makePipeline())
        val ctx = ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = emptyMap(),
            args = mapOf("destNationID" to 5),
            destNationId = 5,
        )
        val constraints = cmd.buildConstraints(ctx)
        val diplomacyConstraint = constraints.find { it.name == "AllowDiplomacyBetweenStatus" }
        assertTrue(diplomacyConstraint != null)
    }
}
