package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 무작위건국(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "무작위 도시 건국"

    override val fullConditionConstraints: List<Constraint> by lazy {
        val relYear = env.year - env.startYear
        val nationName = arg?.get("nationName") as? String ?: ""
        listOf(
            BeLord(),
            WanderingNation(),
            ReqNationGeneralCount(2),
            BeOpeningPart(relYear + 1),
            CheckNationNameDuplicate(nationName),
            AllowJoinAction(),
        )
    }

    override val minConditionConstraints: List<Constraint> by lazy {
        val relYear = env.year - env.startYear
        listOf(
            BeOpeningPart(relYear + 1),
            ReqNationValue("level", "국가규모", "==", 0, "정식 국가가 아니어야합니다."),
        )
    }

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val generalName = general.name
        val josaYi = pickJosa(generalName, "이")

        val initYearMonth = env.startYear * 12 + 1
        val yearMonth = env.year * 12 + env.month
        if (yearMonth <= initYearMonth) {
            pushLog("다음 턴부터 건국할 수 있습니다. <1>$date</>")
            return CommandResult(
                success = false,
                logs = logs,
                message = """{"alternativeCommand":"che_인재탐색"}"""
            )
        }

        val nationName = arg?.get("nationName") as? String ?: "신생국"
        val nationType = arg?.get("nationType") as? String ?: "군벌"
        val colorType = arg?.get("colorType") ?: 0
        val josaNationUl = pickJosa(nationName, "을")
        val josaNationYi = pickJosa(nationName, "이")

        // Action log
        pushLog("<D><b>${nationName}</b></>${josaNationUl} 건국하였습니다. <1>$date</>")
        // Global action log
        pushGlobalLog("<Y>${generalName}</>${josaYi} 국가를 건설하였습니다.")
        // Global history log
        pushGlobalLog("<Y><b>【건국】</b></>${nationType} <D><b>${nationName}</b></>${josaNationYi} 새로이 등장하였습니다.")
        // General history log
        pushHistoryLog("<D><b>${nationName}</b></>${josaNationUl} 건국")
        // National history log
        pushNationalHistoryLog("<Y>${generalName}</>${josaYi} <D><b>${nationName}</b></>${josaNationUl} 건국")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":1000,"dedication":1000},"nationChanges":{"foundNation":true,"nationName":"$nationName","nationType":"$nationType","colorType":$colorType,"level":1,"aux":{"can_국기변경":1,"can_무작위수도이전":1}},"findRandomCity":{"query":"neutral_constructable","levelMin":5,"levelMax":6},"moveAllNationGenerals":true,"alternativeCommand":"che_해산"}"""
        )
    }
}
