package opensamguk.gameapi.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class ReadBarrierJdbcTemplate(
    val jdbc: NamedParameterJdbcTemplate,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    val jdbcUrl: String get() = dataSource.jdbcUrl
    val maximumPoolSize: Int get() = dataSource.maximumPoolSize
    val connectionTimeoutMs: Long get() = dataSource.connectionTimeout

    override fun close() {
        dataSource.close()
    }
}

@Configuration
class ReadBarrierDataSourceConfig {
    @Bean(destroyMethod = "close")
    fun readBarrierJdbcTemplate(
        @Value("\${spring.datasource.url}") jdbcUrl: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        @Value("\${opensamguk.read-barrier.pool.max-size:4}") maxPoolSize: Int,
        @Value("\${opensamguk.read-barrier.pool.connection-timeout-ms:250}") connectionTimeoutMs: Long,
    ): ReadBarrierJdbcTemplate {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = maxPoolSize.coerceAtLeast(1)
            minimumIdle = 0
            connectionTimeout = connectionTimeoutMs.coerceAtLeast(250)
            poolName = "game-api-read-barrier"
        }
        val dataSource = HikariDataSource(config)
        return ReadBarrierJdbcTemplate(NamedParameterJdbcTemplate(dataSource), dataSource)
    }
}
