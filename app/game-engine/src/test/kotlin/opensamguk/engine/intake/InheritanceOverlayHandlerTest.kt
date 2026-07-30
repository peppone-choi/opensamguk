package opensamguk.engine.intake

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.BuyHiddenBuffFail
import opensamguk.common.wire.BuyHiddenBuffOk
import opensamguk.common.wire.BuyRandomUniqueFail
import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.InheritResetSpecialWarFail
import opensamguk.common.wire.InheritSetNextSpecialWarFail
import opensamguk.common.wire.InheritResetTurnTimeFail
import opensamguk.common.wire.ResetStatFail
import opensamguk.common.wire.ResetStatOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InheritanceOverlayHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(
        id: Int,
        name: String,
        ownerId: Int,
        ownerName: String = "소유자$ownerId",
    ) = TurnGeneral(
        id = id,
        name = name,
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = 12,
        role = GeneralRole(),
        gold = 100,
        turnTime = t0,
        meta = linkedMapOf("owner" to ownerId, "owner_name" to ownerName),
    )

    private fun world(previousPoint: Double, generals: List<TurnGeneral> = listOf(general(10, "유비", 100))) =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 3,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    meta = linkedMapOf(
                        "isunited" to 0,
                        "turnterm" to 2,
                        "hiddenSeed" to "overlay-seed",
                        "season" to 7,
                        "inheritancePrevious" to linkedMapOf(100 to previousPoint),
                    ),
                ),
                generals = generals,
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
                worldId = WorldId(1),
            ),
        )

    @Test
    fun `a first inheritance spend is visible to a later spend in the same recorder`() {
        val world = world(previousPoint = 1000.0)
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val first = handler.handleBuyHiddenBuff(
            TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1),
        )
        val second = handler.handleResetTurnTime(TurnDaemonCommand.InheritResetTurnTime(generalId = 10))

        assertTrue(first is BuyHiddenBuffOk)
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", (second as InheritResetTurnTimeFail).reason)
        assertEquals(800.0, (recorder.inheritanceKvWrites().single().value as List<*>)[0])
    }

    @Test
    fun `resetStat cannot repeat in the same recorder season`() {
        val world = world(previousPoint = 5000.0)
        val recorder = ChangeRecorder()
        var persistedReads = 0
        val handler = InheritResetHandler(
            world,
            recorder,
            lastStatResetReader = {
                persistedReads += 1
                emptyList()
            },
        )
        val command = TurnDaemonCommand.ResetStat(
            generalId = 10,
            leadership = 55,
            strength = 55,
            intel = 55,
            inheritBonusStat = listOf(1, 1, 1),
        )

        val first = handler.handleResetStat(command)
        val duplicate = handler.handleResetStat(command)

        assertTrue(first is ResetStatOk)
        assertEquals("이번 시즌에 이미 능력치를 초기화하셨습니다.", (duplicate as ResetStatFail).reason)
        assertEquals(listOf(7), recorder.kvDirty()[KvKey("user", "user_100", "last_stat_reset")])
        assertEquals(1, persistedReads)
    }

    @Test
    fun `CheckOwner spend is visible to a later inheritance spend in the same recorder`() {
        val world = world(
            previousPoint = 1000.0,
            generals = listOf(general(10, "유비", 100), general(20, "관우", 200, "관우소유자")),
        )
        val recorder = ChangeRecorder()

        val checkOwner = InstantActionHandler(world, recorder).handleCheckOwner(
            TurnDaemonCommand.CheckOwner(generalId = 10, destGeneralId = 20),
        ) as GeneralBoolResult
        val purchase = InheritResetHandler(world, recorder).handleBuyHiddenBuff(
            TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1),
        ) as BuyHiddenBuffFail

        assertTrue(checkOwner.ok)
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", purchase.reason)
        assertEquals(0.0, (recorder.inheritanceKvWrites().single().value as List<*>)[0])
    }

    @Test
    fun `CheckOwner sees a prior inheritance spend in the same recorder`() {
        val world = world(
            previousPoint = 1000.0,
            generals = listOf(general(10, "유비", 100), general(20, "관우", 200, "관우소유자")),
        )
        val recorder = ChangeRecorder()
        val purchase = InheritResetHandler(world, recorder).handleBuyHiddenBuff(
            TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1),
        )
        val checkOwner = InstantActionHandler(world, recorder).handleCheckOwner(
            TurnDaemonCommand.CheckOwner(generalId = 10, destGeneralId = 20),
        ) as GeneralBoolResult

        assertTrue(purchase is BuyHiddenBuffOk)
        assertFalse(checkOwner.ok)
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", checkOwner.reason)
    }

    @Test
    fun `all inheritance spend paths prefer the pending previous balance`() {
        val general = general(10, "유비", 100).copy(role = GeneralRole(specialWar = "ExistingSpecial"))
        val world = world(previousPoint = 5000.0, generals = listOf(general))
        val recorder = ChangeRecorder()
        recorder.recordInheritancePointSet(100, "previous", 0.0, null)
        var persistedReads = 0
        val handler = InheritResetHandler(
            world,
            recorder,
            previousPointReader = {
                persistedReads += 1
                5000.0
            },
        )

        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (handler.handleResetTurnTime(TurnDaemonCommand.InheritResetTurnTime(generalId = 10)) as InheritResetTurnTimeFail).reason,
        )
        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (handler.handleResetSpecialWar(TurnDaemonCommand.InheritResetSpecialWar(generalId = 10)) as InheritResetSpecialWarFail).reason,
        )
        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (handler.handleSetNextSpecialWar(
                TurnDaemonCommand.InheritSetNextSpecialWar(generalId = 10, specialWar = GameConst.availableSpecialWar.first()),
            ) as InheritSetNextSpecialWarFail).reason,
        )
        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (handler.handleResetStat(
                TurnDaemonCommand.ResetStat(
                    generalId = 10,
                    leadership = 55,
                    strength = 55,
                    intel = 55,
                    inheritBonusStat = listOf(1, 1, 1),
                ),
            ) as ResetStatFail).reason,
        )
        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (handler.handleBuyHiddenBuff(
                TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1),
            ) as BuyHiddenBuffFail).reason,
        )
        assertEquals(
            "충분한 유산 포인트를 가지고 있지 않습니다.",
            (handler.handleBuyRandomUnique(TurnDaemonCommand.BuyRandomUnique(generalId = 10)) as BuyRandomUniqueFail).reason,
        )
        assertEquals(0, persistedReads)
    }
}
