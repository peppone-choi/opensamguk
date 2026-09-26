package opensamguk.gameapi.dto

data class DeployOptions(val available: Boolean, val code: String? = null, val reason: String? = null,
    val maxReservedTurns: Int = 12, val bugoks: List<DeployBugok> = emptyList(),
    val destinations: List<DeployDestination> = emptyList(), val order: DeployOrder? = null)
data class DeployBugok(val id: Int, val name: String, val troops: Int, val available: Boolean, val reason: String? = null)
data class DeployDestination(val provinceId: String, val name: String)
data class DeployOrder(val orderId: String, val destinationProvinceId: String, val stop: String? = null)
