package opensamguk.infra.seed

import opensamguk.logic.input.RuleProfile

/** 시나리오가 선언한 초기 부곡 한 개(게임 기획 값). 주인은 시드 장수 이름으로 가리킨다. */
data class ScenarioUnit(
    val general: String,
    val name: String,
    val troops: Int,
    val crewTypeId: Int,
    val training: Int,
    val morale: Int,
    val provisions: Int,
) {
    init {
        require(general.isNotBlank() && name.isNotBlank() && name.length <= 24)
        require(troops > 0 && crewTypeId > 0 && provisions >= 0)
        require(training in 0..100 && morale in 0..100)
    }
}

/**
 * `hwihaUnits` — HWIHA 새 월드의 초기 부곡을 명시적으로 선언한다. HWIHA 에는 부곡을 편성하는 입력이 아직 없어서,
 * NPC 가 출병할 병력을 시나리오가 직접 준다. 병력·군량 등 수치는 시나리오 작성자의 게임 기획 값이며
 * 사료 수치가 아니다. 선언이 없으면 부곡을 만들지 않는다(추정·보충 없음).
 */
object ScenarioUnits {
    private val fields = setOf("general", "name", "troops", "crewTypeId", "training", "morale", "provisions")

    fun decode(root: Map<String, Any?>, profile: RuleProfile?): List<ScenarioUnit> {
        if ("hwihaUnits" !in root) return emptyList()
        require(profile == RuleProfile.HWIHA) { "hwihaUnits requires HWIHA" }
        val rows = root["hwihaUnits"] as? List<*> ?: throw IllegalArgumentException("hwihaUnits must be an array")
        return rows.map { raw ->
            val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid hwihaUnits row")
            require(row.keys == fields) { "Unexpected hwihaUnits fields: ${row.keys}" }
            fun text(key: String) = row[key] as? String ?: throw IllegalArgumentException("hwihaUnits.$key must be a string")
            fun int(key: String) = row[key] as? Int ?: throw IllegalArgumentException("hwihaUnits.$key must be an integer")
            ScenarioUnit(text("general"), text("name"), int("troops"), int("crewTypeId"), int("training"),
                int("morale"), int("provisions"))
        }.also { units ->
            require(units.map { it.general to it.name }.distinct().size == units.size) { "Duplicate hwihaUnits name for a general" }
        }
    }
}
