package opensamguk.gameapi.dto

/** Phase 4X-A `/api/my-retinue` · `/api/generals/{id}/retinue` (spec v3 §6). 상수는 `RetainerRules` 를 그대로 노출. */
data class RetinueRetainerDto(
    val id: Int,
    val name: String,
    val origin: String,
    val relation: String,
    val relationLabel: String,
    val role: String,
    val roleLabel: String,
    val loyalty: Int,
    val task: String,
    val taskLabel: String,
    val hasOwnBugok: Boolean,
)

data class RetinueBugokDto(
    val id: Int,
    val name: String,
    val troops: Int,
    val crewTypeId: Int,
    val crewTypeName: String,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val provisions: Int,
    val provisionMonths: Int,
    val commanderRetainerId: Int?,
)

data class RetinueRulesDto(
    val maxRetainers: Int,
    val maxBugok: Int,
    val pledgeCostGold: Int,
    val minBugokTroops: Int,
    val retainerUpkeepGold: Int,
    val retainerUpkeepRice: Int,
    val payGoldPer100Troops: Int,
    val provisionPerTroopMonth: Int,
    val commanderMoraleBonus: Int,
    val relations: List<Map<String, String>>,
    val roles: List<Map<String, String>>,
    val tasks: List<Map<String, String>>,
    /** 잠정 상수 — UI 는 「잠정」 칩을 붙인다. */
    val provisional: Boolean,
)

data class RetinueResponse(
    val generalId: Int,
    val generalName: String,
    val crew: Int,
    val rice: Int,
    val gold: Int,
    val crewTypeId: Int,
    val crewTypeName: String,
    val retainers: List<RetinueRetainerDto>,
    val bugoks: List<RetinueBugokDto>,
    val rules: RetinueRulesDto,
)
