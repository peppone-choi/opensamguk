package opensamguk.gameapi.compatibility

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.common.auth.GatewayJwtKeys
import opensamguk.common.world.WorldId
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.GeneralTurnSlotWriteRow
import opensamguk.infra.persistence.JdbcFlushExecutor
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/** Controlled stored reservations, separate from the immutable zero-row source fixture. */
@Tag("d119-compatibility")
@EnabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
class D119PopulatedV69CompatibilityIT {
    @Test
    fun `exact candidate preserves controlled old V69 reservations through reads and rollback`() {
        D119LegacyV69Fixture().use { fixture ->
            val support = D119PopulatedV69Support(fixture)
            try {
                support.verifyCandidateSource() // Check an explicit slot pin before runtime build or DB.
                val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
                check(GatewayJwtKeys.rsaPublicKey(publicKey).encoded.contentEquals(keys.public.encoded))
                fixture.prepare() // Actual immutable old Flyway69/importer; requires zero reserved rows.
                val reservedBefore = support.populateFromOldProducer()
                assertEquals(2, reservedBefore.size)
                val before = fixture.fingerprint()
                support.verifyCandidateSource()
                fixture.boot(publicKey)
                val app = assertNotNull(fixture.application)
                assertEquals("false", app.environment.getProperty("spring.flyway.enabled"))
                assertEquals("validate", app.environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                assertEquals("1", app.environment.getProperty("SERVER_GENERATION"))
                assertEquals((1..69).toList(), fixture.history())
                val port = (app as WebServerApplicationContext).webServer.port
                val http = HttpClient.newHttpClient()
                fun get(path: String): com.fasterxml.jackson.databind.JsonNode {
                    val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, response.statusCode(), "actual controlled HTTP $path")
                    return fixture.mapper.readTree(response.body())
                }
                assertEquals("UP", get("/actuator/health")["status"].asText())
                val preview = get("/api/map/preview")
                val topology = get("/api/map/strategic-topology")
                val binding = topology["binding"]
                assertEquals(1, binding["worldId"].asInt())
                assertEquals(fixture.manifest["topologyHash"].asText(), binding["topologyHash"].asText())
                assertEquals(fixture.manifest["tilesSha256"].asText(), binding["baseTilesSha256"].asText())
                assertEquals(binding, preview["strategicTopology"])
                assertEquals("REDACTED", topology["controlVisibility"].asText())
                assertTrue(get("/api/server-basic-info")["game"].isObject)
                val cities = app.getBean(CityReadRepository::class.java).findAll()
                val generals = app.getBean(GeneralReadRepository::class.java).findAll()
                val nations = app.getBean(NationReadRepository::class.java).findAll()
                assertEquals(1428, cities.size)
                assertTrue(generals.isNotEmpty() && nations.isNotEmpty())
                assertEquals(1, app.getBean(WorldStateReadRepository::class.java).findAll().single().id)
                val artifacts = assertNotNull(assertNotNull(app.getBean(ActiveWorldArtifactResolver::class.java).resolve()).artifacts)
                assertEquals("V3_1428", artifacts.variant.name)
                assertEquals(artifacts.cityConst.all().keys, cities.map { it.id }.toSet())
                val turns = app.getBean(GeneralTurnReadRepository::class.java)
                assertEquals(2L, turns.count())
                val readRows = turns.findAll().sortedBy { it.turnIdx }
                assertEquals(2, readRows.size)
                readRows.zip(reservedBefore).forEach { (read, expected) ->
                    assertEquals(expected["id"].asInt(), read.id)
                    assertEquals(1, read.worldId)
                    assertEquals(1001, read.generalId)
                    assertEquals(expected["turn_idx"].asInt(), read.turnIdx)
                    assertEquals(expected["action_code"].asText(), read.actionCode)
                    assertEquals(expected["arg"], fixture.mapper.readTree(fixture.mapper.writeValueAsString(read.arg)))
                    assertEquals(expected["brief"].asText(), read.brief)
                }
                assertEquals(reservedBefore, support.reservations(), "startup/JPA must preserve every reserved column")
                val pins = fixture.jdbc.queryForList("""
                    SELECT topology_hash FROM general_spatial_position WHERE world_id=1
                    UNION SELECT topology_hash FROM province_control WHERE world_id=1
                    UNION SELECT topology_hash FROM water_zone_control WHERE world_id=1
                """.trimIndent(), String::class.java)
                assertEquals(listOf(fixture.manifest["topologyHash"].asText()), pins)
                assertEquals(before, fixture.fingerprint(), "startup/reads must preserve schema and all table rows")
                val transaction = TransactionTemplate(DataSourceTransactionManager(fixture.dataSource))
                val executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(fixture.dataSource), transaction)
                val city = cities.first().toLogic()
                val original = fixture.jdbc.queryForObject(
                    "SELECT pop FROM city WHERE world_id=1 AND id=?", Int::class.java, city.id)!!
                val mutation = support.contract["rollbackMutation"]
                val changed = reservedBefore.first().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                changed.put("action_code", mutation["actionCode"].asText())
                changed.set<com.fasterxml.jackson.databind.JsonNode>("arg", mutation["arg"])
                changed.put("brief", mutation["brief"].asText())
                var rowsDuringFlush: List<com.fasterxml.jackson.databind.JsonNode> = emptyList()
                transaction.executeWithoutResult { status ->
                    val clock = fixture.jdbc.queryForMap(
                        "SELECT id,current_year,current_month,current_phase FROM world_state WHERE id=1")
                    executor.flush(FlushPayload(WorldId(1), clock, updatedCities = listOf(city.copy(population = original + 1)),
                        generalTurnSlotWrites = listOf(GeneralTurnSlotWriteRow(1001, mutation["slot"].asInt(),
                            mutation["actionCode"].asText(), fixture.mapper.writeValueAsString(mutation["arg"]),
                            mutation["brief"].asText())))))
                    assertEquals(original + 1, fixture.jdbc.queryForObject(
                        "SELECT pop FROM city WHERE world_id=1 AND id=?", Int::class.java, city.id))
                    rowsDuringFlush = support.reservations()
                    assertEquals(listOf(changed, reservedBefore[1]), rowsDuringFlush,
                        "actual candidate JDBC slot write must mutate only the declared fields")
                    status.setRollbackOnly()
                }
                assertEquals(before, fixture.fingerprint(), "rollback must restore schema and all rows")
                assertEquals(reservedBefore, support.reservations())
                assertEquals((1..69).toList(), fixture.history())
                val denied = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/my-generals"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding())
                assertEquals(401, denied.statusCode())
                fixture.stopAdmissionStub()
                val unavailable = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/map/preview"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding())
                assertEquals(503, unavailable.statusCode())
                support.verifyCandidateSource()
                support.writeProof("A04_CONTROLLED_POPULATED_VERIFIED", mapOf(
                    "before" to before, "after" to fixture.fingerprint(), "reservationRowsBefore" to reservedBefore,
                    "reservationRowsAfter" to support.reservations(), "actualCountBefore" to reservedBefore.size,
                    "reservationRowsDuringFlush" to rowsDuringFlush,
                    "reservationSlotWrite" to "WRITE_READ_ROLLBACK_VERIFIED",
                    "actualCountAfter" to support.reservations().size, "flywayVersions" to fixture.history(),
                    "topologyHash" to fixture.manifest["topologyHash"].asText(), "baseTilesSha256" to fixture.manifest["tilesSha256"].asText(),
                    "jpaReservationRead" to "VERIFIED", "jdbcFlush" to "WRITE_READ_ROLLBACK_VERIFIED",
                    "privateHttp" to 401, "admissionSourceStopped" to 503, "productFilterBypass" to false,
                    "postV69MigrationCompatibility" to "UNVERIFIED"))
            } catch (error: Throwable) {
                fixture.failure(error)
                support.writeProof("A04_CONTROLLED_POPULATED_FAILED", mapOf("exceptionType" to error.javaClass.name))
                throw error
            }
        }
    }
}
