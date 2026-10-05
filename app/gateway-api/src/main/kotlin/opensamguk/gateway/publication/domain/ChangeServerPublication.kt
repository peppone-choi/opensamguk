package opensamguk.gateway.publication.domain

data class VerifyServerPublication(val serverId: String, val expectedRevision: Long, val target: ServerPublicationTarget)
data class PublishServerPublication(val serverId: String, val expectedRevision: Long, val operationId: String, val receiptSha256: String)

interface ServerPublicationWriter {
    fun verifying(command: VerifyServerPublication): ServerPublication
    // Dedicated transaction adapter; caller must authenticate the D101 purpose first.
    fun verifyingD101(command: VerifyServerPublication): ServerPublication = throw ServerPublicationSourceUnavailable()
    fun publish(command: PublishServerPublication): ServerPublication
}

// Production must verify a trusted receipt for the exact stored target/revision.
// A request SHA or reset success alone is never a verification implementation.
fun interface ServerPublicationReceiptVerifier {
    fun verify(serverId: String, publication: ServerPublication, receiptSha256: String)
}
class ServerPublicationConflict : RuntimeException("server publication transition conflicts")
