package opensamguk.gameapi.reserve

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service

data class ReservationSlotDto(
    val turnIdx: Int,
    val actionCode: String,
    val brief: String,
    val arg: Map<String, Any?>,
    val revision: String,
)

/** Payload and its CAS identity are read from one snapshot, without adding a V77 JPA mapping. */
@Repository
class ReservationSlotReader(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
    private val processWorld: GameApiProcessWorld,
) {
    fun read(generalId: Int): List<ReservationSlotDto> = jdbc.query(
        "SELECT turn_idx, action_code, brief, arg::text, reservation_revision::text " +
            "FROM general_turn WHERE world_id=:world AND general_id=:actor ORDER BY turn_idx",
        mapOf("world" to processWorld.worldId.value, "actor" to generalId),
    ) { rs, _ -> ReservationSlotDto(rs.getInt("turn_idx"), rs.getString("action_code"),
        rs.getString("brief"), mapper.readValue(rs.getString("arg"), object : TypeReference<Map<String, Any?>>() {}),
        requireNotNull(rs.getString("reservation_revision"))) }
}

@Service
class ReservationSlotQuery(private val reader: ReservationSlotReader) {
    fun read(generalId: Int) = reader.read(generalId)
}
