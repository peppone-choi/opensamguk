package opensamguk.gameapi.court.offer

import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeAppointmentOffer
import org.springframework.dao.DataAccessException
import org.springframework.web.server.ResponseStatusException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Only the verified actor's persisted personal metadata is an OFFICE source. */
@Component
class CourtOffersReader(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int, generalId: Int, userId: Long): CourtOffersDto {
        val world = try {
            worlds.findProcessWorld()
        } catch (_: ResponseStatusException) {
            throw CourtOffersWorldUnavailable()
        } catch (_: DataAccessException) {
            throw CourtOffersWorldUnavailable()
        } ?: throw CourtOffersWorldUnavailable()
        if (world.id <= 0) throw CourtOffersWorldUnavailable()
        if (world.id != worldId) throw CourtOffersForbidden()
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            throw CourtOffersWorldUnavailable()
        }
        val person = generals.findById(generalId).orElse(null)?.takeIf {
            it.id == generalId && it.worldId == worldId && it.userId == userId.toString()
        } ?: return unavailable(now)
        val office = try {
            OfficeAppointmentOffer.read(person.meta)?.takeIf { it.request.candidateId == generalId }
        } catch (_: IllegalArgumentException) {
            null
        }
        val sources = CourtOfferSourceKind.entries.map { kind ->
            if (kind == CourtOfferSourceKind.OFFICE && office != null) {
                CourtOfferSourceDto(kind, CourtOfferReadStatus.READY,
                    listOf(OfficeStoredOfferView.project(office)), CourtOfferRevisionStatus.UNVERSIONED)
            } else {
                CourtOfferSourceDto(kind, CourtOfferReadStatus.UNAVAILABLE)
            }
        }
        // No producer/key/codec is connected for the other three sources.
        return CourtOffersDto(CourtOffersStatus.UNAVAILABLE, now, sources.flatMap { it.records.orEmpty() }, sources)
    }

    private fun unavailable(now: Phase? = null) = CourtOffersDto(
        CourtOffersStatus.UNAVAILABLE, now, emptyList(),
        CourtOfferSourceKind.entries.map { CourtOfferSourceDto(it, CourtOfferReadStatus.UNAVAILABLE) },
    )
}
