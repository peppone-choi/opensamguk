package opensamguk.gateway.d101.api

import jakarta.servlet.http.HttpServletRequest
import opensamguk.gateway.d101.application.D101RecoveryService
import opensamguk.gateway.d101.domain.*
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.Collections
import java.util.concurrent.Semaphore

@RestController
internal class D101RecoveryController(private val service: D101RecoveryService) {
    private val capacity = Semaphore(2)

    @PostMapping(BASE + "/recovery-begin", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun begin(@PathVariable operationId: String, request: HttpServletRequest): ResponseEntity<*> = bounded(
        operationId, request, D101PurposeAction.RECOVERY_BEGIN,
    ) {
        val result = service.begin(operationId, body(request), grants(request), authorizations(request))
        ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(linkedMapOf(
                "schemaVersion" to 1, "serverId" to "pep", "operationId" to result.execution.intent.operationId,
                "state" to result.execution.state.name,
                "verifyingRevision" to result.execution.verifyingRevision.toString(),
                "recoveryBeginReceiptSha256" to result.beginReceiptSha256,
            ))
    }

    @PostMapping(BASE + "/recovery-close", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun close(@PathVariable operationId: String, request: HttpServletRequest): ResponseEntity<*> = bounded(
        operationId, request, D101PurposeAction.RECOVERY_CLOSE,
    ) {
        val result = service.close(operationId, body(request), grants(request), authorizations(request))
        ResponseEntity.status(HttpStatus.OK).cacheControl(CacheControl.noStore()).body(linkedMapOf(
            "schemaVersion" to 1, "serverId" to "pep", "operationId" to result.execution.intent.operationId,
            "state" to result.execution.state.name,
            "verifyingRevision" to result.execution.verifyingRevision.toString(),
            "recoveryBeginReceiptSha256" to result.beginReceiptSha256,
            "recoveryResultReceiptSha256" to result.recoveryResultReceiptSha256,
        ))
    }

    private fun bounded(
        operationId: String, request: HttpServletRequest, action: D101PurposeAction,
        work: () -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        if (!capacity.tryAcquire()) return error("CAPACITY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE)
        try {
            if (!D101StrictJson.OPERATION.matches(operationId) || request.queryString != null ||
                request.method != action.method || request.requestURI != action.path(operationId)) throw D101RequestInvalid()
            return work()
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

    private fun body(request: HttpServletRequest): ByteArray {
        if (request.contentLengthLong > 16 * 1024) throw D101RequestInvalid()
        return request.inputStream.readNBytes(16 * 1024 + 1).also { if (it.size > 16 * 1024) throw D101RequestInvalid() }
    }

    private fun grants(request: HttpServletRequest): List<String> = Collections.list(request.getHeaders("X-D101-Grant"))
    private fun authorizations(request: HttpServletRequest) = Collections.list(request.getHeaders("Authorization")).size
    private fun error(code: String, status: HttpStatus) = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
        .body(mapOf("schemaVersion" to 1, "code" to code, "status" to status.value()))

    private companion object {
        const val BASE = "/internal/d101/servers/pep/operations/{operationId}"
    }
}
