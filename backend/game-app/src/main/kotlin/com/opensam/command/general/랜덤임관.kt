package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.math.pow
import kotlin.math.sqrt
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
        val generalName = general.name
        val josaYi = pickJosa(generalName, "이")
        val randomTalk = RANDOM_TALK_LIST[rng.nextInt(RANDOM_TALK_LIST.size)]

        val relYear = env.year - env.startYear
        val genLimit = if (relYear < 3) env.initialNationGenLimit else env.defaultMaxGeneral

        // Nation selection is delegated to the turn engine, but we provide
        // the weighting algorithm parameters and all necessary data
        // so the engine can pick using the inverse-cube-power formula.
        //
        // Legacy algorithm for player generals:
        //   For each candidate nation, compute:
        //     warpower = sum of (killcrew/deathcrew ratio * leadership * npc_coef) for generals with leadership>=40
        //     develpower = sum of (sqrt(intel*strength)*2 + leadership/2) / 5
        //     calcCnt = warpower + develpower
        //     if player and nation name starts with 'ⓤ': calcCnt *= 100
        //     weight = (1/calcCnt)^3
        //   Pick nation weighted by these weights.

        pushLog("<D>임관할 국가를 찾고 있습니다.</> <1>$date</>")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"randomJoin":{"randomTalk":"$randomTalk","genLimit":$genLimit,"relYear":$relYear,"isNpc":${general.npcType >= 2}},"alternativeCommand":"che_인재탐색","statTemplate":{"officerLevel":1,"officerCity":0,"belong":1,"troop":0},"expGain":{"smallNation":700,"default":100},"logTemplates":{"action":"<D>{nationName}</>에 랜덤 임관했습니다. <1>$date</>","history":"<D><b>{nationName}</b></>에 랜덤 임관","global":"<Y>${generalName}</>${josaYi} {randomTalk} <D><b>{nationName}</b></>에 <S>임관</>했습니다."}}"""
        )
    }
}
