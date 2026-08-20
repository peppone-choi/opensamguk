package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1 Task F3 — the [ReservedTurnHandler] end-to-end: full constraints (the SAME `:logic` library) →
 * per-action RNG → resolve → [ChangeRecorder] (single dirty source) → dirty-free apply + logs.
 *
 * Determinism is asserted structurally (same world + same seed → identical post-state). The exact
 * float golden is pinned by the AREA G PHP oracle (G1/G2) once the captured `hiddenSeed` lands —
 * here `FIXTURE_HIDDEN_SEED` is the placeholder the resolve tests use.
 */
class ReservedTurnHandlerTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    // FIXTURE INPUT — replaced by the G1-captured golden hiddenSeed (UniqueConst::$hiddenSeed) before lock.
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    private fun general(
        id: Int = 42,
        nationId: Int = 1,
        cityId: Int = 7,
        gold: Int = 100_000,
        intel: Int = 80,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = intel),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = gold,
        rice = 1000,
        injury = 0,
        turnTime = t0,
        // killturn>0: 살아있는 장수는 양수 killturn을 가진다(PHP는 $gameStor->killturn에서 시드). strict-< 교정
        // 후 drain 꼬리(updateTurnTime, TurnExecutionHelper.php:185)의 killturn<=0 kill 게이트가 동작하므로,
        // 기본 env baselineKillturn=0에서 killturn 미설정(0) 장수는 tail에서 kill된다 → 양수로 생존시킨다.
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0, "killturn" to 80),
    )

    private fun city(
        id: Int = 7,
        nationId: Int = 1,
        agri: Int = 1000,
        agriMax: Int = 20000,
        supplyState: Int = 1,
        frontState: Int = 0,
    ) = City(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = 5,
        agriculture = agri,
        agricultureMax = agriMax,
        commerce = 1000,
        commerceMax = 20000,
        supplyState = supplyState,
        frontState = frontState,
        meta = linkedMapOf("trust" to 50),   // INTEGER-valued trust (G1 invariant), typed Double in logic
    )

    private fun nation(id: Int = 1, level: Int = 2, capital: Int = 99) =
        Nation(id = id, name = "n$id", color = "#000", level = level, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
        config = linkedMapOf("mapName" to "che"),
    )

    private fun worldWith(
        generals: List<TurnGeneral> = listOf(general()),
        cities: List<City> = listOf(city()),
        nations: List<Nation> = listOf(nation()),
        meta: Map<String, Any?> = emptyMap(),
    ) = InMemoryTurnWorld(WorldSnapshot(baseState(), generals, cities, nations, worldId = opensamguk.common.world.WorldId((baseState()).id)))
        .let { world ->
            if (meta.isEmpty()) world else InMemoryTurnWorld(WorldSnapshot(baseState().copy(meta = meta), generals, cities, nations, worldId = opensamguk.common.world.WorldId((baseState().copy(meta = meta)).id)))
        }

    private fun handlerFor(world: InMemoryTurnWorld, scenario: Int = 0) =
        ReservedTurnHandler(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, scenario = scenario)

    private fun forcedLotteryMeta(): Map<String, Any?> = linkedMapOf(
        "init_year" to YEAR,
        "init_month" to MONTH,
        "minMonthToAllowInheritItem" to 0,
        "allItems" to linkedMapOf(
            "horse" to linkedMapOf("che_명마_15_적토마" to 1),
            "weapon" to linkedMapOf("che_무기_15_의천검" to 1),
        ),
    )

    private fun withForcedLottery(general: TurnGeneral): TurnGeneral = general.copy(
        userId = "777",
        meta = LinkedHashMap(general.meta).apply {
            put("aux", linkedMapOf("inheritRandomUnique" to "MARK"))
        },
    )

    private fun uniqueItemCount(general: TurnGeneral): Int = listOf(
        general.role.items.horse,
        general.role.items.weapon,
        general.role.items.book,
        general.role.items.item,
    ).count { it != null && it != "None" }

    @Test
    fun `PHP unique lottery call-site contract is exhaustive`() {
        val callSites = checkNotNull(javaClass.getResourceAsStream("/parity/php-unique-item-lottery-call-sites.txt"))
            .bufferedReader()
            .useLines { lines -> lines.filter { it.isNotBlank() }.toList() }
        val codes = callSites.map { it.substringBefore('|') }

        assertEquals(34, callSites.size)
        assertEquals(33, codes.toSet().size)
        assertEquals(2, codes.count { it == "che_인재탐색" })
        assertEquals(codes.toSet(), ReservedTurnHandler.UNIQUE_ITEM_LOTTERY_COMMAND_CODES)
        assertTrue("cr_건국" in codes)
        assertTrue("cr_맹훈련" in codes)
        assertFalse("che_선양" in codes)
    }

    @Test
    fun `city trust materializes through the MariaDB FLOAT text boundary`() {
        assertEquals(81.1021, ReservedTurnHandler.materializeMariaDbFloat(81.10215004199256))
        assertEquals(83.0571, ReservedTurnHandler.materializeMariaDbFloat(83.05710274525107))
        assertEquals(84.1945, ReservedTurnHandler.materializeMariaDbFloat(84.19451884333633))
        assertEquals(86.2042, ReservedTurnHandler.materializeMariaDbFloat(86.20424282211812))
    }

    @Test
    fun `nation tech materializes at every MariaDB FLOAT write`() {
        val scores = listOf(18, 18, 18, 22, 22, 22)
        val stored = scores.runningFold(500.0) { tech, score ->
            ReservedTurnHandler.materializeMariaDbFloat(tech + score / 19.0)
        }

        assertEquals(
            listOf(500.0, 500.947, 501.894, 502.841, 503.999, 505.157, 506.315),
            stored,
        )
    }

    @Test
    fun `cityless general can consume a reserved rest turn without resolving a city`() {
        val world = worldWith(
            generals = listOf(general(id = 1012, nationId = 8, cityId = 0)),
            cities = emptyList(),
            nations = listOf(nation(id = 8, level = 0, capital = 0)),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(1012, "휴식", YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "an actual reserved 휴식 is not a command fallback")
        assertEquals("휴식", outcome.definition.key)
        assertNull(outcome.denyReason)
        assertFalse(handler.recorder.isDirty, "cityless 휴식 must not invent a city delta")
    }

    @Test
    fun `available general che_농지개간 increases agriculture decreases gold pushes log and records dirty`() {
        val noLevelCrossGeneral = general().copy(
            experience = 1_000,
            dedication = 1_000,
            meta = linkedMapOf(
                "explevel" to 10,
                "dedlevel" to 4,
                "intel_exp" to 3,
                "max_domestic_critical" to 0.0,
                "killturn" to 80,
            ),
        )
        val world = worldWith(generals = listOf(noLevelCrossGeneral))
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "AVAILABLE general resolves the requested action, not the fallback")
        assertEquals("che_농지개간", outcome.definition.key)
        assertNull(outcome.denyReason)
        assertEquals(1, outcome.logs.size, "exactly one action log (no level cross in P1)")

        // post-state visible in the world (dirty-free apply wrote the engine rows)
        val postCity = world.getCityById(7)!!
        val postGeneral = world.getGeneralById(42)!!
        assertTrue(postCity.agriculture > 1000, "agriculture increased: ${postCity.agriculture}")
        assertTrue(postGeneral.gold < 100_000, "gold decreased by reqGold: ${postGeneral.gold}")
        assertEquals(4, (postGeneral.meta["intel_exp"] as Number).toInt(), "intel_exp incremented 3 -> 4")

        // ChangeRecorder is the SINGLE dirty source — the resolver never touched world.updateGeneral/updateCity
        assertTrue(handler.recorder.isDirty, "recorder marked the mutation dirty")
        assertEquals(setOf(42), handler.recorder.dirtyGeneralIds())
        assertEquals(setOf(7), handler.recorder.dirtyCityIds())

        // the world's OWN dirty set stays empty (dirty-free apply path — flush reads the recorder, not the world)
        val worldDirty = world.consumeDirtyState()
        assertTrue(worldDirty.generals.isEmpty(), "dirty-free apply never marks the world general dirty")
        assertTrue(worldDirty.cities.isEmpty(), "dirty-free apply never marks the world city dirty")
        assertEquals(1, worldDirty.logs.size, "the action log was pushed to the world")
    }

    @Test
    fun `reserved arg command binds a PHP numeric string before resolve`() {
        val world = worldWith(
            generals = listOf(general(gold = 0).copy(rice = 2_000)),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_군량매매", """{"buyRice":false,"amount":"1234"}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        assertEquals(1_200, outcome.args["amount"])
        assertEquals(800, world.getGeneralById(42)!!.rice)
        assertEquals(1_188, world.getGeneralById(42)!!.gold)
        assertTrue(handler.recorder.dirtyGeneralIds().contains(42))
    }

    @Test
    fun `invalid reserved args fall back without reaching the resolver`() {
        val world = worldWith(
            generals = listOf(general(gold = 0).copy(rice = 2_000)),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_군량매매", """{"buyRice":false,"amount":"oops"}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertTrue(outcome.fellBack)
        assertEquals("휴식", outcome.definition.key)
        assertEquals("인자가 올바르지 않습니다.", outcome.denyReason)
        assertEquals(2_000, world.getGeneralById(42)!!.rice)
        assertEquals(0, world.getGeneralById(42)!!.gold)
        assertFalse(handler.recorder.isDirty)
        assertEquals(
            "<C>●</>${MONTH}월:인자가 올바르지 않습니다. 군량매매 실패. <1>12:34</>",
            world.consumeDirtyState().logs.single().text,
        )
    }

    @Test
    fun `rare equipment sale records PHP global history`() {
        val actor = general(gold = 1_000).copy(
            name = "최강자",
            role = GeneralRole(items = GeneralItems(horse = "che_명마_15_적토마")),
        )
        val world = worldWith(generals = listOf(actor))
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_장비매매", """{"itemType":"horse","itemCode":"None"}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        val logs = world.consumeDirtyState().logs
        val actionIndex = logs.indexOfFirst {
            it.scope == "global" && it.category == "action" && it.text.contains("적토마(+15)</>를 판매했습니다!")
        }
        val historyIndex = logs.indexOfFirst {
            it.scope == "global" &&
                it.category == "history" &&
                it.text == "<C>●</>${YEAR}년 ${MONTH}월:<R><b>【판매】</b></><D><b>n1</b></>의 <Y>최강자</>가 <C>적토마(+15)</>를 판매했습니다!"
        }
        assertTrue(actionIndex >= 0, "rare sale must emit the PHP global action line")
        assertTrue(historyIndex >= 0, "rare sale must emit the PHP YEAR_MONTH global history line")
        assertEquals(
            actionIndex + 1,
            historyIndex,
            "PHP emits rare-sale history immediately after the global sale action, before tail logs and lottery",
        )
    }

    @Test
    fun `rare equipment sale by a neutral general uses 재야 in global history`() {
        val actor = general(gold = 1_000, nationId = 0).copy(
            name = "방랑객",
            role = GeneralRole(items = GeneralItems(horse = "che_명마_15_적토마")),
        )
        val world = worldWith(generals = listOf(actor), cities = listOf(city(nationId = 0)), nations = emptyList())
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_장비매매", """{"itemType":"horse","itemCode":"None"}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        assertTrue(
            world.consumeDirtyState().logs.any {
                it.scope == "global" &&
                    it.category == "history" &&
                    it.text == "<C>●</>${YEAR}년 ${MONTH}월:<R><b>【판매】</b></><D><b>재야</b></>의 <Y>방랑객</>이 <C>적토마(+15)</>를 판매했습니다!"
            },
        )
    }

    @Test
    fun `unique lottery uses active scenario allItems catalog and insertion order`() {
        fun resolveWith(allItems: Map<String, Map<String, Int>>): String {
            val actor = general(gold = 100_000).copy(
                userId = "777",
                meta = linkedMapOf(
                    "explevel" to 10,
                    "intel_exp" to 3,
                    "max_domestic_critical" to 0.0,
                    "killturn" to 80,
                    "aux" to linkedMapOf("inheritRandomUnique" to "MARK"),
                ),
            )
            val meta = linkedMapOf<String, Any?>(
                "init_year" to YEAR,
                "init_month" to MONTH,
                "minMonthToAllowInheritItem" to 0,
                "allItems" to allItems,
            )
            val world = worldWith(generals = listOf(actor), meta = meta)
            val handler = handlerFor(world, scenario = 905)

            val outcome = handler.handle(
                42,
                ReservedTurn("che_군량매매", """{"buyRice":false,"amount":100}"""),
                YEAR,
                MONTH,
                "12:34",
            )

            assertFalse(outcome.fellBack)
            return listOfNotNull(
                world.getGeneralById(42)!!.role.items.weapon,
                world.getGeneralById(42)!!.role.items.horse,
            ).single { it != "None" }
        }

        val weaponFirst = resolveWith(
            linkedMapOf(
                "weapon" to linkedMapOf("che_무기_15_의천검" to 1),
                "horse" to linkedMapOf("che_명마_15_적토마" to 1),
            ),
        )
        val horseFirst = resolveWith(
            linkedMapOf(
                "horse" to linkedMapOf("che_명마_15_적토마" to 1),
                "weapon" to linkedMapOf("che_무기_15_의천검" to 1),
            ),
        )

        assertEquals(
            setOf("che_무기_15_의천검", "che_명마_15_적토마"),
            setOf(weaponFirst, horseFirst),
            "scenario 905 active allItems insertion order must drive the weighted selection",
        )
    }

    @Test
    fun `forced unique refund logs exact PHP no-space inheritance log`() {
        val actor = general(gold = 100_000).copy(
            userId = "777",
            role = GeneralRole(items = GeneralItems(horse = "che_명마_15_적토마")),
            meta = linkedMapOf(
                "explevel" to 10,
                "intel_exp" to 3,
                "max_domestic_critical" to 0.0,
                "killturn" to 80,
                "aux" to linkedMapOf("inheritRandomUnique" to "MARK"),
            ),
        )
        val meta = linkedMapOf<String, Any?>(
            "init_year" to YEAR,
            "init_month" to MONTH,
            "minMonthToAllowInheritItem" to 0,
            "allItems" to linkedMapOf("horse" to linkedMapOf("che_명마_15_적토마" to 1)),
        )
        val world = worldWith(generals = listOf(actor), meta = meta)
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_군량매매", """{"buyRice":false,"amount":100}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        assertEquals(
            listOf("유니크를 얻을 공간이 없어 3000 포인트 반환"),
            handler.recorder.inheritanceLogInserts().map { it.text },
        )
        assertEquals(listOf("inheritPoint"), handler.recorder.inheritanceLogInserts().map { it.tag })
        val aux = world.getGeneralById(42)!!.meta["aux"] as Map<*, *>
        assertFalse(aux.containsKey("inheritRandomUnique"))
    }

    @Test
    fun `forced unique refund logs exact PHP no-available inheritance log`() {
        val actor = general(gold = 100_000).copy(
            userId = "777",
            meta = linkedMapOf(
                "explevel" to 10,
                "intel_exp" to 3,
                "max_domestic_critical" to 0.0,
                "killturn" to 80,
                "aux" to linkedMapOf("inheritRandomUnique" to "MARK"),
            ),
        )
        val meta = linkedMapOf<String, Any?>(
            "init_year" to YEAR,
            "init_month" to MONTH,
            "minMonthToAllowInheritItem" to 0,
            "allItems" to linkedMapOf(
                "horse" to linkedMapOf<String, Int>(),
                "weapon" to linkedMapOf<String, Int>(),
                "book" to linkedMapOf<String, Int>(),
                "item" to linkedMapOf<String, Int>(),
            ),
        )
        val world = worldWith(generals = listOf(actor), meta = meta)
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_군량매매", """{"buyRice":false,"amount":100}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        assertEquals(
            listOf("얻을 유니크가 없어 3000 포인트 반환"),
            handler.recorder.inheritanceLogInserts().map { it.text },
        )
        assertEquals(listOf("inheritPoint"), handler.recorder.inheritanceLogInserts().map { it.tag })
        val aux = world.getGeneralById(42)!!.meta["aux"] as Map<*, *>
        assertFalse(aux.containsKey("inheritRandomUnique"))
    }

    @Test
    fun `che_임관 consumes exactly one unique lottery after success`() {
        val actor = withForcedLottery(general(nationId = 0, cityId = 1))
        val lord = general(id = 50, nationId = 2, cityId = 9).copy(officerLevel = 12)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0))
        val world = worldWith(
            generals = listOf(actor, lord),
            cities = listOf(city(id = 1, nationId = 0), city(id = 9, nationId = 2)),
            nations = listOf(destNation),
            meta = forcedLotteryMeta(),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_임관", """{"destNationID":2}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        assertEquals(1, uniqueItemCount(world.getGeneralById(42)!!))
    }

    @Test
    fun `previously uncovered che_이동 consumes exactly one unique lottery after success`() {
        val actor = withForcedLottery(general(nationId = 1, cityId = 1)).copy(officerLevel = 12)
        val follower = general(id = 43, nationId = 1, cityId = 1)
        val world = worldWith(
            generals = listOf(actor, follower),
            cities = listOf(city(id = 1, nationId = 1), city(id = 9, nationId = 1)),
            nations = listOf(nation(id = 1, level = 0)),
            meta = forcedLotteryMeta(),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_이동", """{"destCityID":9}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertFalse(outcome.fellBack)
        assertEquals(9, world.getGeneralById(42)!!.cityId)
        assertEquals(9, world.getGeneralById(43)!!.cityId)
        assertEquals(1, uniqueItemCount(world.getGeneralById(42)!!))
    }

    @Test
    fun `failed che_훈련 consumes no unique lottery`() {
        val actor = withForcedLottery(general(nationId = 1, cityId = 1).copy(crew = 0))
        val world = worldWith(
            generals = listOf(actor),
            cities = listOf(city(id = 1, nationId = 1)),
            nations = listOf(nation(id = 1)),
            meta = forcedLotteryMeta(),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_훈련", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack)
        val post = world.getGeneralById(42)!!
        assertEquals(0, uniqueItemCount(post))
        @Suppress("UNCHECKED_CAST")
        val aux = post.meta["aux"] as Map<String, Any?>
        assertEquals("MARK", aux["inheritRandomUnique"])
    }

    @Test
    fun `schema integer rejects a fractional destination before constraints`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_임관", """{"destNationID":2.5}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertTrue(outcome.fellBack)
        assertEquals("인자가 올바르지 않습니다.", outcome.denyReason)
        assertFalse(handler.recorder.isDirty)
    }

    @Test
    fun `recruit rejects a negative amount before applying the minimum clamp`() {
        val world = worldWith()
        val handler = handlerFor(world)
        val before = world.getGeneralById(42)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_징병", """{"crewType":1100,"amount":-1}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertTrue(outcome.fellBack)
        assertEquals("인자가 올바르지 않습니다.", outcome.denyReason)
        assertEquals(before, world.getGeneralById(42))
        assertFalse(handler.recorder.isDirty)
    }

    @Test
    fun `unsupported active unit set is denied again by the daemon`() {
        val state = baseState().copy(
            config = linkedMapOf(
                "mapName" to "che",
                "map" to linkedMapOf("unitSet" to "che"),
                "unitSet" to "not-ported",
            ),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = listOf(general()),
                cities = listOf(city().copy(population = 50_000)),
                nations = listOf(nation()),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )
        val outcome = handlerFor(world).handle(
            42,
            ReservedTurn("che_징병", """{"crewType":1100,"amount":100}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertTrue(outcome.fellBack)
        assertEquals("현재 선택할 수 없는 병종입니다.", outcome.denyReason)
    }

    @Test
    fun `abdication rejects the actor as its own successor`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_선양", """{"destGeneralID":42}"""),
            YEAR,
            MONTH,
            "12:34",
        )

        assertTrue(outcome.fellBack)
        assertEquals("인자가 올바르지 않습니다.", outcome.denyReason)
        assertFalse(handler.recorder.isDirty)
    }

    @Test
    fun `recorded patch matches the applied world post-state`() {
        val world = worldWith()
        val handler = handlerFor(world)

        handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val cityPatch = handler.recorder.cityPatches().single()
        assertEquals(7, cityPatch.id)
        assertEquals(world.getCityById(7)!!.agriculture, cityPatch.columns["agriculture"],
            "recorder's agriculture patch equals the world's applied post-state")
    }

    @Test
    fun `blocked general non-owned city falls back to rest with deny reason and no economic mutation`() {
        // general's city is owned by nation 2, not the general's nation 1 → OccupiedCity Deny.
        val world = worldWith(
            generals = listOf(general(cityId = 7)),
            cities = listOf(city(id = 7, nationId = 2)),
            nations = listOf(nation(id = 1), nation(id = 2)),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack, "denied turn falls back to 휴식")
        assertEquals("휴식", outcome.definition.key)
        assertEquals("아국이 아닙니다.", outcome.denyReason, "OccupiedCity deny reason (PHP getFailString)")

        // no economic mutation
        assertEquals(1000, world.getCityById(7)!!.agriculture, "agriculture untouched on a denied turn")
        assertEquals(100_000, world.getGeneralById(42)!!.gold, "gold untouched on a denied turn")
        assertFalse(handler.recorder.isDirty, "nothing recorded dirty on a denied turn")

        // the deny-reason log was pushed (휴식-fallback log)
        val worldDirty = world.consumeDirtyState()
        assertEquals(1, worldDirty.logs.size, "the deny-reason log was pushed")
        assertTrue(worldDirty.logs.single().text.contains("아국이 아닙니다."), "deny log carries the reason")
    }

    @Test
    fun `insufficient gold falls back with the funds deny reason`() {
        val world = worldWith(generals = listOf(general(gold = 0)))
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack)
        assertEquals("자금이 모자랍니다.", outcome.denyReason, "ReqGeneralGold deny reason")
        assertEquals(0, world.getGeneralById(42)!!.gold, "gold untouched")
        assertFalse(handler.recorder.isDirty)
    }

    @Test
    fun `determinism same world and seed yield identical post-state across two runs`() {
        val worldA = worldWith()
        handlerFor(worldA).handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val worldB = worldWith()
        handlerFor(worldB).handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val a = worldA.getGeneralById(42)!!
        val b = worldB.getGeneralById(42)!!
        assertEquals(a.gold, b.gold)
        assertEquals(a.experience, b.experience)
        assertEquals(a.dedication, b.dedication)
        assertEquals(a.meta["intel_exp"], b.meta["intel_exp"])
        assertEquals(a.meta["max_domestic_critical"], b.meta["max_domestic_critical"])
        assertEquals(worldA.getCityById(7)!!.agriculture, worldB.getCityById(7)!!.agriculture)
    }

    @Test
    fun `full-mode env equals the precheck env for the same fixture (single shared env-builder)`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        // The precheck call site (E2 PrecheckStateViewFactory) builds its env through the SAME helper.
        val precheckEnv = LinkedHashMap(WorldEnvBuilder.commandEnvMap(YEAR, START_YEAR, MONTH, 1)).apply {
            this["ownCities"] = linkedMapOf(7 to 5)
            this["unitSet"] = "che"
            this["mapName"] = "che"
        }

        // key-for-key equality proves the one shared helper — neither call site can drift (P1 #7).
        assertEquals(precheckEnv, outcome.env, "full-mode env == precheck env (same WorldEnvBuilder)")
        assertEquals(precheckEnv.keys.toList(), outcome.env.keys.toList(), "env key order identical")
        assertEquals(YEAR, outcome.env["year"])
        assertEquals(START_YEAR, outcome.env["startYear"])
        assertEquals((YEAR - START_YEAR + 10) * 2, outcome.env["develCost"], "develCost = (year-startYear+10)*2")
    }

    @Test
    fun `che_인재탐색 creates discovered NPC and records active action inheritance through flush payload`() {
        val actor = general(id = 42, gold = 100_000).copy(
            userId = "777",
            name = "유비",
            stats = GeneralStats(leadership = 95, strength = 90, intelligence = 85, politics = 77, charm = 66),
            meta = linkedMapOf(
                "name" to "유비",
                "leadership_exp" to 0.0,
                "strength_exp" to 0.0,
                "intel_exp" to 0.0,
                "explevel" to 10,
                "killturn" to 80,
                "dex1" to 4,
                "dex2" to 3,
                "dex3" to 2,
                "dex4" to 1,
                "dex5" to 5,
            ),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState().copy(meta = linkedMapOf("maxgeneral" to 500, "develcost" to 52, "turnterm" to 60)),
                generals = listOf(actor),
                cities = listOf(city(id = 7, nationId = 1), city(id = 8, nationId = 0)),
                nations = listOf(nation(id = 1)),
                worldId = opensamguk.common.world.WorldId((baseState().copy(meta = linkedMapOf("maxgeneral" to 500, "develcost" to 52, "turnterm" to 60))).id),
            ),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_인재탐색", YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack)
        assertEquals("che_인재탐색", outcome.definition.key)
        assertEquals(2, world.listGenerals().size, "the discovered NPC is visible in the live world")
        val created = world.listGenerals().single { it.id != 42 }
        assertEquals(3, created.npcState)
        assertEquals(50, created.stats.politics, "scout NPC keeps the 5-stat raw politics contract")
        assertEquals(50, created.stats.charm, "scout NPC keeps the 5-stat raw charm contract")
        assertTrue((created.meta["dex5"] as Number).toInt() >= 0, "dex5 is carried in meta for JSON raw round-trip")

        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())
        assertEquals(listOf(created.id), payload.createdGenerals.map { it.columns["id"] })
        assertEquals(listOf("inheritance_777"), payload.inheritanceKvWrites.map { it.namespace })
        assertEquals(listOf("active_action"), payload.inheritanceKvWrites.map { it.key })
        assertTrue(
            (payload.inheritanceKvWrites.single().value as List<*>).first() as Double >= 1.0,
            "PHP valueFit(sqrt(1 / foundProp), 1) lower-bound is persisted",
        )
    }

    @Test
    fun `inheritance point increases are ordered cumulative deltas with key multiplier`() {
        val recorder = ChangeRecorder()
        val aux = mapOf("source" to "same")

        recorder.recordInheritancePointIncrease(777, "active_action", 1.0, null)
        recorder.recordInheritancePointIncrease(777, "active_action", 2.0, null)
        recorder.recordInheritancePointIncrease(777, "active_action", 1.0, aux)
        recorder.recordInheritancePointIncrease(777, "active_action", 1.0, aux)
        recorder.recordInheritancePointIncrease(777, "unifier", 250.0, null)

        val writes = recorder.inheritanceKvWrites()
        assertEquals(
            listOf(3.0, 9.0, 3.0, 6.0, 250.0),
            writes.map { (it.value as List<*>)[0] as Double },
            "increase writes must carry old + value * InheritanceKey coefficient in emission order",
        )
        assertEquals(
            listOf(null, null, aux, aux, null),
            writes.map { (it.value as List<*>)[1] },
            "aux is rewritten only when the requested aux differs from the pending aux",
        )
        assertEquals(
            listOf("active_action", "active_action", "active_action", "active_action", "unifier"),
            writes.map { it.key },
            "increment deltas stay ordered instead of collapsing to an absolute overwrite",
        )
    }

    @Test
    fun `che_임관 preloads the destination nation and increments gennum`() {
        val actor = general(nationId = 0, cityId = 7).copy(troopId = 42, userId = "777")
        val lord = general(id = 50, nationId = 2, cityId = 8).copy(officerLevel = 12)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0))
        val world = worldWith(
            generals = listOf(actor, lord),
            cities = listOf(city(id = 7, nationId = 0), city(id = 8, nationId = 2)),
            nations = listOf(destNation),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, ReservedTurn("che_임관", """{"destNationID":2}"""), YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "임관 should pass full constraints: ${outcome.denyReason}")
        val joined = world.getGeneralById(42)!!
        assertEquals(2, joined.nationId)
        assertEquals(1, joined.officerLevel)
        assertEquals(8, joined.cityId, "지정 국가 임관은 목적 국가 군주의 도시로 이동한다")
        assertEquals(0, joined.troopId, "che_임관은 PHP처럼 troop을 0으로 리셋한다")
        assertEquals(2, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertEquals(setOf(2), handler.recorder.dirtyNationIds())
        assertEquals(listOf("active_action"), handler.recorder.inheritanceKvWrites().map { it.key })
    }

    @Test
    fun `che_랜덤임관 loads live candidate nations and increments gennum`() {
        val actor = general(nationId = 0, cityId = 7).copy(
            npcState = 2,
            userId = "777",
            meta = general().meta + mapOf("affinity" to 40, "name" to "g42"),
        )
        val lord = general(id = 50, nationId = 2, cityId = 8).copy(officerLevel = 12, npcState = 2)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0, "affinity" to 45))
        val world = worldWith(
            generals = listOf(actor, lord),
            cities = listOf(city(id = 7, nationId = 0), city(id = 8, nationId = 2)),
            nations = listOf(destNation),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(42, ReservedTurn("che_랜덤임관", ""), YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "랜덤임관 should pass full constraints: ${outcome.denyReason}")
        val joined = world.getGeneralById(42)!!
        assertEquals(2, joined.nationId)
        assertEquals(1, joined.officerLevel)
        assertEquals(8, joined.cityId)
        assertEquals(2, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertTrue(outcome.logs.none { it.contains("임관 가능한 국가가 없습니다.") })
        assertEquals(setOf(2), handler.recorder.dirtyNationIds())
        // PHP InheritancePointManager.php:261-270 skips every npc >= 2 even when owner is present.
        assertTrue(handler.recorder.inheritanceKvWrites().isEmpty())
    }

    @Test
    fun `che_장수대상임관 follows the target general city and keeps troop`() {
        val actor = general(nationId = 0, cityId = 7).copy(troopId = 42)
        val target = general(id = 50, nationId = 2, cityId = 8).copy(officerLevel = 1)
        val lord = general(id = 51, nationId = 2, cityId = 9).copy(officerLevel = 12)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0))
        val world = worldWith(
            generals = listOf(actor, target, lord),
            cities = listOf(city(id = 7, nationId = 0), city(id = 8, nationId = 2), city(id = 9, nationId = 2)),
            nations = listOf(destNation),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, ReservedTurn("che_장수대상임관", """{"destGeneralID":50}"""), YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "장수대상임관 should pass full constraints: ${outcome.denyReason}")
        val joined = world.getGeneralById(42)!!
        assertEquals(2, joined.nationId)
        assertEquals(1, joined.officerLevel)
        assertEquals(8, joined.cityId, "장수대상임관은 목적 장수의 도시를 따른다")
        assertEquals(42, joined.troopId, "che_장수대상임관은 PHP처럼 troop을 유지한다")
        assertEquals(2, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertEquals(setOf(2), handler.recorder.dirtyNationIds())
    }

    @Test
    fun `lifecycle drains all due generals in one pass`() {
        val world = worldWith(
            generals = listOf(general(id = 42), general(id = 43)),
            cities = listOf(city(id = 7)),
        )
        val handler = handlerFor(world)
        val lifecycle = TurnDaemonLifecycle(world, handler) {
            opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn("che_농지개간", "")
        }

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. t0보다 미래 시각을 넘겨 두 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        val runTime = t0.plusSeconds(1)  // both generals' turnTime(t0) < runTime → due
        val handled = lifecycle.runTick(runTime)

        assertEquals(2, handled.size, "both due generals processed in one pass")
        assertEquals(listOf(42, 43), handled.map { it.generalId }, "deterministic order: ascending id")
        assertNotNull(handled.first { it.generalId == 42 })
        // both generals share the one city; the recorder accumulates across the pass (one flush boundary)
        assertEquals(setOf(42, 43), handler.recorder.dirtyGeneralIds())
        assertEquals(setOf(7), handler.recorder.dirtyCityIds())
    }
}
