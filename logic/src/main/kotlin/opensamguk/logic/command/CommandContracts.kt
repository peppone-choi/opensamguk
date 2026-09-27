package opensamguk.logic.command

import java.time.Duration
import kotlin.reflect.KClass
import opensamguk.common.wire.TurnDaemonCommandResult

enum class CommandLayer { PERSONAL, CHIEF, STRATEGIC, TACTICAL }

enum class CommandSourceRing { GENERAL_TURN, NATION_TURN, NONE }

enum class CommandSubjectType { GENERAL, RETINUE, OPERATION, BATTLE, CITY, NATION }

enum class CommandTarget { CITY, CITY_ROUTE }

enum class CommandActor { GENERAL }

enum class CommandAuthority { OWNED_CITY }

enum class AuthorityPolicyId { SUBJECT_OWNER }

enum class CommandParityStatus { LOCKED, ADAPTED, NEW, DEPRECATED }

enum class IdempotencyPolicy { NOT_SUPPORTED }

enum class RouteRevisionPolicy { NOT_APPLICABLE, PASSTHROUGH, REQUIRED }

sealed interface CommandArgs

data class GarrisonRecruitArgs(
    val cityId: Int,
    val amount: Int,
) : CommandArgs

data class CityTransportArgs(
    val fromCityId: Int,
    val toCityId: Int,
    val gold: Long,
    val rice: Long,
    val garrison: Int,
    val routeRevision: Long?,
    val topologyRevision: String? = null,
    val routePathHash: String? = null,
) : CommandArgs

data class CommandSchema(
    val canonicalId: String,
    val legacyAliases: Set<String>,
    val layer: CommandLayer,
    val sourceRing: CommandSourceRing,
    val subjectType: CommandSubjectType,
    val target: CommandTarget,
    val actor: CommandActor,
    val authority: CommandAuthority,
    val authorityPolicyId: AuthorityPolicyId,
    val authorityContextVersion: Int,
    val payloadVersion: Int,
    val adapter: String,
    val parityStatus: CommandParityStatus,
    val argsType: KClass<out CommandArgs>,
    val resultType: KClass<out TurnDaemonCommandResult>,
    val idempotency: IdempotencyPolicy,
    val expiry: Duration,
    val replayEvent: String,
    val routeRevision: RouteRevisionPolicy,
    internal val parse: (Map<String, Any?>) -> CommandAvailability,
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

sealed interface CommandAvailability {
    data class Available(
        val schema: CommandSchema,
        val args: CommandArgs,
    ) : CommandAvailability

    data class NeedsInput(val missing: List<String>) : CommandAvailability

    data class Blocked(
        val code: String,
        val reason: String,
    ) : CommandAvailability

    data class Unknown(
        val code: String = "UNKNOWN_COMMAND",
    ) : CommandAvailability
}
