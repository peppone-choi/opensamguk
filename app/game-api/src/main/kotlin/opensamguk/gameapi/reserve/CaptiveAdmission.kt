package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.logic.input.CaptiveState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** The world-bound actor row is authoritative for every HWIHA personal reservation. */
@Service
class CaptiveAdmission(private val generals: GeneralReadRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun requireFreeActor(generalId: Int, ownerUserId: Int?) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw AdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        }
        val actor = generals.findById(generalId).orElse(null)
            ?: throw AdmissionDenied("STATE_UNAVAILABLE", "장수 상태를 확인할 수 없습니다.")
        if (actor.userId?.toLongOrNull() != ownerUserId.toLong()) {
            throw AdmissionDenied("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.")
        }
        if (CaptiveState.META_KEY in actor.meta) {
            throw AdmissionDenied("STATE_UNAVAILABLE", "구금된 장수는 개인 순 행동을 예약할 수 없습니다.")
        }
    }
}
