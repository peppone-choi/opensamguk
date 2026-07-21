package opensamguk.engine.intake

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-94 — 프로필 아이콘 typed sync 핸들러 게이트/predicate/flush 테스트.
 * gate on/off·show_img_level=0·grade=0·NPC/타 owner 불변·target0 성공·idempotency·flush persistence를
 * memory/dirty/flush-payload 세 층에서 단언한다.
 */
class ProfileIconSyncHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(
        general: TurnGeneral,
        showImgLevel: Int? = null,
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
                meta = buildMap { showImgLevel?.let { put("show_img_level", it) } },
            ),
            generals = listOf(general),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0")),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0,
                meta = buildMap { showImgLevel?.let { put("show_img_level", it) } },
            )).id),
        ),
    )

    private fun general(
        id: Int = 10,
        userId: String? = "7",
        npcState: Int = 0,
        picture: String = "default.jpg",
        imageServer: Int = 0,
    ) = TurnGeneral(
        id = id,
        userId = userId,
        name = "관우",
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(90, 80, 70),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        npcState = npcState,
        turnTime = t0,
        meta = mapOf("picture" to picture, "image_server" to imageServer, "imgsvr" to imageServer),
    )

    private fun sync(userId: Long = 7L, picture: String = "abcd1234.jpg", imgsvr: Int = 1, grade: Int = 5) =
        TurnDaemonCommand.ProfileIconSync(userId = userId, picture = picture, imgsvr = imgsvr, grade = grade)

    private fun profileRows(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()).profileIconUpdates

    @Test
    fun `eligible owner-npc0 general is mutated in memory and recorded for typed-column flush`() {
        val world = world(general(picture = "default.jpg", imageServer = 0))
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync(picture = "abcd1234.jpg", imgsvr = 1, grade = 5))

        // memory: meta picture/image_server 반영.
        val g = world.getGeneralById(10)!!
        assertEquals("abcd1234.jpg", g.meta["picture"])
        assertEquals(1, g.meta["image_server"])
        // dirty + flush payload: 전용 채널 1행(typed 컬럼).
        assertTrue(recorder.isDirty)
        val rows = profileRows(world, recorder)
        assertEquals(1, rows.size)
        assertEquals(10, rows.single().columns["id"])
        assertEquals("7", rows.single().columns["user_id"])
        assertEquals("abcd1234.jpg", rows.single().columns["picture"])
        assertEquals(1, rows.single().columns["image_server"])
    }

    @Test
    fun `grade below 1 blocks all mutation`() {
        val world = world(general())
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync(grade = 0))

        assertEquals("default.jpg", world.getGeneralById(10)!!.meta["picture"])
        assertFalse(recorder.isDirty)
        assertTrue(profileRows(world, recorder).isEmpty())
    }

    @Test
    fun `show_img_level 0 from world config blocks even with high wire grade`() {
        // 핸들러가 authoritative world config를 읽고 wire를 신뢰하지 않음을 증명(criterion 2).
        val world = world(general(), showImgLevel = 0)
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync(grade = 9))

        assertEquals("default.jpg", world.getGeneralById(10)!!.meta["picture"])
        assertFalse(recorder.isDirty)
        assertTrue(profileRows(world, recorder).isEmpty())
    }

    @Test
    fun `npc general owned by the same user is never repainted`() {
        val world = world(general(npcState = 1))
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync())

        assertEquals("default.jpg", world.getGeneralById(10)!!.meta["picture"])
        assertFalse(recorder.isDirty)
        assertTrue(profileRows(world, recorder).isEmpty())
    }

    @Test
    fun `general owned by another user is never repainted`() {
        val world = world(general(userId = "99"))
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync(userId = 7L))

        assertEquals("default.jpg", world.getGeneralById(10)!!.meta["picture"])
        assertFalse(recorder.isDirty)
        assertTrue(profileRows(world, recorder).isEmpty())
    }

    @Test
    fun `zero matching generals succeeds with no dirty rows`() {
        val world = world(general(userId = null)) // 소유 유저 없음.
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync(userId = 7L))

        assertFalse(recorder.isDirty)
        assertTrue(profileRows(world, recorder).isEmpty())
    }

    @Test
    fun `identical payload is idempotent - no dirty row when value unchanged`() {
        val world = world(general(picture = "abcd1234.jpg", imageServer = 1))
        val recorder = ChangeRecorder()

        ProfileIconSyncHandler(world, recorder).handle(sync(picture = "abcd1234.jpg", imgsvr = 1))

        assertFalse(recorder.isDirty)
        assertTrue(profileRows(world, recorder).isEmpty())
    }

    @Test
    fun `delete resets portrait to default via same eligible path`() {
        val world = world(general(picture = "abcd1234.jpg", imageServer = 1))
        val recorder = ChangeRecorder()

        // 삭제 후 gateway는 default.jpg/imgsvr=0을 싣고 grade는 여전히 >=1.
        ProfileIconSyncHandler(world, recorder).handle(sync(picture = "default.jpg", imgsvr = 0, grade = 3))

        val g = world.getGeneralById(10)!!
        assertEquals("default.jpg", g.meta["picture"])
        assertEquals(0, g.meta["image_server"])
        val rows = profileRows(world, recorder)
        assertEquals(1, rows.size)
        assertEquals("default.jpg", rows.single().columns["picture"])
        assertEquals(0, rows.single().columns["image_server"])
    }
}
