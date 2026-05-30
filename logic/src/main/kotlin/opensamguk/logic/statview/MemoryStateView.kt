package opensamguk.logic.statview

import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.*

class MemoryStateView(
    private val generals: Map<Int, General>,
    private val cities: Map<Int, City>,
    private val nations: Map<Int, Nation>,
    private val env: Map<String, Any?>,
    // CD1 — preloaded directional diplomacy rows (me,you) for the dest/diplomacy constraint family.
    private val diplomacy: List<Diplomacy> = emptyList(),
) : StateView {
    private val diplomacyByPair: Map<Pair<Int, Int>, Diplomacy> =
        diplomacy.associateBy { it.me to it.you }

    override fun has(req: RequirementKey): Boolean = when (req) {
        is RequirementKey.General -> generals.containsKey(req.id)
        is RequirementKey.City -> cities.containsKey(req.id)
        is RequirementKey.Nation -> nations.containsKey(req.id)
        is RequirementKey.Env -> env.containsKey(req.key)
        is RequirementKey.Arg -> true
        // CD1 — dest-* keys resolve from the same preloaded row store (the adapters stage dest rows in).
        is RequirementKey.DestGeneral -> generals.containsKey(req.id)
        is RequirementKey.DestCity -> cities.containsKey(req.id)
        is RequirementKey.DestNation -> nations.containsKey(req.id)
        is RequirementKey.NationList -> true
        is RequirementKey.GeneralList -> true
        is RequirementKey.Diplomacy -> diplomacyByPair.containsKey(req.me to req.you)
    }
    override fun get(req: RequirementKey): Any? = when (req) {
        is RequirementKey.General -> generals[req.id]
        is RequirementKey.City -> cities[req.id]
        is RequirementKey.Nation -> nations[req.id]
        is RequirementKey.Env -> env[req.key]
        is RequirementKey.Arg -> null
        is RequirementKey.DestGeneral -> generals[req.id]
        is RequirementKey.DestCity -> cities[req.id]
        is RequirementKey.DestNation -> nations[req.id]
        is RequirementKey.NationList -> nations.values
        is RequirementKey.GeneralList -> generals.values
        is RequirementKey.Diplomacy -> diplomacyByPair[req.me to req.you]
    }
}
