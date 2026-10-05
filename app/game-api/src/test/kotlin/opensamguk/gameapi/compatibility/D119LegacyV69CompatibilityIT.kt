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
import opensamguk.infra.persistence.JdbcFlushExecutor
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/** A1a only. Real JWT -> old engine dispatcher/result/SSE and new web belong to A2/A4. */
@Tag("d119-compatibility")
@EnabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
class D119LegacyV69CompatibilityIT {
    @Test
    fun `candidate boots reads and JDBC flushes the public immutable old V69 fixture`() {
        D119LegacyV69Fixture().use { fixture ->
            try {
                val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
                check(GatewayJwtKeys.rsaPublicKey(publicKey).encoded.contentEquals(keys.public.encoded))
                fixture.prepare()
                val before = fixture.fingerprint()
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
                    assertEquals(200, response.statusCode(), "actual HTTP $path")
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
                assertTrue(get("/api/server-basic-info")["game"].isObject)
                assertEquals("REDACTED", topology["controlVisibility"].asText())
                // Actual production repositories materialize complete old rows under JPA validate.
                val cities = app.getBean(CityReadRepository::class.java).findAll()
                val generals = app.getBean(GeneralReadRepository::class.java).findAll()
                val nations = app.getBean(NationReadRepository::class.java).findAll()
                val world = app.getBean(WorldStateReadRepository::class.java).findAll().single()
                assertEquals(1428, cities.size)
                assertTrue(generals.isNotEmpty() && nations.isNotEmpty())
                assertEquals(1, world.id)
                val selected = assertNotNull(app.getBean(ActiveWorldArtifactResolver::class.java).resolve())
                val artifacts = assertNotNull(selected.artifacts)
                assertEquals("V3_1428", artifacts.variant.name)
                assertEquals(artifacts.cityConst.all().keys, cities.map { it.id }.toSet())
                // The pinned old HWIHA importer seeds a sparse queue with no reservations.
                val turns = app.getBean(GeneralTurnReadRepository::class.java)
                assertEquals(0, fixture.jdbc.queryForObject(
                    "SELECT count(*) FROM general_turn WHERE world_id=1", Int::class.java))
                assertEquals(0L, turns.count())
                assertEquals(emptyList(), turns.findAll())
                val pins = fixture.jdbc.queryForList("""
                    SELECT topology_hash FROM general_spatial_position WHERE world_id=1
                    UNION SELECT topology_hash FROM province_control WHERE world_id=1
                    UNION SELECT topology_hash FROM water_zone_control WHERE world_id=1
                """.trimIndent(), String::class.java)
                assertEquals(listOf(fixture.manifest["topologyHash"].asText()), pins)
                assertEquals(before, fixture.fingerprint(), "startup and real reads must not alter schema/rows")
                // Real candidate flush executor; transaction rolls back before the next comparison.
                val transaction = TransactionTemplate(DataSourceTransactionManager(fixture.dataSource))
                val executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(fixture.dataSource), transaction)
                val city = cities.first().toLogic()
                val original = fixture.jdbc.queryForObject(
                    "SELECT pop FROM city WHERE world_id=1 AND id=?", Int::class.java, city.id)!!
                transaction.executeWithoutResult { status ->
                    val clock = fixture.jdbc.queryForMap(
                        "SELECT id,current_year,current_month,current_phase FROM world_state WHERE id=1")
                    executor.flush(FlushPayload(WorldId(1), clock, updatedCities = listOf(city.copy(population = original + 1))))
                    assertEquals(original + 1, fixture.jdbc.queryForObject(
                        "SELECT pop FROM city WHERE world_id=1 AND id=?", Int::class.java, city.id))
                    status.setRollbackOnly()
                }
                assertEquals(before, fixture.fingerprint(), "rollback must restore schema/all rows")
                assertEquals((1..69).toList(), fixture.history())
                // No issued token/principal injection: identity-required access remains closed.
                val denied = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/my-generals"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding())
                assertEquals(401, denied.statusCode())
                // The product admission filter must close again when its test source disappears.
                fixture.stopAdmissionStub()
                val unavailable = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/map/preview"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding())
                assertEquals(503, unavailable.statusCode())
                fixture.writeReceipt("A1A_PUBLIC_FIXTURE_VERIFIED", mapOf(
                    "before" to before, "after" to fixture.fingerprint(), "flywayVersions" to fixture.history(),
                    "readRepositories" to listOf("city", "general", "nation", "world_state", "general_turn"),
                    "realHttp" to listOf("health", "preview", "strategic-topology", "server-basic-info"),
                    "jdbcFlush" to "WRITE_READ_ROLLBACK_VERIFIED", "privateHttpWire" to "UNVERIFIED",
                    "admissionSourceStopped" to "HTTP_503_VERIFIED",
                    "generalTurnSeed" to "OLD_HWIHA_EMPTY_QUEUE_VERIFIED",
                    "populatedGeneralTurnCompatibility" to "UNVERIFIED",
                    "postV69MigrationCompatibility" to "UNVERIFIED"))
            } catch (error: Throwable) {
                fixture.failure(error)
                throw error
            }
        }
    }
}
