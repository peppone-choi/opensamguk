package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MapAdministrativeOwnershipTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `historical ownership survives absent runtime files and applies a live conquest`() {
        val resolver = opensamguk.infra.seed.HanWorldArtifactsResolver(Path.of("../.."))
        val projection = MapAdministrativeOwnership(ObjectMapper(), "/missing/tiles", "/missing/owners", "/missing/allowlist")
        for (variant in opensamguk.logic.world.HanWorldVariant.entries) {
            val artifacts = resolver.artifacts(variant)
            val map = opensamguk.infra.seed.MapJson.loadMap(artifacts.artifactBytes("infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8))
            val city = map.cities.first { it.provinceId != null }
            val provinceIndex = requireNotNull(city.provinceId)
            val before = projection.project("scenario_1020", emptyList(), artifacts)
            val captured = projection.project("scenario_1020", listOf(LiveCityOwnership(city.id, provinceIndex, 999)), artifacts)
            assertEquals(before.provinceOccupancy.map { it.provinceRecordId }, captured.provinceOccupancy.map { it.provinceRecordId })
            assertEquals(999, captured.provinceOccupancy.single { it.provinceIndex == provinceIndex }.nationId)
            assertEquals(before, projection.project("scenario_1020", emptyList(), artifacts))
        }
    }

    @Test
    fun `a nation founded on a stand-in commandery seat paints its own territory`() {
        // 2026-09-16 pep: NPC 금선이 城 835 「정양군 선무현」(定襄郡 대리 治所)에 건국했는데 지도에 색이
        // 하나도 안 칠해졌다. 848 판에서 그 城은 provinceId 가 없어 live 점령 투영(城 → 省 → 관할)에서
        // 빠졌고, 땅은 시나리오 초기 주인 색으로 남았다. 1098 판은 대리 治所 省 규칙으로 704·833–835 를
        // 직할 省에 앉힌다 — 신생 국가가 그 城 하나만 가져도 제 관할 省을 칠해야 한다.
        val resolver = opensamguk.infra.seed.HanWorldArtifactsResolver(Path.of("../.."))
        val artifacts = resolver.artifacts(opensamguk.logic.world.HanWorldVariant.V3_1098)
        val map = opensamguk.infra.seed.MapJson.loadMap(
            artifacts.artifactBytes("infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8),
        )
        assertEquals(emptyList<Int>(), map.cities.filter { it.provinceId == null }.map { it.id }, "省 없는 城")
        val projection = MapAdministrativeOwnership(ObjectMapper(), "/missing/tiles", "/missing/owners", "/missing/allowlist")
        for (cityId in listOf(704, 833, 834, 835)) {
            val city = map.cities.single { it.id == cityId }
            val founded = projection.project(
                "scenario_1020", listOf(LiveCityOwnership(city.id, requireNotNull(city.provinceId), 4242)), artifacts,
            )
            val painted = founded.provinceOccupancy.filter { it.nationId == 4242 }.map { it.provinceIndex }
            org.junit.jupiter.api.Assertions.assertTrue(city.provinceId in painted, "city $cityId must paint its own province")
            assertEquals(1, founded.jurisdictionOwnership.count { it.nationId == 4242 }, "city $cityId jurisdiction")
        }
    }

    @Test
    fun `commandery tie fallback prefers the lowest positive owner over neutral`() {
        assertEquals(1, resolveCommanderyController(mapOf(0 to 2, 1 to 2, 2 to 1), seatOwner = 2))
        assertEquals(0, resolveCommanderyController(mapOf(0 to 2), seatOwner = 0))
    }

    @Test
    fun `scenario code accepts only canonical decimal or scenario prefix`() {
        val projection = fixtureProjection()
        listOf("+1010", "01010", "scenario_01010", "che_1010").forEach { malformed ->
            assertThrows<IllegalStateException> { projection.project(malformed, emptyList()) }
        }
        projection.project("1010", emptyList())
        projection.project("scenario_1010", emptyList())
    }

    @Test
    fun `projects direct spatial ownership without choosing a representative province color`() {
        // R1(ADR-LITE-052): live 점령은 縣 소속 전체에 번진다. J1 의 P2 는 기준표상
        // 2 였지만 live city 10(소유 4)이 들어오면 4 가 된다. 초기 배치(대표색 뭉개기
        // 금지)는 liveCities 가 비었을 때의 모양이다.
        val projection = fixtureProjection().project(
            scenarioCode = "scenario_1010",
            liveCities = listOf(LiveCityOwnership(cityId = 10, provinceIndex = 1, nationId = 4)),
        )

        assertEquals(
            listOf(
                ProvinceOccupancyProjection("P1", 0, 4),
                ProvinceOccupancyProjection("P2", 1, 4),
                ProvinceOccupancyProjection("P3", 2, 2),
                ProvinceOccupancyProjection("P4", 3, 2),
            ),
            projection.provinceOccupancy,
        )
        assertEquals(
            listOf(
                JurisdictionOwnershipProjection("J1", 4),
                JurisdictionOwnershipProjection("J2", 2),
            ),
            projection.jurisdictionOwnership,
        )
        assertEquals(
            listOf(CommanderyControlProjection("C1", 4)),
            projection.commanderyControl,
        )
    }

    @Test
    fun `a live city recolors every province of its county`() {
        // 縣 J2 는 省 P3(治所) · P4 를 함께 가진다. 城을 빼앗으면 두 칸이 같이 바뀌어야 한다 —
        // 治所만 바뀌면 같은 縣 안에 색이 빠진 칸이 남는다(프로빈스 빵꾸). R1(ADR-LITE-052):
        // 현 크기와 무관하게 소속 프로빈스 전체가 함께 움직인다.
        val projection = fixtureProjection().project(
            scenarioCode = "scenario_1010",
            liveCities = listOf(LiveCityOwnership(cityId = 20, provinceIndex = 2, nationId = 7)),
        )

        assertEquals(
            listOf(
                ProvinceOccupancyProjection("P1", 0, 1),
                ProvinceOccupancyProjection("P2", 1, 2),
                ProvinceOccupancyProjection("P3", 2, 7),
                ProvinceOccupancyProjection("P4", 3, 7),
            ),
            projection.provinceOccupancy,
        )
    }

    @Test
    fun `a neutral province of the county follows the conquest instead of staying neutral`() {
        // 빵꾸 방지가 중립 분류와 충돌하지 않는다: 같은 縣의 중립 칸(P4, owner 0)도
        // 점령에 따라 넘어간다. 중립 고정이 아니다(중립 점령은 기본값=가능으로 진행).
        val projection = fixtureProjectionWithNeutralP4().project(
            scenarioCode = "scenario_1010",
            liveCities = listOf(LiveCityOwnership(cityId = 20, provinceIndex = 2, nationId = 7)),
        )

        assertEquals(
            listOf(
                ProvinceOccupancyProjection("P1", 0, 1),
                ProvinceOccupancyProjection("P2", 1, 2),
                ProvinceOccupancyProjection("P3", 2, 7),
                ProvinceOccupancyProjection("P4", 3, 7),
            ),
            projection.provinceOccupancy,
        )
    }

    @Test
    fun `an adjudicated split keeps its canonical owner statically but follows a live conquest`() {
        // J1 의 P2 는 기준표가 치소(P1, 소유 1)와 다른 세력 2 에게 준 칸이다
        // (충돌 허용 원장). 초기 정적 배치에서는 대표 색으로 뭉개지지 않는다.
        val static = fixtureProjection().project("scenario_1010", emptyList())
        assertEquals(1, static.provinceOccupancy.single { it.provinceRecordId == "P1" }.nationId)
        assertEquals(2, static.provinceOccupancy.single { it.provinceRecordId == "P2" }.nationId)
        // R1(ADR-LITE-052): live 점령이 들어오면 심사 분할 칸도 함께 넘어간다.
        val captured = fixtureProjection().project(
            scenarioCode = "scenario_1010",
            liveCities = listOf(LiveCityOwnership(cityId = 10, provinceIndex = 0, nationId = 4)),
        )
        assertEquals(4, captured.provinceOccupancy.single { it.provinceRecordId == "P1" }.nationId)
        assertEquals(4, captured.provinceOccupancy.single { it.provinceRecordId == "P2" }.nationId)
    }

    @Test
    fun `fails when two runtime cities resolve to the same jurisdiction`() {
        val failure = assertThrows<IllegalStateException> {
            fixtureProjection().project(
                scenarioCode = "1010",
                liveCities = listOf(
                    LiveCityOwnership(cityId = 10, provinceIndex = 0, nationId = 1),
                    LiveCityOwnership(cityId = 11, provinceIndex = 1, nationId = 2),
                ),
            )
        }

        assertEquals(
            "Runtime cities 10, 11 resolve to the same jurisdiction J1",
            failure.message,
        )
    }

    @Test
    fun `fails a mixed jurisdiction without an evidence linked allowlist entry`() {
        val failure = assertThrows<IllegalStateException> {
            fixtureProjection(allowMixedJurisdiction = false).project("1010", emptyList())
        }

        assertEquals(
            "Scenario 1010 jurisdiction J1 has unexplained owners 1, 2",
            failure.message,
        )
    }

    @Test
    fun `all canonical scenarios cover every spatial administrative unit exactly once`() {
        val projection = MapAdministrativeOwnership(
            objectMapper = ObjectMapper(),
            mapPath = "../../data/map/han-tiles.json",
            ownershipPath = "../../data/map/han-scenario-province-ownership-v1.json",
            conflictAllowlistPath = "../../data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json",
        )
        val scenarioCodes = listOf(
            1010, 1020, 1021, 1030, 1031,
            1040, 1041, 1050, 1060, 1070,
            1080, 1090, 1100, 1110, 1120,
        )

        scenarioCodes.forEach { scenarioCode ->
            val snapshot = projection.project(scenarioCode.toString(), emptyList())
            assertEquals(1_520, snapshot.provinceOccupancy.size, "scenario $scenarioCode provinces")
            // 1,071 에서 1,070 으로 — 南鄉郡(PARENT-0113)의 합성 치소 관할
            // JURISDICTION-PARENT-0113-SEAT 하나가 접혔다. 동명이지(漢中 南鄉縣)에 잘못
            // 묶여 있던 진짜 南鄉縣(71022)이 제자리로 돌아와 그 임시 관할과 같은 칸에
            // 서게 되자, 임시 관할의 seat 가 제 省 밖으로 나가 아래 seat 검사가 깨졌다.
            // 실물 縣이 그 省들을 받고 郡의 치소 관할이 된다.
            // data/curated/han/county-misbinding-rebindings-v1.json 의
            // supersedesJurisdictionSeatRecovery 참조.
            assertEquals(1_070, snapshot.jurisdictionOwnership.size, "scenario $scenarioCode jurisdictions")
            assertEquals(172, snapshot.commanderyControl.size, "scenario $scenarioCode commanderies")
            assertEquals(
                snapshot.provinceOccupancy.size,
                snapshot.provinceOccupancy.map { it.provinceRecordId }.toSet().size,
                "scenario $scenarioCode duplicate province projection",
            )
            assertEquals(
                snapshot.jurisdictionOwnership.size,
                snapshot.jurisdictionOwnership.map { it.jurisdictionId }.toSet().size,
                "scenario $scenarioCode duplicate jurisdiction projection",
            )
            assertEquals(
                snapshot.commanderyControl.size,
                snapshot.commanderyControl.map { it.commanderyId }.toSet().size,
                "scenario $scenarioCode duplicate commandery projection",
            )
        }
    }

    @Test
    fun `Shu commandery counties use the same projection rule across Liu Yan Liu Zhang Liu Bei and Liu Shan eras`() {
        val projection = MapAdministrativeOwnership(
            objectMapper = ObjectMapper(),
            mapPath = "../../data/map/han-tiles.json",
            ownershipPath = "../../data/map/han-scenario-province-ownership-v1.json",
            conflictAllowlistPath = "../../data/map/han-scenario-jurisdiction-conflict-allowlist-v1.json",
        )
        val shuJurisdictionIds = setOf(
            "200253", "200261", "44394", "44398", "44401",
            "44409", "44445", "44448", "96055", "96436",
        )

        listOf(1020, 1070, 1090, 1100).forEach { scenarioCode ->
            val owners = projection.project(scenarioCode.toString(), emptyList())
                .jurisdictionOwnership
                .filter { it.jurisdictionId in shuJurisdictionIds }
            assertEquals(10, owners.size, "scenario $scenarioCode Shu jurisdictions")
            assertEquals(1, owners.map { it.nationId }.toSet().size, "scenario $scenarioCode Shu owner")
            assertEquals(true, owners.first().nationId > 0, "scenario $scenarioCode Shu is owned")
        }
    }

    private fun fixtureProjectionWithNeutralP4(): MapAdministrativeOwnership {
        val mapPath = tempDir.resolve("han-tiles-neutral.json")
        Files.writeString(
            mapPath,
            """
            {
              "provinceRecords": [
                {"id":"P1","jurisdictionId":"J1"},
                {"id":"P2","jurisdictionId":"J1"},
                {"id":"P3","jurisdictionId":"J2"},
                {"id":"P4","jurisdictionId":"J2"}
              ],
              "jurisdictionRecords": [
                {"id":"J1","commanderyId":"C1","seatPlaceId":"P1","provinceIds":["P1","P2"]},
                {"id":"J2","commanderyId":"C1","seatPlaceId":"P3","provinceIds":["P3","P4"]}
              ],
              "commanderyRecords": [
                {"id":"C1","seatJurisdictionId":"J1","jurisdictionIds":["J1","J2"]}
              ]
            }
            """.trimIndent(),
        )
        val ownershipPath = tempDir.resolve("han-scenario-province-ownership-neutral.json")
        Files.writeString(
            ownershipPath,
            """
            {
              "scenarios": [{
                "scenarioCode": 1010,
                "effectiveYear": 184,
                "assignments": [
                  {"provinceId":"P1","ownerNationId":1,"evidenceIds":["TEST-EVIDENCE-1"]},
                  {"provinceId":"P2","ownerNationId":2,"evidenceIds":["TEST-EVIDENCE-2"]},
                  {"provinceId":"P3","ownerNationId":2,"evidenceIds":["TEST-EVIDENCE-2"]},
                  {"provinceId":"P4","ownerNationId":0,"evidenceIds":["TEST-EVIDENCE-2"]}
                ]
              }]
            }
            """.trimIndent(),
        )
        val allowlistPath = tempDir.resolve("han-scenario-jurisdiction-conflict-allowlist-neutral.json")
        Files.writeString(
            allowlistPath,
            """
            {"entries":[
              {"scenarioCode":1010,"effectiveYear":184,"jurisdictionId":"J1",
               "ownerNationIds":[1,2],"reason":"test conflict",
               "evidenceIds":["TEST-EVIDENCE-1","TEST-EVIDENCE-2"]},
              {"scenarioCode":1010,"effectiveYear":184,"jurisdictionId":"J2",
               "ownerNationIds":[0,2],"reason":"test neutral split",
               "evidenceIds":["TEST-EVIDENCE-2"]}
            ]}
            """.trimIndent(),
        )
        return MapAdministrativeOwnership(
            objectMapper = ObjectMapper(),
            mapPath = mapPath.toString(),
            ownershipPath = ownershipPath.toString(),
            conflictAllowlistPath = allowlistPath.toString(),
        )
    }

    private fun fixtureProjection(allowMixedJurisdiction: Boolean = true): MapAdministrativeOwnership {
        val mapPath = tempDir.resolve("han-tiles.json")
        Files.writeString(
            mapPath,
            """
            {
              "provinceRecords": [
                {"id":"P1","jurisdictionId":"J1"},
                {"id":"P2","jurisdictionId":"J1"},
                {"id":"P3","jurisdictionId":"J2"},
                {"id":"P4","jurisdictionId":"J2"}
              ],
              "jurisdictionRecords": [
                {"id":"J1","commanderyId":"C1","seatPlaceId":"P1","provinceIds":["P1","P2"]},
                {"id":"J2","commanderyId":"C1","seatPlaceId":"P3","provinceIds":["P3","P4"]}
              ],
              "commanderyRecords": [
                {"id":"C1","seatJurisdictionId":"J1","jurisdictionIds":["J1","J2"]}
              ]
            }
            """.trimIndent(),
        )
        val ownershipPath = tempDir.resolve("han-scenario-province-ownership-v1.json")
        Files.writeString(
            ownershipPath,
            """
            {
              "scenarios": [{
                "scenarioCode": 1010,
                "effectiveYear": 184,
                "assignments": [
                  {"provinceId":"P1","ownerNationId":1,"evidenceIds":["TEST-EVIDENCE-1"]},
                  {"provinceId":"P2","ownerNationId":2,"evidenceIds":["TEST-EVIDENCE-2"]},
                  {"provinceId":"P3","ownerNationId":2,"evidenceIds":["TEST-EVIDENCE-2"]},
                  {"provinceId":"P4","ownerNationId":2,"evidenceIds":["TEST-EVIDENCE-2"]}
                ]
              }]
            }
            """.trimIndent(),
        )
        val allowlistPath = tempDir.resolve("han-scenario-jurisdiction-conflict-allowlist-v1.json")
        Files.writeString(
            allowlistPath,
            if (allowMixedJurisdiction) {
                """
                {"entries":[{
                  "scenarioCode":1010,"effectiveYear":184,"jurisdictionId":"J1",
                  "ownerNationIds":[1,2],"reason":"test conflict",
                  "evidenceIds":["TEST-EVIDENCE-1","TEST-EVIDENCE-2"]
                }]}
                """.trimIndent()
            } else {
                """{"entries":[]}"""
            },
        )
        return MapAdministrativeOwnership(
            objectMapper = ObjectMapper(),
            mapPath = mapPath.toString(),
            ownershipPath = ownershipPath.toString(),
            conflictAllowlistPath = allowlistPath.toString(),
        )
    }
}
