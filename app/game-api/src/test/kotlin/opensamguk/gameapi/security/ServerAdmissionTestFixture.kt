package opensamguk.gameapi.security

/** 기존 route/ACL fixture에서만 명시적으로 넣는 PUBLIC 원천. 제품에 default-open bean은 없다. */
object ServerAdmissionTestFixture {
    fun publicPolicy() = ServerAdmissionPolicy(ServerAdmissionSource {
        ServerAdmissionRead.Known(ServerAdmissionSnapshot("testfixture", ServerPublicationState.PUBLIC, 1),
            System.nanoTime(), ServerAdmissionDraftBudget.totalNanos)
    })
}
