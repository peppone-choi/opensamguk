package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.world.WorldMapVariant
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 휘하 조회가 읽는 두 원장. 정본은 저장소 루트의 `data/curated/han/` 파일이고 빌드가 classpath
 * `campaign/` 로 그대로 싣는다(`app/game-api/build.gradle.kts`). 사본을 코드에 두지 않는다.
 *
 * - `resource-production-v1.json` — 縣(`jurisdictionId`, han-tiles 관할 id)별 철·목재·말 설계 산출량(목재는 모든 縣, 면적 축).
 * - `officer-native-county-v1.json` — 인물 본관 縣. `method == DIRECT` 행만 쓴다.
 */
@Component
class CampLedgers(private val objectMapper: ObjectMapper) {
    data class Specialty(val resource: String, val ledgerMonthly: Long)

    /** 본관 한 건 — 원장의 郡·縣 한자와(있으면) han-tiles 관할 id. */
    data class NativeCounty(val commandery: String?, val county: String, val jurisdictionId: String?) {
        /** 원장 한자 그대로(「沛國 譙」). */
        val hanja: String get() = listOfNotNull(commandery, county).joinToString(" ")
    }

    /** 지명 대조 정규화 — audit 도구와 같은 표·규칙. */
    val fold: PlaceNameFold by lazy { PlaceNameFold.loadDefault(objectMapper) }

    /**
     * 같은 고향인가. 두 쪽 다 `jurisdictionId` 가 있으면 그것으로, 아니면 정규화한 (郡, 縣) 쌍으로 본다
     * (원장이 縣을 관할에 못 붙인 경우 — 조조·하후돈 「沛國 譙」).
     */
    fun sameHome(a: NativeCounty, b: NativeCounty): Boolean =
        if (a.jurisdictionId != null && b.jurisdictionId != null) a.jurisdictionId == b.jurisdictionId
        else fold.group(a.commandery) == fold.group(b.commandery) && fold.county(a.county) == fold.county(b.county)

    val productionByJurisdiction: Map<String, List<Specialty>> by lazy { parseProduction(read(PRODUCTION)) }
    val nativeCountyByScenarioName: Map<String, NativeCounty> by lazy { parseNativeCounty(read(NATIVE_COUNTY)) }

    /**
     * 장수의 본관 縣. **추정하지 않는다** — 다음을 모두 만족할 때만 돌려준다.
     *
     * 1. 사람이 만든 장수가 아니다: 시나리오·이벤트로 들어온 장수만 meta `npc_org` 를 가진다
     *    (`ScenarioImporter`·`BuiltGeneralMapper`). 사람이 만든 장수(`MakeGeneralHandler`)는 이름을
     *    자유롭게 지으므로 「조조」라는 이름만으로 曹操의 본관을 줄 수 없다. 휘하 정책의 출처가
     *    `opensamguk:created-general` 이어도 제외한다.
     * 2. 원장 행의 `scenarioLink == EXACT` 이고 장수 이름이 그 행의 `scenarioNames` 에 있다. 이 이름을 가진
     *    원장 행이 둘 이상이면(동명이인) 고르지 않는다. `HOMONYM` 행은 빌더가 「어느 쪽이 어느 인물인지
     *    정하지 않는다」고 적었으므로 쓰지 않는다.
     * 3. `method == DIRECT` 이고 `nativeCounty` 가 있다(郡만 아는 행은 縣 결속을 만들지 않는다).
     */
    fun nativeCountyOf(general: GeneralReadEntity): NativeCounty? {
        if ("npc_org" !in general.meta) return null
        val policy = try { PersonPolicyState.read(general.meta) } catch (_: IllegalArgumentException) { null }
        if (policy?.statSourceId == CREATED_GENERAL_SOURCE) return null
        return nativeCountyByScenarioName[general.name]
    }

    private fun read(resource: String): ByteArray =
        checkNotNull(CampLedgers::class.java.classLoader.getResourceAsStream(resource)) {
            "HWIHA ledger resource is missing: $resource"
        }.use { it.readBytes() }

    fun parseProduction(bytes: ByteArray): Map<String, List<Specialty>> {
        val root = objectMapper.readTree(bytes)
        check(root.path("schemaVersion").asInt() == 2 && root.path("ledgerId").asText() == "resource-production-v1") {
            "Unexpected HWIHA resource production ledger header"
        }
        val result = linkedMapOf<String, List<Specialty>>()
        root.required("counties").forEach { row ->
            val id = row.required("jurisdictionId").asText()
            val monthly = row.required("monthly")
            val specialties = monthly.fieldNames().asSequence().sorted().map { resource ->
                val amount = monthly.required(resource)
                check(amount.isIntegralNumber && amount.asLong() >= 0) { "Invalid monthly $resource for $id" }
                Specialty(resource, amount.asLong())
            }.toList()
            check(result.put(id, specialties) == null) { "Duplicate production row $id" }
        }
        return result
    }

