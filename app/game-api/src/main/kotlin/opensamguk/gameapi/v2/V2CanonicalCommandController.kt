package opensamguk.gameapi.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class V2CommandIntakeResponse(
    val status: String,
    val commandId: String? = null,
    val requestId: String? = null,
    val terminal: Boolean = false,
    val code: String? = null,
    val reason: String? = null,
    val missing: List<String> = emptyList(),
)

@RestController
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v2/commands")
class V2CanonicalCommandController(
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
    private val contextualPrecheck: V2CommandPrecheckService,
) {
    @PostMapping("/{commandId}/precheck")
    fun precheck(
        @PathVariable commandId: String,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<V2CommandIntakeResponse> {
        val authFailure = authenticate(userId, generalId)
        if (authFailure != null) return authFailure
        return availability(commandId, generalId, argJson)
    }

    @PostMapping("/{commandId}")
    fun submit(
        @PathVariable commandId: String,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<V2CommandIntakeResponse> {
        val authFailure = authenticate(userId, generalId)
        if (authFailure != null) return authFailure
        val schema = V2CommandRegistry.resolve(commandId)
        if (schema == null || schema.canonicalId != commandId) return unknown(commandId)
        val parsed = parseAvailability(commandId, argJson)
        val available = parsed as? V2CommandAvailability.Available
            ?: return response(commandId, parsed)
        val checked = contextualPrecheck.precheck(generalId, available)
        if (checked !is V2CommandAvailability.Available) return response(commandId, checked)
        val reserved = reserve.reserveV2(generalId, schema, checked.args, Math.toIntExact(userId!!))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            V2CommandIntakeResponse(
                status = "ACCEPTED",
                commandId = schema.canonicalId,
                requestId = reserved.requestId,
                terminal = false,
            ),
        )
    }

    private fun availability(
        commandId: String,
        generalId: Int,
        argJson: String?,
    ): ResponseEntity<V2CommandIntakeResponse> {
        val schema = V2CommandRegistry.resolve(commandId)
        if (schema == null || schema.canonicalId != commandId) return unknown(commandId)
        val parsed = parseAvailability(commandId, argJson)
        val result = if (parsed is V2CommandAvailability.Available) {
            contextualPrecheck.precheck(generalId, parsed)
        } else {
            parsed
        }
        return response(commandId, result)
    }

    private fun parseAvailability(commandId: String, argJson: String?): V2CommandAvailability {
        val args = V2CommandArgumentParser.parse(argJson)
            ?: return V2CommandAvailability.Blocked("INVALID_ARGUMENTS", "명령 인자 형식이 올바르지 않습니다.")
        return V2CommandRegistry.precheck(commandId, args)
    }

    private fun response(
        commandId: String,
        result: V2CommandAvailability,
    ): ResponseEntity<V2CommandIntakeResponse> {
        val canonicalId = V2CommandRegistry.resolve(commandId)?.canonicalId
        return when (result) {
            is V2CommandAvailability.Available -> ResponseEntity.ok(
                V2CommandIntakeResponse(status = "AVAILABLE", commandId = canonicalId),
            )
            is V2CommandAvailability.NeedsInput -> ResponseEntity.unprocessableEntity().body(
                V2CommandIntakeResponse(status = "NEEDS_INPUT", commandId = canonicalId, missing = result.missing),
            )
            is V2CommandAvailability.Blocked -> blocked(result.code, result.reason, canonicalId)
            is V2CommandAvailability.Unknown -> unknown(commandId)
        }
    }

    private fun authenticate(userId: Long?, generalId: Int): ResponseEntity<V2CommandIntakeResponse>? {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (generalId != resolver.resolveGeneralId(userId)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    private fun unknown(commandId: String): ResponseEntity<V2CommandIntakeResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            V2CommandIntakeResponse(status = "UNKNOWN", commandId = commandId, code = "UNKNOWN_COMMAND"),
        )

    private fun blocked(
        code: String,
        reason: String,
        commandId: String? = null,
    ): ResponseEntity<V2CommandIntakeResponse> = ResponseEntity.unprocessableEntity().body(
        V2CommandIntakeResponse(status = "BLOCKED", commandId = commandId, code = code, reason = reason),
    )
}

object V2CommandArgumentParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun parse(raw: String?): Map<String, Any?>? = try {
        val element = json.parseToJsonElement(raw?.takeIf(String::isNotBlank) ?: "{}") as? JsonObject ?: return null
        element.mapValues { (_, value) ->
            when (value) {
                JsonNull -> null
                is JsonPrimitive -> if (value.isString) {
                    value.content
                } else {
                    value.booleanOrNull ?: value.longOrNull ?: value.contentOrNull
                }
                else -> value.toString()
            }
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    fun invalid(): V2CommandAvailability.Blocked =
        V2CommandAvailability.Blocked("INVALID_ARGUMENTS", "명령 인자 형식이 올바르지 않습니다.")
}

private fun V2CommandAvailability.toLegacyError(commandId: String): ResponseEntity<Any> = when (this) {
    is V2CommandAvailability.NeedsInput -> ResponseEntity.unprocessableEntity().body(
        V2CommandIntakeResponse(status = "NEEDS_INPUT", commandId = commandId, missing = missing),
    )
    is V2CommandAvailability.Blocked -> ResponseEntity.unprocessableEntity().body(
        V2CommandIntakeResponse(status = "BLOCKED", commandId = commandId, code = code, reason = reason),
    )
    is V2CommandAvailability.Unknown -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        V2CommandIntakeResponse(status = "UNKNOWN", commandId = commandId, code = code),
    )
    is V2CommandAvailability.Available -> error("available command is not an error")
}

fun validateLegacyV2Arguments(commandId: String, argJson: String?): V2CommandAvailability {
    val args = V2CommandArgumentParser.parse(argJson) ?: return V2CommandArgumentParser.invalid()
    return V2CommandRegistry.precheck(commandId, args)
}

fun V2CommandAvailability.legacyError(commandId: String): ResponseEntity<Any> = toLegacyError(commandId)
