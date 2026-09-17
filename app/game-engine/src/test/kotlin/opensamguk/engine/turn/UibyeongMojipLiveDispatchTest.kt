package opensamguk.engine.turn

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 의병모집 라이브 디스패치 프로브 — 골든 픽스처 args(createGenCnt 등) 없이 [ProcessNationCommand]만 거쳐
 * 의병장 NPC가 실제로 world에 생성되고 nation gennum이 오르는지 본다. 라이브 경로가 월드 집계를
 * 주입하지 않으면(수정 전 상태) exp/ded만 오르고 NPC 0명·RNG 무소비로 빨개진다.
 */
class UibyeongMojipLiveDispatchTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    @AfterTest fun reset() = NationActionResolverRegistry.clear()

    private fun world(existing: List<TurnGeneral> = emptyList()): InMemoryTurnWorld {
        val state = TurnWorldState(
            id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
            config = linkedMapOf("mapName" to "che"),
        )
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 100, turnTime = t0,
                        meta = linkedMapOf("dex1" to 800, "dex2" to 0, "dex3" to 0, "dex4" to 0, "dex5" to 40),
                    ),
                ) + existing,
                cities = listOf(
                    City(id = 5, name = "업", nationId = 1, level = 6, supplyState = 1),
                    City(id = 8, name = "허창", nationId = 2, level = 6, supplyState = 1),
                ),
                nations = listOf(
                    // avg(gennum) over level>0 nations = (20+12)/2 = 16 → 3 + round(16/8) = 5.
                    Nation(id = 1, name = "촉", color = "#0f0", level = 2,
                        meta = linkedMapOf("strategic_cmd_limit" to 0, "gennum" to 20)),
                    Nation(id = 2, name = "위", color = "#00f", level = 2, meta = linkedMapOf("gennum" to 12)),
                    // level 0(방랑군)은 PHP `WHERE level > 0`에서 빠진다 — 들어가면 avg=44 → 9명으로 어긋난다.
                    Nation(id = 3, name = "유랑", color = "#fff", level = 0, meta = linkedMapOf("gennum" to 100)),
                ),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )
    }

    private fun run(world: InMemoryTurnWorld): LastTurn {
        NationActionResolverRegistry.clear()
        return ProcessNationCommand(
            world, ChangeRecorder(), hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()), startYear = 184, turnTerm = 60,
        ).process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("che_의병모집", linkedMapOf()),
            lastTurn = LastTurn(command = "의병모집", arg = linkedMapOf(), term = 2, seq = 0),
            year = 200, month = 3, date = "12:00",
        )
    }

    @Test
    fun `live dispatch creates 의병장 NPCs in the actor city and raises gennum without fixture args`() {
        val world = world()
        val lastTurn = run(world)
        assertEquals(emptyMap<String, Any?>(), lastTurn.arg ?: emptyMap(), "월드 집계 입력이 LastTurn.arg로 새면 안 된다")

        // 커맨드 자체는 실행됐다(preReqTurn=2 → exp/ded += 15) — 아래 단언이 빨개도 미실행 탓이 아님을 고정.
        assertEquals(15, world.getGeneralById(10)!!.experience)

        val created = world.listGenerals().filter { it.id != 10 }
        assertEquals(5, created.size, "3 + round(avg(gennum WHERE level>0)=16 / 8) = 5 의병장")
        for (npc in created) {
            assertEquals(4, npc.npcState, "NPCType 4")
            assertEquals(1, npc.nationId)
            assertEquals(5, npc.cityId, "actor city")
            assertTrue(npc.name.startsWith("ⓖ"), "의병장 prefix: ${npc.name}")
            assertEquals(1000, npc.gold)
            assertEquals(1000, npc.rice)
            // actor 1명 평균: exp/ded 0 → build()의 age*100 폴백, dex5 40, dex_t 800 → 무 5/8·1/8 또는 지 1/8.
            assertEquals(2000, npc.experience)
            assertEquals(40, (npc.meta["dex5"] as Number).toInt())
            assertEquals(800, (1..4).sumOf { (npc.meta["dex$it"] as Number).toInt() }, "dex_t 분배 합")
        }
        assertEquals(created.size, created.map { it.name }.toSet().size, "names unique")
        assertEquals(25, (world.getNationById(1)!!.meta["gennum"] as Number).toInt(), "gennum 20 + 5")
        assertEquals(9, (world.getNationById(1)!!.meta["strategic_cmd_limit"] as Number).toInt(), "전략 재사용 대기")
        assertEquals(5, world.consumeDirtyState().createdGenerals.size, "flush created-set")
    }

    @Test
    fun `a name already in the world gets the PHP duplicate suffix instead of a twin`() {
        val first = world().also(::run).listGenerals().first { it.id != 10 }
        val occupied = TurnGeneral(
            id = 99, name = first.name, nationId = 2, cityId = 8, troopId = 0,
            stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0, officerLevel = 1, turnTime = t0,
        )

        val world = world(existing = listOf(occupied))
        run(world)

        // 같은 시드 → 같은 첫 이름. dupCnt=1 → `이름2`(AbsGeneralPool::checkDuplicatedCnt + RandomNameGeneral:48-50).
        val names = world.listGenerals().filter { it.id != 10 && it.id != 99 }.map { it.name }
        assertTrue(first.name + "2" in names, "expected ${first.name}2 in $names")
        assertTrue(first.name !in names, "twin of ${first.name} in $names")
    }

    @Test
    fun `a nation row without a gennum key counts its generals instead of restarting from zero`() {
        val world = world()
        world.updateNation(world.getNationById(1)!!.copy(meta = linkedMapOf("strategic_cmd_limit" to 0)))
        run(world)

        // avg = (1 + 12)/2 = 6.5 → 3 + round(0.8125) = 4. gennum = 실제 1명 + 4.
        assertEquals(4, world.listGenerals().count { it.id != 10 })
        assertEquals(5, (world.getNationById(1)!!.meta["gennum"] as Number).toInt())
    }
}
