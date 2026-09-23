package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.input.RuleProfile
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 개인 턴 결정론 계약(2026-09-18, 이슈 #787) 1차 — 게이트 G1·G2·G7 의 시드 문자열 축.
 *
 * - G1: 같은 `turnTime` 의 장수는 id 오름차순으로 처리된다(SAMMO·HWIHA 공통, 현행 동작 고정).
 * - G7: SAMMO 시드 문자열은 PHP 동결 기준선(ADR-LITE-042) 그대로다 — 성분이 하나라도 늘면 빨개진다.
 * - G2: HWIHA 시드 문자열은 worldId·순(phase)을 품는다.
 */
class PersonalTurnDeterminismTest {
    private val t0 = Instant.parse("0200-06-15T14:00:00Z")
    private val hidden = "0".repeat(32)
    private val hash = "d".repeat(64)

    private fun gen(id: Int, turnTime: Instant = t0) = TurnGeneral(
        id = id, name = "g$id", nationId = 1, cityId = 5, troopId = 0, stats = GeneralStats(80, 70, 60),
        experience = 0, dedication = 0, officerLevel = 1, age = 30, turnTime = turnTime,
        meta = linkedMapOf<String, Any?>("killturn" to 5, "block" to 0, "deadyear" to 999, "lived_month" to 100),
    )

    private fun world(
        profile: RuleProfile,
        generals: List<TurnGeneral>,
        worldId: Int = 1,
        phase: Int = 1,
    ): InMemoryTurnWorld {
        val hwiha = profile == RuleProfile.HWIHA
        val positions = if (!hwiha) null else generals.fold(
            GeneralPositionSnapshot("r1", hash, setOf("p1"), emptySet()),
        ) { acc, g -> acc.withState(GeneralPositionState("r1", hash, g.id, StrategicNodeRef.LandProvince("p1"), 1)) }
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    worldId, 200, 6, 3600, t0, currentPhase = phase,
                    config = if (hwiha) mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA") else mapOf("mapName" to "che"),
                ),
                generals = generals,
                cities = listOf(City(id = 5, name = "c5", nationId = 1, level = 5, meta = linkedMapOf("trust" to 50.0))),
                nations = listOf(Nation(1, "n1", "#000")),
                worldId = WorldId(worldId),
                generalPositionSnapshot = positions,
                cityLandProvinceById = if (hwiha) mapOf(5 to "p1") else emptyMap(),
            ),
        )
    }

    private fun handler(world: InMemoryTurnWorld, seeds: MutableList<String> = mutableListOf()) = ReservedTurnHandler(
        world,
        registry = CommandRegistry(GeneralActionPipeline()),
        hiddenSeed = hidden,
        startYear = 184,
        actionRngFactory = { seed -> seeds += seed; RandUtil(LiteHashDrbg(seed)) },
    )

    private fun lifecycle(world: InMemoryTurnWorld) = TurnDaemonLifecycle(
        world = world,
        handler = handler(world),
        reservedActionOf = { opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn("휴식", "") },
        lifecycleEnvOf = { state, date ->
            LifecycleEnv(
                baselineKillturn = 100, year = state.currentYear, month = state.currentMonth,
                turnTerm = state.tickSeconds / 60, isunited = 0, turnTimeHm = date,
            )
        },
    )

    // ---- G1 ----

    private fun assertTieOrder(profile: RuleProfile) {
        // 삽입 순서를 뒤섞는다. 더 이른 turnTime 의 큰 id(99)는 맨 앞, 동률 묶음은 id 오름차순.
        val shuffled = listOf(7, 3, 11, 1, 5).map { gen(it) } + gen(99, t0.minusSeconds(1))
        val due = lifecycle(world(profile, shuffled)).dueGenerals(t0.plusSeconds(3600))
        assertEquals(listOf(99, 1, 3, 5, 7, 11), due.map { it.id }, "$profile: (turnTime, id) 오름차순")
    }

    @Test
    fun `G1 sammo - generals sharing a turnTime are due in ascending id order`() = assertTieOrder(RuleProfile.SAMMO)

    @Test
    fun `G1 hwiha - generals sharing a turnTime are due in ascending id order`() = assertTieOrder(RuleProfile.HWIHA)

    @Test
    fun `G1 sammo - the drain handles tied generals in ascending id order`() {
        val lc = lifecycle(world(RuleProfile.SAMMO, listOf(7, 3, 11, 1, 5).map { gen(it) }))
        assertEquals(listOf(1, 3, 5, 7, 11), lc.runTick(t0.plusSeconds(3600)).map { it.generalId })
    }

    // ---- G7: SAMMO 시드 문자열 고정 ----

    @Test
    fun `G7 sammo seed strings are the frozen PHP shape - no worldId, no phase`() {
        fun sammo(domain: String, vararg scope: Any) =
            personalTurnSeed(RuleProfile.SAMMO, hidden, domain, 42, 200, 6, 3, 17, *scope)
        val h = "str(32,$hidden)"
        assertEquals("$h|str(14,generalCommand)|int(200)|int(6)|int(17)|str(2,휴식)", sammo("generalCommand", "휴식"))
        assertEquals("$h|str(13,nationCommand)|int(200)|int(6)|int(17)|str(6,che_포상)", sammo("nationCommand", "che_포상"))
        assertEquals("$h|str(10,preprocess)|int(200)|int(6)|int(17)", sammo("preprocess"))
    }

    @Test
    fun `G7 the sammo handler seeds the general command with exactly the frozen string, whatever the phase or world`() {
        val seen = (1..3).flatMap { phase ->
            listOf(1, 2).map { wid ->
                val seeds = mutableListOf<String>()
                handler(world(RuleProfile.SAMMO, listOf(gen(17)), worldId = wid, phase = phase), seeds)
                    .handle(17, "휴식", 200, 6, "14:00")
                seeds.single()
            }
        }.toSet()
        assertEquals(setOf("str(32,$hidden)|str(14,generalCommand)|int(200)|int(6)|int(17)|str(2,휴식)"), seen)
    }

    // ---- G2: HWIHA 시드 ----

    @Test
    fun `G2 hwiha seed strings carry worldId and phase`() {
        assertEquals(
            "str(32,$hidden)|str(14,generalCommand)|int(42)|int(200)|int(6)|int(3)|int(17)|str(2,휴식)",
            personalTurnSeed(RuleProfile.HWIHA, hidden, "generalCommand", 42, 200, 6, 3, 17, "휴식"),
        )
        for (domain in listOf("generalCommand", "nationCommand", "preprocess")) {
            val byPhase = (1..3).map { personalTurnSeed(RuleProfile.HWIHA, hidden, domain, 1, 200, 6, it, 17) }
            assertEquals(3, byPhase.toSet().size, "$domain: 한 달의 세 순이 서로 다른 시드")
            assertNotEquals(
                personalTurnSeed(RuleProfile.HWIHA, hidden, domain, 1, 200, 6, 1, 17),
                personalTurnSeed(RuleProfile.HWIHA, hidden, domain, 2, 200, 6, 1, 17),
                "$domain: worldId 만 다른 두 월드",
            )
        }
    }

    @Test
    fun `G2 world derived hwiha seeds differ across the three phases and across worlds`() {
        fun seedOf(worldId: Int, phase: Int): String {
            return world(RuleProfile.HWIHA, listOf(gen(17)), worldId = worldId, phase = phase)
                .personalTurnSeed(hidden, "generalCommand", 200, 6, 17, "휴식")
        }
        assertEquals(3, (1..3).map { seedOf(1, it) }.toSet().size)
        assertNotEquals(seedOf(1, 2), seedOf(2, 2))
        assertEquals(
            "str(32,$hidden)|str(14,generalCommand)|int(2)|int(200)|int(6)|int(3)|int(17)|str(2,휴식)",
            seedOf(2, 3),
        )
    }
    @Test
    fun `undelivered and wrong profile inputs do not consume an action random stream`() {
        for (code in listOf("휴식", "stratagem.play")) {
            val seeds = mutableListOf<String>()
            val result = handler(world(RuleProfile.HWIHA, listOf(gen(17))), seeds)
                .handle(17, code, 200, 6, "14:00")
            kotlin.test.assertIs<opensamguk.engine.hwiha.HwihaTurnOutcome.Rejected>(result.hwihaOutcome)
            assertEquals(emptyList(), seeds)
        }
    }

}
