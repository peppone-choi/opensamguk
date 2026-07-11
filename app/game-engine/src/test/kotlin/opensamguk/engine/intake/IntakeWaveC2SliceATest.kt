package opensamguk.engine.intake

import opensamguk.common.wire.InheritResetTurnTimeFail
import opensamguk.common.wire.InheritResetTurnTimeOk
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.ResetStatFail
import opensamguk.common.wire.ResetStatOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F4 Wave C2 (slice A) full-cycle tests — the single-actor intake commands.
 *
 * Each test exercises the FULL daemon path the spec mandates:
 *   command → handler (world mutate) → ChangeRecorder delta → DatabaseHooks.toFlushPayload (the
 *   exact builder TurnRunService routes to JdbcFlushExecutor) → assert the flush payload + world.
 *
 * Covered: the 7 nation-finance setters (incl. the SetBlockWar counter-decrement deny + the
 * SetBlockScout game-env gate + the permission gate) + tournament enroll + the resetTurnTime
 * inheritance reset (RNG draw + previous deduction + rank bump + inheritance_log).
 */
class IntakeWaveC2SliceATest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun state(meta: Map<String, Any?> = emptyMap()) =
        TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0, meta = meta)

    private fun general(
        id: Int = 10,
        nationId: Int = 1,
        officerLevel: Int = 12,
        meta: Map<String, Any?> = emptyMap(),
        specialWar: String? = null,
    ) = TurnGeneral(
        id = id, name = "유비", nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = officerLevel, gold = 100, turnTime = t0,
        role = opensamguk.engine.turn.GeneralRole(specialWar = specialWar),
        meta = meta,
    )

    private fun nation(meta: Map<String, Any?> = emptyMap()) =
        Nation(id = 1, name = "촉", color = "#0f0", gold = 1000, meta = meta)

    private fun world(
        general: TurnGeneral = general(),
        nation: Nation = nation(),
        stateMeta: Map<String, Any?> = emptyMap(),
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(state = state(stateMeta), generals = listOf(general), nations = listOf(nation)),
    )

    /** Build the flush payload exactly as TurnRunService does (recorder + world + drained dirty). */
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    // ── nation-finance setters ────────────────────────────────────────────────────

    @Test
    fun `setRate writes nation_meta rate and flushes the dirty nation`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetRate(TurnDaemonCommand.SetRate(generalId = 10, amount = 25))

        assertTrue((res as NationSettingResult).ok)
        // world post-state mutated.
        assertEquals(25, world.getNationById(1)!!.meta["rate"])
        // recorder is the dirty source.
        assertEquals(setOf(1), recorder.dirtyNationIds())
        // flush payload carries the nation with rate=25 in its meta.
        val payload = flush(world, recorder)
        val flushed = payload.updatedNations.single { it.id == 1 }
        assertEquals(25, flushed.meta["rate"])
    }

    @Test
    fun `setRate out of range is denied with the PHP reason and nothing flushes`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetRate(TurnDaemonCommand.SetRate(generalId = 10, amount = 4)) as NationSettingResult

        assertTrue(!res.ok)
        assertEquals("'amount' 항목은 5 ~ 30 사이여야 합니다.", res.reason)
        assertTrue(recorder.dirtyNationIds().isEmpty())
        assertTrue(flush(world, recorder).updatedNations.isEmpty())
    }

    @Test
    fun `setBill and setSecretLimit write their meta keys`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        handler.handleSetBill(TurnDaemonCommand.SetBill(generalId = 10, amount = 100))
        handler.handleSetSecretLimit(TurnDaemonCommand.SetSecretLimit(generalId = 10, amount = 30))

        val n = world.getNationById(1)!!
        assertEquals(100, n.meta["bill"])
        assertEquals(30, n.meta["secretlimit"])
        assertEquals(30, flush(world, recorder).updatedNations.single().meta["secretlimit"])
    }

    @Test
    fun `low-officer without secret permission is denied`() {
        // officer_level 3, no ambassador/auditor permission → financeSetterDenyReason fires.
        val world = world(general = general(officerLevel = 3))
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetRate(TurnDaemonCommand.SetRate(generalId = 10, amount = 20)) as NationSettingResult

        assertTrue(!res.ok)
        assertEquals("권한이 부족합니다.", res.reason)
    }

    @Test
    fun `setNotice writes the nationNotice nation_env KV`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder) { "2026-06-03 12:00:00" }

        val res = handler.handleSetNotice(TurnDaemonCommand.SetNotice(generalId = 10, msg = "전군 대기")) as NationSettingResult

        assertTrue(res.ok)
        val kv = recorder.kvDirty()
        val notice = kv[KvKey("nation_env", "1", "nationNotice")]
        @Suppress("UNCHECKED_CAST")
        val noticeMap = notice as Map<String, Any?>
        assertEquals("전군 대기", noticeMap["msg"])
        assertEquals("유비", noticeMap["author"])
        assertEquals(10, noticeMap["authorID"])
        assertEquals("2026-06-03 12:00:00", noticeMap["date"])
        // and it survives into the flush payload.
        assertTrue(flush(world, recorder).kvWrites.any { it.table == "nation_env" && it.key == "nationNotice" })
    }

    @Test
    fun `setScoutMsg writes scout_msg nation_env KV`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        handler.handleSetScoutMsg(TurnDaemonCommand.SetScoutMsg(generalId = 10, msg = "함께 합시다"))

        assertEquals("함께 합시다", recorder.kvDirty()[KvKey("nation_env", "1", "scout_msg")])
    }

    @Test
    fun `setBlockWar writes nation_meta war and decrements available_war_setting_cnt`() {
        val world = world(nation = nation(meta = linkedMapOf("nation_env" to linkedMapOf("available_war_setting_cnt" to 2))))
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetBlockWar(TurnDaemonCommand.SetBlockWar(generalId = 10, value = true)) as NationSettingResult

        assertTrue(res.ok)
        assertEquals(1, world.getNationById(1)!!.meta["war"])
        // counter decremented 2 → 1 via nation_env KV.
        assertEquals(1, recorder.kvDirty()[KvKey("nation_env", "1", "available_war_setting_cnt")])
        // nation meta war=1 flushes.
        assertEquals(1, flush(world, recorder).updatedNations.single().meta["war"])
    }

    @Test
    fun `setBlockWar denied when no remaining setting count`() {
        val world = world(nation = nation(meta = linkedMapOf("nation_env" to linkedMapOf("available_war_setting_cnt" to 0))))
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetBlockWar(TurnDaemonCommand.SetBlockWar(generalId = 10, value = true)) as NationSettingResult

        assertTrue(!res.ok)
        assertEquals("잔여 횟수가 부족합니다.", res.reason)
        assertTrue(recorder.dirtyNationIds().isEmpty())
        assertTrue(recorder.kvDirty().isEmpty())
    }

    @Test
    fun `setBlockScout writes nation_meta scout when game-env allows`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetBlockScout(TurnDaemonCommand.SetBlockScout(generalId = 10, value = false)) as NationSettingResult

        assertTrue(res.ok)
        assertEquals(0, world.getNationById(1)!!.meta["scout"])
    }

    @Test
    fun `setBlockScout denied when game-env block_change_scout is set`() {
        val world = world(stateMeta = linkedMapOf("block_change_scout" to true))
        val recorder = ChangeRecorder()
        val handler = NationFinanceSetterHandler(world, recorder)

        val res = handler.handleSetBlockScout(TurnDaemonCommand.SetBlockScout(generalId = 10, value = true)) as NationSettingResult

        assertTrue(!res.ok)
        assertEquals("임관 설정을 바꿀 수 없도록 설정되어 있습니다.", res.reason)
        assertTrue(recorder.dirtyNationIds().isEmpty())
    }

    // ── tournament enroll ───────────────────────────────────────────────────────

    @Test
    fun `tournament enroll writes general tnmt and flushes the dirty general`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = TournamentEnrollHandler(world, recorder)

        val res = handler.handle(TurnDaemonCommand.TournamentEnroll(generalId = 10, value = 0)) as NationSettingResult

        assertTrue(res.ok)
        assertEquals(0, world.getGeneralById(10)!!.meta["tnmt"])
        assertEquals(setOf(10), recorder.dirtyGeneralIds())
        assertEquals(0, flush(world, recorder).updatedGenerals.single().meta["tnmt"])
    }

    @Test
    fun `tournament enroll clamps out-of-range to 1 (PHP faithful)`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = TournamentEnrollHandler(world, recorder)

        handler.handle(TurnDaemonCommand.TournamentEnroll(generalId = 10, value = 7))

        assertEquals(1, world.getGeneralById(10)!!.meta["tnmt"])
    }

    // ── inheritance reset: resetTurnTime ──────────────────────────────────────────

    @Test
    fun `resetTurnTime deducts previous spends rank logs and flushes the full delta`() {
        // owner 100 has 5000 previous; currentLevel -1 → nextLevel 0 → reqPoint = base[0] = 1000.
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf(
                "isunited" to 0,
                "turnterm" to 2,
                "hiddenSeed" to "seed-xyz",
                "inheritancePrevious" to linkedMapOf(100 to 5000.0),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleResetTurnTime(TurnDaemonCommand.InheritResetTurnTime(generalId = 10))

        assertTrue(res is InheritResetTurnTimeOk)
        assertEquals(1000, (res as InheritResetTurnTimeOk).spent)

        // aux written on the general (level bumped to 0, nextTurnTimeBase set).
        @Suppress("UNCHECKED_CAST")
        val aux = world.getGeneralById(10)!!.meta["aux"] as Map<String, Any?>
        assertEquals(0, aux["inheritResetTurnTime"])
        assertTrue(aux.containsKey("nextTurnTimeBase"))

        // inheritance previous write: 5000 - 1000 = 4000.
        val invKv = recorder.inheritanceKvWrites().single { it.namespace == "inheritance_100" && it.key == "previous" }
        @Suppress("UNCHECKED_CAST")
        val prevPayload = invKv.value as List<Any?>
        assertEquals(4000.0, prevPayload[0])

        // rank bump inherit_point_spent_dynamic += 1000.
        val rank = recorder.rankPatches()[10]!![RankColumn.INHERIT_SPENT_DYN]
        assertEquals(opensamguk.engine.turn.RankDelta.Increment(1000), rank)

        // inheritance_log line pushed.
        val log = recorder.inheritanceLogInserts().single()
        assertEquals("inheritPoint", log.tag)
        assertTrue(log.text.startsWith("1000 포인트로 턴 시간을 바꾸어"))
        assertEquals(100, log.ownerID)

        // flush payload carries all four channels.
        val payload = flush(world, recorder)
        assertTrue(payload.inheritanceKvWrites.any { it.key == "previous" })
        assertTrue(payload.inheritanceLogInserts.isNotEmpty())
        assertTrue(payload.rankWrites.any { it.type == RankColumn.INHERIT_SPENT_DYN.column })
        assertTrue(payload.updatedGenerals.any { it.id == 10 })
    }

    @Test
    fun `resetTurnTime denied when insufficient previous points`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf("isunited" to 0, "turnterm" to 2, "hiddenSeed" to "s",
                "inheritancePrevious" to linkedMapOf(100 to 500.0)),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleResetTurnTime(TurnDaemonCommand.InheritResetTurnTime(generalId = 10))

        assertTrue(res is InheritResetTurnTimeFail)
        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", (res as InheritResetTurnTimeFail).reason)
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `resetTurnTime denied when already united`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf("isunited" to 2, "turnterm" to 2, "hiddenSeed" to "s",
                "inheritancePrevious" to linkedMapOf(100 to 9999.0)),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleResetTurnTime(TurnDaemonCommand.InheritResetTurnTime(generalId = 10))

        assertEquals("이미 천하가 통일되었습니다.", (res as InheritResetTurnTimeFail).reason)
    }

    @Test
    fun `resetStat explicit bonus writes stats previous user season log and rank`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)).copy(
                stats = GeneralStats(80, 70, 60, 73, 84),
            ),
            stateMeta = linkedMapOf(
                "isunited" to 0,
                "season" to 7,
                "hiddenSeed" to "seed-xyz",
                "inheritancePrevious" to linkedMapOf(100 to 5000.0),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleResetStat(
            TurnDaemonCommand.ResetStat(
                generalId = 10,
                leadership = 55,
                strength = 55,
                intel = 55,
                inheritBonusStat = listOf(1, 1, 1),
            ),
        )

        val ok = res as ResetStatOk
        assertEquals(1000, ok.spent)
        assertEquals(56, ok.leadership)
        assertEquals(56, ok.strength)
        assertEquals(56, ok.intel)
        assertEquals(GeneralStats(56, 56, 56, 73, 84), world.getGeneralById(10)!!.stats)

        val invKv = recorder.inheritanceKvWrites().single { it.namespace == "inheritance_100" && it.key == "previous" }
        @Suppress("UNCHECKED_CAST")
        assertEquals(4000.0, (invKv.value as List<Any?>)[0])
        assertEquals(listOf(7), recorder.kvDirty()[KvKey("user", "user_100", "last_stat_reset")])
        assertEquals(opensamguk.engine.turn.RankDelta.Increment(1000), recorder.rankPatches()[10]!![RankColumn.INHERIT_SPENT_DYN])
        assertEquals(
            listOf(
                "통솔 55, 무력 55, 지력 55 스탯 재설정",
                "1000로 통솔 1, 무력 1, 지력 1 보너스 능력치 적용",
            ),
            recorder.inheritanceLogInserts().map { it.text },
        )

        val payload = flush(world, recorder)
        assertTrue(payload.updatedGenerals.any {
            it.id == 10 && it.leadership == 56 && it.strength == 56 && it.intel == 56 &&
                it.politics == 73 && it.charm == 84
        })
        assertTrue(payload.inheritanceKvWrites.any { it.namespace == "inheritance_100" && it.key == "previous" })
        assertTrue(payload.kvWrites.any { it.table == "user" && it.namespace == "user_100" && it.key == "last_stat_reset" })
        assertEquals(2, payload.inheritanceLogInserts.size)
    }

    @Test
    fun `resetStat denied when the user already reset in this season`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf("isunited" to 0, "season" to 7, "hiddenSeed" to "seed-xyz"),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder, lastStatResetReader = { listOf(7) })

        val res = handler.handleResetStat(
            TurnDaemonCommand.ResetStat(generalId = 10, leadership = 55, strength = 55, intel = 55),
        )

        assertEquals("이번 시즌에 이미 능력치를 초기화하셨습니다.", (res as ResetStatFail).reason)
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
        assertTrue(recorder.kvDirty().isEmpty())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `resetStat reads string keyed world meta snapshots`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf(
                "isunited" to 0,
                "season" to 7,
                "hiddenSeed" to "seed-xyz",
                "inheritancePrevious" to linkedMapOf("100" to 5000.0),
                "lastStatReset" to linkedMapOf("100" to listOf(6)),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleResetStat(
            TurnDaemonCommand.ResetStat(
                generalId = 10,
                leadership = 55,
                strength = 55,
                intel = 55,
                inheritBonusStat = listOf(1, 1, 1),
            ),
        )

        assertEquals(1000, (res as ResetStatOk).spent)
        val invKv = recorder.inheritanceKvWrites().single { it.namespace == "inheritance_100" && it.key == "previous" }
        @Suppress("UNCHECKED_CAST")
        assertEquals(4000.0, (invKv.value as List<Any?>)[0])
        assertEquals(listOf(6, 7), recorder.kvDirty()[KvKey("user", "user_100", "last_stat_reset")])
    }

    // ── inheritance buy: BuyHiddenBuff / BuyRandomUnique ──────────────────────────

    @Test
    fun `buyHiddenBuff deducts previous sets aux buff rank logs and flushes`() {
        // owner 100 has 5000 previous; fresh warAvoidRatio L1 → req = inheritBuffPoints[1]-[0] = 200.
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf("isunited" to 0, "inheritancePrevious" to linkedMapOf(100 to 5000.0)),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleBuyHiddenBuff(
            TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1),
        )

        assertTrue(res is opensamguk.common.wire.BuyHiddenBuffOk)
        assertEquals(200, (res as opensamguk.common.wire.BuyHiddenBuffOk).spent)

        @Suppress("UNCHECKED_CAST")
        val aux = world.getGeneralById(10)!!.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val buff = aux["inheritBuff"] as Map<String, Any?>
        assertEquals(1, buff["warAvoidRatio"])

        val invKv = recorder.inheritanceKvWrites().single { it.namespace == "inheritance_100" && it.key == "previous" }
        @Suppress("UNCHECKED_CAST")
        assertEquals(4800.0, (invKv.value as List<Any?>)[0])

        assertEquals(opensamguk.engine.turn.RankDelta.Increment(200), recorder.rankPatches()[10]!![RankColumn.INHERIT_SPENT_DYN])

        val log = recorder.inheritanceLogInserts().single()
        assertEquals("inheritPoint", log.tag)
        assertEquals("200 포인트로 회피 확률 증가 1 단계 구입", log.text)
        assertEquals(100, log.ownerID)

        val payload = flush(world, recorder)
        assertTrue(payload.inheritanceKvWrites.any { it.key == "previous" })
        assertTrue(payload.inheritanceLogInserts.isNotEmpty())
        assertTrue(payload.updatedGenerals.any { it.id == 10 })
    }

    @Test
    fun `buyHiddenBuff denied when insufficient previous and nothing records`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf("isunited" to 0, "inheritancePrevious" to linkedMapOf(100 to 199.0)),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleBuyHiddenBuff(
            TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1),
        )

        assertEquals("충분한 유산 포인트를 가지고 있지 않습니다.", (res as opensamguk.common.wire.BuyHiddenBuffFail).reason)
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `buyRandomUnique deducts previous sets marker rank logs`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100)),
            stateMeta = linkedMapOf("isunited" to 0, "inheritancePrevious" to linkedMapOf(100 to 5000.0)),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder, nowMarkerProvider = { "MARK" })

        val res = handler.handleBuyRandomUnique(TurnDaemonCommand.BuyRandomUnique(generalId = 10))

        assertTrue(res is opensamguk.common.wire.BuyRandomUniqueOk)
        assertEquals(3000, (res as opensamguk.common.wire.BuyRandomUniqueOk).spent)

        @Suppress("UNCHECKED_CAST")
        val aux = world.getGeneralById(10)!!.meta["aux"] as Map<String, Any?>
        assertEquals("MARK", aux["inheritRandomUnique"])

        val invKv = recorder.inheritanceKvWrites().single { it.key == "previous" }
        @Suppress("UNCHECKED_CAST")
        assertEquals(2000.0, (invKv.value as List<Any?>)[0])
        assertEquals("3000 포인트로 랜덤 유니크 구입", recorder.inheritanceLogInserts().single().text)
    }

    @Test
    fun `buyRandomUnique denied when already ordered`() {
        val world = world(
            general = general(meta = linkedMapOf("owner" to 100, "aux" to linkedMapOf("inheritRandomUnique" to "prev"))),
            stateMeta = linkedMapOf("isunited" to 0, "inheritancePrevious" to linkedMapOf(100 to 9999.0)),
        )
        val recorder = ChangeRecorder()
        val handler = InheritResetHandler(world, recorder)

        val res = handler.handleBuyRandomUnique(TurnDaemonCommand.BuyRandomUnique(generalId = 10))

        assertEquals("이미 구입 명령을 내렸습니다. 다음 턴까지 기다려주세요.", (res as opensamguk.common.wire.BuyRandomUniqueFail).reason)
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
    }

    // ── dispatcher binding ──────────────────────────────────────────────────────

    /**
     * A no-op [AuctionRepository]/[AuctionBidRepository] via a reflection proxy — the intake commands
     * never call any repo method, so the proxy only needs to satisfy the dispatcher's constructor.
     */
    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T

    @Test
    fun `dispatcher routes the new intake command types to their handlers`() {
        val world = world()
        val recorder = ChangeRecorder()
        // repos are no-op proxies — only the intake handlers fire (they never touch a repo).
        val dispatcher = TurnDaemonCommandDispatcher(
            world, recorder,
            noopRepo<opensamguk.infra.read.AuctionRepository>(),
            noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            noopRepo<opensamguk.infra.read.BoardPostRepository>(),
        )

        val r = dispatcher.dispatch(TurnDaemonCommand.SetRate(generalId = 10, amount = 15))
        assertTrue((r as NationSettingResult).ok)
        assertEquals("setRate", r.type)
        assertEquals(15, world.getNationById(1)!!.meta["rate"])

        val enroll = dispatcher.dispatch(TurnDaemonCommand.TournamentEnroll(generalId = 10, value = 1))
        assertEquals("tournamentEnroll", (enroll as NationSettingResult).type)

        // 유산 구매 두 타입도 라우팅(handler가 result 반환 = silent no-op 아님).
        val buff = dispatcher.dispatch(TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 1))
        assertEquals("buyHiddenBuff", buff!!.type)
        val rnd = dispatcher.dispatch(TurnDaemonCommand.BuyRandomUnique(generalId = 10))
        assertEquals("buyRandomUnique", rnd!!.type)

        val resetStat = dispatcher.dispatch(TurnDaemonCommand.ResetStat(generalId = 10, leadership = 55, strength = 55, intel = 55))
        assertEquals("resetStat", resetStat!!.type)

        // an unbound type still returns null (control command).
        assertNull(dispatcher.dispatch(TurnDaemonCommand.Pause()))
    }
}
