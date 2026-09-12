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
        val projection = fixtureProjection().project(
            scenarioCode = "scenario_1010",
            liveCities = listOf(LiveCityOwnership(cityId = 10, provinceIndex = 1, nationId = 4)),
        )

        assertEquals(
            listOf(
                ProvinceOccupancyProjection("P1", 0, 4),
                ProvinceOccupancyProjection("P2", 1, 2),
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
    fun `a live city recolors every province of its county that shares the seat baseline`() {
        // 縣 J2 는 省 P3(治所) · P4 를 함께 가진다. 城을 빼앗으면 두 칸이 같이 바뀌어야 한다 —
        // 治所만 바뀌면 같은 縣 안에 색이 빠진 칸이 남는다(프로빈스 빵꾸).
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
    fun `an adjudicated split province keeps its canonical owner when the county seat falls`() {
        // J1 의 P2 는 기준표가 치소(P1, 소유 1)와 다른 세력 2 에게 준 칸이다
        // (충돌 허용 원장). 城이 넘어가도 이 칸은 대표 색으로 뭉개지 않는다.
        val projection = fixtureProjection().project(
            scenarioCode = "scenario_1010",
            liveCities = listOf(LiveCityOwnership(cityId = 10, provinceIndex = 0, nationId = 4)),
        )

        assertEquals(4, projection.provinceOccupancy.single { it.provinceRecordId == "P1" }.nationId)
        assertEquals(2, projection.provinceOccupancy.single { it.provinceRecordId == "P2" }.nationId)
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
