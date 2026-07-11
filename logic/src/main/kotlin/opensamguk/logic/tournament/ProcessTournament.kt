package opensamguk.logic.tournament

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

class TournamentProcessor(
    private val store: TournamentStore,
    private val betting: TournamentBettingPort,
) {
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
                    playGroupPair(next.phase, groupBase = 0, groupCount = 8)
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
                    playGroupPair(next.phase, groupBase = 10, groupCount = 8)
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

    private fun playGroupPair(phase: Int, groupBase: Int, groupCount: Int) {
        val localPhase = phase / groupCount
        val group = groupBase + phase % groupCount
        val members = store.entries().filter { it.group == group }.sortedBy { it.groupNo }
        if (members.size < 2) return
        val pair = roundRobinPair(members.size, localPhase) ?: return
        resolveMatch(members[pair.first], members[pair.second], targetGroup = null)
    }

    private fun roundRobinPair(size: Int, phase: Int): Pair<Int, Int>? {
        val pairs = buildList {
            for (left in 0 until size) {
                for (right in left + 1 until size) add(left to right)
            }
        }
        return pairs.getOrNull(phase)
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
            val winner = resolveMatch(members[0], members[1], targetGroup = targetGroup + state.phase / 2)
            store.upsert(winner.copy(groupNo = state.phase % 2, promote = 0))
        }
        val phase = state.phase + 1
        return if (phase >= phaseLimit) state.copy(tournament = nextTournament, phase = 0) else state.copy(phase = phase)
    }

    private fun resolveMatch(left: TournamentEntry, right: TournamentEntry, targetGroup: Int?): TournamentEntry {
        val leftScore = score(left)
        val rightScore = score(right)
        val winner = if (leftScore >= rightScore) left else right
        val loser = if (winner.id == left.id) right else left
        val winnerNext = winner.copy(
            win = winner.win + 1,
            goal = winner.goal + max(1, kotlin.math.abs(leftScore - rightScore) / 10),
            group = targetGroup ?: winner.group,
        )
        val loserNext = loser.copy(lose = loser.lose + 1, goal = loser.goal - 1)
        store.upsert(loserNext)
        store.upsert(winnerNext)
        return winnerNext
    }

    private fun score(entry: TournamentEntry): Int = entry.total + entry.level

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
