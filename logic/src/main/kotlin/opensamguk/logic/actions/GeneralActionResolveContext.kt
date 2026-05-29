package opensamguk.logic.actions

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.domain.WorldEnv

/**
 * Mutable per-turn draft. The resolver mutates these in place (the Immer-draft replacement);
 * ChangeRecorder (game-engine) diffs pre/post to derive patch+dirty.
 *
 * P2 (Task FS1) adds:
 *  - `destGeneral`/`destCity`/`destNation` — the secondary target the personnel/nation commands
 *    mutate + persist (발령/포상 write a SECOND general; 등용 reads a dest general; 임관/천도 a dest
 *    city/nation). Default null.
 *  - the CREATED-set — brand-new entities INSERTed by founding commands (거병 INSERTs a nation +
 *    one diplomacy row per existing nation + 24 nation_turn rows).
 *  - the CASCADE-set — existing entities bulk-mutated by 방랑 (the lord's nation reverts to wandering,
 *    cascading over every city/general/diplomacy of that nation).
 */
class GeneralActionDraft(
    var general: General,
    var city: City,
    var nation: Nation?,
) {
    // --- dest carriers (personnel / nation-internal secondary target) ---
    var destGeneral: General? = null
    var destCity: City? = null
    var destNation: Nation? = null

    // --- created-set (founding: 거병 INSERTs nation + diplomacy + nation_turn) ---
    val createdNations: MutableList<Nation> = mutableListOf()
    val createdDiplomacy: MutableList<Diplomacy> = mutableListOf()
    val createdNationTurns: MutableList<NationTurn> = mutableListOf()

    // --- cascade-set (방랑: bulk-mutate every general/city/diplomacy of the abandoned nation) ---
    val cascadeGenerals: MutableList<General> = mutableListOf()
    val cascadeCities: MutableList<City> = mutableListOf()
    val cascadeDiplomacy: MutableList<Diplomacy> = mutableListOf()
}

class GeneralActionResolveContext(
    val draft: GeneralActionDraft,
    val rng: RandUtil,
    val env: WorldEnv,
    val month: Int,                        // game month — the ActionLogger MONTH-format prefix companion to env.year
    val date: String,                      // turn-time HH:MM for the log <1>date</>
    /**
     * The PARSED reserved arg map (PHP `$this->arg` after `argTest()`). reqArg commands read their
     * normalized args here (징병: crewType/amount; 이동: destCityID). Default empty for no-arg commands.
     */
    val args: Map<String, Any?> = emptyMap(),
    private val logs: MutableList<String> = mutableListOf(),
    private val destLogs: MutableMap<Int, MutableList<String>> = linkedMapOf(),
    private val globalActionLogs: MutableList<String> = mutableListOf(),
    private val plainLogs: MutableList<String> = mutableListOf(),
) {
    /**
     * Buffer an action-log line, applying PHP `ActionLogger::pushGeneralActionLog`'s DEFAULT
     * `MONTH` format (ActionLogger.php:135 + formatText:250): `<C>●</>{month}월:{body}`.
     * The resolver passes the BODY (mirroring che `run()` which passes the body to
     * `pushGeneralActionLog`); the logger boundary owns the month prefix. (G2 byte oracle.)
     */
    fun addLog(text: String) { if (text.isNotEmpty()) logs.add("<C>●</>${month}월:$text") }

    /**
     * Buffer a line on a DEST general's own action-log scope (a separate bucket keyed by
     * `targetGeneralId`) — 발령/포상/등용수락 write to a second general's log, and 방랑/이동 push a
     * PLAIN line to each follower's logger. The TARGET logger receives the body as-is; the
     * resolver decides the format via [addPlainLog] vs a MONTH body, mirroring PHP's
     * `$targetLogger->pushGeneralActionLog(...)`. We store the raw body the resolver hands over.
     */
    fun addLogTo(targetGeneralId: Int, text: String) {
        if (text.isEmpty()) return
        destLogs.getOrPut(targetGeneralId) { mutableListOf() }.add(text)
    }

    /**
     * Buffer a GLOBAL (broadcast, general_id=0) action-log line. PHP
     * `ActionLogger::pushGlobalActionLog` DEFAULTS to the same `MONTH` format (ActionLogger.php:199
     * + formatText:250) — `<C>●</>{month}월:{body}` — but routes to a distinct global bucket.
     */
    fun addGlobalActionLog(text: String) {
        if (text.isNotEmpty()) globalActionLogs.add("<C>●</>${month}월:$text")
    }

    /**
     * Buffer a PLAIN-format line (no MONTH prefix). PHP `ActionLogger::formatText` PLAIN
     * (ActionLogger.php:238) = `<C>●</>{text}` — the per-target 이동/집합 lines whose body already
     * carries its own `<1>HH:MM</>` suffix. No `{month}월:` segment.
     */
    fun addPlainLog(text: String) {
        if (text.isNotEmpty()) plainLogs.add("<C>●</>$text")
    }

    fun logs(): List<String> = logs.toList()
    fun logsTo(targetGeneralId: Int): List<String> = destLogs[targetGeneralId]?.toList() ?: emptyList()
    fun globalActionLogs(): List<String> = globalActionLogs.toList()
    fun plainLogs(): List<String> = plainLogs.toList()
}
