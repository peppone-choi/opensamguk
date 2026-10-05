package opensamguk.gateway.d101.api

import jakarta.servlet.http.HttpServletRequest
import opensamguk.gateway.d101.application.D101ExecutionService
import opensamguk.gateway.d101.domain.*
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.Collections
import java.util.Base64
import java.util.concurrent.Semaphore

@RestController
internal class D101ExecutionController(
    private val service: D101ExecutionService,
    private val recoveryBeginReader: D101RecoveryBeginReader? = null,
) {
    private val capacity = Semaphore(2)

    @PostMapping(BASE + "/prepare", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun prepare(@PathVariable operationId: String, request: HttpServletRequest): ResponseEntity<*> = bounded(request, operationId) {
        val result = service.prepare(operationId, body(request, 64 * 1024), grants(request), authorizations(request))
        reply(result.execution, if (result.created) HttpStatus.CREATED else HttpStatus.OK)
    }

    @GetMapping(BASE)
    fun query(@PathVariable operationId: String, request: HttpServletRequest): ResponseEntity<*> = bounded(request, operationId) {
        if (body(request, 0).isNotEmpty()) throw D101RequestInvalid()
        val execution = service.query(operationId, grants(request), authorizations(request))
        // QUERY purpose verification precedes the locked committed BEGIN read.
        val begin = if (execution.state == D101ExecutionState.RECOVERY_REQUIRED) {
            try {
                val observed = recoveryBeginReader?.readForQuery(execution) ?: throw D101ObservationUnavailable()
                if (observed.operationId != execution.intent.operationId ||
                    observed.verifyingRevision != execution.verifyingRevision ||
                    D101StrictJson.hash(observed.originalBytes()) != observed.beginReceiptSha256) throw D101OperationConflict()
                observed
            } catch (conflict: D101OperationConflict) {
                throw conflict
            } catch (_: Exception) {
                throw D101ObservationUnavailable()
            }
        } else null
        reply(execution, HttpStatus.OK, begin)
    }

    @PostMapping(BASE + "/dispatch-intent", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun dispatch(@PathVariable operationId: String, request: HttpServletRequest): ResponseEntity<*> = bounded(request, operationId) {
        val result = service.dispatch(operationId, body(request, 16 * 1024), grants(request), authorizations(request))
        reply(result.execution, if (result.created) HttpStatus.CREATED else HttpStatus.OK)
    }

    @PostMapping(BASE + "/terminal", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun terminal(@PathVariable operationId: String, request: HttpServletRequest): ResponseEntity<*> = bounded(request, operationId) {
        val result = service.terminal(operationId, body(request, 16 * 1024), grants(request), authorizations(request))
        reply(result.execution, HttpStatus.OK)
    }

    private fun bounded(request: HttpServletRequest, operationId: String, action: () -> ResponseEntity<*>): ResponseEntity<*> {
        if (!capacity.tryAcquire()) return error("CAPACITY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE)
        try {
            if (!D101StrictJson.OPERATION.matches(operationId) || request.queryString != null) throw D101RequestInvalid()
            val paths = D101PurposeAction.entries.filter { it.method == request.method }.map { it.path(operationId) }
            if (request.requestURI !in paths) throw D101RequestInvalid()
            return action()
        } catch (_: java.io.IOException) {
            return error("INVALID_REQUEST", HttpStatus.BAD_REQUEST)
        } catch (_: D101RequestInvalid) {
            return error("INVALID_REQUEST", HttpStatus.BAD_REQUEST)
        } catch (_: D101PurposeGrantInvalid) {
            return error("PURPOSE_GRANT_INVALID", HttpStatus.FORBIDDEN)
        } catch (_: D101PurposeAuthorityUnavailable) {
            return error("PURPOSE_AUTHORITY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE)
        } catch (_: D101OperationNotFound) {
            return error("OPERATION_NOT_FOUND", HttpStatus.NOT_FOUND)
        } catch (_: D101OperationConflict) {
            return error("OPERATION_CONFLICT", HttpStatus.CONFLICT)
        } catch (_: D101ObservationUnavailable) {
            return error("OBSERVATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE)
        } finally {
            capacity.release()
        }
    }

    private fun body(request: HttpServletRequest, limit: Int): ByteArray {
        if (request.contentLengthLong > limit) throw D101RequestInvalid()
        val bytes = request.inputStream.readNBytes(limit + 1)
        if (bytes.size > limit) throw D101RequestInvalid()
        return bytes
    }

    private fun grants(request: HttpServletRequest): List<String> = Collections.list(request.getHeaders("X-D101-Grant"))
    private fun authorizations(request: HttpServletRequest) = Collections.list(request.getHeaders("Authorization")).size
    private fun error(code: String, status: HttpStatus) = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
        .body(mapOf("schemaVersion" to 1, "code" to code, "status" to status.value()))

    private fun reply(execution: D101Execution, status: HttpStatus, begin: D101RecoveryBeginRead? = null): ResponseEntity<*> = ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore()).body(linkedMapOf(
            "schemaVersion" to 1, "serverId" to "pep", "operationId" to execution.intent.operationId,
            "state" to execution.state.name, "targetFingerprint" to execution.intent.targetFingerprint,
            "approvalIntentSha256" to execution.intent.sha256, "gatewayPayloadSha256" to execution.gatewayPayloadSha256,
            "initialPublicRevision" to execution.intent.initialPublicRevision.toString(),
            "verifyingRevision" to execution.verifyingRevision.toString(), "publishedRevision" to execution.publishedRevision?.toString(),
            "rootRequestFingerprint" to execution.dispatch?.rootRequestFingerprint,
            "rootResultReceiptSha256" to execution.rootResultReceiptSha256, "validationReceiptSha256" to execution.validationReceiptSha256,
            "createdAtUtc" to execution.createdAt.toString(), "updatedAtUtc" to execution.updatedAt.toString(),
        ).apply {
            if (begin != null) {
                put("recoveryBeginReceiptSha256", begin.beginReceiptSha256)
                put("recoveryBeginReceiptBytesBase64url", Base64.getUrlEncoder().withoutPadding().encodeToString(begin.originalBytes()))
            }
        })

    companion object {
        const val BASE = "/internal/d101/servers/pep/operations/{operationId}"
    }
}
