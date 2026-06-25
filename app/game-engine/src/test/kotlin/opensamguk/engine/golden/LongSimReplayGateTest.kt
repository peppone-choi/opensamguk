package opensamguk.engine.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.constants.EffectiveGameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.AiTurnAdapter
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LifecycleEnv
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.tick.ServerClock
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import java.time.Instant

/**
 * Phase 3 — 12-month long-simulation replay gate.
 *
 * Loads the PHP golden baseline + capture points, materializes [InMemoryTurnWorld], drives the same
 * months with production wiring minus flush/Redis/JPA, and asserts state/draw parity at each capture
 * point.
 */
class LongSimReplayGateTest {

    private companion object {
        const val BASELINE = "golden/longsim/capture-00-baseline.json"
        const val MANIFEST = "golden/longsim/manifest_longsim.json"
    }

    private fun loadResource(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    private fun loadJson(name: String): JsonObject =
        Json.parseToJsonElement(loadResource(name)).jsonObject

    @Test
    fun `PHP long-sim fixtures expose the replay oracle fields`() {
        val manifest = loadJson(MANIFEST)
        val baseline = LongSimWorldMaterializer.loadBaseline(BASELINE)

        assertEquals(2, baseline.state["nation"]!!.jsonArray.size, "pristine scenario 1010 starts with two nations")

        for (point in manifest["points"]!!.jsonArray.map { it.jsonObject }) {
            val capture = loadJson("golden/longsim/${point["file"]!!.jsonPrimitive.content}")

            assertEquals(point["gameMonths"]!!.jsonPrimitive.int, capture["gameMonths"]!!.jsonPrimitive.int)
            assertEquals(point["year"]!!.jsonPrimitive.int, capture["year"]!!.jsonPrimitive.int)
            assertEquals(point["month"]!!.jsonPrimitive.int, capture["month"]!!.jsonPrimitive.int)
            assertEquals(
                point["rngDraws"]?.jsonPrimitive?.intOrNull,
                capture["monthlyRngDraws"]?.jsonPrimitive?.intOrNull,
                "manifest rngDraws mirrors capture monthlyRngDraws",
            )
            assertNotNull(capture["monthlySeedString"]?.jsonPrimitive?.contentOrNull)
            assertNotNull(capture["state"]?.jsonObject)
        }
    }

    @Disabled(
        "Phase 3 blocked: PHP 12-month snapshot reaches 12 nations, while the current Kotlin replay reaches 5. " +
            "Keep this as the executable first-divergence gate until AI/founding long-sim parity closes.",
    )
    @Test
    fun `12 month structural replay matches PHP golden`() {
        val manifest = loadJson(MANIFEST)
        val baseline = LongSimWorldMaterializer.loadBaseline(BASELINE)
        val world = LongSimWorldMaterializer.materializeWorld(baseline)
        val hiddenSeed = baseline.hiddenSeed
        val startYear = baseline.startYear
        val turnterm = baseline.turnterm
        val startTime = LongSimWorldMaterializer.parseTurnTime(
            baseline.state["game_env"]!!.jsonObject["starttime"]?.jsonPrimitive?.contentOrNull,
        )
        val mapName = baseline.state["game_env"]!!.jsonObject["map"]?.jsonPrimitive?.contentOrNull ?: "che"
        val scenario = baseline.state["game_env"]!!.jsonObject["scenario"]?.jsonPrimitive?.intOrNull ?: 1010

        val pipeline = GeneralActionPipeline()
        val registry = CommandRegistry(pipeline)
        val ai = AiTurnAdapter(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            turnTerm = turnterm,
            pipeline = pipeline,
            reservedCommandNameOf = { "che_휴식" },
        )
        val recorder = ChangeRecorder()
        val handler = ReservedTurnHandler(
            world = world,
            registry = registry,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            scenario = scenario,
            turnTerm = turnterm,
            recorder = recorder,
            aiHook = { generalId, reserved -> ai.chooseGeneralTurn(generalId, reserved) },
        )
        val nationProcessor = ProcessNationCommand(world, recorder, hiddenSeed)
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            nationProcessor = nationProcessor,
            reservedNationActionOf = { _, _ -> ReservedTurn("휴식", "") },
            chooseNationTurn = { generalId, reserved ->
                val g = world.getGeneralById(generalId)!!
                val raw = world.getNationById(g.nationId)?.meta?.get("turn_last_${g.officerLevel}")
                @Suppress("UNCHECKED_CAST")
                ai.chooseNationTurn(generalId, reserved, LastTurn.fromRaw(raw as? Map<String, Any?>))
            },
            beginGeneralTurn = { generalId -> ai.beginGeneralTurn(generalId) },
            lifecycleEnvOf = { state, date ->
                val turnTerm = state.tickSeconds / 60
                LifecycleEnv(
                    baselineKillturn = EffectiveGameConst.killturn(turnTerm, npcmode = 0),
                    year = state.currentYear,
                    month = state.currentMonth,
                    turnTerm = turnTerm,
                    isunited = state.meta["isunited"] as? Int ?: 0,
                    turnTimeHm = date,
                )
            },
            reservedActionOf = { _ -> ReservedTurn("휴식", "") },
        )

        val monthlyRecorders = LinkedHashMap<Pair<Int, Int>, AiDrawRecorder>()
        val monthlyPipeline = MonthlyPipeline(
            monthlyRngFactory = { year, month ->
                val rec = AiDrawRecorder(LiteHashDrbg(MonthScopedRng.monthlySeed(hiddenSeed, year, month)))
                monthlyRecorders[year to month] = rec
                rec
            },
            clock = MonthlyClock { nextTurn, st -> ServerClock.turnDate(nextTurn, startYear, st, turnterm) },
            preUpdateMonthly = PreUpdateMonthly { true },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = MonthlyPostUpdateHook(world, recorder, pipeline),
        )

        val eventDispatcher = EventDispatcher(EventStore.withDefaults(), WorldActions.register(EventActionFactory()))
        val worldContextFactory = WorldEventContextFactory.create(
            world = world,
            recorder = recorder,
            pipeline = pipeline,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            mapName = mapName,
            eventStore = EventStore.withDefaults(),
        )

        var aiKvApplied = 0
        fun applyAiKvDeltas() {
            val deltas = ai.kvDeltas
            while (aiKvApplied < deltas.size) {
                val d = deltas[aiKvApplied++]
                val nation = world.getNationById(d.nationId) ?: continue
                world.applyNationDirtyFree(nation.copy(meta = nation.meta + (d.key to d.value)))
                recorder.recordNationEnvKv(d.nationId, d.key, d.value)
            }
        }

        fun applyRecorderKvToWorld() {
            for ((kvKey, value) in recorder.kvDirty()) {
                if (kvKey.table != "nation_env") continue
                val nationId = kvKey.namespace.toIntOrNull() ?: continue
                val nation = world.getNationById(nationId) ?: continue
                world.applyNationDirtyFree(nation.copy(meta = nation.meta + (kvKey.key to value)))
            }
        }

        val driver = TurnDaemonLifecycle.MonthBoundaryDriver(
            drain = { upto ->
                lifecycle.runTick(upto)
                applyAiKvDeltas()
            },
            runMonth = { nextTurn ->
                val state = world.getState()
                monthlyPipeline.runMonth(
                    nextTurn = nextTurn,
                    startYear = startYear,
                    startTime = startTime,
                    turnTerm = turnterm,
                    oldYear = state.currentYear,
                    oldMonth = state.currentMonth,
                    dispatcher = { target, env ->
                        val supplier = {
                            mutableMapOf<String, Any?>(
                                "year" to env.year,
                                "month" to env.month,
                                "currentEventID" to env.currentEventID,
                            )
                        }
                        eventDispatcher.run(target, contextFactory = worldContextFactory, envSupplier = supplier)
                    },
                )
                applyRecorderKvToWorld()
                recorder.clear()
            },
        )

        for (point in manifest["points"]!!.jsonArray.map { it.jsonObject }) {
            val gameMonths = point["gameMonths"]!!.jsonPrimitive.int
            val targetNow = ServerClock.addTurn(startTime, turnterm, gameMonths)
            val isUnited = world.getState().meta["isunited"] as? Int ?: 0
            driver.run(world.getState().lastTurnTime, targetNow, turnterm, isUnited)
            val (newYear, newMonth) = ServerClock.turnDate(targetNow, startYear, startTime, turnterm)
            world.setCurrentDate(newYear, newMonth)
            world.setLastTurnTime(targetNow)

            // Assert the monthly seed + draw count for the LAST crossed month.
            val oldDate = startYear * 12 + (gameMonths - 1)
            val oldYear = Math.floorDiv(oldDate, 12)
            val oldMonth = Math.floorMod(oldDate, 12) + 1
            val expectedCapture = loadJson("golden/longsim/${point["file"]!!.jsonPrimitive.content}")
            val rec = monthlyRecorders[oldYear to oldMonth]
            val expectedSeed = MonthScopedRng.monthlySeed(hiddenSeed, oldYear, oldMonth)
            assertEquals(
                expectedCapture["monthlySeedString"]?.jsonPrimitive?.contentOrNull,
                expectedSeed,
                "monthly seed mismatch at gameMonths=$gameMonths",
            )
            val expectedDraws = expectedCapture["monthlyRngDraws"]?.jsonPrimitive?.intOrNull
            if (rec != null && expectedDraws != null) {
                assertEquals(
                    expectedDraws,
                    rec.drawStream().count { it.consumed },
                    "monthly rng draws at gameMonths=$gameMonths",
                )
            }

            val expectedState = expectedCapture["state"]!!.jsonObject
            val liveState = LongSimStateCapture.captureState(world, baseline.state)
            val mismatch = LongSimStateCapture.compareStates(expectedState, liveState)
            if (mismatch != null) {
                throw AssertionError("State divergence at gameMonths=$gameMonths: $mismatch")
            }
        }
    }
}
