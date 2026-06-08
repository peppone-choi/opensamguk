package opensamguk.engine.boot

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

fun interface WorldStateAvailability {
    fun hasWorld(): Boolean
}

@Component
class JdbcWorldStateAvailability(private val jdbc: JdbcTemplate) : WorldStateAvailability {
    override fun hasWorld(): Boolean =
        (jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java) ?: 0) > 0
}
