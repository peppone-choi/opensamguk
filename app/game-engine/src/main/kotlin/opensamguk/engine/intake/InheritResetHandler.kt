package opensamguk.engine.intake

import opensamguk.common.wire.InheritResetSpecialWarFail
import opensamguk.common.wire.InheritResetSpecialWarOk
import opensamguk.common.wire.InheritResetTurnTimeFail
import opensamguk.common.wire.InheritResetTurnTimeOk
import opensamguk.common.wire.InheritSetNextSpecialWarFail
import opensamguk.common.wire.InheritSetNextSpecialWarOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.intake.InheritResetOutcome
import opensamguk.logic.actions.intake.InheritResets

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
        ((world.getState().meta["inheritancePrevious"] as? Map<*, *>)?.get(ownerId) as? Number)?.toDouble() ?: 0.0
    },
    private val specialWarName: (type: String) -> String = { it },
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

    // ── env readers ─────────────────────────────────────────────────────────────

    private fun ownerId(me: TurnGeneral): Int = (me.meta["owner"] as? Number)?.toInt() ?: me.id

    @Suppress("UNCHECKED_CAST")
    private fun aux(me: TurnGeneral): Map<String, Any?> = (me.meta["aux"] as? Map<String, Any?>) ?: emptyMap()

    private fun isUnited(): Boolean = ((world.getState().meta["isunited"] as? Number)?.toInt() ?: 0) != 0

    private fun turnTerm(): Int = (world.getState().meta["turnterm"] as? Number)?.toInt() ?: 1

    private fun hiddenSeed(): String = world.getState().meta["hiddenSeed"] as? String ?: ""
}
