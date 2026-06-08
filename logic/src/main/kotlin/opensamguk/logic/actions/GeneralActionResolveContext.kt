package opensamguk.logic.actions

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.message.Message

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
     * The PARSED reserved arg map (PHP `$this->arg` after `argTest()`/initWithArg). reqArg commands read
     * their normalized args here (징병: crewType/amount; 이동: destCityID; 발령/포상/국호변경/국기변경/천도
     * read their reqArgs). Default empty for no-arg commands.
     */
    val args: Map<String, Any?> = emptyMap(),
    /**
     * Preloaded candidate generals for the multi-row general-UPDATE commands (이동 roaming-leader moves
     * EVERY same-nation general; 집합 moves the troop's members). These mirror the PHP DB query result
     * (`SELECT … FROM general WHERE nation=me AND …`) that the daemon/precheck adapters stage in; the
     * resolver applies the PHP WHERE predicate in-memory, mutates the matches, and appends the moved
     * copies to [GeneralActionDraft.cascadeGenerals]. Default empty for single-general commands.
     */
    val candidateGenerals: List<General> = emptyList(),
    /**
     * Preloaded troop name for 집합 (PHP `SELECT name FROM troop WHERE troop_leader = me`). The follower
     * PLAIN log embeds it (`{troopName} 부대원들은 …`). Default empty.
     */
    val troopName: String = "",
    // Names are not modeled on the logic entities (General/City carry no name); the daemon/precheck
    // adapters supply them per-turn. Optional + defaulted so the P1 call sites stay source-compatible.
    val generalName: String = "",          // actor general name — the <Y>{name}</> global/dest log token
    val destGeneralName: String = "",       // dest general name (발령/포상) — the <Y>{name}</> dest log token
    // PHP che_발령.php:162 `cutTurn(actor) != cutTurn(dest)` — the dest general's turn falls in a
    // DIFFERENT turn bucket than the actor's, which bumps `last발령` by 1. The turn-bucket comparison
    // is engine-level wall-clock math (turnTime is not on the logic General), so the decision is
    // supplied by the adapter; defaults false.
    val destDifferentTurnBucket: Boolean = false,
    // PHP che_국호변경.php:128 `SELECT name FROM nation WHERE name=? LIMIT 1` — a RUNTIME dup-name
    // re-check inside run() (AFTER the constraints pass), which the precheck constraint
    // (CheckNationNameDuplicate) cannot stand in for because the world may change between reserve and
    // run. The full-nation scan is engine/precheck-adapter work; the result is threaded here.
    val runtimeNameDuplicate: Boolean = false,
    // 천도 distance = CalcCityDistance(capital, dest, ownedCitySet) ?? 50 (che_천도.php:115). The
    // owned-city allow-list is engine data (city ownership not on the logic draft), so the resolved
    // distance is threaded; null → the PHP `?? 50` fallback.
    val cityDistance: Int? = null,
    // 무작위수도이전 candidate neutral level-5/6 city ids (che_무작위수도이전.php:98 SELECT). The world
    // scan is engine data; rng->choice picks one of these in resolve.
    val candidateCityIds: List<Int> = emptyList(),
    // 게임 env `turnterm`(턴 간격 분, PHP `$env['turnterm']`). 외교 제의 명령(불가침/종전/불가침파기)이
    // 서신 validUntil = date + max(30, turnterm*3)분 공식([opensamguk.logic.actions.nation.DiplomacySeam])에
    // 쓴다. turnterm은 logic 엔티티에 없는 월드/엔진 데이터라 어댑터(ReservedTurnHandler)가 per-turn으로
    // 주입한다 — defaulted=60(prod 기본값)이라 기존 호출부는 source-compatible. 외교 외 명령은 미사용.
    val turnterm: Int = 60,
    // P4 BO3 — the battle-command carrier (BattleCommandContextBuilder output) the che_출병 resolver reads:
    // the BFS distanceList + per-city defenders + raw rows + war-seed inputs. null for non-battle commands.
    val battleContext: opensamguk.logic.war.BattleCommandContext? = null,
    private val logs: MutableList<String> = mutableListOf(),
    private val destLogs: MutableMap<Int, MutableList<String>> = linkedMapOf(),
    private val destPlainLogs: MutableMap<Int, MutableList<String>> = linkedMapOf(),
    private val globalActionLogs: MutableList<String> = mutableListOf(),
    private val plainLogs: MutableList<String> = mutableListOf(),
    private val messages: MutableList<Message> = mutableListOf(),
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

    /**
     * Buffer a PLAIN-format line into the ACTOR's OWN action-log stream ([logs]), no MONTH prefix.
     * PHP `$logger->pushGeneralActionLog(body, ActionLogger::PLAIN)` on the actor's own logger
     * (e.g. che_물자원조.php:226) — a PLAIN-rendered line that IS part of the actor's action log
     * (golden `logLines`), unlike [addPlainLog] which routes to the separate plainLogs side-bucket
     * (per-target 이동/집합 + 레벨 변동 lines). Same `<C>●</>{text}` render, different stream.
     */
    fun addActionPlainLog(text: String) { if (text.isNotEmpty()) logs.add("<C>●</>$text") }

    /**
     * Buffer a PLAIN-format line on a DEST general's own action-log scope. PHP che_포상.php:174
     * `$destGeneral->getLogger()->pushGeneralActionLog($body, ActionLogger::PLAIN)` — PLAIN format
     * `<C>●</>{body}` (no MONTH prefix), routed to the dest general's bucket.
     */
    fun addPlainLogTo(targetGeneralId: Int, text: String) {
        if (text.isEmpty()) return
        destPlainLogs.getOrPut(targetGeneralId) { mutableListOf() }.add("<C>●</>$text")
    }

    fun logs(): List<String> = logs.toList()
    fun logsTo(targetGeneralId: Int): List<String> = destLogs[targetGeneralId]?.toList() ?: emptyList()
    fun plainLogsTo(targetGeneralId: Int): List<String> = destPlainLogs[targetGeneralId]?.toList() ?: emptyList()
    fun globalActionLogs(): List<String> = globalActionLogs.toList()
    fun plainLogs(): List<String> = plainLogs.toList()

    /** Buffer a [Message] to send (the engine routes it to the mailbox channel, receiver-before-sender). */
    fun sendMessage(message: Message) { messages.add(message) }

    fun messages(): List<Message> = messages.toList()
}
