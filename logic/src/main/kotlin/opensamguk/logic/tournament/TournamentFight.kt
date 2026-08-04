package opensamguk.logic.tournament

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.PhpMt19937
import opensamguk.logic.util.phpRound
import kotlin.math.log10

data class TournamentRankDelta(
    val generalId: Int,
    val type: String,
    val amount: Int,
)

data class TournamentFightResult(
    val left: TournamentEntry,
    val right: TournamentEntry,
    val selection: Int,
    val logs: List<String>,
    val rankDeltas: List<TournamentRankDelta>,
)

/**
 * PHP `hwe/func_tournament.php:1004-1393` (`fight`) on the native MT19937 stream.
 *
 * This stream is intentionally distinct from sammo's LiteHashDRBG. Tests pin [random] to the
 * captured PHP seed; production supplies an ambient instance because the PHP source uses its
 * process-global, automatically seeded native generator and exposes no deterministic game seed.
 */
class TournamentFightEngine(
    private val random: PhpMt19937,
    private val rankValue: (generalId: Int, type: String) -> Int = { _, _ -> 0 },
) {
    fun fight(
        tournamentType: Int,
        tournament: Int,
        phase: Int,
        group: Int,
        left: TournamentEntry,
        right: TournamentEntry,
        decisive: Boolean,
        nextPair: Pair<String, String>? = null,
    ): TournamentFightResult {
        val statKey = tournamentStatKey(tournamentType)
        val rankPrefix = tournamentRankPrefix(tournamentType)
        val leftStat = tournamentFightStat(left, tournamentType)
        val rightStat = tournamentFightStat(right, tournamentType)
        val logs = mutableListOf<String>()

        appendItemLogs(logs, left, tournamentType)
        appendItemLogs(logs, right, tournamentType)

        val levelRatio = levelRatio(left.level, right.level)
        val initialLeftEnergy = phpRound(leftStat * levelRatio * 10)
        val initialRightEnergy = phpRound(rightStat * levelRatio * 10)
        var leftEnergy = initialLeftEnergy
        var rightEnergy = initialRightEnergy
        logs += "<S>●</> <Y>${left.name}</> <C>($leftEnergy)</> vs <C>($rightEnergy)</> <Y>${right.name}</>"

        var leftGoal = 0
        var rightGoal = 0
        var turn = 0
        var selection = 2
        val turnLimit = if (decisive) 100 else 10
        while (turn < turnLimit) {
            turn += 1
            var damageToLeft = phpRound(rightStat * (random.modulo(21) + 90) / 130.0)
            var damageToRight = phpRound(leftStat * (random.modulo(21) + 90) / 130.0)

            if (leftStat >= random.modulo(100)) {
                damageToRight += phpRound(leftStat * (random.modulo(41) + 10) / 130.0)
            }
            if (rightStat >= random.modulo(100)) {
                damageToLeft += phpRound(rightStat * (random.modulo(41) + 10) / 130.0)
            }

            var leftCritical = false
            var rightCritical = false
            var leftFactor = 1
            var rightFactor = 1
            val leftRageRatio = random.modulo(300)
            if (initialLeftEnergy / 5.0 > leftEnergy &&
                damageToLeft > damageToRight &&
                leftStat >= leftRageRatio
            ) {
                rightFactor = phpRound((random.modulo(301) + 200) / 100.0)
                leftCritical = true
                logs += "<S>●</> <Y>${left.name}</>의 분노의 <M>${randomChoice(CRITICAL_SKILLS.getValue(statKey))}</> 공격!"
            }
            val rightRageRatio = random.modulo(300)
            if (initialRightEnergy / 5.0 > rightEnergy &&
                damageToRight > damageToLeft &&
                rightStat >= rightRageRatio
            ) {
                leftFactor = phpRound((random.modulo(301) + 200) / 100.0)
                rightCritical = true
                logs += "<S>●</> <Y>${right.name}</>의 분노의 <M>${randomChoice(CRITICAL_SKILLS.getValue(statKey))}</> 공격!"
            }
            damageToLeft *= leftFactor
            damageToRight *= rightFactor

            if (turn == 1) {
                val ratio = random.modulo(400)
                if (leftStat * 0.9 > rightStat && leftStat >= ratio) {
                    damageToLeft = 0
                    damageToRight = initialRightEnergy
                    logs += "<S>●</> <Y>${left.name}</>의 <M>${FATALITY_SKILLS.getValue(statKey)}</>!"
                }
                if (rightStat * 0.9 > leftStat && rightStat >= ratio) {
                    damageToRight = 0
                    damageToLeft = initialLeftEnergy
                    logs += "<S>●</> <Y>${right.name}</>의 <M>${FATALITY_SKILLS.getValue(statKey)}</>!"
                }
            } else {
                val leftSkillRatio = random.modulo(1000)
                if (!leftCritical && leftStat >= leftSkillRatio) {
                    damageToRight = phpRound(damageToRight * random.range(150, 300) / 100.0)
                    leftCritical = true
                    logs += "<S>●</> <Y>${left.name}</>의 <M>${randomChoice(SKILLS.getValue(statKey))}</>!"
                }
                val rightSkillRatio = random.modulo(1000)
                if (!rightCritical && rightStat >= rightSkillRatio) {
                    damageToLeft = phpRound(damageToLeft * random.range(150, 300) / 100.0)
                    logs += "<S>●</> <Y>${right.name}</>의 <M>${randomChoice(SKILLS.getValue(statKey))}</>!"
                }
            }

            val rawDamageToLeft = damageToLeft
            val rawDamageToRight = damageToRight
            leftEnergy -= damageToLeft
            rightEnergy -= damageToRight
            val rawLeftEnergy = leftEnergy
            val rawRightEnergy = rightEnergy
            if (leftEnergy <= 0 && rightEnergy <= 0) {
                val leftRatio = rawLeftEnergy.toDouble() / rawDamageToLeft.coerceAtLeast(1)
                val rightRatio = rawRightEnergy.toDouble() / rawDamageToRight.coerceAtLeast(1)
                if (leftRatio > rightRatio) {
                    val offset = phpRound(rawRightEnergy * rawDamageToLeft.toDouble() / rawDamageToRight.coerceAtLeast(1))
                    damageToLeft += offset
                    leftEnergy -= offset
                    damageToRight += rawRightEnergy
                    rightEnergy = 0
                } else {
                    val offset = phpRound(rawLeftEnergy * rawDamageToRight.toDouble() / rawDamageToLeft.coerceAtLeast(1))
                    damageToRight += offset
                    rightEnergy -= offset
                    damageToLeft += rawLeftEnergy
                    leftEnergy = 0
                }
            } else if (leftEnergy * rightEnergy <= 0) {
                if (rightEnergy < 0) {
                    val offset = phpRound(rawRightEnergy * rawDamageToLeft.toDouble() / rawDamageToRight.coerceAtLeast(1))
                    damageToLeft += offset
                    leftEnergy -= offset
                    damageToRight += rawRightEnergy
                    rightEnergy = 0
                }
                if (leftEnergy < 0) {
                    val offset = phpRound(rawLeftEnergy * rawDamageToRight.toDouble() / rawDamageToLeft.coerceAtLeast(1))
                    damageToRight += offset
                    rightEnergy -= offset
                    damageToLeft += rawLeftEnergy
                    leftEnergy = 0
                }
            }
            leftGoal += damageToLeft
            rightGoal += damageToRight

            logs += "<S>●</> ${turn.toString().padStart(2, '0')}合 : " +
                "<C>${leftEnergy.toString().padStart(3, '0')}</>" +
                "<span class=\"ev_highlight\">(-${damageToLeft.toString().padStart(3, '0')})</span> vs " +
                "<span class=\"ev_highlight\">(-${damageToRight.toString().padStart(3, '0')})</span>" +
                "<C>${rightEnergy.toString().padStart(3, '0')}</>"

            if (leftEnergy <= 0 && rightEnergy <= 0) {
                if (!decisive) {
                    selection = 2
                    break
                }
                leftEnergy = phpRound(initialLeftEnergy / 2.0)
                rightEnergy = phpRound(initialRightEnergy / 2.0)
                logs += "<S>●</> <span class='ev_highlight'>재대결</span>!"
            }
            if (leftEnergy <= 0) {
                selection = 1
                break
            }
            if (rightEnergy <= 0) {
                selection = 0
                break
            }
        }

        val leftRankGoal = rankValue(left.id, "${rankPrefix}g")
        val rightRankGoal = rankValue(right.id, "${rankPrefix}g")
        val rankDeltas = mutableListOf<TournamentRankDelta>()
        val nextLeft: TournamentEntry
        val nextRight: TournamentEntry
        when (selection) {
            0 -> {
                logs += "<S>●</> <Y>${left.name}</> <S>승리</>!"
                val goal = phpRound((rightGoal - leftGoal) / 50.0)
                nextLeft = left.copy(win = left.win + 1, goal = left.goal + goal)
                nextRight = right.copy(lose = right.lose + 1, goal = right.goal - goal)
                val (leftGoalRank, rightGoalRank) = rankGoalDeltas(leftRankGoal, rightRankGoal)
                rankDeltas += TournamentRankDelta(left.id, "${rankPrefix}w", 1)
                rankDeltas += TournamentRankDelta(left.id, "${rankPrefix}g", leftGoalRank)
                rankDeltas += TournamentRankDelta(right.id, "${rankPrefix}l", 1)
                rankDeltas += TournamentRankDelta(right.id, "${rankPrefix}g", rightGoalRank)
            }
            1 -> {
                logs += "<S>●</> <Y>${right.name}</> <S>승리</>!"
                val goal = phpRound((leftGoal - rightGoal) / 50.0)
                nextLeft = left.copy(lose = left.lose + 1, goal = left.goal - goal)
                nextRight = right.copy(win = right.win + 1, goal = right.goal + goal)
                val (rightGoalRank, leftGoalRank) = rankGoalDeltas(rightRankGoal, leftRankGoal)
                rankDeltas += TournamentRankDelta(left.id, "${rankPrefix}l", 1)
                rankDeltas += TournamentRankDelta(left.id, "${rankPrefix}g", leftGoalRank)
                rankDeltas += TournamentRankDelta(right.id, "${rankPrefix}w", 1)
                rankDeltas += TournamentRankDelta(right.id, "${rankPrefix}g", rightGoalRank)
            }
            else -> {
                logs += "<S>●</> 무승부!"
                nextLeft = left.copy(draw = left.draw + 1)
                nextRight = right.copy(draw = right.draw + 1)
                val leftGoalRank = when {
                    leftRankGoal > rightRankGoal -> 1
                    leftRankGoal == rightRankGoal -> 0
                    else -> -1
                }
                rankDeltas += TournamentRankDelta(left.id, "${rankPrefix}d", 1)
                rankDeltas += TournamentRankDelta(left.id, "${rankPrefix}g", leftGoalRank)
                rankDeltas += TournamentRankDelta(right.id, "${rankPrefix}d", 1)
                rankDeltas += TournamentRankDelta(right.id, "${rankPrefix}g", -leftGoalRank)
            }
        }

        if ((tournament == 2 && phase < 55) || (tournament == 4 && phase < 5)) {
            nextPair?.let {
                logs += "--------------- 다음경기 ---------------<br><S>☞</> <Y>${it.first}</> vs <Y>${it.second}</>"
            }
        }
        return TournamentFightResult(nextLeft, nextRight, selection, logs, rankDeltas)
    }

    private fun appendItemLogs(logs: MutableList<String>, entry: TournamentEntry, tournamentType: Int) {
        item(entry.horse)?.takeIf { !it.buyable && tournamentType in setOf(0, 1) }?.let { horse ->
            logs += when (random.modulo(4)) {
                0 -> "<S>●</> <Y>${entry.name}</>의 <S>${horse.name}</>${JosaUtil.pick(horse.rawName, "이")} 포효합니다!"
                1 -> "<S>●</> <Y>${entry.name}</>의 <S>${horse.name}</>${JosaUtil.pick(horse.rawName, "이")} 그 위용을 뽐냅니다!"
                2 -> "<S>●</> <Y>${entry.name}</>${JosaUtil.pick(entry.name, "이")} <S>${horse.name}</>${JosaUtil.pick(horse.rawName, "을")} 타고 있습니다!"
                else -> "<S>●</> <Y>${entry.name}</>의 <S>${horse.name}</>${JosaUtil.pick(horse.rawName, "이")} 갈기를 휘날립니다!"
            }
        }
        item(entry.weapon)?.takeIf { !it.buyable && tournamentType in setOf(0, 2) }?.let { weapon ->
            logs += when (random.modulo(4)) {
                0 -> "<S>●</> <Y>${entry.name}</>의 <S>${weapon.name}</>${JosaUtil.pick(weapon.rawName, "이")} 번뜩입니다!"
                1 -> "<S>●</> <Y>${entry.name}</>의 <S>${weapon.name}</>${JosaUtil.pick(weapon.rawName, "이")} 푸르게 빛납니다!"
                2 -> "<S>●</> <Y>${entry.name}</>의 <S>${weapon.name}</>에서 살기가 느껴집니다!"
                else -> "<S>●</> <Y>${entry.name}</>의 손에는 <S>${weapon.name}</>${JosaUtil.pick(weapon.rawName, "이")} 쥐어져 있습니다!"
            }
        }
        item(entry.book)?.takeIf { !it.buyable && tournamentType in setOf(0, 3) }?.let { book ->
            logs += when (random.modulo(4)) {
                0 -> "<S>●</> <Y>${entry.name}</>${JosaUtil.pick(entry.name, "이")} <S>${book.name}</>${JosaUtil.pick(book.rawName, "을")} 펼쳐듭니다!"
                1 -> "<S>●</> <Y>${entry.name}</>${JosaUtil.pick(entry.name, "이")} <S>${book.name}</>${JosaUtil.pick(book.rawName, "을")} 품에서 꺼냅니다!"
                2 -> "<S>●</> <Y>${entry.name}</>${JosaUtil.pick(entry.name, "이")} <S>${book.name}</>${JosaUtil.pick(book.rawName, "을")} 들고 있습니다!"
                else -> "<S>●</> <Y>${entry.name}</>의 손에는 <S>${book.name}</>${JosaUtil.pick(book.rawName, "이")} 쥐어져 있습니다!"
            }
        }
    }

    private fun item(code: String): TournamentItem? {
        if (code == "None" || code.isBlank()) return null
        val tokens = code.split('_')
        if (tokens.size < 4) return null
        val value = tokens[tokens.size - 2].toIntOrNull() ?: return null
        val rawName = tokens.last()
        return TournamentItem(rawName, "$rawName(+$value)", buyable = value < 7)
    }

    private fun randomChoice(values: List<String>): String = values[random.arrayIndex(values.size)]

    companion object {
        private val TYPE_KEYS = listOf("total", "leadership", "strength", "intel")
        private val RANK_PREFIXES = listOf("tt", "tl", "ts", "ti")
        private val CRITICAL_SKILLS = mapOf(
            "total" to listOf("전력", "집중"),
            "leadership" to listOf("봉시진", "어린진"),
            "strength" to listOf("삼단", "나선"),
            "intel" to listOf("독설", "논파"),
        )
        private val FATALITY_SKILLS = mapOf(
            "total" to "압도",
            "leadership" to "팔문금쇄진",
            "strength" to "일격 필살",
            "intel" to "모독 욕설",
        )
        private val SKILLS = mapOf(
            "total" to listOf("참격", "집중", "역공", "반격", "선제", "도발"),
            "leadership" to listOf("추행진", "학익진", "장사진", "형액진", "기형진", "구행진"),
            "strength" to listOf("기합", "기염", "반격", "역공", "삼단", "나선"),
            "intel" to listOf("논파", "항변", "반론", "반박", "도발", "면박"),
        )

        private fun tournamentStatKey(type: Int): String =
            TYPE_KEYS.getOrElse(type) { error("Unsupported tournament type: $type") }

        private fun tournamentRankPrefix(type: Int): String =
            RANK_PREFIXES.getOrElse(type) { error("Unsupported tournament type: $type") }

        private fun tournamentFightStat(entry: TournamentEntry, type: Int): Double = when (type) {
            0 -> (entry.leadership + entry.strength + entry.intel) * 7.0 / 15.0
            1 -> entry.leadership.toDouble()
            2 -> entry.strength.toDouble()
            3 -> entry.intel.toDouble()
            else -> error("Unsupported tournament type: $type")
        }

        private fun levelRatio(leftLevel: Int, rightLevel: Int): Double =
            if (leftLevel >= rightLevel) {
                1 + log10(1.0 + leftLevel - rightLevel) / 10
            } else {
                1 - log10(1.0 + rightLevel - leftLevel) / 10
            }

        private fun rankGoalDeltas(winnerGoal: Int, loserGoal: Int): Pair<Int, Int> = when {
            winnerGoal > loserGoal -> 1 to 0
            winnerGoal == loserGoal -> 2 to -1
            else -> 3 to -2
        }
    }
}

private data class TournamentItem(
    val rawName: String,
    val name: String,
    val buyable: Boolean,
)
