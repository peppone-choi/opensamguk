package opensamguk.logic.statview

import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.*

class MemoryStateView(
    private val generals: Map<Int, General>,
    private val cities: Map<Int, City>,
    private val nations: Map<Int, Nation>,
    private val env: Map<String, Any?>,
) : StateView {
    override fun has(req: RequirementKey): Boolean = when (req) {
        is RequirementKey.General -> generals.containsKey(req.id)
        is RequirementKey.City -> cities.containsKey(req.id)
        is RequirementKey.Nation -> nations.containsKey(req.id)
        is RequirementKey.Env -> env.containsKey(req.key)
        is RequirementKey.Arg -> true
    }
    override fun get(req: RequirementKey): Any? = when (req) {
        is RequirementKey.General -> generals[req.id]
        is RequirementKey.City -> cities[req.id]
        is RequirementKey.Nation -> nations[req.id]
        is RequirementKey.Env -> env[req.key]
        is RequirementKey.Arg -> null
    }
}
