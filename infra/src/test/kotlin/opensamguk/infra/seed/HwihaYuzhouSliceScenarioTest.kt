package opensamguk.infra.seed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.util.phpRound

/**
 * 豫州 조각 운영 후보 시나리오(`tools/e2e/fixtures/hwiha-yuzhou/scenario_990002.json`)의 생성기이자 드리프트 게이트.
 *
 * 파일은 손으로 쓰지 않는다 — 활성 `han-world-v3` 지도(城 표·郡/州 메타)와 부팅이 고를 판의 핀·행정 縣 목록에서
 * 결정론으로 만든다. 파일이 없을 때만 새로 쓴다(다시 만들려면 파일을 지우고 이 테스트를 돌린다). 파일이 있으면
 * 커밋된 파일이 생성 결과와 바이트 단위로 같은지 본다. 사람 수치(능력치·병력·창고 재고)는 게임 기획 값이며 사료가 아니다 — README 참조.
 */
class HwihaYuzhouSliceScenarioTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("data/map")) }
    private val file: Path = repo.resolve("tools/e2e/fixtures/hwiha-yuzhou/scenario_990002.json")
    private val mapJson: String = Files.readString(repo.resolve("infra/src/main/resources/map/han-world-v3.json"))
    private val cities = ScenarioJson.loadMapCities(mapJson)

    /** 郡 메타는 ScenarioCity 에 없어서 지도 JSON 을 직접 읽는다. */
    @Suppress("UNCHECKED_CAST")
    private val cityMeta: Map<Int, Map<String, Any?>> = (MetaJson.decode(mapJson)["cities"] as List<Map<String, Any?>>)
        .associate { (it["id"] as Number).toInt() to (it["meta"] as Map<String, Any?>) }

    private data class Commandery(val jun: String, val counties: List<Int>)

    private fun generate(): String {
        val projection = HanWorldArtifactsResolver(repo).resolve(cities.map { it.id }, emptyList()).projection
        val admin = projection.administrativeCountyIds
        val byId = cities.associateBy { it.id }
        // Commandery order and each capital are fixed by id: the seat county when it is an administrative county.
        val commanderies = cities.filter { it.id in admin && cityMeta[it.id]?.get("ju") == JU }
            .groupBy { cityMeta.getValue(it.id)["jun"] as String }
            .map { (jun, rows) ->
                val ids = rows.map { it.id }.sorted()
                val seat = rows.filter { cityMeta.getValue(it.id)["isSeat"] == true }.minOfOrNull { it.id } ?: ids.first()
                Commandery(jun, listOf(seat) + (ids - seat))
            }.sortedBy { it.counties.first() }
        require(commanderies.size == 6) { "豫州 must have its six commanderies, was ${commanderies.map { it.jun }}" }
        fun lordName(c: Commandery) = "${c.jun.removeSuffix("군")} 주공"
        val garrisonOf = { id: Int -> phpRound(byId.getValue(id).defMax * 0.7) }

        val out = StringBuilder()
        fun line(text: String) = out.append(text).append('\n')
        fun j(value: Any?) = MetaJson.encode(value)
        line("{")
        line("  \"title\": ${j("휘하 예주 조각 (합성 운영 후보)")},")
        line("  \"startYear\": $START_YEAR,")
        line("  \"ruleProfile\": \"HWIHA\",")
        line("  \"map\": {\"mapName\": \"han-world-v3\"},")
        line("  \"seedContract\": {\"activeGenerals\": {\"base\": ${commanderies.size}, \"extended\": ${commanderies.size}}},")
        line("  \"hwihaLords\": ${j(commanderies.map(::lordName))},")
        line("  \"nation\": [")
        commanderies.forEachIndexed { index, c ->
            val row = listOf("${c.jun.removeSuffix("군")} 세력", COLORS[index], 0, 0, "합성 운영 후보 — ${c.jun} 縣 ${c.counties.size}곳",
                0, null, 1, c.counties.map(Int::toString))
            line("    ${j(row)}${if (index < commanderies.lastIndex) "," else ""}")
        }
        line("  ],")
        line("  \"diplomacy\": [")
        val pairs = commanderies.indices.flatMap { a -> commanderies.indices.filter { it != a }.map { b -> listOf(a + 1, b + 1, 0, 0) } }
        pairs.forEachIndexed { i, p -> line("    ${j(p)}${if (i < pairs.lastIndex) "," else ""}") }
        line("  ],")
        line("  \"general\": [")
        commanderies.forEachIndexed { index, c ->
            val s = STATS
            val row = listOf(0, lordName(c), null, index + 1, c.counties.first().toString(), s[0], s[1], s[2], 12, 150, 250,
                null, null, null, s[3], s[4])
            line("    ${j(row)}${if (index < commanderies.lastIndex) "," else ""}")
        }
        line("  ],")
        line("  \"hwihaPersonPolicies\": [")
        commanderies.forEachIndexed { index, c ->
            val row = linkedMapOf("name" to lordName(c), "statSourceId" to "synthetic-qa:yuzhou-slice",
                "statSourceRevision" to "v1", "officerId" to index + 1, "acceptsEnlistment" to true,
                "stats" to linkedMapOf("leadership" to STATS[0], "strength" to STATS[1], "intelligence" to STATS[2],
                    "politics" to STATS[3], "charm" to STATS[4]))
            line("    ${j(row)}${if (index < commanderies.lastIndex) "," else ""}")
        }
        line("  ],")
        line("  \"hwihaUnits\": [")
        val units = commanderies.flatMap { c -> (1..UNITS_PER_LORD).map { n -> linkedMapOf("general" to lordName(c),
            "name" to "${c.jun.removeSuffix("군")} 부곡 $n", "troops" to UNIT_TROOPS, "crewTypeId" to UNIT_CREW_TYPE,
            "training" to UNIT_TRAINING, "morale" to UNIT_MORALE, "provisions" to UNIT_TROOPS * UNIT_PROVISION_MONTHS) } }
        units.forEachIndexed { i, u -> line("    ${j(u)}${if (i < units.lastIndex) "," else ""}") }
        line("  ],")
        val slice = commanderies.flatMap { it.counties }.toSet()
        val capitals = commanderies.map { it.counties.first() }.toSet()
        line("  \"hwihaWarehouses\": {")
        line("    \"version\": 1, \"units\": \"game-resource-v1\", \"source\": \"GAME_DESIGN\",")
        line("    \"topologyRevision\": ${j(projection.topology.topologyRevision)}, \"topologyHash\": ${j(projection.topology.contentHash)},")
        line("    \"warehouses\": [")
        val rows = admin.sorted()
        rows.forEachIndexed { i, id ->
            val grain = if (id in slice) garrisonOf(id).toLong() * GRAIN_PER_SOLDIER_TURN * RATION_TURNS else 0L
            val money = if (id in capitals) CAPITAL_MONEY else 0L
            val row = linkedMapOf("countyId" to id, "stock" to linkedMapOf("money" to money, "grain" to grain,
                "iron" to 0, "timber" to 0, "horses" to 0))
            line("      ${j(row)}${if (i < rows.lastIndex) "," else ""}")
        }
        line("    ]")
        line("  }")
        line("}")
        return out.toString()
    }

    @Test fun `committed scenario equals the deterministic generator output`() {
        val generated = generate()
        if (!Files.exists(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(file, generated)
        }
        assertEquals(generated, Files.readString(file), "scenario drifted from the map; regenerate and review")
    }

    @Test fun `scenario seeds a HWIHA world the importer accepts`() {
        val scenario = ScenarioJson.loadScenario(Files.readString(file))
        assertEquals(RuleProfile.HWIHA, scenario.ruleProfile)
        val importer = ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_990002", artifactsRoot = repo)
        importer.validateWarehouseSeed()
        importer.validateSeedContract()
        assertTrue(scenario.nations.all { it.gold == 0 && it.rice == 0 && it.scale >= 1 }, "treasury lives only in county warehouses")
        val owned = scenario.nations.flatMap { n -> n.cities.map(String::toInt) }
        assertEquals(owned.size, owned.toSet().size, "a county has one owner")
        assertTrue(owned.all { cityMeta.getValue(it)["ju"] == JU }, "only 豫州 counties are owned")
        val nations = scenario.nations.map { it.id }
        assertEquals(nations.size * (nations.size - 1), scenario.diplomacy.count { it.state == 0 }, "every lord pair is at war")
        assertEquals(scenario.nations.size, scenario.generals.count { it.hwihaLord == true })
        assertTrue(scenario.generals.all { it.hwihaPersonPolicy?.acceptsEnlistment == true }, "a human can enlist with any lord")
    }

    @Test fun `each lord can besiege at least one enemy county of the slice`() {
        // The NPC deployment chooser needs twice the garrison it targets; seats (5670–8190) stay out of reach at start.
        val scenario = ScenarioJson.loadScenario(Files.readString(file))
        val byId = cities.associateBy { it.id }
        for (nation in scenario.nations) {
            val lord = scenario.generals.single { it.nationId == nation.id && it.hwihaLord == true }
            val troops = scenario.hwihaUnits.filter { it.general == lord.name }.sumOf { it.troops }
            val weakestEnemy = scenario.nations.filter { it.id != nation.id }.flatMap { it.cities }
                .minOf { phpRound(byId.getValue(it.toInt()).defMax * 0.7) }
            assertTrue(troops >= 2 * weakestEnemy, "${lord.name}: $troops troops vs weakest enemy garrison $weakestEnemy")
        }
    }

    private companion object {
        const val JU = "예주"
        const val START_YEAR = 190
        val COLORS = listOf("#8B1E1E", "#1E4F8B", "#2E7D32", "#B8860B", "#6A1B9A", "#00695C")
        /** 합성 NPC 주공 능력치(통솔·무력·지력·정치·매력) — 게임 기획 값, 사료 아님. */
        val STATS = listOf(70, 65, 65, 60, 70)
        const val UNITS_PER_LORD = 2
        const val UNIT_TROOPS = 4000
        const val UNIT_CREW_TYPE = 1100
        const val UNIT_TRAINING = 50
        const val UNIT_MORALE = 60
        const val UNIT_PROVISION_MONTHS = 6
        /** 창고 곡 = 시드 수비병 × 순당 100 × 18순(siegeResolution.referenceInitialRationTurns 승인값). */
        const val GRAIN_PER_SOLDIER_TURN = 100L
        const val RATION_TURNS = 18L
        const val CAPITAL_MONEY = 100_000L
    }
}
