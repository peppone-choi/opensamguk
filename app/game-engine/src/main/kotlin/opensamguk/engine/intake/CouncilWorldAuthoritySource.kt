package opensamguk.engine.intake

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.council.*

/** 접수 시 proof를 캐시하지 않고 같은 틱의 지정/회수 및 소속 변경을 다음 실행에 반영한다. */
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
        // in-memory chief도 durable binding과 일치해야 한다. 직함으로 보충하지 않는다.
        if (projected.ruler?.generalId != nation.chiefGeneralId) return CouncilExecutionAuthority()
        return CouncilExecutionAuthority(projected.readers, projected.writers, projected.noticeWriters,
            projected.ruler?.generalId, projected.ruler?.revision)
    }
}
