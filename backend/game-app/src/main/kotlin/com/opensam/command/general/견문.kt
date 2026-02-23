package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.entity.General
import kotlin.math.min
import kotlin.random.Random

// Legacy-parity flag values (hex bitmask matching PHP/TS)
private const val INC_EXP = 0x1
private const val INC_HEAVY_EXP = 0x2
private const val INC_LEADERSHIP = 0x10
private const val INC_STRENGTH = 0x20
private const val INC_INTEL = 0x40
private const val INC_GOLD = 0x100
private const val INC_RICE = 0x200
private const val DEC_GOLD = 0x400
private const val DEC_RICE = 0x800
private const val WOUNDED = 0x1000
private const val HEAVY_WOUNDED = 0x2000

private data class SightseeingEntry(val flags: Int, val texts: List<String>, val weight: Int)

private val SIGHTSEEING_EVENTS = listOf(
    SightseeingEntry(INC_EXP, listOf(
        "아무일도 일어나지 않았습니다.",
        "명사와 설전을 벌였으나 망신만 당했습니다.",
        "동네 장사와 힘겨루기를 했지만 망신만 당했습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP, listOf(
        "주점에서 사람들과 어울려 술을 마셨습니다.",
        "위기에 빠진 사람을 구해주었습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or INC_LEADERSHIP, listOf(
        "백성들에게 현인의 가르침을 설파했습니다.",
        "어느 집의 도망친 가축을 되찾아 주었습니다."
    ), 2),
    SightseeingEntry(INC_HEAVY_EXP or INC_STRENGTH, listOf(
        "동네 장사와 힘겨루기를 하여 멋지게 이겼습니다.",
        "어느 집의 무너진 울타리를 고쳐주었습니다."
    ), 2),
    SightseeingEntry(INC_HEAVY_EXP or INC_INTEL, listOf(
        "어느 명사와 설전을 벌여 멋지게 이겼습니다.",
        "거리에서 글 모르는 아이들을 모아 글을 가르쳤습니다."
    ), 2),
    SightseeingEntry(INC_EXP or INC_GOLD, listOf(
        "지나가는 행인에게서 금을 :goldAmount: 받았습니다."
    ), 1),
    SightseeingEntry(INC_EXP or INC_RICE, listOf(
        "지나가는 행인에게서 쌀을 :riceAmount: 받았습니다."
    ), 1),
    SightseeingEntry(INC_EXP or DEC_GOLD, listOf(
        "산적을 만나 금 :goldAmount:을 빼앗겼습니다.",
        "돈을 :goldAmount: 빌려주었다가 떼어먹혔습니다."
    ), 1),
    SightseeingEntry(INC_EXP or DEC_RICE, listOf(
        "쌀을 :riceAmount: 빌려주었다가 떼어먹혔습니다."
    ), 1),
    SightseeingEntry(INC_EXP or WOUNDED, listOf(
        "호랑이에게 물려 다쳤습니다.",
        "곰에게 할퀴어 다쳤습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or WOUNDED, listOf(
        "위기에 빠진 사람을 구해주다가 다쳤습니다."
    ), 1),
    SightseeingEntry(INC_EXP or HEAVY_WOUNDED, listOf(
        "호랑이에게 물려 크게 다쳤습니다.",
        "곰에게 할퀴어 크게 다쳤습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or WOUNDED or HEAVY_WOUNDED, listOf(
        "위기에 빠진 사람을 구하다가 죽을뻔 했습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or INC_STRENGTH or INC_GOLD, listOf(
        "산적과 싸워 금 :goldAmount:을 빼앗았습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or INC_STRENGTH or INC_RICE, listOf(
        "호랑이를 잡아 고기 :riceAmount:을 얻었습니다.",
        "곰을 잡아 고기 :riceAmount:을 얻었습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or INC_INTEL or INC_GOLD, listOf(
        "돈을 빌려주었다가 이자 :goldAmount:을 받았습니다."
    ), 1),
    SightseeingEntry(INC_HEAVY_EXP or INC_INTEL or INC_RICE, listOf(
        "쌀을 빌려주었다가 이자 :riceAmount:을 받았습니다."
    ), 1),
)

private fun pickByWeight(rng: Random): Pair<Int, String> {
    val totalWeight = SIGHTSEEING_EVENTS.sumOf { it.weight }
    if (totalWeight <= 0) return Pair(0, "")
    var cursor = rng.nextDouble() * totalWeight
    for (entry in SIGHTSEEING_EVENTS) {
        cursor -= entry.weight
        if (cursor <= 0) {
            val text = entry.texts[rng.nextInt(entry.texts.size)]
            return Pair(entry.flags, text)
        }
    }
    val last = SIGHTSEEING_EVENTS.last()
    return Pair(last.flags, last.texts[rng.nextInt(last.texts.size)])
}

class 견문(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "견문"

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val (flags, rawText) = pickByWeight(rng)

        var exp = 0
        val changes = mutableMapOf<String, Any>()
        var text = rawText

        if (flags and INC_EXP != 0) exp += 30
        if (flags and INC_HEAVY_EXP != 0) exp += 60
        if (flags and INC_LEADERSHIP != 0) changes["leadershipExp"] = 2
        if (flags and INC_STRENGTH != 0) changes["strengthExp"] = 2
        if (flags and INC_INTEL != 0) changes["intelExp"] = 2
        if (flags and INC_GOLD != 0) {
            changes["gold"] = 300
            text = text.replace(":goldAmount:", "300")
        }
        if (flags and INC_RICE != 0) {
            changes["rice"] = 300
            text = text.replace(":riceAmount:", "300")
        }
        if (flags and DEC_GOLD != 0) {
            changes["gold"] = -200
            text = text.replace(":goldAmount:", "200")
        }
        if (flags and DEC_RICE != 0) {
            changes["rice"] = -200
            text = text.replace(":riceAmount:", "200")
        }
        if (flags and WOUNDED != 0) {
            changes["injury"] = rng.nextInt(10, 21) // Legacy: nextRangeInt(10, 20) is inclusive
        }
        if (flags and HEAVY_WOUNDED != 0) {
            val delta = rng.nextInt(20, 51) // Legacy: nextRangeInt(20, 50) inclusive
            val existing = (changes["injury"] as? Int) ?: 0
            changes["injury"] = existing + delta
        }

        // Legacy: injury capped at 80
        if (changes.containsKey("injury")) {
            changes["injuryMax"] = 80
        }

        changes["experience"] = exp

        pushLog("${text} <1>$date</>")

        val changesJson = changes.entries.joinToString(",") { "\"${it.key}\":${it.value}" }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{$changesJson}}"""
        )
    }
}
