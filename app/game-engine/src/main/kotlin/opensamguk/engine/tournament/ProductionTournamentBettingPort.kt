package opensamguk.engine.tournament

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.RankColumn
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.GameKvRepository
import opensamguk.infra.read.InheritanceRepository
import opensamguk.logic.betting.BettingEngine
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.BettingItem
import opensamguk.logic.betting.BettingWorldView
import opensamguk.logic.betting.GeneralForBetting
import opensamguk.logic.betting.SelectItem
import opensamguk.logic.tournament.TournamentBettingPort
import opensamguk.logic.tournament.TournamentEntry
import opensamguk.logic.tournament.tournamentTypeText
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonDecodeAny
import opensamguk.logic.util.phpRound

data class TournamentBettingRow(
    val generalId: Int,
    val userId: Int?,
    val bettingType: String,
    val amount: Int,
)

class ProductionTournamentBettingPort(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val bettingInfoReader: (Int) -> BettingInfo?,
    private val bettingRowsReader: (Int) -> List<TournamentBettingRow>,
    private val nextBettingIdReader: () -> Int,
    private val previousPointReader: (Int) -> Double,
) : TournamentBettingPort {

    constructor(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        gameKvRepository: GameKvRepository,
        bettingRepository: BettingRepository,
        inheritanceRepository: InheritanceRepository,
    ) : this(
        world = world,
        recorder = recorder,
        bettingInfoReader = gameKvRepository.bettingInfoReader(),
        bettingRowsReader = { bettingId ->
            bettingRepository.findByBettingId(bettingId).map {
                TournamentBettingRow(
                    generalId = it.generalId,
                    userId = it.userId,
                    bettingType = it.bettingType,
                    amount = it.amount,
                )
            }
        },
        nextBettingIdReader = gameKvRepository.lastBettingIdReader(),
        previousPointReader = inheritanceRepository.previousPointReader(),
    )

    override fun open(type: Int, unitSeconds: Int, finalists: List<TournamentEntry>): Int {
        val bettingId = nextBettingIdReader() + 1
        val state = world.getState()
        val yearMonth = state.currentYear * 12 + state.currentMonth - 1
        val statName = tournamentStatName(type)
        val statValue = tournamentStatValue(type)
        val candidates = linkedMapOf<Int, SelectItem>()
        finalists.sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.groupNo })
            .take(16)
            .forEachIndexed { idx, entry ->
                if (entry.id > 0) {
                    candidates[entry.id] = SelectItem(
                        title = entry.name,
                        info = "$statName: ${statValue(entry)}",
                        aux = linkedMapOf(
                            "no" to entry.id,
                            "generalId" to entry.id,
                            "npc" to entry.npc,
                            "name" to entry.name,
                            "win" to entry.win,
                            "leadership" to entry.leadership,
                            "strength" to entry.strength,
                            "intel" to entry.intel,
                            "total" to entry.total,
                            "idx" to idx,
                            "group" to entry.group,
                        ),
                    )
                }
            }

        val info = BettingInfo(
            id = bettingId,
            type = "tournament",
            name = tournamentTypeText(type),
            finished = false,
            selectCnt = 1,
            isExclusive = null,
            reqInheritancePoint = false,
            openYearMonth = yearMonth,
            closeYearMonth = yearMonth + 120,
            candidates = candidates,
            winner = null,
        )
        saveBettingInfo(info)
        recorder.recordKv("game_env", "game_env", "last_betting_id", bettingId)
        recorder.recordKv("game_env", "game_env", "last_tournament_betting_id", bettingId)
        world.pushLog(
            LogEntryDraft(
                scope = "global",
                category = "history",
                text = "<S>◆</>${state.currentYear}년 ${state.currentMonth}월:<B><b>【대회】</b></>우승자를 예상하는 <C>내기</>가 진행중입니다! 호사가의 참여를 기다립니다!",
            ),
        )

        placeNpcBets(info)
        return bettingId
    }

    override fun close(bettingId: Int) {
        val info = bettingInfoReader(bettingId) ?: return
        val state = world.getState()
        info.closeYearMonth = state.currentYear * 12 + state.currentMonth - 1
        saveBettingInfo(info)
    }

    override fun refund(bettingId: Int) {
        val info = bettingInfoReader(bettingId) ?: return
        if (!info.finished) reward(info, listOf(-1))
    }

    override fun payout(bettingId: Int, winnerId: Int) {
        val info = bettingInfoReader(bettingId) ?: return
        reward(info, listOf(winnerId))
    }

    private fun placeNpcBets(info: BettingInfo) {
        val targetList = info.candidates.keys.toList()
        if (targetList.isEmpty()) return

        val state = world.getState()
        val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: state.currentYear
        val betGold = maxOf(kotlin.math.floor((3 + state.currentYear - startYear) * 0.334).toInt() * 10, 10)
        val hiddenSeed = state.meta["hiddenSeed"] as? String ?: ""
        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "OpenBettingTournament", info.id)))

        world.listGenerals()
            .filter { it.npcState >= 2 && it.gold >= 500 + betGold }
            .forEach { npc ->
                val target = rng.choice(targetList)
                recorder.recordBettingInsert(
                    linkedMapOf(
                        "betting_id" to info.id,
                        "general_id" to npc.id,
                        "user_id" to null,
                        "betting_type" to opensamguk.logic.util.jsonEncode(listOf(target)),
                        "amount" to betGold,
                    ),
                )
                world.updateGeneral(npc.copy(gold = npc.gold - betGold))
                recorder.recordRankIncrease(npc.id, RankColumn.BETGOLD, betGold)
            }
    }

    private fun reward(info: BettingInfo, winnerType: List<Int>) {
        BettingEngine(info).giveReward(
            winnerType,
            bettingRowsReader(info.id).map {
                BettingItem(
                    bettingId = info.id,
                    generalId = it.generalId,
                    userId = it.userId,
                    bettingType = it.bettingType,
                    amount = it.amount,
                )
            },
            TournamentBettingWorldView(world, recorder, previousPointReader) { saveBettingInfo(it) },
        )
    }

    private fun saveBettingInfo(info: BettingInfo) {
        recorder.recordKv("betting", "betting", "id_${info.id}", info.toKvMap())
    }
}

