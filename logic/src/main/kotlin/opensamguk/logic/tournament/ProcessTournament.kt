package opensamguk.logic.tournament

import opensamguk.common.rng.PhpMt19937
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

class TournamentProcessor(
    private val store: TournamentStore,
    private val betting: TournamentBettingPort,
    random: PhpMt19937 = PhpMt19937.ambient(),
    rankValue: (generalId: Int, type: String) -> Int = { _, _ -> 0 },
) {
    private val rankValues = LinkedHashMap<Pair<Int, String>, Int>()
    private val rankReader = rankValue
    private val fightEngine = TournamentFightEngine(random) { generalId, type ->
        rankValues.getOrPut(generalId to type) { rankReader(generalId, type) }
    }
    private val fightLogs = LinkedHashMap<Int, List<String>>()
    private val rankDeltas = mutableListOf<TournamentRankDelta>()

    fun process(state: TournamentState, now: Instant): TournamentProcessResult {
        val baseTime = state.time ?: return TournamentProcessResult(state, changed = false)
        if (!state.auto || now.isBefore(baseTime)) return TournamentProcessResult(state, changed = false)

        val unit = calcTournamentTermSeconds(state.turnTermMinutes)
        val offset = Duration.between(baseTime, now).seconds
        val iterations = Math.floorDiv(offset, unit.toLong()).toInt() + 1

        var next = state
        var changed = false
        for (i in 0 until iterations) {
            next = when (next.tournament) {
                1 -> {
                    seedPreliminaryGroups()
                    next.copy(tournament = 2, phase = 0)
                }
                2 -> {
                    playGroupPhase(next.type, next.phase, tournament = 2, groupBase = 0)
                    val phase = next.phase + 1
                    if (phase >= 56) {
                        promoteGroups(groupBase = 0, groupCount = 8, promotedPerGroup = 4)
                        next.copy(tournament = 3, phase = 0)
                    } else {
                        next.copy(phase = phase)
                    }
                }
                3 -> {
                    assignMainGroups(startGroup = 10, phase = next.phase)
                    val phase = next.phase + 8
                    if (phase >= 32) next.copy(tournament = 4, phase = 0) else next.copy(phase = phase)
                }
                4 -> {
                    playGroupPhase(next.type, next.phase, tournament = 4, groupBase = 10)
                    val phase = next.phase + 1
                    if (phase >= 6) {
                        promoteGroups(groupBase = 10, groupCount = 8, promotedPerGroup = 2)
                        next.copy(tournament = 5, phase = 0)
                    } else {
                        next.copy(phase = phase)
                    }
                }
                5 -> {
                    assignFinal16()
                    val bettingId = betting.open(next.type, unit, finalists())
                    val betTerm = min(unit * 60, 3600)
                    val bettingDeadline = baseTime.plusSeconds((unit * i + betTerm).toLong())
                    return TournamentProcessResult(
                        next.copy(
                            tournament = 6,
                            phase = 0,
                            lastBettingId = bettingId,
                            time = bettingDeadline,
                        ),
                        changed = true,
                        fightLogs = fightLogs,
                        rankDeltas = rankDeltas,
                    )
                }
                6 -> {
                    betting.close(next.lastBettingId)
                    next.copy(tournament = 7, phase = 0)
                }
                7 -> playKnockout(next, phaseLimit = 8, nextTournament = 8, sourceGroup = 20, targetGroup = 30)
                8 -> playKnockout(next, phaseLimit = 4, nextTournament = 9, sourceGroup = 30, targetGroup = 40)
                9 -> playKnockout(next, phaseLimit = 2, nextTournament = 10, sourceGroup = 40, targetGroup = 50)
                10 -> {
                    playKnockout(next, phaseLimit = 1, nextTournament = 0, sourceGroup = 50, targetGroup = 60)
                    val winnerId = store.entries().firstOrNull { it.group >= 60 }?.id ?: 0
                    if (winnerId > 0) betting.payout(next.lastBettingId, winnerId)
                    next.copy(tournament = 0, phase = 0, auto = false)
                }
                else -> next
            }
            changed = true
        }

        return TournamentProcessResult(
            next.copy(time = baseTime.plusSeconds(unit.toLong() * iterations)),
            changed = changed,
            fightLogs = fightLogs,
            rankDeltas = rankDeltas,
        )
    }

    fun reset(state: TournamentState): TournamentProcessResult {
        betting.refund(state.lastBettingId)
        store.clear()
        return TournamentProcessResult(
            state.copy(tournament = 0, phase = 0, auto = false, lastBettingId = 0),
            changed = true,
        )
    }

    private fun seedPreliminaryGroups() {
        val existing = store.entries()
        if (existing.isEmpty()) return
        val seeded = existing.mapIndexed { index, entry ->
            entry.copy(group = index % 8, groupNo = index / 8, seq = index + 1, promote = 0)
        }
        store.replaceAll(seeded)
    }

    private fun playGroupPhase(type: Int, phase: Int, tournament: Int, groupBase: Int) {
        val pair = tournamentPair(tournament, phase)
        val nextPair = if ((tournament == 2 && phase < 55) || (tournament == 4 && phase < 5)) {
            tournamentPair(tournament, phase + 1)
        } else {
            null
        }
        for (group in groupBase until groupBase + 8) {
            val members = store.entries().filter { it.group == group }.associateBy { it.groupNo }
            val left = members[pair.first] ?: continue
            val right = members[pair.second] ?: continue
            val nextNames = nextPair?.let { next ->
                val nextLeft = members[next.first]?.name ?: return@let null
                val nextRight = members[next.second]?.name ?: return@let null
                nextLeft to nextRight
            }
            val result = fightEngine.fight(
                tournamentType = type,
                tournament = tournament,
                phase = phase,
                group = group,
                left = left,
                right = right,
                decisive = false,
                nextPair = nextNames,
            )
            applyFight(result, group)
        }
    }

    private fun promoteGroups(groupBase: Int, groupCount: Int, promotedPerGroup: Int) {
        val promotedIds = LinkedHashSet<Int>()
        for (group in groupBase until groupBase + groupCount) {
            store.entries()
                .filter { it.group == group }
                .sortedWith(compareByDescending<TournamentEntry> { it.point }.thenByDescending { it.goal }.thenBy { it.seq })
                .take(promotedPerGroup)
                .forEachIndexed { index, entry ->
                    promotedIds.add(entry.id)
                    store.upsert(entry.copy(promote = index + 1))
                }
        }
        store.entries().filter { it.id !in promotedIds && it.group in groupBase until groupBase + groupCount }
            .forEach { store.upsert(it.copy(promote = 0)) }
    }

    private fun assignMainGroups(startGroup: Int, phase: Int) {
        val promoted = store.entries().filter { it.promote > 0 && it.group < 10 }.sortedWith(
            compareBy<TournamentEntry> { it.promote }.thenBy { it.group }.thenBy { it.seq },
        )
        if (promoted.isEmpty()) return
        val assigned = promoted.mapIndexed { index, entry ->
            entry.copy(group = startGroup + (phase + index) % 8, groupNo = index / 8, promote = 0)
        }
        assigned.forEach(store::upsert)
    }

    private fun assignFinal16() {
        val promoted = store.entries().filter { it.group in 10..17 && it.promote in 1..2 }
            .sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.promote }.thenBy { it.seq })
        promoted.take(16).forEachIndexed { index, entry ->
            store.upsert(entry.copy(group = 20 + index / 2, groupNo = index % 2, promote = 0))
        }
    }

    private fun playKnockout(
        state: TournamentState,
        phaseLimit: Int,
        nextTournament: Int,
        sourceGroup: Int,
        targetGroup: Int,
    ): TournamentState {
        val group = sourceGroup + state.phase
        val members = store.entries().filter { it.group == group }.sortedBy { it.groupNo }
        if (members.size >= 2) {
            val result = fightEngine.fight(
                tournamentType = state.type,
                tournament = state.tournament,
                phase = state.phase,
                group = group,
                left = members[0],
                right = members[1],
                decisive = true,
            )
            applyFight(result, group)
            val winner = when (result.selection) {
                0 -> result.left
                1 -> result.right
                else -> null
            }
            winner?.let {
                store.upsert(
                    it.copy(
                        group = targetGroup + state.phase / 2,
                        groupNo = state.phase % 2,
                        promote = 0,
                    ),
                )
            }
        }
        val phase = state.phase + 1
        return if (phase >= phaseLimit) state.copy(tournament = nextTournament, phase = 0) else state.copy(phase = phase)
    }

    private fun applyFight(result: TournamentFightResult, group: Int) {
        store.upsert(result.left)
        store.upsert(result.right)
        fightLogs[group] = result.logs
        for (delta in result.rankDeltas) {
            rankDeltas += delta
            val key = delta.generalId to delta.type
            rankValues[key] = (rankValues.getOrPut(key) { rankReader(delta.generalId, delta.type) }) + delta.amount
        }
    }

    private fun tournamentPair(tournament: Int, phase: Int): Pair<Int, Int> {
        val preliminary = listOf(
            0 to 1, 2 to 3, 4 to 5, 6 to 7,
            0 to 2, 1 to 3, 4 to 6, 5 to 7,
            0 to 3, 1 to 6, 2 to 5, 4 to 7,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
            0 to 5, 1 to 4, 2 to 7, 3 to 6,
            0 to 6, 1 to 7, 2 to 4, 3 to 5,
            0 to 7, 1 to 2, 3 to 4, 5 to 6,
        )
        val main = listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3, 0 to 3, 1 to 2)
        return when (tournament) {
            2 -> preliminary[phase % 28].let { if (phase >= 28) it.second to it.first else it }
            4 -> main[phase % 6]
            else -> error("Unsupported tournament phase: $tournament")
        }
    }

    private fun finalists(): List<TournamentEntry> =
        store.entries().filter { it.group >= 20 }.sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.groupNo })
}

fun calcTournamentTermSeconds(turnTermMinutes: Int): Int =
    min(max(turnTermMinutes, 5), 120)

fun tournamentTermText(turnTermMinutes: Int): String {
    val term = calcTournamentTermSeconds(turnTermMinutes)
    if (term % 60 == 0) return "경기당 ${term / 60}분"
    if (term > 60) return "경기당 ${term / 60}분 ${term % 60}초"
    return "경기당 ${term}초"
}
