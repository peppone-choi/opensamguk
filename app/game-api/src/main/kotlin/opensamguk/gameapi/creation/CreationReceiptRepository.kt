package opensamguk.gameapi.creation

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class CreationReceiptRow(
    val worldId: Int,
    val accountId: Long,
    val clientRequestId: UUID,
    val internalCommandRequestId: String,
    val bodySha256: String,
    val choiceKind: String,
)

@Repository
class CreationReceiptRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** A conflict is resolved by reading the existing receipt, never by replacing its payload. */
    fun insertIfAbsent(row: CreationReceiptRow): Boolean = jdbc.update(
        """
        INSERT INTO general_creation_receipt
            (world_id, account_id, client_request_id, internal_command_request_id, body_sha256, choice_kind)
        VALUES (:worldId, :accountId, :clientRequestId, :internalCommandRequestId, :bodySha256, :choiceKind)
        ON CONFLICT (world_id, account_id, client_request_id) DO NOTHING
        """.trimIndent(), params(row),
    ) == 1

    fun find(worldId: Int, accountId: Long, clientRequestId: UUID): CreationReceiptRow? = jdbc.query(
        """
        SELECT world_id, account_id, client_request_id, internal_command_request_id, body_sha256, choice_kind
          FROM general_creation_receipt
         WHERE world_id = :worldId AND account_id = :accountId AND client_request_id = :clientRequestId
        """.trimIndent(),
        MapSqlParameterSource().addValue("worldId", worldId).addValue("accountId", accountId)
            .addValue("clientRequestId", clientRequestId),
    ) { rs, _ -> CreationReceiptRow(
        rs.getInt("world_id"), rs.getLong("account_id"), rs.getObject("client_request_id", UUID::class.java),
        rs.getString("internal_command_request_id"), rs.getString("body_sha256").trim(),
        rs.getString("choice_kind"),
    ) }.firstOrNull()

    private fun params(row: CreationReceiptRow) = MapSqlParameterSource()
        .addValue("worldId", row.worldId)
        .addValue("accountId", row.accountId)
        .addValue("clientRequestId", row.clientRequestId)
        .addValue("internalCommandRequestId", row.internalCommandRequestId)
        .addValue("bodySha256", row.bodySha256)
        .addValue("choiceKind", row.choiceKind)
}
