package opensamguk.engine.world

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.RawAction
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.InvaderEndingAction
import opensamguk.logic.world.InvaderEndingContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 침략자(이민족) 종료 leaf [InvaderEndingAction] 의 엔진 시임([WorldActionContext] 가 구현하는
 * [InvaderEndingContext]) 통합 검증 — PHP `Event/Action/InvaderEnding.php`.
 *
 * sibling Q14([WorldActionContext] 의 CheckEmperiorContext 구현) 와 동일 형태: InMemoryTurnWorld read
 * + isunited/refreshLimit meta 전이 write + global-history 로그 push. Docker 불요(in-memory). leaf 의
 * byte-exact 로그/로직은 logic 측 InvaderEndingActionTest 가 본수(本守); 본 테스트는 **엔진 배선**(env 공급 +
 * WorldActionContext 의 10 메서드 위임)이 실제로 leaf 를 실행시키는지를 검증한다.
 *
 * 로그는 ActionLogger::pushGlobalHistoryLog 기본 YEAR_MONTH 포맷까지 저장 byte 전체를 검사한다.
 *
 * RaiseInvader 수락 경로가 isunited=1과 월별 InvaderEnding 행을 함께 기록하므로 이 종료 leaf는
 * 재기동 뒤에도 live EventStore에서 도달 가능하다.
 */
class WorldInvaderEndingContextTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(
        isunited: Int,
        nations: List<Nation>,
        cities: List<City>,
        refreshLimit: Int? = null,
    ): InMemoryTurnWorld {
        val meta = LinkedHashMap<String, Any?>()
        meta["map"] = "miniche"
        meta["isunited"] = isunited
        if (refreshLimit != null) meta["refreshLimit"] = refreshLimit
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    meta = meta,
                ),
                nations = nations,
                cities = cities,
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    meta = meta,
                )).id),
            ),
        )
    }

    private fun ctx(
        world: InMemoryTurnWorld,
        eventStore: EventStore,
        currentEventID: Int,
    ): WorldActionContext {
        val env = mutableMapOf<String, Any?>(
            "currentEventID" to currentEventID,
            "year" to 200,
            "month" to 1,
        )
        env[DeleteEventContext.ENV_KEY] = eventStore
        val wctx = WorldActionContext(
            env = env,
            world = world,
            recorder = ChangeRecorder(),
            pipeline = GeneralActionPipeline(),
        )
        // WorldEventContextFactory 가 live 에서 심는 키와 동일하게 self-바인딩.
        env[InvaderEndingContext.ENV_KEY] = wctx
        return wctx
    }

    private fun storeWithRow(id: Int): EventStore {
        val store = EventStore()
        // InvaderEnding 자기-삭제 대상 row. target/priority/condition 은 본 테스트와 무관(삭제 멱등).
        val insertedId = store.insert(
            targetCode = "month",
            priority = 0,
            condition = EventCondition.ConstBool(true),
            actions = listOf(RawAction("InvaderEnding", emptyList())),
        )
        require(insertedId == id) { "테스트 가정: 첫 insert 의 id 는 $id 이어야 한다(실제 $insertedId)" }
        return store
    }

    @Test
    fun `userWin 엔딩 — 1국 공백지0 비이민족 → 승리로그2 + isunited3 + refreshLimit_100 + event삭제`() {
        // php:35-42, 53-56, 63-68. 공백지 0(전 도시 소유) + 국가명 ⓞ 아님 → 유저 승리.
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = (1..3).map { City(id = it, name = "c$it", nationId = 7, level = 5) }
        val w = world(isunited = 1, nations = nations, cities = cities, refreshLimit = 5)
        val store = storeWithRow(1)
        val eventId = 1

        InvaderEndingAction().run(ctx(w, store, eventId))

        val dirty = w.consumeDirtyState()
        assertEquals(
            listOf(
                "<C>●</>200년 1월:<L><b>【이벤트】</b></>이민족을 모두 소탕했습니다!",
                "<C>●</>200년 1월:<L><b>【이벤트】</b></>중원은 당분간 태평성대를 누릴 것입니다.",
            ),
            dirty.logs.filter { it.scope == "global" && it.category == "history" }.map { it.text },
        )
        assertEquals(3, (w.getState().meta["isunited"] as Number).toInt(), "isunited → 3(엔딩 확정)")
        assertEquals(500, (w.getState().meta["refreshLimit"] as Number).toInt(), "refreshLimit *= 100 (5→500)")
        assertFalse(store.allRows().any { it.id == eventId }, "자기 event row 삭제")
    }

    @Test
    fun `defeat 엔딩 — 모든 도시가 공백지(cityCnt==total) → 패배로그2 + isunited3`() {
        // php:44-47, 58-61. 전 도시 공백지(이민족 중원 장악) → 유저 패배.
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = CityConstRegistry.of("miniche").all().values.map {
            City(id = it.id, name = it.name, nationId = 0, level = it.level)
        }
        val w = world(isunited = 1, nations = nations, cities = cities)
        val store = storeWithRow(1)

        InvaderEndingAction().run(ctx(w, store, 1))

        val dirty = w.consumeDirtyState()
        assertEquals(
            listOf(
                "<C>●</>200년 1월:<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.",
                "<C>●</>200년 1월:<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.",
            ),
            dirty.logs.filter { it.scope == "global" && it.category == "history" }.map { it.text },
        )
        assertEquals(3, (w.getState().meta["isunited"] as Number).toInt())
        assertFalse(store.allRows().any { it.id == 1 }, "자기 event row 삭제")
    }

    @Test
    fun `ⓞ 국가명 공백지0 → 이민족 엔딩(userWin 아님)`() {
        // php:38-40 — 마지막 국가명이 ⓞ(이민족 prefix) 로 시작하면 userWin 유지 false.
        val nations = listOf(Nation(id = 7, name = "ⓞ강족", color = "#fff", level = 1))
        val cities = (1..3).map { City(id = it, name = "c$it", nationId = 7, level = 5) }
        val w = world(isunited = 1, nations = nations, cities = cities)
        val store = storeWithRow(1)

        InvaderEndingAction().run(ctx(w, store, 1))

        val texts = w.consumeDirtyState().logs.filter { it.scope == "global" }.map { it.text }
        assertEquals(
            listOf(
                "<C>●</>200년 1월:<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.",
                "<C>●</>200년 1월:<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.",
            ),
            texts,
        )
        assertEquals(3, (w.getState().meta["isunited"] as Number).toInt())
    }

    @Test
    fun `isunited 0이면 No Invader no-op`() {
        // php:22-23 — isunited ∈ {0,2} → no-op.
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = (1..3).map { City(id = it, name = "c$it", nationId = 7, level = 5) }
        val w = world(isunited = 0, nations = nations, cities = cities)
        val store = storeWithRow(1)

        InvaderEndingAction().run(ctx(w, store, 1))

        assertTrue(w.consumeDirtyState().logs.isEmpty(), "no-op 이면 로그 없음")
        assertEquals(0, (w.getState().meta["isunited"] as Number).toInt(), "isunited 불변")
        assertTrue(store.allRows().any { it.id == 1 }, "event row 미삭제")
    }

    @Test
    fun `isunited 2(천하통일)면 No Invader no-op`() {
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = (1..3).map { City(id = it, name = "c$it", nationId = 7, level = 5) }
        val w = world(isunited = 2, nations = nations, cities = cities)
        val store = storeWithRow(1)

        InvaderEndingAction().run(ctx(w, store, 1))

        assertTrue(w.consumeDirtyState().logs.isEmpty())
        assertEquals(2, (w.getState().meta["isunited"] as Number).toInt())
    }

    @Test
    fun `국가 수 2 이상이면 On Event no-op`() {
        // php:25-28 — nationCnt >= 2 → no-op.
        val nations = listOf(
            Nation(id = 7, name = "촉", color = "#fff", level = 1),
            Nation(id = 8, name = "위", color = "#000", level = 1),
        )
        val cities = (1..4).map { City(id = it, name = "c$it", nationId = if (it <= 2) 7 else 8, level = 5) }
        val w = world(isunited = 1, nations = nations, cities = cities)
        val store = storeWithRow(1)

        InvaderEndingAction().run(ctx(w, store, 1))

        assertTrue(w.consumeDirtyState().logs.isEmpty())
        assertEquals(1, (w.getState().meta["isunited"] as Number).toInt())
        assertTrue(store.allRows().any { it.id == 1 }, "event row 미삭제")
    }

    @Test
    fun `종료 조건 미충족(공백지가 0도 전체도 아님)이면 On Event no-op`() {
        // php:49-51 — needStop=false → no-op. 1국, 도시 3개 중 1개만 공백지.
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = listOf(
            City(id = 1, name = "c1", nationId = 7, level = 5),
            City(id = 2, name = "c2", nationId = 7, level = 5),
            City(id = 3, name = "c3", nationId = 0, level = 5),
        )
        val w = world(isunited = 1, nations = nations, cities = cities)
        val store = storeWithRow(1)

        InvaderEndingAction().run(ctx(w, store, 1))

        assertTrue(w.consumeDirtyState().logs.isEmpty())
        assertEquals(1, (w.getState().meta["isunited"] as Number).toInt())
        assertTrue(store.allRows().any { it.id == 1 })
    }
}
