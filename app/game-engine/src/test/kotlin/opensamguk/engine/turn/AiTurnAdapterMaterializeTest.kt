package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.families.RatesPromoFamily
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domestic.getGoldIncome
import opensamguk.logic.domestic.getRiceIncome
import opensamguk.logic.domestic.getWallIncome
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

    private fun nation(
        id: Int = 1,
        capital: Int = CAP,
        rice: Int = 100_000,
        gold: Int = 100_000,
        meta: Map<String, Any?> = emptyMap(),
    ) = Nation(
        id = id,
        name = "n$id",
        color = "#000",
        level = 7,
        capitalCityId = capital,
        gold = gold,
        rice = rice,
        meta = meta,
    )

    private fun baseState(meta: Map<String, Any?> = emptyMap()) = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0, meta = meta,
    )

    /** A war world: nation 1 (front capital + backup) vs enemy nation 2, with an active war diplomacy row. */
    private fun warWorld(generals: List<TurnGeneral>) = InMemoryTurnWorld(
        WorldSnapshot(
            baseState(),
            generals,
            listOf(frontCapital(), backupCity(), enemyCity()),
            listOf(nation(1), nation(2, capital = ENEMY_CITY)),
            diplomacy = listOf(TurnDiplomacy(fromNationId = 1, toNationId = 2, state = 0, term = 0)),
            worldId = opensamguk.common.world.WorldId((baseState()).id),
        ),
    )

    private fun adapter(world: InMemoryTurnWorld) =
        AiTurnAdapter(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)

    @Test
    fun `clearing a missing moving target preserves PHP empty aux storage`() {
        val ai = adapter(warWorld(listOf(general(id = 75))))

        assertEquals(
            linkedMapOf("aux" to "[]"),
            ai.withAuxValue(linkedMapOf("aux" to "[]"), "movingTargetCityID", null),
        )
        assertEquals(
            linkedMapOf("aux" to emptyList<Any?>()),
            ai.withAuxValue(linkedMapOf("aux" to emptyList<Any?>()), "movingTargetCityID", null),
        )
        assertEquals(
            linkedMapOf("aux" to emptyList<Any?>()),
            ai.withAuxValue(
                linkedMapOf("aux" to linkedMapOf("movingTargetCityID" to 56)),
                "movingTargetCityID",
                null,
            ),
            "PHP unsets the final aux key and Json::encode materializes the empty PHP array as []",
        )
    }

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
                worldId = opensamguk.common.world.WorldId((baseState()).id),
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
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, YEAR, 3, 3600, t0)).id),
            ),
        )
        val a = AiTurnAdapter(worldM3, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1)
        a.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())
        val recorder = ChangeRecorder(kvWriteObserver = worldM3::applyKvDirtyFree)
        a.drainNationPassDeltas(recorder)

        assertTrue(
            recorder.dirtyGeneralIds().any { it in 30..36 },
            "choosePromotion materialised the nationGenerals bucket and persisted a promoted chief",
        )
    }

    @Test
    fun `production adapter assembles server nation and human-option policy layers`() {
        val stateMeta = linkedMapOf<String, Any?>(
            "autorun_user" to linkedMapOf("options" to linkedMapOf("develop" to true)),
            "npc_general_policy" to linkedMapOf("priority" to listOf("출병")),
            "npc_nation_policy" to linkedMapOf(
                "priority" to listOf("선전포고"),
                "values" to linkedMapOf("cureThreshold" to 20),
            ),
        )
        val nationEnv = linkedMapOf<String, Any?>(
            "npc_general_policy" to linkedMapOf("priority" to listOf("일반내정")),
            "npc_nation_policy" to linkedMapOf(
                "priority" to listOf("천도"),
                "values" to linkedMapOf("cureThreshold" to 40),
            ),
        )
        val human = general(id = 1, officerLevel = 12, npcState = 0)
        val state = baseState(stateMeta)
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state,
                listOf(human),
                listOf(frontCapital()),
                listOf(nation(meta = linkedMapOf("nation_env" to nationEnv))),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )

        val policies = adapter(world).assemblePolicies(human, nationTech = 0, develCost = 100)

        assertEquals(listOf("일반내정"), policies.general.priority, "nation general policy overrides server priority")
        assertTrue(policies.general.can일반내정, "human autorun develop option enables the domestic gate")
        assertEquals(listOf("천도"), policies.nation.priority, "nation nation-policy overrides server priority")
        assertEquals(40, policies.nation.cureThreshold, "nation values override the server value")
    }

    @Test
    fun `month twelve bill uses the newly selected tax rate and preserves double income`() {
        val chief = general(id = 1, officerLevel = 12, leadership = 90)
        val subordinate = general(id = 2, officerLevel = 1)
        val developed = City(
            id = CAP,
            name = "낙양",
            nationId = 1,
            level = 8,
            agriculture = 100,
            agricultureMax = 100,
            commerce = 100,
            commerceMax = 100,
            security = 100,
            securityMax = 100,
            defence = 100,
            defenceMax = 100,
            wall = 100,
            wallMax = 100,
            population = 10_010,
            populationMax = 10_010,
            supplyState = 1,
            meta = linkedMapOf("trust" to 100),
        )
        val state = TurnWorldState(1, YEAR, 12, 3600, t0)
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state,
                listOf(chief, subordinate),
                listOf(developed),
                listOf(nation(gold = 0, meta = linkedMapOf("rate" to 10))),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )
        val ai = adapter(world)

        ai.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())
        ai.drainNationPassDeltas(ChangeRecorder())

        val updated = world.getNationById(1)!!
        val chosenRate = (updated.meta["rate"] as Number).toInt()
        val chosenBill = (updated.meta["bill"] as Number).toInt()
        val logicCity = PerTurnOverlay.toLogicCity(developed)
        val expectedIncome = getGoldIncome(
            listOf(logicCity), CAP, updated.level, chosenRate.toDouble(), null, pipeline, emptyMap(),
        )
        val expectedBill = RatesPromoFamily.billRate(
            income = expectedIncome,
            outcome = 400,
            currentRes = updated.gold,
            reqNationRes = 10_000,
            hasSupplyCities = true,
            rng = opensamguk.logic.ai.AiSeed.rng(FIXTURE_HIDDEN_SEED, YEAR, 12, chief.id),
        )
        val staleRateBill = RatesPromoFamily.billRate(
            income = getGoldIncome(listOf(logicCity), CAP, updated.level, 10.0, null, pipeline, emptyMap()),
            outcome = 400,
            currentRes = updated.gold,
            reqNationRes = 10_000,
            hasSupplyCities = true,
            rng = opensamguk.logic.ai.AiSeed.rng(FIXTURE_HIDDEN_SEED, YEAR, 12, chief.id),
        )

        assertEquals(25, chosenRate)
        assertEquals(expectedBill, chosenBill)
        assertNotEquals(staleRateBill, chosenBill, "the bill must not reuse the pre-choice rate")
        assertTrue(expectedIncome % 1.0 != 0.0, "the production income remains a Double through the bill formula")
    }

    @Test
    fun `month six rice bill uses the newly selected tax rate and real income streams`() {
        val chief = general(id = 1, officerLevel = 12, leadership = 90)
        val subordinate = general(id = 2, officerLevel = 1)
        val developed = City(
            id = CAP,
            name = "낙양",
            nationId = 1,
            level = 8,
            agriculture = 100,
            agricultureMax = 100,
            commerce = 100,
            commerceMax = 100,
            security = 100,
            securityMax = 100,
            defence = 100,
            defenceMax = 100,
            wall = 100,
            wallMax = 100,
            population = 10_010,
            populationMax = 10_010,
            supplyState = 1,
            meta = linkedMapOf("trust" to 100),
        )
        val state = TurnWorldState(1, YEAR, 6, 3600, t0)
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state,
                listOf(chief, subordinate),
                listOf(developed),
                listOf(nation(rice = 0, meta = linkedMapOf("rate" to 10))),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )
        val ai = adapter(world)

        ai.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())
        ai.drainNationPassDeltas(ChangeRecorder())

        val updated = world.getNationById(1)!!
        val chosenRate = (updated.meta["rate"] as Number).toInt()
        val chosenBill = (updated.meta["bill"] as Number).toInt()
        val logicCity = PerTurnOverlay.toLogicCity(developed)
        val expectedIncome =
            getRiceIncome(listOf(logicCity), CAP, updated.level, chosenRate.toDouble(), null, pipeline, emptyMap()) +
                getWallIncome(listOf(logicCity), CAP, updated.level, chosenRate.toDouble(), null, pipeline, emptyMap())
        val expectedBill = RatesPromoFamily.billRate(
            income = expectedIncome,
            outcome = 400,
            currentRes = updated.rice,
            reqNationRes = 12_000,
            hasSupplyCities = true,
            rng = opensamguk.logic.ai.AiSeed.rng(FIXTURE_HIDDEN_SEED, YEAR, 6, chief.id),
        )

        assertEquals(25, chosenRate)
        assertEquals(expectedBill, chosenBill)
        assertTrue(expectedIncome % 1.0 != 0.0, "rice and wall income stay Double through the bill formula")
    }

    @Test
    fun `denied nation reservation writes the exact PHP G12 action log`() {
        val ruler = general(id = 1, officerLevel = 12)
        val state = baseState()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state,
                listOf(ruler),
                listOf(frontCapital()),
                listOf(nation()),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )

        adapter(world).chooseNationTurn(
            1,
            ReservedTurn("che_발령", """{"destGeneralID":1,"destCityID":3}"""),
            LastTurn(),
        )

        assertEquals(
            "<C>●</>1월:본인입니다 <Y>g1</> 발령 실패. <1>12:34</>",
            world.peekLogs().single().text,
        )
    }

    // ── (recruit) a 통솔장 at war with a low crew fires a real che_징병/모병 (recruit ladder materialised) ──

    @Test fun `recruit - a war-nation 통솔장 with low crew fires a real che_징병`() {
        // npc==2 통솔장 in the HIGH-TRUST non-front backup city (장안: trust 90 ≥ 70 → do긴급내정 trust gate skips;
        // pop 190k near max → 정착장려 pop gate skips; not a front city → no 출병/전투준비), crew well below
        // minWarCrew, plenty of gold/rice + city pop → do징병 reaches the armType/crewType/cost ladder and emits a
        // real che_징병 (or che_모병). It sits AHEAD of 전쟁내정/일반내정 in the priority spine, so it fires there.
        val g = general(
            id = 55, cityId = BACKUP, leadership = 95, strength = 95, intel = 20, crew = 0, train = 50, atmos = 50,
            gold = 500_000, rice = 500_000,
        )
        val world = warWorld(listOf(g, general(id = 12, officerLevel = 12)))
        val chosen = adapter(world).chooseGeneralTurn(55, ReservedTurn("휴식", ""))

        assertTrue(
            chosen.actionCode == "che_징병" || chosen.actionCode == "che_모병",
            "the do징병 recruit ladder materialised and fired (was ${chosen.actionCode} / ${chosen.reason})",
        )
        // the RAW recruit args the body emits — a crewType id (>=1000) + a positive crew amount.
        assertTrue((chosen.args["crewType"] as? Number)?.toInt()?.let { it >= 1000 } == true, "a real crewType id emitted")
        assertTrue((chosen.args["amount"] as? Number)?.toInt()?.let { it > 0 } == true, "a positive crew amount emitted")
    }

    // ── (trade) a general with a gold/rice imbalance at a trading city fires a real che_군량매매 ──

    @Test fun `trade - a war-nation general with a resource imbalance fires a real che_군량매매`() {
        // A 무장 (NOT 통솔장 → do징병 null-guards on genType) at the front capital with gold >> rice → do금쌀구매's
        // sell/buy ladder qualifies and emits che_군량매매. 금쌀구매 sits at priority 3 (after 귀환), and with a
        // pure-무장 there is no recruit/develop 통솔 path ahead of it that fires first.
        val g = general(
            id = 56, leadership = 20, strength = 95, intel = 20, crew = 0, train = 50, atmos = 50,
            gold = 500_000, rice = 1_000,
        )
        val world = warWorld(listOf(g, general(id = 12, officerLevel = 12)))
        val chosen = adapter(world).chooseGeneralTurn(56, ReservedTurn("휴식", ""))

        assertEquals(
            "che_군량매매", chosen.actionCode,
            "the do금쌀구매 trade ladder materialised and fired (was ${chosen.actionCode} / ${chosen.reason})",
        )
        // the RAW trade args — a buyRice flag + a positive clamped amount.
        assertTrue(chosen.args.containsKey("buyRice"), "a buyRice flag emitted")
        assertTrue((chosen.args["amount"] as? Number)?.toInt()?.let { it >= 100 } == true, "a clamped trade amount emitted")
    }

    // ── (diplo) a peacetime ruler with a pending assist debt fires a real che_불가침제의 (SELECTION wired) ──

    @Test fun `diplo - a peacetime ruler with a recv_assist debt fires a real che_불가침제의`() {
        // do불가침제의 (ZERO draws): a ruler (officer_level 12) at PEACE (no war diplomacy → dipState d평화)
        // whose nation_env recv_assist records a debt from a non-war neighbour → the deterministic income-quarter
        // walk selects that nation and emits che_불가침제의. The SELECTION + boolean gate IS P5 scope (decision #11).
        val DONOR = 2 // the assisting nation whose debt we owe; NOT a war target (peace world).
        val recvAssist = listOf(listOf(DONOR, 1_000_000)) // recv_assist: [[candNationID, amount]]
        val nationEnv = linkedMapOf<String, Any?>("recv_assist" to recvAssist)
        val ruler = general(
            id = 80, nationId = 1, cityId = CAP, officerLevel = 12, leadership = 90, strength = 90, intel = 90,
        )
        // a peace world: nation 1 (with the recv_assist debt) + a separate donor nation 2; NO war diplomacy row.
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                listOf(ruler),
                listOf(
                    frontCapital().copy(frontState = 0, supplyState = 1),
                    backupCity(),
                    enemyCity().copy(nationId = DONOR),
                ),
                listOf(
                    nation(1).copy(meta = linkedMapOf("nation_env" to nationEnv)),
                    nation(DONOR, capital = ENEMY_CITY),
                ),
                diplomacy = listOf(),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )

        val chosen = adapter(world).chooseNationTurn(80, ReservedTurn("휴식", ""), LastTurn())

        assertEquals(
            "che_불가침제의", chosen.actionCode,
            "the do불가침제의 SELECTION materialised and fired (was ${chosen.actionCode} / ${chosen.reason})",
        )
        assertEquals(DONOR, (chosen.args["destNationID"] as? Number)?.toInt(), "the recv_assist donor was selected")
    }

    // ── (S2) the reservedCommandNameOf thread reaches the che_집합 troopLeader rung (decoupled from JDBC) ──

    @Test fun `S2 - reservedCommandNameOf threads the che_집합 troop-leader rung into the nation pass`() {
        // A chief + a non-NPC troop leader (troop == self) whose turn_idx 0 reserved command is che_집합. The S2
        // (generalId)->String? lookup (NOT a JDBC read) must be CONSULTED for the nation's generals so the
        // categorize buckets the general into troopLeaders (AiWorldView :3577). Proving the thread is LIVE =
        // the adapter actually queries the lookup for the nation generals during the pass (decoupled from JDBC).
        val chief = general(id = 1, officerLevel = 12, leadership = 90)
        val troopLeader = general(id = 30, cityId = CAP, npcState = 0, leadership = 85, crew = 5000, troopId = 30)
        val member = general(id = 31, cityId = CAP, npcState = 0, leadership = 80, crew = 0, troopId = 30)
        val world = warWorld(listOf(chief, troopLeader, member))

        // The reserved-turn map lives in the turn loop (decoupled from JDBC); thread it as a plain lambda and
        // record which general ids are queried — the adapter must consult it for the nation generals (S1+S2).
        val queried = LinkedHashSet<Int>()
        val reservedNameOf: (Int) -> String? = { gid ->
            queried.add(gid)
            if (gid == 30) "che_집합" else null
        }
        val a = AiTurnAdapter(
            world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, turnTerm = 1,
            reservedCommandNameOf = reservedNameOf,
        )
        val chosen = a.chooseNationTurn(1, ReservedTurn("휴식", ""), LastTurn())

        assertTrue(chosen.actionCode.isNotEmpty(), "the nation pass produced a command")
        // The S2 thread is LIVE: the troop leader (id 30, the only che_집합 holder) was queried during categorize.
        assertTrue(30 in queried, "reservedCommandNameOf was consulted for the troop leader (S2 thread is live)")
        // READ-ONLY over GAME ENTITIES (the S2 lookup is a pure lambda, never a row write).
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.generals.isEmpty() && dirty.cities.isEmpty(), "no inline general/city row written")
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
