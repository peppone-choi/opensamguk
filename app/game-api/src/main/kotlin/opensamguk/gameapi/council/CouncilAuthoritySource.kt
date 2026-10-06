package opensamguk.gameapi.council

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity

/** Return verified evidence from one primary snapshot; request or JWT roles do not establish authority. */
interface CouncilAuthoritySource {
    fun read(world: WorldStateReadEntity, nation: NationReadEntity,
             people: List<GeneralReadEntity>): CouncilAuthority
}

data class CouncilAuthority(
    val readers: Set<Int>,
    val writers: Set<Int>,
    val noticeWriters: Set<Int>,
    val roles: Map<Int, String>,
    val complete: Boolean,
    val rulerGeneralId: Int? = null,
    val rulerRevision: String? = null,
    val designationRevision: String? = null,
    val designationWritable: Boolean = true,
) {
    init {
        require(readers.all { it > 0 } && writers.all { it > 0 } && noticeWriters.all { it > 0 })
        require(writers.all { it in readers } && noticeWriters.all { it in writers })
        require(roles.keys.all { it in readers })
    }

    companion object {
        fun unavailable() = CouncilAuthority(emptySet(), emptySet(), emptySet(), emptyMap(), false, designationWritable = false)
    }
}

class CouncilReadFailure(val status: Int, val code: String, val explanation: String) : RuntimeException(explanation)

/** Admission and reads share these internal facts within one transaction. */
data class CouncilSession(
    val actor: GeneralReadEntity,
    val world: WorldStateReadEntity?,
    val people: List<GeneralReadEntity>,
    val authority: CouncilAuthority,
)
