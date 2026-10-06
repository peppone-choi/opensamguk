package opensamguk.gateway.d101.application

import opensamguk.gateway.d101.domain.D101PreResetOriginalsRead
import opensamguk.gateway.d101.domain.D101PreResetOriginalsReader

/** Reuses the approved QUERY grant and exact execution binding. No new read authority. */
internal class D101PreResetOriginalsService(
    private val executions: D101ExecutionService,
    private val originals: D101PreResetOriginalsReader,
) {
    fun query(operationId: String, headers: List<String>, authorizationCount: Int): D101PreResetOriginalsRead =
        originals.readForQuery(executions.query(operationId, headers, authorizationCount))
}
