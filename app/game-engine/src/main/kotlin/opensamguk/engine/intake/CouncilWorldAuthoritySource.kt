package opensamguk.engine.intake

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.council.*

/** Re-read same-tick designation and affiliation changes instead of caching admission evidence. */
class CouncilWorldAuthoritySource(private val world: InMemoryTurnWorld) : CouncilExecutionAuthoritySource {
    override fun read(nationId: Int): CouncilExecutionAuthority {
        val nation = world.getNationById(nationId) ?: return CouncilExecutionAuthority()
        val designation = try { CouncilAuthorityProjector.designation(PersistedMetaJson.raw(
            world.getState().meta[CouncilDesignationCodec.META_KEY])) }
            catch (_: RuntimeException) { CouncilDesignationSnapshot(CouncilSourceState.UNAVAILABLE) }
        val projected = CouncilAuthorityProjector.project(nationId, nation.meta,
            world.listGenerals().filter { it.nationId == nationId }.map {
                CouncilAuthorityPerson(it.id, it.nationId, it.npcState, it.meta)
            }, designation, CouncilVassalSnapshot(CouncilSourceState.UNAVAILABLE))
        // The in-memory chief must match the durable binding; office levels cannot supply missing evidence.
        if (projected.ruler?.generalId != nation.chiefGeneralId) return CouncilExecutionAuthority()
        return CouncilExecutionAuthority(projected.readers, projected.writers, projected.noticeWriters,
            projected.ruler?.generalId, projected.ruler?.revision)
    }
}
