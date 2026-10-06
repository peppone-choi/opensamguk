package opensamguk.gameapi.council

import java.time.Instant
import java.util.Base64

/** A cursor grants no access; recheck the current actor, affiliation and room on every page. */
data class CouncilScope(
    val worldId: Int,
    val nationId: Int,
    val generalId: Int,
    val room: CouncilRoom,
    val kind: CouncilArticleKind?,
) {
    init { require(worldId > 0 && nationId > 0 && generalId > 0) }
}

data class CouncilPosition(val createdAt: Instant, val id: Int) {
    init { require(id > 0) }
}

object CouncilCursor {
    fun encode(scope: CouncilScope, position: CouncilPosition): String = Base64.getUrlEncoder()
        .withoutPadding().encodeToString(
            listOf("1", scope.worldId, scope.nationId, scope.generalId, scope.room.name,
                scope.kind?.name ?: "ALL", position.createdAt, position.id)
                .joinToString("|").toByteArray(Charsets.US_ASCII),
        )

    /** Reject cursors from a different world, account actor, affiliation, room or article kind. */
    fun decode(value: String?, scope: CouncilScope): CouncilPosition? {
        if (value == null) return null
        return try {
            require(value.length in 1..256 && value.matches(Regex("[A-Za-z0-9_-]+")))
            val bytes = Base64.getUrlDecoder().decode(value)
            require(bytes.all { it.toInt() in 0..127 })
            val parts = bytes.toString(Charsets.US_ASCII).split('|')
            require(parts.size == 8 && parts[0] == "1")
            require(parts[1] == scope.worldId.toString() && parts[2] == scope.nationId.toString() &&
                parts[3] == scope.generalId.toString() && parts[4] == scope.room.name &&
                parts[5] == (scope.kind?.name ?: "ALL"))
            val position = CouncilPosition(Instant.parse(parts[6]), parts[7].toInt())
            require(encode(scope, position) == value)
            position
        } catch (_: RuntimeException) {
            throw InvalidCouncilCursor()
        }
    }
}

class InvalidCouncilCursor : IllegalArgumentException("회의실 커서가 올바르지 않습니다.")
