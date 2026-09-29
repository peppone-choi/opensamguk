package opensamguk.infra.content

enum class ContentStatus {
    ACTIVE,
    CANDIDATE,
    EXCLUDED,
    BUDGET_ONLY,
}

data class ContentMetadata(
    val schemaVersion: Int,
    val id: String,
    val status: ContentStatus,
    val source: String,
    val sha256: String,
    val cityCount: Int,
    val scenarioOwnedCityCount: Int,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}
