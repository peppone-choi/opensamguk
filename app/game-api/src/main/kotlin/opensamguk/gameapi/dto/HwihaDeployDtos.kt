package opensamguk.gameapi.dto

data class HwihaDeployOptions(val available: Boolean, val code: String? = null, val reason: String? = null,
    val maxReservedTurns: Int = 12, val bugoks: List<HwihaDeployBugok> = emptyList(),
    val destinations: List<HwihaDeployDestination> = emptyList(), val order: HwihaDeployOrder? = null)
data class HwihaDeployBugok(val id: Int, val name: String, val troops: Int, val available: Boolean, val reason: String? = null)
data class HwihaDeployDestination(val provinceId: String, val name: String)
data class HwihaDeployOrder(val orderId: String, val destinationProvinceId: String, val stop: String? = null)
