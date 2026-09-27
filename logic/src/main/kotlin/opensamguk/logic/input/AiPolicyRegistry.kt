package opensamguk.logic.input

/** The concrete NPC selection path for one input. These keys are bound to selectors by the engine. */
enum class AiSelectorKey {
    ENLIST, DEPLOY, MUSTER, COURT_DISPATCH, COURT_REWARD, FIELD, CITY_MILITARY, PEOPLE, PERSONAL,
}

sealed interface AiPolicyBinding {
    data class Selector(val key: AiSelectorKey) : AiPolicyBinding
    data class Unused(val reason: String) : AiPolicyBinding
}

/**
 * Explicit policy ledger. A new catalog row cannot silently acquire a no-op NPC policy.
 * `PLANNED` rows stay unused until their human handler is delivered; D1/D4 add selectors here.
 */
object AiPolicyRegistry {
    private fun selector(key: AiSelectorKey, vararg ids: String): Map<String, AiPolicyBinding> =
        ids.associate { "ai.$it" to AiPolicyBinding.Selector(key) }

    private fun unused(reason: String, vararg ids: String): Map<String, AiPolicyBinding> =
        ids.associate { "ai.$it" to AiPolicyBinding.Unused(reason) }

    val bindings: Map<String, AiPolicyBinding> = buildMap {
        putAll(selector(AiSelectorKey.ENLIST, "action.enlist"))
        putAll(selector(AiSelectorKey.DEPLOY, "action.deploy"))
        putAll(selector(AiSelectorKey.MUSTER, "action.muster"))
        putAll(selector(AiSelectorKey.COURT_DISPATCH, "court.dispatch"))
        putAll(selector(AiSelectorKey.COURT_REWARD, "court.reward"))
        putAll(selector(AiSelectorKey.FIELD, "action.farm", "action.commerce", "action.fortify",
            "action.repairWall", "action.security", "action.settle", "action.selectResidents"))
        putAll(selector(AiSelectorKey.CITY_MILITARY, "action.conscript", "action.raiseVolunteers",
            "action.train", "action.boostMorale", "action.demobilize"))
        putAll(selector(AiSelectorKey.PEOPLE, "action.search", "action.employ"))
        putAll(selector(AiSelectorKey.PERSONAL, "action.travel", "action.selfTrain", "action.recuperate"))

        putAll(unused("human input is not delivered", "stratagem.rumor", "stratagem.play",
            "action.donate", "action.retire", "action.resign", "action.rise", "action.dissolve",
            "action.persuadeCaptive", "action.independence", "action.tradeEquipment",
            "court.diplomacy", "court.institution", "court.confiscate", "court.nonAggression",
            "court.declareWar", "court.offerPeace", "court.breakNonAggression", "work.reduce",
            "stratagem.steal", "stratagem.sabotage", "stratagem.fire", "stratagem.lastStand",
            "stratagem.mobilizePeople", "stratagem.flood", "stratagem.falseReport",
            "stratagem.raiseMilitia", "stratagem.provokeRivalry", "stratagem.raid",
            "stratagem.reciprocity"))
        putAll(unused("NPC selector has not been delivered", "action.scout", "action.assault",
            "action.demandSurrender", "action.siegeRoadFort",
            "court.politicalConsent", "placement.assign", "policy.set", "work.start",
            "action.gift", "action.move", "action.forcedMarch", "action.return",
            "action.foundState", "action.abdicate", "action.tour", "action.oath",
            "action.convertProficiency", "action.tradeGrain", "action.transport",
            "court.releaseCorps", "court.abandonCounty", "court.moveCapital"))
        putAll(unused("dispatch reply belongs to the card owner", "court.dispatchReply"))
    }

    fun validate(catalog: InputCatalog) {
        val byPolicy = catalog.entries.groupBy { it.aiPolicyId }
        require(byPolicy.values.all { it.size == 1 }) { "duplicate aiPolicyId in hwiha input catalog" }
        require(byPolicy.keys == bindings.keys) {
            "unregistered aiPolicyId: ${byPolicy.keys - bindings.keys}; stale aiPolicyId: ${bindings.keys - byPolicy.keys}"
        }
        for (entry in catalog.entries) {
            val binding = bindings.getValue(entry.aiPolicyId)
            require(entry.aiPolicyId == "ai.${entry.inputId}") { "aiPolicyId does not match inputId: ${entry.inputId}" }
            require(binding !is AiPolicyBinding.Selector || entry.deliveryState.hasHandler) {
                "NPC selector for undelivered input: ${entry.inputId}"
            }
        }
    }

    fun selectable(catalog: InputCatalog, inputId: String, key: AiSelectorKey): Boolean {
        val entry = catalog[inputId] ?: return false
        return entry.deliveryState.hasHandler && bindings[entry.aiPolicyId] == AiPolicyBinding.Selector(key)
    }
}
