package opensamguk.gameapi.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ServerAdmissionPolicyTest {
    private var now = 0L
    private var read: ServerAdmissionRead = ServerAdmissionRead.Unavailable
    private var calls = 0
    private fun policy() = ServerAdmissionPolicy(ServerAdmissionSource { calls++; read }, { now })
    private fun known(state: ServerPublicationState, revision: Long = 10) = ServerAdmissionRead.Known(
        ServerAdmissionSnapshot("pep", state, revision), now, ServerAdmissionDraftBudget.totalNanos,
    )

    @Test fun `PUBLIC continues existing route ACL for anonymous and verified accounts`() {
        val policy = policy()
        read = known(ServerPublicationState.PUBLIC)
        assertInstanceOf(ServerAdmissionDecision.Allowed::class.java, policy.checkHttp(false))
        assertInstanceOf(ServerAdmissionDecision.Allowed::class.java, policy.checkHttp(true))
        assertEquals(2, calls)
    }

    @Test fun `VERIFYING returns exact anonymous 401 and authenticated ordinary 403`() {
        val policy = policy()
        read = known(ServerPublicationState.VERIFYING)
        assertEquals(ServerAdmissionDecision.Denied.AUTH_REQUIRED, policy.checkHttp(false))
        // 역할별 bypass가 없다. verified USER와 ordinary ADMIN은 동일 경로를 사용한다.
        repeat(2) { assertEquals(ServerAdmissionDecision.Denied.NOT_PUBLIC, policy.checkHttp(true)) }
    }

    @Test fun `source unavailable is 503 even after successful PUBLIC`() {
        val policy = policy()
        read = known(ServerPublicationState.PUBLIC)
        assertInstanceOf(ServerAdmissionDecision.Allowed::class.java, policy.checkOrdinary())
        read = ServerAdmissionRead.Unavailable
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkHttp(false))
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkHttp(true))
    }

    @Test fun `late lower revision cannot restore PUBLIC`() {
        val policy = policy()
        read = known(ServerPublicationState.VERIFYING, 11)
        policy.checkOrdinary()
        read = known(ServerPublicationState.PUBLIC, 10)
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
    }

    @Test fun `same revision conflicting state cannot reopen and higher fresh publication can`() {
        val policy = policy()
        read = known(ServerPublicationState.VERIFYING, 11)
        policy.checkOrdinary()
        read = known(ServerPublicationState.PUBLIC, 11)
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
        read = known(ServerPublicationState.PUBLIC, 12)
        assertInstanceOf(ServerAdmissionDecision.Allowed::class.java, policy.checkOrdinary())
    }

    @Test fun `higher observed revision invalidates older fanOut proof before next send`() {
        val policy = policy()
        read = known(ServerPublicationState.PUBLIC, 10)
        val allowed = policy.checkOrdinary() as ServerAdmissionDecision.Allowed
        assertTrue(policy.stillCurrent(allowed))
        read = known(ServerPublicationState.VERIFYING, 11)
        policy.checkOrdinary()
        assertFalse(policy.stillCurrent(allowed))
    }

    @Test fun `expired result cannot update revision or be used by delayed callback`() {
        val policy = policy()
        read = known(ServerPublicationState.PUBLIC, 10)
        val allowed = policy.checkOrdinary() as ServerAdmissionDecision.Allowed
        now += ServerAdmissionDraftBudget.totalNanos
        assertFalse(policy.stillCurrent(allowed))
        read = known(ServerPublicationState.VERIFYING, 11)
        now += ServerAdmissionDraftBudget.totalNanos
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
        read = known(ServerPublicationState.PUBLIC, 10)
        assertInstanceOf(ServerAdmissionDecision.Allowed::class.java, policy.checkOrdinary())
    }
    @Test fun `watchdog error fences existing positive proof even if later fresh PUBLIC recovers`() {
        val policy = policy()
        read = known(ServerPublicationState.PUBLIC)
        val previous = policy.checkOrdinary() as ServerAdmissionDecision.Allowed
        read = ServerAdmissionRead.Unavailable
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
        assertFalse(policy.stillCurrent(previous))
        read = known(ServerPublicationState.PUBLIC)
        val recovered = policy.checkOrdinary() as ServerAdmissionDecision.Allowed
        assertTrue(policy.stillCurrent(recovered))
        assertFalse(policy.stillCurrent(previous))
    }

    @Test fun `local capacity rejects this request without fencing an existing fresh PUBLIC round`() {
        val policy = policy(); read = known(ServerPublicationState.PUBLIC)
        val previous = policy.checkOrdinary() as ServerAdmissionDecision.Allowed
        read = ServerAdmissionRead.LocalCapacity
        assertEquals(ServerAdmissionDecision.Denied.LOCAL_CAPACITY, policy.checkHttp(false))
        assertEquals(503, ServerAdmissionDecision.Denied.LOCAL_CAPACITY.httpStatus)
        assertTrue(policy.stillCurrent(previous))
        read = ServerAdmissionRead.Unavailable
        assertEquals(ServerAdmissionDecision.Denied.UNAVAILABLE, policy.checkOrdinary())
        assertFalse(policy.stillCurrent(previous))
    }

}
