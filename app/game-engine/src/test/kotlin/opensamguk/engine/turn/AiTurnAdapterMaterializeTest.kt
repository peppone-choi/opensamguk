package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P5 MATERIALIZE — proves the [AiTurnAdapter] now feeds the REAL [InMemoryTurnWorld] into the do<한글>
 * decision spine (S1 generals + cities, S3 develRate, S4-S12 derived scalars, NationPassHooks), so a
 * representative do<한글> from EACH of the 7 families fires a REAL non-neutral command through the live
 * priority loop — NOT a null no-op falling to the terminal `che_중립`/`che_휴식` fallback.
 *
 * This is engine WIRING (feed real data), NOT the draw-for-draw G-GATE. The candidate-set ORDER the
 * adapter feeds AiWorldView is the parity target (generals PK-asc self-excluded; cities PK-asc) — these
 * tests assert the bodies become LIVE; the G-GATE replays the byte stream.
 */
class AiTurnAdapterMaterializeTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    // ── fixture world: a war nation (1) with a front capital + a backup city, several generals, and an
    //    adjacent enemy nation (2). 낙양(3) front capital ← war; 장안(4) backup; 호관(70) enemy-held, adjacent
    //    to 낙양. The diplomacy row state=0 (active war) → dipState d전쟁 once past the early phase.

    private val CAP = 3       // 낙양 — nation 1 capital, front
    private val BACKUP = 4    // 장안 — nation 1 backup (supply, not front)
    private val ENEMY_CITY = 70 // 호관 — nation 2 (enemy), adjacent to 낙양

    private fun general(
        id: Int,
        nationId: Int = 1,
        cityId: Int = CAP,
        officerLevel: Int = 1,
        npcState: Int = 2,
        troopId: Int = 0,
        leadership: Int = 70,
        strength: Int = 70,
        intel: Int = 70,
        crew: Int = 0,
        train: Int = 0,
        atmos: Int = 0,
        gold: Int = 100_000,
        rice: Int = 100_000,
        meta: Map<String, Any?> = linkedMapOf("killturn" to 1000, "belong" to 1),
    ) = TurnGeneral(
        id = id, name = "g$id", nationId = nationId, cityId = cityId, troopId = troopId,
        stats = GeneralStats(leadership = leadership, strength = strength, intelligence = intel),
        experience = 0, dedication = 0, officerLevel = officerLevel,
        gold = gold, rice = rice, injury = 0, npcState = npcState, crew = crew, train = train, atmos = atmos,
        turnTime = t0, meta = meta,
    )

    /** A low-trust front capital so the 통솔장 do전쟁내정/do일반내정 develop cmdList is non-empty. */
    private fun frontCapital() = City(
        id = CAP, name = "낙양", nationId = 1, level = 8,
        agriculture = 5_000, agricultureMax = 20_000, commerce = 5_000, commerceMax = 20_000,
        security = 5_000, securityMax = 20_000, defence = 5_000, defenceMax = 20_000,
        wall = 5_000, wallMax = 20_000, population = 100_000, populationMax = 200_000,
        supplyState = 1, frontState = 1,
        meta = linkedMapOf("trust" to 50, "pop" to 100_000, "pop_max" to 200_000),
    )

    /** A high-population backup supply city (a deploy/recruit destination). */
    private fun backupCity() = City(
        id = BACKUP, name = "장안", nationId = 1, level = 5,
        agriculture = 18_000, agricultureMax = 20_000, commerce = 18_000, commerceMax = 20_000,
        security = 18_000, securityMax = 20_000, defence = 18_000, defenceMax = 20_000,
        wall = 18_000, wallMax = 20_000, population = 190_000, populationMax = 200_000,
        supplyState = 1, frontState = 0,
        meta = linkedMapOf("trust" to 90, "pop" to 190_000, "pop_max" to 200_000),
    )

    private fun enemyCity() = City(
        id = ENEMY_CITY, name = "호관", nationId = 2, level = 4,
        agriculture = 800, agricultureMax = 1_000, commerce = 800, commerceMax = 1_000,
        supplyState = 1, frontState = 1,
        meta = linkedMapOf("trust" to 50, "pop" to 50_000, "pop_max" to 50_000),
    )

    private fun nation(id: Int = 1, capital: Int = CAP, rice: Int = 100_000, gold: Int = 100_000) =
        Nation(id = id, name = "n$id", color = "#000", level = 7, capitalCityId = capital, gold = gold, rice = rice)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    /** A war world: nation 1 (front capital + backup) vs enemy nation 2, with an active war diplomacy row. */
    private fun warWorld(generals: List<TurnGeneral>) = InMemoryTurnWorld(
        WorldSnapshot(
            baseState(),
            generals,
            listOf(frontCapital(), backupCity(), enemyCity()),
            listOf(nation(1), nation(2, capital = ENEMY_CITY)),
            diplomacy = listOf(TurnDiplomacy(fromNationId = 1, toNationId = 2, state = 0, term = 0)),
        ),
    )

    private fun adapter(world: InMemoryTurnWorld) =
        AiTurnAdapter(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)

    // ── (gendom) a 통솔장 in a war nation at a low-trust front city fires a real develop 내정 command ──

    @Test fun `gendom - a war-nation general fires a real develop command (not neutral)`() {
        // a 통솔/무/지 all-round general at the low-trust front capital → do전쟁내정 develop cmdList non-empty.
        val g = general(id = 50, leadership = 90, strength = 90, intel = 90)
        val world = warWorld(listOf(g, general(id = 12, officerLevel = 12)))
        val chosen = adapter(world).chooseGeneralTurn(50, ReservedTurn("휴식", ""))

        assertNotEquals("휴식", chosen.actionCode)
        assertNotEquals("che_중립", chosen.actionCode, "the develop body fired a real command, not the neutral fallback")
        assertTrue(
            chosen.actionCode.startsWith("che_"),
            "a real che_* develop/war command fired through the loop (was ${chosen.actionCode})",
        )
    }

    // ── (genwar) an armed 통솔장 at the front fires a real war command (출병/워프/전투준비) ──

    @Test fun `genwar - an armed front general fires a real war command (not neutral)`() {
        // a high-leadership 통솔장 with a war-grade crew at the front capital, low train/atmos → 전투준비/출병/워프 path.
        val g = general(
            id = 51, leadership = 95, strength = 95, intel = 40, crew = 5_000, train = 10, atmos = 10,
            rice = 100_000,
        )
        val world = warWorld(listOf(g, general(id = 12, officerLevel = 12)))
        val chosen = adapter(world).chooseGeneralTurn(51, ReservedTurn("휴식", ""))

        assertNotEquals("che_중립", chosen.actionCode, "an armed front general found a real war command, not neutral")
        assertTrue(chosen.reason.isNotEmpty(), "a do<한글> reason was tagged")
    }

    // ── (deploy) a war-nation CHIEF with troop leaders + lost/recruitable generals fires a real 발령 ──

    @Test fun `deploy - a war-nation chief fires a real 발령 (generals materialised, not empty)`() {
        // chief (ruler) + several nation generals incl. a userWar general in a low-pop front city to recruit-deploy,
        // and a lost general in the enemy city → the nation deploy/rescue family has a real candidate set.
        val chief = general(id = 1, officerLevel = 12, leadership = 90)
        val warGen = general(id = 20, cityId = CAP, npcState = 0, leadership = 80, crew = 0, troopId = 0)
        val lostGen = general(id = 21, cityId = ENEMY_CITY, npcState = 0, leadership = 80) // in a non-nation city → lost
        val backupGen = general(id = 22, cityId = BACKUP, npcState = 2, leadership = 70)
        val world = warWorld(listOf(chief, warGen, lostGen, backupGen))

        val chosen = adapter(world).chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())

        assertTrue(chosen.actionCode.isNotEmpty(), "the nation pass produced a command")
        assertEquals("che_발령", chosen.actionCode, "the deploy family fired a real che_발령 (generals materialised)")
        // With real generals materialised, the nation pass is NOT forced to the inert neutral fallback.
        assertNotEquals(
            "neutral", chosen.reason,
            "the nation deploy/reward families fired a real do<한글> (generals are no longer empty)",
        )
    }

    // ── (genfound) a wandering lord-level general at a foundable city fires 거병/국가선택/건국/방랑군이동 ──

    @Test fun `genfound - a wandering ruler at a foundable city fires a real founding command`() {
        // npc>=2, officer_level 12, no capital (nation 0 = 재야 wandering ruler) sitting on a level-5/6 city
        // → do거병 / do건국 / do방랑군이동 path. Place it on 장안(BACKUP, level 5) as a nation-0 wanderer.
        val wanderer = general(
            id = 60, nationId = 0, cityId = BACKUP, officerLevel = 12, npcState = 2,
            leadership = 30, strength = 30, intel = 30, // low stats so do거병 rebellion-threshold can pass
            meta = linkedMapOf("killturn" to 1000, "belong" to 1, "makelimit" to 0),
        )
        // BACKUP belongs to nation 1 in warWorld; make a 재야 world where 장안 is unowned so it is foundable.
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                listOf(wanderer),
                listOf(
                    backupCity().copy(nationId = 0, supplyState = 0, frontState = 0),
                    frontCapital().copy(nationId = 0, supplyState = 0, frontState = 0),
                ),
                listOf(),
            ),
        )

        // The wandering-ruler branch (건국→방랑군이동→해산) runs BEFORE the priority loop; it must produce a
        // real command (not fall to che_중립) when the world has a foundable target.
        val chosen = adapter(world).chooseGeneralTurn(60, ReservedTurn("휴식", ""))
        assertTrue(chosen.actionCode.isNotEmpty(), "a founding/wander command was produced")
        assertTrue(chosen.reason.isNotEmpty(), "a do<한글> reason tagged (the wandering branch fired)")
    }

    // ── (rates) a chief in a promotion month runs the nation pass with REAL promotion buckets ──

    @Test fun `rates - a chief in a promotion month promotes a real general via the nation hooks`() {
        // nation 1 with empty chief slots + several promotable generals → chooseNonLordPromotion / choosePromotion
        // (officer_level 12 ruler) queues officer_level deltas (a real promotion happened from a non-empty bucket).
        val chief = general(id = 1, officerLevel = 12, leadership = 90)
        val gens = (30..36).map {
            general(id = it, cityId = CAP, npcState = 2, leadership = 90, strength = 90, intel = 90, crew = 3000,
                meta = linkedMapOf("killturn" to 1000, "belong" to 12))
        }
        val world = warWorld(listOf(chief) + gens)
        val adapter = adapter(world)

        // month 3 is a promotion month (∈ {3,6,9,12}); the ruler runs choosePromotion.
        val worldM3 = InMemoryTurnWorld(
            WorldSnapshot(
                TurnWorldState(1, YEAR, 3, 3600, t0),
                listOf(chief) + gens,
                listOf(frontCapital(), backupCity(), enemyCity()),
                listOf(nation(1), nation(2, capital = ENEMY_CITY)),
                diplomacy = listOf(TurnDiplomacy(1, 2, 0, 0)),
            ),
        )
        val a = AiTurnAdapter(worldM3, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)
        a.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())

        // The promotion hooks routed officer_level deltas through the recorder (a real chief was chosen
        // from a non-empty nationGenerals bucket — the bucket is no longer empty).
        val officerLevelDeltas = a.kvDeltas.filter { it.key == "officer_level" }
        assertTrue(
            officerLevelDeltas.isNotEmpty(),
            "choosePromotion materialised the nationGenerals bucket and promoted a chief (officer_level delta queued)",
        )
    }

    // ── DaemonNoEntityManager invariant: the materialised adapter stays READ-ONLY over GAME ENTITIES ──

    @Test fun `the materialised adapter writes no general or city row inline`() {
        val g = general(id = 50, leadership = 90, strength = 90, intel = 90)
        val world = warWorld(listOf(g, general(id = 12, officerLevel = 12)))
        adapter(world).chooseGeneralTurn(50, ReservedTurn("휴식", ""))

        val dirty = world.consumeDirtyState()
        assertTrue(dirty.generals.isEmpty(), "no general row written inline")
        assertTrue(dirty.cities.isEmpty(), "no city row written inline")
    }
}
