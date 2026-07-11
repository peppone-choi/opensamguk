package opensamguk.logic.tournament

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import opensamguk.logic.util.PhpMt19937

class TournamentProcessor(
    private val store: TournamentStore,
    private val betting: TournamentBettingPort,
    rank: TournamentRankPort = NoopTournamentRankPort,
    fightLog: TournamentFightLogPort = NoopTournamentFightLogPort,
    // PHP는 전역 mt_rand(미시드 = 엔트로피 자동 시드)를 쓴다 — 파리티는 시드 고정 시에만 성립(골든 경로).
    rng: PhpMt19937 = PhpMt19937((System.nanoTime() and 0x7FFFFFFFL).toInt()),
) {
    // 한 프로세스 틱의 모든 fight가 같은 스트림을 순서대로 소비 (PHP 전역 mt_rand 상태와 동일).
    private val fightEngine = TournamentFightEngine(store, rank, fightLog, rng)

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
                    // qualify() (func_tournament.php:583-612): getTwo(2,phase) 페어로 8조 각 1경기
                    playQualifyPhase(next.type, tnmt = 2, phase = next.phase, groupBase = 0)
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
                    // finallySingle() (func_tournament.php:688-717): getTwo(4,phase) 페어로 10~17조 각 1경기
                    playQualifyPhase(next.type, tnmt = 4, phase = next.phase, groupBase = 10)
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

    /**
     * qualify()/finallySingle()의 조별 페이즈 — getTwo 페어로 8개 조 각 1경기(fight type=0 승무패).
     * 8경기가 같은 RNG 스트림을 순서대로 소비한다(PHP 전역 mt_rand — qualify 골든이 증명).
     */
    private fun playQualifyPhase(tnmtType: Int, tnmt: Int, phase: Int, groupBase: Int) {
        val cand = getTwo(tnmt, phase) ?: return
        for (grp in groupBase until groupBase + 8) {
            // PHP는 fillLowGenAll이 8×8을 무명장수로 채워 항상 성립 — 미충족 스토어만 방어(파리티 상태에선 미도달).
            val members = store.entries().filter { it.group == grp }
            if (members.none { it.groupNo == cand.first } || members.none { it.groupNo == cand.second }) continue
            fightEngine.fight(tnmtType, tnmt, phase, grp, cand.first, cand.second, 0)
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
            // finalFight (func_tournament.php:765-808): fight(grp, 0, 1, type=1 승패전) 후 승자 진출.
            val sel = fightEngine.fight(state.type, state.tournament, state.phase, group, 0, 1, 1)
            // PHP는 `win>0 AND (grp_no=0 OR grp_no=1)` 행 조회 — 매 라운드 새 행(win=0)이라 이번 승자와 동치.
            // sel=2(100합 무승부)는 PHP도 null 행 접근으로 fatal — 동일하게 실패시킨다.
            val winnerNo = when (sel) {
                0 -> 0
                1 -> 1
                else -> error("MustNotBeReached: knockout draw (PHP fight sel=2 → null row fatal)")
            }
            val winner = store.entries().first { it.group == group && it.groupNo == winnerNo }
            // PHP는 승자를 다음 라운드 조에 새 행으로 INSERT(이전 행 보존)하지만, 스토어가 id-키라
            // 승자 행 이동으로 대체(기존 상태머신 계약 유지 — tools/php-golden/tournament-capture-backlog.md).
            store.upsert(winner.copy(group = targetGroup + state.phase / 2, groupNo = state.phase % 2, promote = 0))
        }
        val phase = state.phase + 1
        return if (phase >= phaseLimit) state.copy(tournament = nextTournament, phase = 0) else state.copy(phase = phase)
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
