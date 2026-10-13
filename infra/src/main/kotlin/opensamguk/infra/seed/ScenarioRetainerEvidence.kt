package opensamguk.infra.seed

/** Attribution metadata only; never creates a relationship or changes seed budgets. */
data class ScenarioRetainerEvidence(
    val general: String,
    val master: String,
    val classification: Classification,
    val sources: List<Source>,
    val asOfYear: Int,
) {
    enum class Classification { HISTORICAL_EXPLICIT, PROVISIONAL, GAME_ONLY }
    data class Source(val book: String, val chapter: String, val quote: String)

    companion object {
        internal fun decode(root: Map<String, Any?>, retainers: List<ScenarioRetainer>, startYear: Int): List<ScenarioRetainerEvidence> {
            if ("retainerEvidence" !in root) return emptyList()
            val rows = root["retainerEvidence"] as? List<*>
                ?: throw IllegalArgumentException("retainerEvidence must be an array")
            val seen = mutableSetOf<ScenarioRetainer>()
            return rows.map { raw ->
                val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid retainerEvidence row")
                require(row.keys == setOf("general", "master", "class", "sources", "asOfYear")) {
                    "Unexpected retainerEvidence fields"
                }
                fun text(map: Map<*, *>, key: String): String = (map[key] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing retainerEvidence $key")
                val link = ScenarioRetainer(text(row, "general"), text(row, "master"))
                require(link in retainers) { "Evidence must match a declared retainer link" }
                require(seen.add(link)) { "Duplicate retainerEvidence link" }
                val classification = Classification.valueOf(text(row, "class"))
                val year = row["asOfYear"] as? Int ?: throw IllegalArgumentException("asOfYear must be an integer")
                require(year <= startYear) { "retainerEvidence cannot postdate scenario start" }
                val rawSources = row["sources"] as? List<*> ?: throw IllegalArgumentException("sources must be an array")
                val sources = rawSources.map { source ->
                    val fields = source as? Map<*, *> ?: throw IllegalArgumentException("Invalid evidence source")
                    require(fields.keys == setOf("book", "chapter", "quote")) { "Unexpected evidence source fields" }
                    Source(text(fields, "book"), text(fields, "chapter"), text(fields, "quote"))
                }
                require(classification != Classification.HISTORICAL_EXPLICIT || sources.isNotEmpty()) {
                    "Historical explicit evidence requires an original quotation"
                }
                ScenarioRetainerEvidence(link.general, link.master, classification, sources, year)
            }
        }

        internal fun isCompleteHistorical(retainers: List<ScenarioRetainer>, evidence: List<ScenarioRetainerEvidence>): Boolean =
            retainers.isNotEmpty() && evidence.size == retainers.size && evidence.all {
                it.classification == Classification.HISTORICAL_EXPLICIT
            }
    }
}
