package opensamguk.gateway.d101.infra

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*

internal class D101VerifiedRecoveryAuthorityAdapter(
    private val beginClient: D101RootExecutionResultClient,
    private val closeClient: D101RootRecoveryResultClient,
):D101RecoveryAuthority {
    override fun readBegin(execution:D101Execution,rootResultReceiptSha256:String):D101VerifiedRecoveryBegin {
        val result=beginClient.readVerified(execution,rootResultReceiptSha256)
        return D101VerifiedRecoveryBegin(execution,result.rawSha256,result.status.name,result.originalBytes())
            .also { it.requireMatches(execution,rootResultReceiptSha256) }
    }
    override fun readClose(execution:D101Execution,recoveryBeginReceiptSha256:String,
        recoveryResultReceiptSha256:String):D101VerifiedRecoveryClose =
        closeClient.readVerified(execution,recoveryBeginReceiptSha256,recoveryResultReceiptSha256)
            .also {
                beginClient.readVerified(execution,it.originalRootResultSha256)
                it.requireMatches(execution,recoveryBeginReceiptSha256,recoveryResultReceiptSha256)
            }
}
