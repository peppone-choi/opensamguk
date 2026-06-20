package opensamguk.engine.intake

import opensamguk.common.wire.BuyHiddenBuffFail
import opensamguk.common.wire.BuyHiddenBuffOk
import opensamguk.common.wire.BuyRandomUniqueFail
import opensamguk.common.wire.BuyRandomUniqueOk
import opensamguk.common.wire.InheritResetSpecialWarFail
import opensamguk.common.wire.InheritResetSpecialWarOk
import opensamguk.common.wire.InheritResetTurnTimeFail
import opensamguk.common.wire.InheritResetTurnTimeOk
import opensamguk.common.wire.InheritSetNextSpecialWarFail
import opensamguk.common.wire.InheritSetNextSpecialWarOk
import opensamguk.common.wire.ResetStatFail
import opensamguk.common.wire.ResetStatOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.intake.InheritBuys
import opensamguk.logic.actions.intake.InheritResetOutcome
import opensamguk.logic.actions.intake.InheritResets
import opensamguk.logic.actions.intake.ResetStatOutcome

/**
 * 유산 (inheritance) reset/reserve 핸들러 — `ResetTurnTime` / `ResetSpecialWar` / `SetNextSpecialWar`.
 *
 * Mirrors [opensamguk.engine.betting.PlaceBetHandler]. The pure spend/RNG/log logic is in `:logic`
 * [InheritResets]; this handler resolves the acting general + the env inputs the PHP reads, runs the
 * resolver, and records the delta through the recorder (the SINGLE dirty source):
 *  - general aux/var mutation → [ChangeRecorder.diffGeneral] (rides `general.meta`).
 *  - `previous` balance write → [ChangeRecorder.recordInheritancePointSet] (game_kv inheritance_{owner}).
 *  - `inherit_point_spent_dynamic` bump → [ChangeRecorder.recordRankIncrease].
 *  - the userLogger line → [ChangeRecorder.recordInheritanceLog] (inheritance_log, tag "inheritPoint").
 *
 * Inputs read in-world (the env the PHP `launch` reads):
 *  - owner id: `general.meta["owner"]` (PHP `general.owner`; falls back to generalId when absent).
 *  - `previous` balance: [previousPointReader] (the inheritance_{owner} `previous[0]`; default reads
 *    the world-state `inheritancePrevious` snapshot map seeded at rehydrate).
 *  - isunited / turnterm / hiddenSeed: `world.getState().meta`.
 *  - aux levels / special2: `general.meta["aux"]` / `general.meta["special2"]`.
 *  - special-war display name: [specialWarName] (default = the type key — the registry name lookup
 *    is a display-only string; flagged for the P8 golden — see [InheritResets.setNextSpecialWar]).
 */
class InheritResetHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val previousPointReader: (ownerId: Int) -> Double = { ownerId ->
        @Suppress("UNCHECKED_CAST")
        (ownerScopedMetaValue(world.getState().meta["inheritancePrevious"] as? Map<*, *>, ownerId) as? Number)
            ?.toDouble()
            ?: 0.0
    },
    private val lastStatResetReader: (ownerId: Int) -> List<Int> = { ownerId ->
        @Suppress("UNCHECKED_CAST")
        (ownerScopedMetaValue(world.getState().meta["lastStatReset"] as? Map<*, *>, ownerId) as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?: emptyList()
    },
    private val specialWarName: (type: String) -> String = { it },
    /**
     * BuyRandomUnique의 `aux.inheritRandomUnique` 마커 값 공급자(PHP `TimeUtil::now()`). 값은 다음 턴
     * 가드(`!== null`)의 non-null 마커로만 쓰여 로그/차감/패리티와 무관 — 기본은 데몬 현재 시각.
     */
    private val nowMarkerProvider: () -> Any = { world.getState().lastTurnTime.toString() },
) {

    fun handleResetTurnTime(c: TurnDaemonCommand.InheritResetTurnTime): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return InheritResetTurnTimeFail(generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val ownerId = ownerId(me)
        val currentLevel = (aux(me)["inheritResetTurnTime"] as? Number)?.toInt() ?: -1
        val nextTurnTimeBase = (aux(me)["nextTurnTimeBase"] as? Number)?.let { it.toString() } ?: me.turnTime.toString()

        val out = InheritResets.resetTurnTime(
            userId = ownerId,
            currentLevel = currentLevel,
            previousPoint = previousPointReader(ownerId),
            isUnited = isUnited(),
            turnTerm = turnTerm(),
            hiddenSeed = hiddenSeed(),
            currentTurnTimeOrBase = nextTurnTimeBase,
        )
        return when (out) {
            is InheritResetOutcome.Denied -> InheritResetTurnTimeFail(generalId = c.generalId, reason = out.reason)
            is InheritResetOutcome.Applied -> {
                apply(me, ownerId, out)
                InheritResetTurnTimeOk(generalId = c.generalId, spent = out.spent)
            }
        }
    }

    fun handleResetSpecialWar(c: TurnDaemonCommand.InheritResetSpecialWar): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return InheritResetSpecialWarFail(generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val ownerId = ownerId(me)
        val currentLevel = (aux(me)["inheritResetSpecialWar"] as? Number)?.toInt() ?: -1
        @Suppress("UNCHECKED_CAST")
        val prevTypes = (aux(me)["prev_types_special2"] as? List<*>)?.map { it.toString() } ?: emptyList()

        val out = InheritResets.resetSpecialWar(
            currentLevel = currentLevel,
            previousPoint = previousPointReader(ownerId),
            isUnited = isUnited(),
            currentSpecialWar = me.role.specialWar,
            prevTypesSpecial2 = prevTypes,
        )
        return when (out) {
            is InheritResetOutcome.Denied -> InheritResetSpecialWarFail(generalId = c.generalId, reason = out.reason)
            is InheritResetOutcome.Applied -> {
                apply(me, ownerId, out)
                InheritResetSpecialWarOk(generalId = c.generalId, spent = out.spent)
            }
        }
    }

    fun handleSetNextSpecialWar(c: TurnDaemonCommand.InheritSetNextSpecialWar): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return InheritSetNextSpecialWarFail(generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val ownerId = ownerId(me)
        val reserved = aux(me)["inheritSpecificSpecialWar"] as? String

        val out = InheritResets.setNextSpecialWar(
            type = c.specialWar,
            previousPoint = previousPointReader(ownerId),
            isUnited = isUnited(),
            currentSpecialWar = me.role.specialWar,
            reservedSpecialWar = reserved,
            specialWarName = specialWarName(c.specialWar),
        )
        return when (out) {
            is InheritResetOutcome.Denied -> InheritSetNextSpecialWarFail(generalId = c.generalId, reason = out.reason)
            is InheritResetOutcome.Applied -> {
                apply(me, ownerId, out)
                InheritSetNextSpecialWarOk(generalId = c.generalId, spent = out.spent)
            }
        }
    }

    fun handleResetStat(c: TurnDaemonCommand.ResetStat): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return ResetStatFail(generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val ownerId = ownerId(me)
        val out = InheritResets.resetStat(
            userId = ownerId,
            leadership = c.leadership,
            strength = c.strength,
            intel = c.intel,
            inheritBonusStat = c.inheritBonusStat,
            previousPoint = previousPointReader(ownerId),
            isUnited = isUnited(),
            season = season(),
            lastStatReset = lastStatResetReader(ownerId),
            npcType = me.npcState,
            hiddenSeed = hiddenSeed(),
        )
        return when (out) {
            is ResetStatOutcome.Denied -> ResetStatFail(generalId = c.generalId, reason = out.reason)
            is ResetStatOutcome.Applied -> {
                applyResetStat(me, ownerId, out)
                ResetStatOk(
                    generalId = c.generalId,
                    spent = out.spent,
                    leadership = out.nextLeadership,
                    strength = out.nextStrength,
                    intel = out.nextIntel,
                )
            }
        }
    }

    /**
     * BuyHiddenBuff.php. 히든 버프 레벨 구매 — prevLevel은 general `aux.inheritBuff[buffKey]`에서
     * **서버측** 산출(클라 무시). 누적 차분 비용을 inheritance `previous`에서 차감하고
     * `aux.inheritBuff[buffKey]=level` + rank + inheritance_log를 기록한다.
     */
    fun handleBuyHiddenBuff(c: TurnDaemonCommand.BuyHiddenBuff): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return BuyHiddenBuffFail(generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val ownerId = ownerId(me)
        val out = InheritBuys.buyHiddenBuff(
            type = c.buffKey,
            level = c.level,
            currentBuffMap = currentInheritBuff(me),
            previousPoint = previousPointReader(ownerId),
            isUnited = isUnited(),
        )
        return when (out) {
            is InheritResetOutcome.Denied -> BuyHiddenBuffFail(generalId = c.generalId, reason = out.reason)
            is InheritResetOutcome.Applied -> {
                apply(me, ownerId, out)
                BuyHiddenBuffOk(generalId = c.generalId, spent = out.spent)
            }
        }
    }

    /**
     * BuyRandomUnique.php. 랜덤 유니크 확정 드롭 플래그 구매 — `aux.inheritRandomUnique`가 이미 non-null이면
     * deny. inheritItemRandomPoint(3000) 차감 + 마커 적재 + rank + inheritance_log.
     */
    fun handleBuyRandomUnique(c: TurnDaemonCommand.BuyRandomUnique): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return BuyRandomUniqueFail(generalId = c.generalId, reason = "장수가 존재하지 않습니다.")
        val ownerId = ownerId(me)
        val out = InheritBuys.buyRandomUnique(
            alreadyOrdered = aux(me)["inheritRandomUnique"] != null,
            previousPoint = previousPointReader(ownerId),
            isUnited = isUnited(),
            nowMarker = nowMarkerProvider(),
        )
        return when (out) {
            is InheritResetOutcome.Denied -> BuyRandomUniqueFail(generalId = c.generalId, reason = out.reason)
            is InheritResetOutcome.Applied -> {
                apply(me, ownerId, out)
                BuyRandomUniqueOk(generalId = c.generalId, spent = out.spent)
            }
        }
    }

    // ── shared apply ──────────────────────────────────────────────────────────────

    /**
     * Apply an [InheritResetOutcome.Applied]: mutate the general (aux + special2), record the dirty
     * patch, the inheritance `previous` write, the rank bump, and the inheritance_log line. Side-effect
     * order mirrors the PHP `launch`: logger push → setAuxVar/setVar → previous set → rank increase.
     */
    private fun apply(me: TurnGeneral, ownerId: Int, out: InheritResetOutcome.Applied) {
        // 1. inheritance_log push (UserLogger, tag "inheritPoint").
        recorder.recordInheritanceLog(ownerId, out.log, "inheritPoint")

        // 2. general aux + var updates.
        val pre = PerTurnOverlay.toLogicGeneral(me)
        val nextMeta = LinkedHashMap(me.meta)
        @Suppress("UNCHECKED_CAST")
        val nextAux = LinkedHashMap((nextMeta["aux"] as? Map<String, Any?>) ?: emptyMap())
        for ((k, v) in out.auxUpdates) nextAux[k] = v
        nextMeta["aux"] = nextAux

        var next = me.copy(meta = nextMeta)
        // var updates: only `special2` is touched by these resets (rides role.specialWar).
        out.varUpdates["special2"]?.let { sw ->
            next = next.copy(role = next.role.copy(specialWar = if (sw == "None") "None" else sw.toString()))
        }
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))

        // 3. previous balance write (inheritance_{owner} key "previous" = [remaining, null]).
        recorder.recordInheritancePointSet(ownerId, "previous", out.remainingPrevious, null)

        // 4. rank bump inherit_point_spent_dynamic += spent.
        recorder.recordRankIncrease(me.id, RankColumn.INHERIT_SPENT_DYN, out.spent)
    }

    private fun applyResetStat(me: TurnGeneral, ownerId: Int, out: ResetStatOutcome.Applied) {
        for (log in out.logs) {
            recorder.recordInheritanceLog(ownerId, log, "inheritPoint")
        }

        val pre = PerTurnOverlay.toLogicGeneral(me)
        val next = me.copy(stats = GeneralStats(out.nextLeadership, out.nextStrength, out.nextIntel))
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))

        recorder.recordInheritancePointSet(ownerId, "previous", out.remainingPrevious, null)
        recorder.recordKv("user", "user_$ownerId", "last_stat_reset", out.nextLastStatReset)
        recorder.recordRankIncrease(me.id, RankColumn.INHERIT_SPENT_DYN, out.spent)
    }

    // ── env readers ─────────────────────────────────────────────────────────────

    private fun ownerId(me: TurnGeneral): Int = (me.meta["owner"] as? Number)?.toInt() ?: me.id

    @Suppress("UNCHECKED_CAST")
    private fun aux(me: TurnGeneral): Map<String, Any?> = (me.meta["aux"] as? Map<String, Any?>) ?: emptyMap()

    /** `aux.inheritBuff`(PHP `?? []`)를 `Map<buffKey, level>`로. 값은 Number/Int 모두 허용해 Int로 정규화. */
    private fun currentInheritBuff(me: TurnGeneral): Map<String, Int> =
        (aux(me)["inheritBuff"] as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to ((it.value as? Number)?.toInt() ?: 0) }
            ?: emptyMap()

    private fun isUnited(): Boolean = ((world.getState().meta["isunited"] as? Number)?.toInt() ?: 0) != 0

    private fun turnTerm(): Int = (world.getState().meta["turnterm"] as? Number)?.toInt() ?: 1

    private fun hiddenSeed(): String = world.getState().meta["hiddenSeed"] as? String ?: ""

    private fun season(): Int =
        (world.getState().meta["season"] as? Number)?.toInt()
            ?: (world.getState().meta["server_generation"] as? Number)?.toInt()
            ?: (world.getState().meta["serverCnt"] as? Number)?.toInt()
            ?: 0
}

private fun ownerScopedMetaValue(map: Map<*, *>?, ownerId: Int): Any? =
    map?.get(ownerId) ?: map?.get(ownerId.toString())