private class TournamentBettingWorldView(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val previousPointReader: (Int) -> Double,
    private val saver: (BettingInfo) -> Unit,
) : BettingWorldView {
    override fun generalsById(ids: List<Int>): Map<Int, GeneralForBetting> =
        ids.mapNotNull { id ->
            world.getGeneralById(id)?.let { id to GeneralForBetting(id = id, npcType = it.npcState, name = it.name) }
        }.toMap()

    override fun addGeneralGold(generalId: Int, amount: Int) {
        val general = world.getGeneralById(generalId) ?: return
        world.updateGeneral(general.copy(gold = general.gold + amount))
    }

    override fun increaseRankData(generalId: Int, type: String, amount: Double) {
        val column = RankColumn.byColumn(type) ?: return
        recorder.recordRankIncrease(generalId, column, phpRound(amount))
    }

    override fun getRankVar(generalId: Int, type: String, default: Int): Int = default

    override fun increaseInheritancePointRaw(userId: Int, amount: Double): Double {
        val current = previousPointReader(userId)
        val next = current + amount
        recorder.recordInheritancePointSet(userId, "previous", next, null)
        return next
    }

    override fun pushUserLogs(userId: Int, lines: List<String>, type: String) {
        for (line in lines) recorder.recordInheritanceLog(userId, line, type)
    }

    override fun pushGeneralActionLog(generalId: Int, msg: String) {
        world.pushLog(LogEntryDraft(scope = "general", category = "action", generalId = generalId, text = msg))
    }

    override fun saveBettingInfo(info: BettingInfo) = saver(info)
}

private fun BettingInfo.toKvMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "type" to type,
        "name" to name,
        "finished" to finished,
        "selectCnt" to selectCnt,
        "isExclusive" to isExclusive,
        "reqInheritancePoint" to reqInheritancePoint,
        "openYearMonth" to openYearMonth,
        "closeYearMonth" to closeYearMonth,
        "candidates" to candidates.mapKeys { it.key.toString() }.mapValues { (_, item) ->
            linkedMapOf(
                "title" to item.title,
                "info" to item.info,
                "isHtml" to item.isHtml,
                "aux" to item.aux,
            )
        },
        "winner" to winner,
    )

private fun GameKvRepository.bettingInfoReader(): (Int) -> BettingInfo? = { bettingId ->
    findByTable("betting").firstNotNullOfOrNull { row ->
        runCatching { jsonDecode(row.value) }.getOrNull()
            ?.let { BettingInfo.fromKvMap(it) }
            ?.takeIf { it.id == bettingId }
    }
}

private fun GameKvRepository.lastBettingIdReader(): () -> Int = {
    findByTable("game_env").firstNotNullOfOrNull { row ->
        if (row.namespace == "game_env" && row.key == "last_betting_id") {
            runCatching { jsonDecodeAny(row.value) }.getOrNull()?.let { (it as? Number)?.toInt() }
        } else {
            null
        }
    } ?: 0
}

private fun InheritanceRepository.previousPointReader(): (Int) -> Double = { ownerId ->
    findByTableAndNamespaceAndKey("inheritance", "inheritance_$ownerId", "previous")
        ?.let { row ->
            ((runCatching { jsonDecodeAny(row.value) }.getOrNull() as? List<*>)
                ?.getOrNull(0) as? Number)?.toDouble()
        } ?: 0.0
}

private fun tournamentStatName(type: Int): String =
    listOf("종능", "통솔", "무력", "지력").getOrElse(type) { "종능" }

private fun tournamentStatValue(type: Int): (TournamentEntry) -> Int =
    when (type) {
        1 -> { it -> it.leadership }
        2 -> { it -> it.strength }
        3 -> { it -> it.intel }
        else -> { it -> it.total }
    }
