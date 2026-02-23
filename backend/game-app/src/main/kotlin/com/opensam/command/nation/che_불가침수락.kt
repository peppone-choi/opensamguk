package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import com.opensam.util.JosaUtil
import kotlin.random.Random

class che_불가침수락(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "불가침 수락"
    override val canDisplay = false
    override val isReservable = false

    override val fullConditionConstraints = listOf(
        BeChief(), NotBeNeutral(), OccupiedCity(), SuppliedCity(),
        ExistsDestNation(), ExistsDestGeneral(),
        ReqDestNationGeneralMatch(),
        DisallowDiplomacyBetweenStatus(mapOf(
            0 to "아국과 이미 교전중입니다.",
            1 to "아국과 이미 선포중입니다."
        ))
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val n = nation ?: return CommandResult(false, listOf("국가 정보를 찾을 수 없습니다"))
        val dn = destNation ?: return CommandResult(false, listOf("대상 국가 정보를 찾을 수 없습니다"))
        val dg = destGeneral ?: return CommandResult(false, listOf("대상 장수 정보를 찾을 수 없습니다"))

        val year = arg?.get("year") as? Int ?: return CommandResult(false, listOf("연도 정보가 없습니다"))
        val month = arg?.get("month") as? Int ?: return CommandResult(false, listOf("월 정보가 없습니다"))

        val currentMonth = env.year * 12 + env.month - 1
        val reqMonth = year * 12 + month
        if (reqMonth <= currentMonth) {
            return CommandResult(false, listOf("이미 기한이 지났습니다."))
        }

        val term = reqMonth - currentMonth

        services!!.diplomacyService.setDiplomacyState(env.worldId, n.id, dn.id, state = 7, term = term)

        val josaWaDest = JosaUtil.pick(dn.name, "와")
        pushLog("<D><b>${dn.name}</b></>${josaWaDest} <C>$year</>년 <C>${month}</>월까지 불가침에 성공했습니다.")
        pushHistoryLog("<D><b>${dn.name}</b></>${josaWaDest} ${year}년 ${month}월까지 불가침 성공")

        val josaWaSrc = JosaUtil.pick(n.name, "와")
        pushDestGeneralLog("<D><b>${n.name}</b></>${josaWaSrc} <C>$year</>년 <C>${month}</>월까지 불가침에 성공했습니다.")
        pushDestGeneralHistoryLog("<D><b>${n.name}</b></>${josaWaSrc} ${year}년 ${month}월까지 불가침 성공")

        return CommandResult(true, logs)
    }
}
