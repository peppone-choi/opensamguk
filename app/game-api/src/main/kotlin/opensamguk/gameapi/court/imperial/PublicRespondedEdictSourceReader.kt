package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.imperial.ImperialEdictCodec
import opensamguk.logic.imperial.ImperialEdictProjection
import opensamguk.logic.input.Phase
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.Collections

/** Public RESPONDED subset from the existing projection, without privileged viewer inference. */
@Component
class PublicRespondedEdictSourceReader(private val worlds: WorldStateReadRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(): PublicRespondedEdictSource = try {
        val world = requireNotNull(worlds.findProcessWorld())
        require(world.id > 0 && world.currentYear > 0)
        val context = PublicRespondedEdictContext(world.id,
            Phase(world.currentYear, world.currentMonth, world.currentPhase))
        val edicts = ImperialEdictCodec.read(world.meta)
        if (edicts == null) {
            PublicRespondedEdictSource(PublicRespondedEdictSourceStatus.NOT_SEEDED, context)
        } else {
            val records = edicts.mapNotNull { edict ->
                ImperialEdictProjection.forViewer(edict, null, null, emptySet())
            }
            PublicRespondedEdictSource(PublicRespondedEdictSourceStatus.READY, context,
                Collections.unmodifiableList(records))
        }
    } catch (_: IllegalArgumentException) {
        unavailable()
    } catch (_: IllegalStateException) {
        unavailable()
    } catch (cause: ResponseStatusException) {
        if (cause.statusCode == HttpStatus.CONFLICT) unavailable() else throw cause
    }

    private fun unavailable() = PublicRespondedEdictSource(PublicRespondedEdictSourceStatus.UNAVAILABLE)
}