    fun parseNativeCounty(bytes: ByteArray): Map<String, NativeCounty> {
        val root = objectMapper.readTree(bytes)
        check(root.path("schemaVersion").asInt() == 1 && root.path("ledgerId").asText() == "officer-native-county-v1") {
            "Unexpected officer native county ledger header"
        }
        val rows = root.required("officers").toList()
        // 이름 → 그 이름을 실은 원장 행 수. 둘 이상이면 동명이인이라 어느 쪽도 고르지 않는다.
        val carriers = rows.flatMap { row -> row.required("scenarioNames").map(JsonNode::asText).distinct() }
            .groupingBy { it }.eachCount()
        val result = linkedMapOf<String, NativeCounty>()
        rows.forEach { row ->
            if (row.path("scenarioLink").asText() != "EXACT" || row.path("method").asText() != "DIRECT") return@forEach
            val county = row.path("nativeCounty").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: return@forEach
            val native = NativeCounty(
                commandery = row.path("nativeCommandery").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() },
                county = county,
                jurisdictionId = row.path("jurisdictionId").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() },
            )
            row.required("scenarioNames").map(JsonNode::asText).filter { carriers[it] == 1 }.forEach { name ->
                result[name] = native
            }
        }
        return result
    }

    companion object {
        const val PRODUCTION = "campaign/resource-production-v1.json"
        const val NATIVE_COUNTY = "campaign/officer-native-county-v1.json"
        const val CREATED_GENERAL_SOURCE = "opensamguk:created-general"
    }
}

/**
 * 城 id → 郡 이름·縣(관할) id·한글 표시명. 활성 세계 판의 고정 번들에서 읽어 판마다 한 번만 만든다.
 *
 * 縣은 `city.provinceId`(런타임 省 index) → `han-tiles.json provinceRecords[i].jurisdictionId` 로 푼다 —
 * `MapAdministrativeOwnership`·`SupplyDisconnectionPolicyLoader` 와 같은 다리다.
 * 郡 이름은 런타임 지도 `meta.jun`(`MapJson.commanderyName` 과 같은 값)이다.
 */
@Component
class CityGeography(private val objectMapper: ObjectMapper, private val ledgers: CampLedgers) {
    data class Place(
        val commanderyName: String?,
        val jurisdictionId: String?,
        val commanderyHanja: String? = null,
        val countyHanja: String? = null,
        val displayName: String? = null,
    )

    /**
     * 본관 → 한글 「郡 縣」. `jurisdictionId` 가 있으면 그 관할의 城으로만, 없으면 정규화한
     * (`meta.junCh`, `meta.nameCh`) 쌍으로 찾는다. **城이 정확히 하나일 때만** 이름을 준다.
     */
    class CountyNames internal constructor(
        private val byJurisdiction: Map<String, List<String>>,
        private val byPair: Map<Pair<String, String>, List<String>>,
        private val fold: PlaceNameFold,
    ) {
        fun korean(native: CampLedgers.NativeCounty): String? {
            val hits = if (native.jurisdictionId != null) byJurisdiction[native.jurisdictionId]
                else byPair[fold.group(native.commandery) to fold.county(native.county)]
            return hits?.singleOrNull()
        }
    }

    private val cache = ConcurrentHashMap<WorldMapVariant, Map<Int, Place>>()
    private val names = ConcurrentHashMap<WorldMapVariant, CountyNames>()

    fun places(artifacts: ResolvedWorldArtifacts): Map<Int, Place> = cache.computeIfAbsent(artifacts.variant) {
        val cities = objectMapper.readTree(artifacts.artifactBytes(RUNTIME_MAP)).get("cities")
        check(cities != null && cities.isArray) { "runtime map cities missing" }
        val provinces = objectMapper.readTree(artifacts.artifactBytes(TILES)).get("provinceRecords")
        check(provinces != null && provinces.isArray) { "han-tiles provinceRecords missing" }
        cities.mapNotNull { city ->
            val id = city.get("id")?.takeIf { it.isIntegralNumber }?.intValue() ?: return@mapNotNull null
            val meta = city.path("meta")
            val jurisdiction = city.get("provinceId")?.takeIf { it.isIntegralNumber }?.intValue()
                ?.takeIf { it in 0 until provinces.size() }
                ?.let { provinces[it].get("jurisdictionId")?.takeIf(JsonNode::isTextual)?.asText() }
            id to Place(meta.text("jun"), jurisdiction, meta.text("junCh"), meta.text("nameCh"),
                meta.text("displayName")?.let(::withoutDisambiguator))
        }.toMap()
    }

    fun countyNames(artifacts: ResolvedWorldArtifacts): CountyNames = names.computeIfAbsent(artifacts.variant) {
        val places = places(artifacts).values.filter { it.displayName != null }
        val fold = ledgers.fold
        CountyNames(
            byJurisdiction = places.filter { it.jurisdictionId != null }
                .groupBy({ it.jurisdictionId!! }, { it.displayName!! }),
            byPair = places.filter { it.commanderyHanja != null && it.countyHanja != null }
                .groupBy({ fold.group(it.commanderyHanja) to fold.county(it.countyHanja) }, { it.displayName!! }),
            fold = fold,
        )
    }

    companion object {
        const val RUNTIME_MAP = "infra/src/main/resources/map/han-world-v3.json"
        const val TILES = "data/map/han-tiles.json"

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf(JsonNode::isTextual)?.asText()?.takeIf { it.isNotBlank() }

        /** 「여강군 안풍현(安丰)」 — 동음 城을 가르는 끝 괄호는 한글 칩에 싣지 않는다. */
        fun withoutDisambiguator(name: String): String = name.replace(Regex("""\s*\([^()]*\)$"""), "").trim()
    }
}
