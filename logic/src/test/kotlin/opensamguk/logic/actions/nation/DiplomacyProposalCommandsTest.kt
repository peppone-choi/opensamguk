package opensamguk.logic.actions.nation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the diplomacy PROPOSAL commands che_종전제의 ([CheJongjeonjeui]) and
 * che_불가침파기제의 ([CheBulgachimPagijeui]) — the two commands the P6 audit flagged as entirely
 * missing. Pins: key/name/category, argTest (destNationID), the PHP min/full constraint packs, the
 * action log, and CommandRegistry dispatch.
 */
class DiplomacyProposalCommandsTest {

    private fun pipeline() = GeneralActionPipeline()

    private fun makeContext(
        args: Map<String, Any?>,
        destGeneralName: String = "상대국",
    ): GeneralActionResolveContext {
        val general = General(
            id = 1, nationId = 1, cityId = 1,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
        )
        val city = City(
            id = 1, nationId = 1, level = 5,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 50.0,
        )
        val nation = Nation(id = 1, level = 5, capitalCityId = 1, name = "테스트국")
        val draft = GeneralActionDraft(general = general, city = city, nation = nation)
        val rng = RandUtil(LiteHashDrbg(ByteArray(0)))
        val env = WorldEnv(year = 2025, startYear = 190, develCost = 100)
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = 6, date = "12:00",
            args = args, generalName = "테스트장수", destGeneralName = destGeneralName,
        )
    }

    private fun cctx(destNationId: Int = 5) = ConstraintContext(
        actorId = 1, nationId = 1, cityId = 1,
        env = mapOf("year" to 2025, "month" to 6),
        args = mapOf("destNationID" to destNationId),
        destNationId = destNationId,
        mode = ConstraintMode.FULL,
    )

    // ── che_종전제의 ────────────────────────────────────────────────────────
    @Test
    fun `jongjeonjeui key name category`() {
        val cmd = cheJongjeonjeui(pipeline())
        assertEquals("che_종전제의", cmd.key)
        assertEquals("종전 제의", cmd.name)
        assertEquals("외교", cmd.category)
        assertEquals(0, cmd.getPreReqTurn())
    }

    @Test
    fun `jongjeonjeui parseArgs canonicalizes destNationID and rejects invalid`() {
        val cmd = cheJongjeonjeui(pipeline())
        assertEquals(5, cmd.parseArgs(mapOf("destNationID" to 5))["destNationID"])
        assertTrue(cmd.parseArgs(mapOf("destNationID" to 0)).isEmpty())
        assertTrue(cmd.parseArgs(emptyMap()).isEmpty())
    }

    @Test
    fun `jongjeonjeui min constraints are BeChief NotBeNeutral OccupiedCity SuppliedCity`() {
        val cmd = cheJongjeonjeui(pipeline())
        assertEquals(
            listOf("BeChief", "NotBeNeutral", "OccupiedCity", "SuppliedCity"),
            cmd.buildMinConstraints(cctx()).map { it.name },
        )
    }

    @Test
    fun `jongjeonjeui full constraints add ExistsDestNation and AllowDiplomacyBetweenStatus`() {
        val cmd = cheJongjeonjeui(pipeline())
        val c = cmd.buildConstraints(cctx())
        assertEquals(6, c.size)
        assertTrue(c.any { it.name == "ExistsDestNation" })
        assertTrue(c.any { it.name == "AllowDiplomacyBetweenStatus" })
    }

    @Test
    fun `jongjeonjeui resolve produces the proposal-sent log`() {
        val cmd = cheJongjeonjeui(pipeline())
        val ctx = makeContext(args = mapOf("destNationID" to 5), destGeneralName = "오국")
        cmd.resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("오국"))
        assertTrue(logs[0].contains("종전 제의 서신을 보냈습니다"))
    }

    // ── che_불가침파기제의 ──────────────────────────────────────────────────
    @Test
    fun `bulgachimPagijeui key name category`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        assertEquals("che_불가침파기제의", cmd.key)
        assertEquals("불가침 파기 제의", cmd.name)
        assertEquals("외교", cmd.category)
    }

    @Test
    fun `bulgachimPagijeui min constraints are BeChief NotBeNeutral OccupiedCity SuppliedCity`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        assertEquals(
            listOf("BeChief", "NotBeNeutral", "OccupiedCity", "SuppliedCity"),
            cmd.buildMinConstraints(cctx()).map { it.name },
        )
    }

    @Test
    fun `bulgachimPagijeui full constraints add ExistsDestNation and AllowDiplomacyBetweenStatus`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        val c = cmd.buildConstraints(cctx())
        assertEquals(6, c.size)
        assertTrue(c.any { it.name == "ExistsDestNation" })
        assertTrue(c.any { it.name == "AllowDiplomacyBetweenStatus" })
    }

    @Test
    fun `bulgachimPagijeui resolve produces the proposal-sent log`() {
        val cmd = cheBulgachimPagijeui(pipeline())
        val ctx = makeContext(args = mapOf("destNationID" to 5), destGeneralName = "오국")
        cmd.resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("오국"))
        assertTrue(logs[0].contains("불가침 파기 제의 서신을 보냈습니다"))
    }

    // ── CommandRegistry dispatch ────────────────────────────────────────────
    @Test
    fun `command registry resolves both proposal commands`() {
        val registry = CommandRegistry(pipeline())
        assertEquals("che_종전제의", registry.resolve("che_종전제의").key)
        assertEquals("che_불가침파기제의", registry.resolve("che_불가침파기제의").key)
    }
}
