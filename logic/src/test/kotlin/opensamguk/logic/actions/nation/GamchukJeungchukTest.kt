package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful resolve test for che_감축 / che_증축 (Command/Nation/che_감축.php:140-210,
 * che_증축.php:138-202).
 *
 * Pins: cost (develcost*500 + defaultCost{/2 for 감축}); 감축 city floors (greatest math) + level-1 +
 * *_max (can go negative) + nation gold/rice REFUND; 증축 *_max-only += + level+1 + nation SPEND;
 * capset bump; the general action log (<1>date</>) + global action broadcast; exp/ded magnitude 30.
 *
 * No RNG draws (both commands draw nothing). The rng is seeded only to match the seam.
 */
class GamchukJeungchukTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val date = "12:34"

    private fun rng() = RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "nationCommand", 190, MONTH, 42, "che_감축")))

    // explevel/dedlevel chosen CONSISTENT with experience/dedication so that the +30 grant does NOT
    // cross a level boundary (no spurious 레벨업/승급 plain log) — getExpLevel(600)=6, getExpLevel(630)=6;
    // getDedLevel(4000)=7, getDedLevel(4030)=7.
    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 70, intel = 80, injury = 0,
        experience = 600.0, dedication = 4000.0, officerLevel = 12, gold = 1000, rice = 1000,
        meta = linkedMapOf("explevel" to 6, "dedlevel" to 7),
    )

    // The capital city (dest). 대 level 7, all developed near max.
    private fun capital() = City(
        id = 30, nationId = 1, level = 7,
        commerce = 5000, commerceMax = 8000,
        agriculture = 5000, agricultureMax = 8000,
        supplyState = 1, frontState = 0, trust = 80.0,
        security = 5000, securityMax = 8000,
        defense = 3000, defenseMax = 6000,
        wall = 3000, wallMax = 6000,
        population = 250000, populationMax = 300000,
        region = 1,
    )

    private fun nation() = Nation(
        id = 1, level = 5, capitalCityId = 30, name = "촉", color = "#ff0000",
        gold = 500000, rice = 500000, capset = 4,
    )

    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun ctx(d: GeneralActionDraft) =
        GeneralActionResolveContext(d, rng(), env, MONTH, date, generalName = "유비")

    @Test
    fun `gamchuk refunds cost, floors city, decrements level and maxes, bumps capset`() {
        val cost = env.develCost * GameConst.expandCityCostCoef + GameConst.expandCityDefaultCost / 2  // 60000 + 30000
        assertEquals(90000, cost)

        val d = GeneralActionDraft(general(), city = capital().copy(), nation = nation())
        d.destCity = d.city  // for 감축 the dest IS the capital; the adapter sets destCity = capital
        val c = ctx(d)
        cheGamchuk(pipeline).resolve(c)

        // city: level-1; greatest floors; *_max -= increment (can go negative for small maxes, here positive)
        val cap = d.destCity!!
        assertEquals(6, cap.level, "level-1")
        assertEquals(250000 - 100000, cap.population, "pop greatest(pop-100000, 30000)")
        assertEquals(5000 - 2000, cap.agriculture, "agri greatest(agri-2000,0)")
        assertEquals(5000 - 2000, cap.commerce, "comm")
        assertEquals(5000 - 2000, cap.security, "secu")
        assertEquals(3000 - 2000, cap.defense, "def greatest(def-2000,0)")
        assertEquals(3000 - 2000, cap.wall, "wall")
        assertEquals(300000 - 100000, cap.populationMax, "pop_max -= 100000")
        assertEquals(8000 - 2000, cap.agricultureMax, "agri_max")
        assertEquals(8000 - 2000, cap.commerceMax)
        assertEquals(8000 - 2000, cap.securityMax)
        assertEquals(6000 - 2000, cap.defenseMax)
        assertEquals(6000 - 2000, cap.wallMax)

        // nation: capset+1, gold/rice REFUNDED
        assertEquals(5, d.nation!!.capset, "capset bump")
        assertEquals(500000 + 90000, d.nation!!.gold, "gold refund")
        assertEquals(500000 + 90000, d.nation!!.rice, "rice refund")

        // general action log + global broadcast (city 30 = 계양, josa-을: '계양'+'을')
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>3월:<G><b>계양</b></>을 감축했습니다. <1>12:34</>", c.logs()[0])
        assertEquals(1, c.globalActionLogs().size, "one global action log")
        assertEquals("<C>●</>3월:<Y>유비</>가 <G><b>계양</b></>을 <M>감축</>하였습니다.", c.globalActionLogs()[0])

        // exp/ded += 30 (magnitude 5*(5+1))
        assertEquals(630.0, d.general.experience, "exp += 30")
        assertEquals(4030.0, d.general.dedication, "ded += 30")
    }

    @Test
    fun `jeungchuk spends cost, only maxes increase, level+1, bumps capset`() {
        val cost = env.develCost * GameConst.expandCityCostCoef + GameConst.expandCityDefaultCost  // 60000 + 60000
        assertEquals(120000, cost)

        val cap0 = capital().copy()
        val d = GeneralActionDraft(general(), city = cap0, nation = nation())
        d.destCity = d.city
        val c = ctx(d)
        cheJeungchuk(pipeline).resolve(c)

        val cap = d.destCity!!
        assertEquals(8, cap.level, "level+1")
        // current UNCHANGED
        assertEquals(cap0.population, cap.population)
        assertEquals(cap0.agriculture, cap.agriculture)
        assertEquals(cap0.commerce, cap.commerce)
        assertEquals(cap0.security, cap.security)
        assertEquals(cap0.defense, cap.defense)
        assertEquals(cap0.wall, cap.wall)
        // maxes += increments
        assertEquals(300000 + 100000, cap.populationMax)
        assertEquals(8000 + 2000, cap.agricultureMax)
        assertEquals(8000 + 2000, cap.commerceMax)
        assertEquals(8000 + 2000, cap.securityMax)
        assertEquals(6000 + 2000, cap.defenseMax)
        assertEquals(6000 + 2000, cap.wallMax)

        assertEquals(5, d.nation!!.capset, "capset bump")
        assertEquals(500000 - 120000, d.nation!!.gold, "gold spent")
        assertEquals(500000 - 120000, d.nation!!.rice, "rice spent")

        assertEquals(1, c.logs().size)
        assertTrue(c.logs()[0].endsWith("증축했습니다. <1>12:34</>"))
        assertEquals(1, c.globalActionLogs().size)
        assertTrue(c.globalActionLogs()[0].contains("<M>증축</>하였습니다."))

        assertEquals(630.0, d.general.experience)
        assertEquals(4030.0, d.general.dedication)
    }

    @Test
    fun `gamchuk maxes can go negative when small`() {
        val small = capital().copy(
            defense = 100, defenseMax = 100, wall = 100, wallMax = 100,
            agriculture = 100, agricultureMax = 100, commerce = 100, commerceMax = 100,
            security = 100, securityMax = 100, population = 40000, populationMax = 40000,
        )
        val d = GeneralActionDraft(general(), city = small, nation = nation())
        d.destCity = d.city
        cheGamchuk(pipeline).resolve(ctx(d))
        val cap = d.destCity!!
        // current floors at greatest(..,0) / greatest(pop-100000, 30000)
        assertEquals(0, cap.agriculture)
        assertEquals(0, cap.defense)
        assertEquals(GameConst.minAvailableRecruitPop, cap.population, "pop greatest floors at 30000")
        // *_max can go negative (NO floor on max)
        assertEquals(100 - 2000, cap.agricultureMax, "agri_max negative")
        assertEquals(100 - 2000, cap.defenseMax, "def_max negative")
        assertEquals(40000 - 100000, cap.populationMax, "pop_max negative")
    }
}
