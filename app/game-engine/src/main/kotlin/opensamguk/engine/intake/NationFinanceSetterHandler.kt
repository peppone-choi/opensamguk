package opensamguk.engine.intake

import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.actions.intake.FinanceSetterOutcome
import opensamguk.logic.actions.intake.NationFinanceSetters

/**
 * 내무부 (nation-finance) 설정 핸들러 — the 7 `sammo/API/Nation/Set*.php` immediate actions.
 *
 * Mirrors [opensamguk.engine.betting.PlaceBetHandler]: a per-run plain class built against the live
 * [InMemoryTurnWorld] + [ChangeRecorder] (NOT a Spring bean). The pure validation/permission/value
 * logic lives in `:logic` [NationFinanceSetters]; this handler resolves the acting general, runs the
 * resolver, applies the world mutation, and records the delta through the recorder (the SINGLE dirty
 * source) so [opensamguk.infra.persistence.JdbcFlushExecutor] persists it.
 *
 * Write targets (V1 schema folds the scalar PHP `nation` columns into the meta jsonb):
 *  - rate/bill/secretlimit/scout/war → `nation.meta[key]` (recorded via [ChangeRecorder.diffNation]).
 *  - nationNotice/scout_msg/available_war_setting_cnt → `nation_env` KV ([ChangeRecorder.recordNationEnvKv]).
 *
 * NO game log is produced (the PHP setters return `{result:true}` with no logger push).
 */
class NationFinanceSetterHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val now: () -> String = { java.time.Instant.now().toString() },
) {

    fun handleSetNotice(c: TurnDaemonCommand.SetNotice): TurnDaemonCommandResult {
        val (me, nation) = resolve(c.generalId, "setNotice") ?: return notFound("setNotice", c.generalId)
        return when (val out = NationFinanceSetters.setNotice(PerTurnOverlay.toLogicGeneral(me), c.msg)) {
            is FinanceSetterOutcome.Denied -> deny("setNotice", c.generalId, nation.id, out.reason)
            is FinanceSetterOutcome.Notice -> {
                // nationNotice KV ({date, msg, author, authorID}) — insertion order matches PHP array.
                recorder.recordNationEnvKv(
                    nation.id,
                    "nationNotice",
                    linkedMapOf(
                        "date" to now(),
                        "msg" to out.purifiedMsg,
                        "author" to me.name,
                        "authorID" to me.id,
                    ),
                )
                ok("setNotice", c.generalId, nation.id)
            }
            else -> deny("setNotice", c.generalId, nation.id, "내부 오류")
        }
    }

    fun handleSetScoutMsg(c: TurnDaemonCommand.SetScoutMsg): TurnDaemonCommandResult {
        val (me, nation) = resolve(c.generalId, "setScoutMsg") ?: return notFound("setScoutMsg", c.generalId)
        return when (val out = NationFinanceSetters.setScoutMsg(PerTurnOverlay.toLogicGeneral(me), c.msg)) {
            is FinanceSetterOutcome.Denied -> deny("setScoutMsg", c.generalId, nation.id, out.reason)
            is FinanceSetterOutcome.ScoutMsg -> {
                recorder.recordNationEnvKv(nation.id, "scout_msg", out.purifiedMsg)
                ok("setScoutMsg", c.generalId, nation.id)
            }
            else -> deny("setScoutMsg", c.generalId, nation.id, "내부 오류")
        }
    }

    fun handleSetRate(c: TurnDaemonCommand.SetRate): TurnDaemonCommandResult =
        applyNationMeta("setRate", c.generalId) { me ->
            NationFinanceSetters.setRate(me, c.amount)
        }

    fun handleSetBill(c: TurnDaemonCommand.SetBill): TurnDaemonCommandResult =
        applyNationMeta("setBill", c.generalId) { me ->
            NationFinanceSetters.setBill(me, c.amount)
        }

    fun handleSetSecretLimit(c: TurnDaemonCommand.SetSecretLimit): TurnDaemonCommandResult =
        applyNationMeta("setSecretLimit", c.generalId) { me ->
            NationFinanceSetters.setSecretLimit(me, c.amount)
        }

    fun handleSetBlockScout(c: TurnDaemonCommand.SetBlockScout): TurnDaemonCommandResult =
        applyNationMeta("setBlockScout", c.generalId) { me ->
            NationFinanceSetters.setBlockScout(me, c.value, blockChangeScout = gameEnvFlag("block_change_scout"))
        }

    fun handleSetBlockWar(c: TurnDaemonCommand.SetBlockWar): TurnDaemonCommandResult {
        val (me, nation) = resolve(c.generalId, "setBlockWar") ?: return notFound("setBlockWar", c.generalId)
        val availableCnt = (nationEnv(nation)["available_war_setting_cnt"] as? Number)?.toInt() ?: 0
        return when (val out = NationFinanceSetters.setBlockWar(PerTurnOverlay.toLogicGeneral(me), c.value, availableCnt)) {
            is FinanceSetterOutcome.Denied -> deny("setBlockWar", c.generalId, nation.id, out.reason)
            is FinanceSetterOutcome.BlockWar -> {
                writeNationMeta(nation, "war", out.value)
                recorder.recordNationEnvKv(nation.id, "available_war_setting_cnt", out.nextAvailableCnt)
                ok("setBlockWar", c.generalId, nation.id)
            }
            else -> deny("setBlockWar", c.generalId, nation.id, "내부 오류")
        }
    }

    // ── shared helpers ──────────────────────────────────────────────────────────

    private fun applyNationMeta(
        type: String,
        generalId: Int,
        resolve0: (opensamguk.logic.domain.General) -> FinanceSetterOutcome,
    ): TurnDaemonCommandResult {
        val (me, nation) = resolve(generalId, type) ?: return notFound(type, generalId)
        return when (val out = resolve0(PerTurnOverlay.toLogicGeneral(me))) {
            is FinanceSetterOutcome.Denied -> deny(type, generalId, nation.id, out.reason)
            is FinanceSetterOutcome.NationMeta -> {
                writeNationMeta(nation, out.key, out.value)
                ok(type, generalId, nation.id)
            }
            else -> deny(type, generalId, nation.id, "내부 오류")
        }
    }

    /** Resolve `(general, nation)`; null when the general is absent or has no nation (PHP `!nation`). */
    private fun resolve(generalId: Int, @Suppress("UNUSED_PARAMETER") type: String): Pair<opensamguk.engine.turn.TurnGeneral, Nation>? {
        val me = world.getGeneralById(generalId) ?: return null
        val nation = world.getNationById(me.nationId) ?: return null
        return me to nation
    }

    /** Write `nation.meta[key] = value`, recording the dirty patch via the recorder (diffNation). */
    private fun writeNationMeta(nation: Nation, key: String, value: Int) {
        val pre = PerTurnOverlay.toLogicNation(nation)
        val nextMeta = LinkedHashMap(nation.meta)
        nextMeta[key] = value
        val next = nation.copy(meta = nextMeta)
        world.applyNationDirtyFree(next)                       // world read-state reflects it (dirty-free)
        recorder.diffNation(pre, PerTurnOverlay.toLogicNation(next))  // recorder = the single dirty source
    }

    @Suppress("UNCHECKED_CAST")
    private fun nationEnv(nation: Nation): Map<String, Any?> =
        (nation.meta["nation_env"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    private fun gameEnvFlag(key: String): Boolean = when (val v = world.getState().meta[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> false
    }

    private fun ok(type: String, generalId: Int, nationId: Int) =
        NationSettingResult(type = type, ok = true, generalId = generalId, nationId = nationId)

    private fun deny(type: String, generalId: Int, nationId: Int, reason: String) =
        NationSettingResult(type = type, ok = false, generalId = generalId, nationId = nationId, reason = reason)

    private fun notFound(type: String, generalId: Int) =
        NationSettingResult(type = type, ok = false, generalId = generalId, reason = "권한이 부족합니다.")
}
