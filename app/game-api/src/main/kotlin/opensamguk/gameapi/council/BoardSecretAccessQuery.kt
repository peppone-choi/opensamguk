package opensamguk.gameapi.council

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.logic.input.RuleProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** The compatibility board uses the same current ownership and designation evidence as Council. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class BoardSecretAccessQuery(
    private val council: CouncilReader,
    private val worlds: WorldStateReadRepository,
) {
    fun blockedReason(userId: Long, resolved: GeneralResolver.ResolvedGeneral): String? = try {
        when (worlds.processRuleProfile()) {
            RuleProfile.SAMMO -> if (resolved.permission >= 2) null else LEGACY_DENIED
            RuleProfile.HWIHA -> {
                val session = council.session(userId)
                if (session.actor.id != resolved.general.id || session.actor.worldId != resolved.general.worldId ||
                    session.actor.nationId != resolved.nationId) UNAVAILABLE
                else if (session.actor.id in session.authority.readers) null
                else if (!session.authority.complete) UNAVAILABLE
                else "기밀실 참여 권한이 없습니다."
            }
            null -> UNAVAILABLE
        }
    } catch (_: RuntimeException) { UNAVAILABLE }

    companion object {
        const val UNAVAILABLE = "회의실 권한과 저장 상태를 확인할 수 없습니다."
        const val LEGACY_DENIED = "권한이 부족합니다. 수뇌부가 아닙니다."
    }
}
