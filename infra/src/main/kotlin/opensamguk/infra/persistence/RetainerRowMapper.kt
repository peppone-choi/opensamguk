package opensamguk.infra.persistence

/**
 * Phase 4X-A 가신·부곡 flush 행(step-8g). 엔진 `Retainer`/`Bugok` 모양을 그대로 운반하되 infra 가 엔진 타입에
 * 결합되지 않도록 여기서 정의한다(TroopRow 와 같은 위치). `world_id` 는 executor 가 주입한다.
 */
data class RetainerRow(
    val id: Int,
    val masterGeneralId: Int,
    val origin: String,
    val generalId: Int?,
    val name: String,
    val relation: String,
    val role: String,
    val hasOwnBugok: Boolean,
    val releasePolicy: String,
    val loyalty: Int,
    val task: String,
)

data class BugokRow(
    val id: Int,
    val masterGeneralId: Int,
    val name: String,
    val troops: Int,
    val crewTypeId: Int,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val provisions: Int,
    val commanderRetainerId: Int?,
    val commanderBonusApplied: Boolean = false,
)
