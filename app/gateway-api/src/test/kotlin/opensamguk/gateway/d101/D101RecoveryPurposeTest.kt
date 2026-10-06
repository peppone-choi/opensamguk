package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class D101RecoveryPurposeTest {
    @Test fun `separate signed post actions remain available after cutoff until original recovery deadline`() {
        val f=D101Fixture(); f.clock.epoch=f.now+3601
        for(action in listOf(D101PurposeAction.RECOVERY_BEGIN,D101PurposeAction.RECOVERY_CLOSE)) {
            val request=f.request(action)
            assertEquals("POST",request.method)
            assertTrue(request.path.endsWith(if(action==D101PurposeAction.RECOVERY_BEGIN) "/recovery-begin" else "/recovery-close"))
            val claims=f.claims(request).put("issuedAtUnix",f.clock.epoch).put("expiresAtUnix",f.clock.epoch+60)
            val grant=f.verifier().verify(listOf(f.header(claims)),request)
            grant.requireRecoveryWindow()
            assertThrows(D101PurposeGrantInvalid::class.java) { grant.requireSettlementWindow() }
            val query=f.request(D101PurposeAction.QUERY)
            assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(f.header(claims)),query) }
            f.clock.epoch+=60
            assertThrows(D101PurposeGrantInvalid::class.java) { grant.requireRecoveryWindow() }
        }
    }
    @Test fun `query and settlement grants never authorize recovery and deadline cannot renew`() {
        val f=D101Fixture()
        for(action in listOf(D101PurposeAction.QUERY,D101PurposeAction.SETTLE_REGISTRY)) {
            val request=f.request(action)
            val grant=f.verifier().verify(listOf(f.header(f.claims(request))),request)
            assertThrows(D101PurposeGrantInvalid::class.java) { grant.requireRecoveryWindow() }
        }
        f.clock.epoch=f.now+7200
        val request=f.request(D101PurposeAction.RECOVERY_CLOSE)
        val claims=f.claims(request).put("issuedAtUnix",f.clock.epoch).put("expiresAtUnix",f.clock.epoch+60)
        assertThrows(D101PurposeGrantInvalid::class.java) { f.verifier().verify(listOf(f.header(claims)),request) }
    }
}
