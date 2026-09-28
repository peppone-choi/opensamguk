package opensamguk.gameapi.battle.realtime

import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import opensamguk.infra.battle.realtime.BattleSessionStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** Reads the same PostgreSQL wall clock used for battle leases and deadlines. */
class BattleJoinDbClock(private val jdbc: JdbcOperations, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = BattleJoinDbClock(jdbc, zone)

    override fun instant(): Instant = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)
        ?.toInstant() ?: error("battle clock unavailable")
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "battle.join-ticket", name = ["enabled"], havingValue = "true")
class BattleJoinTicketConfiguration {
    @Bean
    fun battleJoinTicketService(
        store: BattleSessionStore,
        jdbc: NamedParameterJdbcTemplate,
        @Value("\${battle.join-ticket.key-base64:}") encodedKey: String,
        @Value("\${battle.join-ticket.server-id:}") serverId: String,
    ): BattleJoinTicketService {
        require(serverId.matches(Regex("[a-z0-9]{1,48}"))) { "battle join server id configuration is invalid" }
        val key = try {
            Base64.getDecoder().decode(encodedKey)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("battle join ticket key configuration is invalid")
        }
        try {
            require(key.size >= 32 && Base64.getEncoder().encodeToString(key) == encodedKey) {
                "battle join ticket key configuration is invalid"
            }
            return BattleJoinTicketService(store, key, BattleJoinDbClock(jdbc.jdbcOperations), serverId)
        } finally {
            key.fill(0)
        }
    }
}
