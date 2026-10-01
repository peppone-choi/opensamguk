package opensamguk.gameapi.council

import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.council.*
import org.springframework.stereotype.Component

/** 호출자의 primary REPEATABLE_READ snapshot을 사용한다. API 외부/engine cache에 조회하지 않는다. */
@Component
class CouncilAuthorityReader(private val gameKv: GameKvReadRepository) : CouncilAuthoritySource {
    override fun read(world: WorldStateReadEntity, nation: NationReadEntity,
                      people: List<GeneralReadEntity>): CouncilAuthority {
        require(world.id == nation.worldId && people.all { it.worldId == world.id && it.nationId == nation.id })
        val designation = try {
            val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", CouncilDesignationCodec.META_KEY)
            if (row != null && (row.worldId != world.id || row.table != "game_env" || row.namespace != "game_env" ||
                    row.key != CouncilDesignationCodec.META_KEY)) CouncilDesignationSnapshot(CouncilSourceState.UNAVAILABLE)
            else CouncilAuthorityProjector.designation(row?.value ?: PersistedMetaJson.raw(world.meta[CouncilDesignationCodec.META_KEY]))
        } catch (_: RuntimeException) { CouncilDesignationSnapshot(CouncilSourceState.UNAVAILABLE) }
        val projected = CouncilAuthorityProjector.project(nation.id, nation.meta,
            people.map { CouncilAuthorityPerson(it.id, it.nationId, it.npcState, it.meta) }, designation,
            // 현재 main에는 봉신 producer 및 계약 atTurn의 실제 접점이 없다. 정원 0으로 위장하지 않는다.
            CouncilVassalSnapshot(CouncilSourceState.UNAVAILABLE))
        return CouncilAuthority(projected.readers, projected.writers, projected.noticeWriters, projected.roles,
            projected.complete, projected.ruler?.generalId, projected.ruler?.revision,
            projected.designationRevision, projected.designationWritable)
    }
}
