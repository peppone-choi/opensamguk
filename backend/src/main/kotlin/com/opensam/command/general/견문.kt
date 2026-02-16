package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.entity.General
import kotlin.random.Random

private const val INC_EXP = 1
private const val INC_HEAVY_EXP = 2
private const val INC_LEADERSHIP = 4
private const val INC_STRENGTH = 8
private const val INC_INTEL = 16
private const val INC_GOLD = 32
private const val INC_RICE = 64
private const val DEC_GOLD = 128
private const val DEC_RICE = 256
private const val WOUNDED = 512
private const val HEAVY_WOUNDED = 1024

private data class SightseeingEvent(val type: Int, val text: String)

private val SIGHTSEEING_EVENTS = listOf(
    SightseeingEvent(INC_EXP, "경치 좋은 곳에서 휴식을 취했습니다."),
    SightseeingEvent(INC_EXP or INC_LEADERSHIP, "유능한 인재들과 교류했습니다."),
    SightseeingEvent(INC_EXP or INC_STRENGTH, "산적들과 싸워 물리쳤습니다."),
    SightseeingEvent(INC_EXP or INC_INTEL, "고서를 발견하여 읽었습니다."),
    SightseeingEvent(INC_GOLD, "길에서 금 300을 주웠습니다."),
    SightseeingEvent(INC_RICE, "농부에게 군량 300을 받았습니다."),
    SightseeingEvent(DEC_GOLD, "도적에게 금 200을 빼앗겼습니다."),
    SightseeingEvent(DEC_RICE, "군량 200이 상했습니다."),
    SightseeingEvent(INC_HEAVY_EXP, "뜻밖의 귀인을 만났습니다."),
    SightseeingEvent(WOUNDED, "넘어져서 부상을 입었습니다."),
    SightseeingEvent(HEAVY_WOUNDED, "맹수에게 습격당해 크게 다쳤습니다."),
    SightseeingEvent(0, "별다른 일이 없었습니다."),
)

class 견문(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "견문"

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val event = SIGHTSEEING_EVENTS[rng.nextInt(SIGHTSEEING_EVENTS.size)]
        val type = event.type

        var exp = 0
        val changes = mutableMapOf<String, Int>()

        if (type and INC_EXP != 0) exp += 30
        if (type and INC_HEAVY_EXP != 0) exp += 60
        if (type and INC_LEADERSHIP != 0) changes["leadershipExp"] = 2
        if (type and INC_STRENGTH != 0) changes["strengthExp"] = 2
        if (type and INC_INTEL != 0) changes["intelExp"] = 2
        if (type and INC_GOLD != 0) changes["gold"] = 300
        if (type and INC_RICE != 0) changes["rice"] = 300
        if (type and DEC_GOLD != 0) changes["gold"] = -200
        if (type and DEC_RICE != 0) changes["rice"] = -200
        if (type and WOUNDED != 0) changes["injury"] = rng.nextInt(10, 20)
        if (type and HEAVY_WOUNDED != 0) changes["injury"] = rng.nextInt(20, 50)

        changes["experience"] = exp

        pushLog("${event.text} <1>$date</>")

        val changesJson = changes.entries.joinToString(",") { "\"${it.key}\":${it.value}" }

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{$changesJson}}"""
        )
    }
}
