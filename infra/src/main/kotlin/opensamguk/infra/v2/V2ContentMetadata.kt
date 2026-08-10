package opensamguk.infra.v2

enum class V2ContentStatus {
    ACTIVE,
    CANDIDATE,
    EXCLUDED,
    BUDGET_ONLY,
}

data class V2ContentMetadata(
    val schemaVersion: Int,
    val id: String,
    val status: V2ContentStatus,
    val source: String,
    val sha256: String,
    val cityCount: Int,
    val scenarioOwnedCityCount: Int,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}
