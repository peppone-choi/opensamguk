package opensamguk.gameapi.member

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Component
@Primary
class CachingMemberProfileClient(
    @Qualifier("gatewayMemberProfileClient") private val gateway: MemberProfileClient,
    private val cache: MemberProfileCache,
) : MemberProfileClient {
    override fun get(userId: Long): MemberProfile? {
        cache.get(userId)?.let { return it }
        return gateway.get(userId)?.also { cache.put(userId, it) }
    }
}
