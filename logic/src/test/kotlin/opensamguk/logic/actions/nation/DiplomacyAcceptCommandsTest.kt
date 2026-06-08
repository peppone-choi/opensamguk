package opensamguk.logic.actions.nation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
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
        general: General = General(
            id = 1, nationId = 1, cityId = 1,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
        ),
        city: City = City(
            id = 1, nationId = 1, level = 5,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 50.0,
        ),
        nation: Nation = Nation(id = 1, level = 5, capitalCityId = 1, name = "테스트국"),
        generalName: String = "테스트장수",
        destGeneralName: String = "상대장수",
    ): GeneralActionResolveContext {
        val draft = GeneralActionDraft(general = general, city = city, nation = nation)
        val rng = RandUtil(LiteHashDrbg(ByteArray(0)))
        val env = WorldEnv(year = 2025, startYear = 190, develCost = 100)
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

    private fun makePipeline() = opensamguk.logic.stats.GeneralActionPipeline()

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
        // PHP che_불가침수락.php:51-63 — destGeneralID는 필수 인자다(누락 시 argTest false).
        val result = cmd.parseArgs(mapOf("destNationID" to 5, "destGeneralID" to 7, "year" to 2026, "month" to 3))
        assertEquals(5, result["destNationID"])
        assertEquals(7, result["destGeneralID"])
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
        // PHP che_불가침수락.php:220 — year/month는 <C>…</> 태그로 감싸진다(byte-exact).
        assertTrue(logs[0].contains("<C>2026</>년 <C>3</>월까지 불가침에 성공했습니다."))
    }

    @Test
    fun `bulgachimSuak resolve writes diplomacy regardless of term length`() {
        // PHP che_불가침수락.php run()에는 6개월 하한 가드가 없다 — term = reqMonth - currentMonth를
        // 그대로 적재한다(reqMonth = year*12+month, currentMonth = env.year*12+env.month-1). 6개월 게이트는
        // 제의(che_불가침제의) 시점 제약일 뿐 수락 run()의 조건이 아니다(이전 if(term<6) return은 날조였다).
        val cmd = CheBulgachimSuak(makePipeline())
        // env.year=2025, month=6 → currentMonth = 2025*12 + 6 - 1 = 24305
        // 요청 2025년 7월 → reqMonth = 2025*12 + 7 = 24307 → term = 2 (< 6 이어도 적재된다)
        val ctx = makeContext(args = mapOf("destNationID" to 5, "year" to 2025, "month" to 7))
        cmd.resolve(ctx)
        assertEquals(2, ctx.draft.cascadeDiplomacy.size)
        val forward = ctx.draft.cascadeDiplomacy.find { it.me == 1 && it.you == 5 }
        assertTrue(forward != null)
        assertEquals(2, forward!!.term)
        assertEquals(DiplomacyState.NON_AGGRESSION, forward.state)
    }

    @Test
    fun `bulgachimSuak min constraints include BeChief and NotBeNeutral`() {
        val cmd = CheBulgachimSuak(makePipeline())
        val ctx = ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = mapOf("year" to 2025, "month" to 6),
            args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3),
            destNationId = 5,
            mode = ConstraintMode.FULL,
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

        // PHP che_종전수락.php:177 — actor 자신의 generalAction(PLAIN)은 "종전에 합의했습니다."이다.
        // ("종전에 성공했습니다."는 L187 dest 장수 로그로, draft.destGeneral 주입 시에만 dest 버킷에 적재된다.)
        val logs = ctx.logs()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs[0].contains("적국"))
        assertTrue(logs[0].contains("종전에 합의했습니다"))

        // PHP che_종전수락.php:180 — actor globalAction(MONTH): "<Y>{general}</>{이} … <M>종전 합의</> 하였습니다."
        val globalLogs = ctx.globalActionLogs()
        assertTrue(globalLogs.isNotEmpty())
        assertTrue(globalLogs[0].contains("종전 합의"))
    }

    @Test
    fun `jongjeonSuak constraints allow war and declaration only`() {
        val cmd = CheJongjeonSuak(makePipeline())
        val ctx = ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = emptyMap(),
            args = mapOf("destNationID" to 5),
            destNationId = 5,
            mode = ConstraintMode.FULL,
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
            mode = ConstraintMode.FULL,
        )
        val constraints = cmd.buildConstraints(ctx)
        val diplomacyConstraint = constraints.find { it.name == "AllowDiplomacyBetweenStatus" }
        assertTrue(diplomacyConstraint != null)
    }
}
