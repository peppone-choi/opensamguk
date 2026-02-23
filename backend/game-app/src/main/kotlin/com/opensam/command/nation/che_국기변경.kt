package com.opensam.command.nation

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.NationCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

private val NATION_COLORS = listOf(
    "#FF0000", "#800000", "#A0522D", "#FF6347", "#FFA500",
    "#FFDAB9", "#FFD700", "#FFFF00", "#7CFC00", "#00FF00",
    "#808000", "#008000", "#2E8B57", "#008080", "#20B2AA",
    "#6495ED", "#7FFFD4", "#AFEEEE", "#87CEEB", "#00FFFF",
    "#00BFFF", "#0000FF", "#000080", "#483D8B", "#7B68EE",
    "#BA55D3", "#800080", "#FF00FF", "#FFC0CB", "#F5F5DC",
    "#E0FFFF", "#FFFFFF", "#A9A9A9",
)

class che_국기변경(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : NationCommand(general, env, arg) {

    override val actionName = "국기변경"

    override val fullConditionConstraints = listOf(
        OccupiedCity(), BeChief(), SuppliedCity(),
        ReqNationAuxValue("can_국기변경", 0, ">", 0, "더이상 변경이 불가능합니다.")
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val colorType = (arg?.get("colorType") as? Number)?.toInt() ?: 0
        if (colorType < 0 || colorType >= NATION_COLORS.size) {
            return CommandResult(false, logs, "유효하지 않은 색상입니다")
        }
        val color = NATION_COLORS[colorType]
        val n = nation ?: return CommandResult(false, logs, "국가 정보를 찾을 수 없습니다")

        n.color = color
        n.meta["can_국기변경"] = 0

        general.experience += 5
        general.dedication += 5

        pushLog("<span style='color:$color;'><b>국기</b></span>를 변경하였습니다 <1>$date</>")
        return CommandResult(true, logs)
    }
}
