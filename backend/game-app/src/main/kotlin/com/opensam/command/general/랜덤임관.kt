package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private val RANDOM_TALK_LIST = listOf(
    "어쩌다 보니",
    "인연이 닿아",
    "발길이 닿는 대로",
    "소문을 듣고",
    "점괘에 따라",
    "천거를 받아",
    "유명한",
    "뜻을 펼칠 곳을 찾아",
    "고향에 가까운",
    "천하의 균형을 맞추기 위해",
    "오랜 은거를 마치고",
)

class 랜덤임관(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "무작위 국가로 임관"

    override val fullConditionConstraints = listOf(
        BeNeutral(),
        AllowJoinAction(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val randomTalk = RANDOM_TALK_LIST[rng.nextInt(RANDOM_TALK_LIST.size)]

        // Actual nation assignment handled by turn engine
        pushLog("${randomTalk} 임관할 국가를 찾고 있습니다. <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"randomJoin":{"randomTalk":"$randomTalk"},"alternativeCommand":"che_인재탐색"}"""
        )
    }
}
