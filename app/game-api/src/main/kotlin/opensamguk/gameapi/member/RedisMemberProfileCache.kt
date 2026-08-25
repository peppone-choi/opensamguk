package opensamguk.gameapi.member

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisMemberProfileCache(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : MemberProfileCache {
    override fun get(userId: Long): MemberProfile? = runCatching {
        redis.opsForValue().get(key(userId))?.let { objectMapper.readValue(it, MemberProfile::class.java) }
    }.getOrNull()

    override fun put(userId: Long, profile: MemberProfile) {
        runCatching {
            // Identity changes can remain stale for this TTL; 120 seconds balances update lag and restart tolerance.
            redis.opsForValue().set(key(userId), objectMapper.writeValueAsString(profile), CACHE_TTL)
        }
    }

    private fun key(userId: Long): String = "member-profile:$userId"

    private companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(120)
    }
}
