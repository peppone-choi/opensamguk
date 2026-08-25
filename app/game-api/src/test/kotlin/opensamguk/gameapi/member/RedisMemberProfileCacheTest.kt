package opensamguk.gameapi.member

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import kotlin.test.assertEquals

class RedisMemberProfileCacheTest {
    private val redis = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>

    private val cache = RedisMemberProfileCache(redis, ObjectMapper().findAndRegisterModules())

    @Test
    fun `stores JSON with a 120 second expiry and reads the same profile back`() {
        val profile = MemberProfile("display-name", 6, "avatar.png", 1)
        `when`(redis.opsForValue()).thenReturn(valueOps)

        cache.put(77, profile)

        val json = ArgumentCaptor.forClass(String::class.java)
        verify(valueOps).set(eq("member-profile:77"), json.capture(), eq(Duration.ofSeconds(120)))

        `when`(valueOps.get("member-profile:77")).thenReturn(json.value)
        assertEquals(profile, cache.get(77))
    }
}
