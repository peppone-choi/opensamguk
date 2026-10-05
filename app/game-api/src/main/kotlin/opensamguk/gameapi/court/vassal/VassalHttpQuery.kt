package opensamguk.gameapi.court.vassal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class VassalHttpQuery(private val stored: VassalStoredTermsQuery) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(generalId: Int, userId: Long): VassalHttpDto = VassalHttpView.project(stored.read(generalId, userId))
}
