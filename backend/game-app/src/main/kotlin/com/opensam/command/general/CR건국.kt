package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class CR건국(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "건국"

    override val fullConditionConstraints: List<Constraint> by lazy {
        val relYear = env.year - env.startYear
        val nationName = arg?.get("nationName") as? String ?: ""
        listOf(
            BeLord(),
            WanderingNation(),
            ReqNationGenCount(2),
            BeOpeningPart(relYear + 1),
            CheckNationNameDuplicate(nationName),
            AllowJoinAction(),
            NeutralCity(),
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

        // Legacy parity: check initYearMonth
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

        val nationName = arg?.get("nationName") as? String
            ?: return CommandResult(success = false, logs = listOf("인자가 없습니다."))
        val nationType = arg?.get("nationType") as? String ?: "군벌"
        val colorType = arg?.get("colorType") ?: 0
        val cityName = city?.name ?: "알 수 없는 도시"
        val generalName = general.name

        val josaUl = JosaUtil.pick(nationName, "을")
        val josaYi = JosaUtil.pick(generalName, "이")
        val josaNationYi = JosaUtil.pick(nationName, "이")

        pushLog("<D><b>${nationName}</b></>${josaUl} 건국하였습니다. <1>$date</>")

        val exp = 1000
        val ded = 1000

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"experience":$exp,"dedication":$ded},"nationFoundation":{"name":"$nationName","type":"$nationType","colorType":$colorType,"capitalCityId":${general.cityId},"can_국기변경":1},"cityChanges":{"claimCity":true},"historyLog":{"global":"<Y><b>【건국】</b></>${nationType} <D><b>${nationName}</b></>${josaNationYi} 새로이 등장하였습니다.","globalAction":"<Y>${generalName}</>${josaYi} <G><b>${cityName}</b></>에 국가를 건설하였습니다.","general":"<D><b>${nationName}</b></>${josaUl} 건국","nation":"<Y>${generalName}</>${josaYi} <D><b>${nationName}</b></>${josaUl} 건국"},"inheritancePoint":{"active_action":1}}"""
        )
    }
}
