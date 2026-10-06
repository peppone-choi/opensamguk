package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.time.Clock
import javax.sql.DataSource

/** Actual read-only same-transaction DB facts. No health/display/config-input
 * fallback. The caller must separately verify the fixed candidate DB/image/source
 * binding and seed-worker stage; these facts do not authorize live promotion. */
class D101SeedCapPromotionGate(
    private val fixedCandidateDataSource: DataSource?,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class ObservedCaps(val worldId: Int, val scenarioCode: String, val generation: Int?,
        val tickSeconds: Int, val configMaxGeneral: Int, val gameEnvMaxGeneral: Int,
        val observedAtUtc: String, val configOriginalSha256: String, val metaOriginalSha256: String,
        val gameEnvOriginalSha256: String)
    private val mapper=ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun observeNewWorldBeforePromotion(): ObservedCaps = try {
        val source=fixedCandidateDataSource ?: throw SelectedSourceUnavailable()
        val started=clock.instant()
        val monotonicStarted=System.nanoTime()
        source.connection.use { connection ->
            connection.isReadOnly=true
            connection.transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit=false
            try {
                var configWire:ByteArray?=null
                var metaWire:ByteArray?=null
                connection.prepareStatement("SELECT id, scenario_code, tick_seconds, config, meta FROM world_state").use { stmt ->
                    stmt.queryTimeout=2
                    stmt.executeQuery().use { rows ->
                        if (!rows.next() || rows.getInt("id")!=1 || rows.wasNull() ||
                            rows.getString("scenario_code")!="scenario_3190" || rows.getInt("tick_seconds")!=3600 || rows.wasNull())
                            throw SelectedSourceUnavailable()
                        configWire=rows.getString("config")?.toByteArray(Charsets.UTF_8)
                        metaWire=rows.getString("meta")?.toByteArray(Charsets.UTF_8)
                        if (rows.next()) throw SelectedSourceUnavailable()
                    }
                }
                var envWire:ByteArray?=null
                connection.prepareStatement("SELECT value FROM game_kv WHERE world_id=1 AND \"table\"='game_env' AND namespace='game_env' AND key='maxgeneral'").use { stmt ->
                    stmt.queryTimeout=2
                    stmt.executeQuery().use { rows ->
                        if (!rows.next()) throw SelectedSourceUnavailable()
                        envWire=rows.getString(1)?.toByteArray(Charsets.UTF_8)
                        if (rows.next()) throw SelectedSourceUnavailable()
                    }
                }
                fun original(wire:ByteArray?)=wire?.takeIf { it.isNotEmpty() && it.size<=64*1024 } ?: throw SelectedSourceUnavailable()
                val config=original(configWire);val meta=original(metaWire);val env=original(envWire)
                fun json(wire:ByteArray)=mapper.readTree(wire)
                fun exactInteger(node:com.fasterxml.jackson.databind.JsonNode?,value:Int) {
                    if (node==null || !node.isIntegralNumber || !node.canConvertToInt() || node.intValue()!=value)
                        throw SelectedSourceUnavailable()
                }
                val configNode=json(config); val metaNode=json(meta)
                if (!configNode.isObject || !metaNode.isObject) throw SelectedSourceUnavailable()
                exactInteger(configNode["maxgeneral"],50)
                // Fresh importer does not persist server_generation. Preserve
                // UNKNOWN here; the caller requires actual C4 runtime provenance.
                val generationNode=metaNode["server_generation"]
                val observedGeneration=if (generationNode==null) null else {
                    exactInteger(generationNode,0); 0
                }
                exactInteger(json(env),50)
                val completed=clock.instant()
                if (completed<started || java.time.Duration.between(started,completed).seconds>=30 ||
                    System.nanoTime()-monotonicStarted>=java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) throw SelectedSourceUnavailable()
                connection.rollback()
                ObservedCaps(1,"scenario_3190",observedGeneration,3600,50,50,completed.toString(),
                    selectedOriginalSha(config),selectedOriginalSha(meta),selectedOriginalSha(env))
            } catch (_:Exception) {
                try { connection.rollback() } catch (_:Exception) {}
                throw SelectedSourceUnavailable()
            }
        }
    } catch (_:Exception) { throw SelectedSourceUnavailable() }
}
