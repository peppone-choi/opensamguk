package opensamguk.gameapi.member

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CachingMemberProfileClientTest {
    @Test
    fun `caches a successful profile and serves the cache without another gateway call`() {
        var calls = 0
        val profile = MemberProfile("테스터", 1, null, 0)
        val gateway = MemberProfileClient {
            calls += 1
            profile
        }
        val client = CachingMemberProfileClient(gateway, MemoryCache())

        assertEquals(profile, client.get(77))
        assertEquals(profile, client.get(77))
        assertEquals(1, calls)
    }

    @Test
    fun `serves a cache hit while the gateway is unavailable`() {
        val profile = MemberProfile("cached", 1, null, 0)
        val cache = MemoryCache(mutableMapOf(77L to profile))
        val client = CachingMemberProfileClient(
            MemberProfileClient { throw MemberProfileUnavailableException() },
            cache,
        )

        assertEquals(profile, client.get(77))
    }

    @Test
    fun `propagates gateway unavailability on a cache miss`() {
        val client = CachingMemberProfileClient(
            MemberProfileClient { throw MemberProfileUnavailableException() },
            MemoryCache(),
        )

        assertFailsWith<MemberProfileUnavailableException> { client.get(77) }
    }

    @Test
    fun `does not cache an absent profile`() {
        var calls = 0
        val client = CachingMemberProfileClient(
            MemberProfileClient {
                calls += 1
                null
            },
            MemoryCache(),
        )

        assertNull(client.get(77))
        assertNull(client.get(77))
        assertEquals(2, calls)
    }

    private class MemoryCache(
        private val values: MutableMap<Long, MemberProfile> = mutableMapOf(),
    ) : MemberProfileCache {
        override fun get(userId: Long): MemberProfile? = values[userId]

        override fun put(userId: Long, profile: MemberProfile) {
            values[userId] = profile
        }
    }
}
