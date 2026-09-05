package opensamguk.logic.v2.command

import java.time.Duration
import kotlin.reflect.KClass
import opensamguk.common.wire.TurnDaemonCommandResult

enum class V2CommandLayer { PERSONAL, CHIEF, STRATEGIC, TACTICAL }

enum class V2CommandSourceRing { GENERAL_TURN, NATION_TURN, NONE }

enum class V2CommandSubjectType { GENERAL, RETINUE, OPERATION, BATTLE, CITY, NATION }

enum class V2CommandTarget { CITY, CITY_ROUTE }

enum class V2CommandActor { GENERAL }

enum class V2CommandAuthority { OWNED_CITY }

enum class V2AuthorityPolicyId { SUBJECT_OWNER }

enum class V2CommandParityStatus { LOCKED, ADAPTED, NEW, DEPRECATED }

enum class V2IdempotencyPolicy { NOT_SUPPORTED }

enum class V2RouteRevisionPolicy { NOT_APPLICABLE, PASSTHROUGH, REQUIRED }

sealed interface V2CommandArgs

data class V2GarrisonRecruitArgs(
    val cityId: Int,
    val amount: Int,
) : V2CommandArgs

data class V2CityTransportArgs(
    val fromCityId: Int,
    val toCityId: Int,
    val gold: Long,
    val rice: Long,
    val garrison: Int,
    val routeRevision: Long?,
    val topologyRevision: String? = null,
    val routePathHash: String? = null,
) : V2CommandArgs

data class V2CommandSchema(
    val canonicalId: String,
    val legacyAliases: Set<String>,
    val layer: V2CommandLayer,
    val sourceRing: V2CommandSourceRing,
    val subjectType: V2CommandSubjectType,
    val target: V2CommandTarget,
    val actor: V2CommandActor,
    val authority: V2CommandAuthority,
    val authorityPolicyId: V2AuthorityPolicyId,
    val authorityContextVersion: Int,
    val payloadVersion: Int,
    val adapter: String,
    val parityStatus: V2CommandParityStatus,
    val argsType: KClass<out V2CommandArgs>,
    val resultType: KClass<out TurnDaemonCommandResult>,
    val idempotency: V2IdempotencyPolicy,
    val expiry: Duration,
    val replayEvent: String,
    val routeRevision: V2RouteRevisionPolicy,
    internal val parse: (Map<String, Any?>) -> V2CommandAvailability,
) {
    init {
        require(canonicalId.isNotBlank())
        require(authorityContextVersion > 0)
        require(payloadVersion > 0)
        require(adapter.isNotBlank())
        require(!expiry.isZero && !expiry.isNegative)
        require(replayEvent.isNotBlank())
    }
}

sealed interface V2CommandAvailability {
    data class Available(
        val schema: V2CommandSchema,
        val args: V2CommandArgs,
    ) : V2CommandAvailability

    data class NeedsInput(val missing: List<String>) : V2CommandAvailability

    data class Blocked(
        val code: String,
        val reason: String,
    ) : V2CommandAvailability

    data class Unknown(
        val code: String = "UNKNOWN_COMMAND",
    ) : V2CommandAvailability
}
