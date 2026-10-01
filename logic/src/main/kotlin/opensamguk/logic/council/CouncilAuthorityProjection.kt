package opensamguk.logic.council

/** 이 목록은 같은 월드/소속의 실제 roster에서 만들어야 한다. 직함 수치는 권한 자료가 아니다. */
data class CouncilAuthorityPerson(val id: Int, val nationId: Int, val npcState: Int,
                                  val meta: Map<String, Any?>)

enum class CouncilSourceState { AVAILABLE, NOT_SEEDED, UNAVAILABLE }

data class CouncilDesignationSnapshot(val state: CouncilSourceState, val value: CouncilDesignationState? = null) {
    init { require((state == CouncilSourceState.AVAILABLE) == (value != null)) }
}

/** producer와 같은 시점 및 실제 계약을 검증한 봉신 주공 ID만 받는 접점이다. */
data class CouncilVassalSnapshot(val state: CouncilSourceState, val lordIds: Set<Int> = emptySet()) {
    init { require(lordIds.all { it > 0 }); require(state == CouncilSourceState.AVAILABLE || lordIds.isEmpty()) }
}

data class CouncilAuthorityProjection(
    val readers: Set<Int>, val writers: Set<Int>, val noticeWriters: Set<Int>, val roles: Map<Int, String>,
    val complete: Boolean, val ruler: CurrentRulerBinding?, val designationRevision: String?,
    val designationWritable: Boolean,
)

object CouncilAuthorityProjector {
    fun project(nationId: Int, nationMeta: Map<String, Any?>, people: List<CouncilAuthorityPerson>,
                designation: CouncilDesignationSnapshot,
                vassals: CouncilVassalSnapshot): CouncilAuthorityProjection {
        require(nationId > 0 && people.all { it.id > 0 && it.nationId == nationId })
        require(people.map { it.id }.distinct().size == people.size)
        val byId = people.associateBy { it.id }
        val ruler = runCatching { CurrentRulerBinding.read(nationMeta) }.getOrNull()?.takeIf { binding ->
            byId[binding.generalId]?.let { person -> binding.agreesWith(person.id, person.nationId,
                nationId, person.npcState, person.meta) } == true
        }
        val roles = linkedMapOf<Int, String>()
        ruler?.let { roles[it.generalId] = "군주" }
        // 봉신 producer/atTurn 또는 군주 근거가 없으면 주공 직함으로 합성하지 않는다.
        if (ruler != null && vassals.state == CouncilSourceState.AVAILABLE) {
            require(vassals.lordIds.all { id -> byId[id]?.npcState?.let { it != 5 } == true })
            vassals.lordIds.sorted().forEach { roles.putIfAbsent(it, "봉신 주공") }
        }
        var successionUndecided = false
        if (ruler != null && designation.state == CouncilSourceState.AVAILABLE) {
            designation.value!!.grants.filter { it.nationId == nationId && it.revokedByRequestId == null }.forEach { grant ->
                if (grant.issuerGeneralId == ruler.generalId && grant.issuerRevision == ruler.revision) {
                    byId[grant.targetGeneralId]?.takeIf { it.npcState != 5 }?.let { roles.putIfAbsent(it.id, "군주 지정") }
                } else {
                    // 교체 전 지정은 보존하며, 사용자 정책 확정 전에는 상속 여부를 해석하지 않는다.
                    successionUndecided = true
                }
            }
        }
        val participants = roles.keys.toSet()
        return CouncilAuthorityProjection(participants, participants, participants, roles,
            ruler != null && designation.state == CouncilSourceState.AVAILABLE &&
                vassals.state == CouncilSourceState.AVAILABLE && !successionUndecided,
            ruler, designation.value?.revision, designation.state != CouncilSourceState.UNAVAILABLE)
    }

    fun designation(raw: String?): CouncilDesignationSnapshot = if (raw == null)
        CouncilDesignationSnapshot(CouncilSourceState.NOT_SEEDED)
    else try {
        CouncilDesignationSnapshot(CouncilSourceState.AVAILABLE, checkNotNull(CouncilDesignationCodec.decode(raw)))
    } catch (_: RuntimeException) { CouncilDesignationSnapshot(CouncilSourceState.UNAVAILABLE) }
}
