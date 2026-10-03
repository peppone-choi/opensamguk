package opensamguk.gameapi.read

import opensamguk.gameapi.dto.DirectoryPerson
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

internal enum class PeopleSort {
    ID, NAME, AFFILIATION, LEADERSHIP, STRENGTH, INTEL, POLITICS, CHARM, TOTAL,
    COMMAND, ADMINISTRATION, STRATEGY, ENVOY, AGE;

    fun key(row: PeopleDirectoryRow): PeopleSortValue {
        val p = row.person
        return when (this) {
            ID -> PeopleSortValue(number = p.generalId.toLong())
            NAME -> PeopleSortValue(text = PeopleNameSearch.normalize(p.name))
            AFFILIATION -> PeopleSortValue(text = p.affiliation?.name?.let(PeopleNameSearch::normalize))
            LEADERSHIP -> PeopleSortValue(number = p.stats?.leadership?.toLong())
            STRENGTH -> PeopleSortValue(number = p.stats?.strength?.toLong())
            INTEL -> PeopleSortValue(number = p.stats?.intel?.toLong())
            POLITICS -> PeopleSortValue(number = p.stats?.politics?.toLong())
            CHARM -> PeopleSortValue(number = p.stats?.charm?.toLong())
            TOTAL -> PeopleSortValue(number = p.stats?.let {
                it.leadership.toLong() + it.strength + it.intel + it.politics + it.charm
            })
            COMMAND -> PeopleSortValue(number = p.aptitudes?.command?.toLong())
            ADMINISTRATION -> PeopleSortValue(number = p.aptitudes?.administration?.toLong())
            STRATEGY -> PeopleSortValue(number = p.aptitudes?.strategy?.toLong())
            ENVOY -> PeopleSortValue(number = p.aptitudes?.envoy?.toLong())
            AGE -> PeopleSortValue(number = row.age?.toLong())
        }
    }
}

internal enum class PeopleDirection { ASC, DESC }
internal data class PeopleDirectoryRow(val person: DirectoryPerson, val age: Int?)
internal data class PeopleSortValue(val text: String? = null, val number: Long? = null) {
    val missing: Boolean get() = text == null && number == null
    fun wire(): String = when {
        text != null -> "t:" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(UTF_8))
        number != null -> "n:$number"
        else -> "z:"
    }
}

internal object PeopleNameSearch {
    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    fun matches(name: String, query: String): Boolean {
        val normalized = normalize(name)
        if (normalized.contains(query)) return true
        if (query.isEmpty() || !query.all { it in INITIALS }) return false
        val initials = normalized.map { c ->
            if (c in '\uAC00'..'\uD7A3') INITIALS[(c.code - 0xAC00) / 588] else c
        }.joinToString("")
        return initials.contains(query)
    }
}

/** Nulls remain last in either direction; an equal primary key always uses ascending general ID. */
internal fun peopleComparator(sort: PeopleSort, direction: PeopleDirection): Comparator<PeopleDirectoryRow> =
    Comparator { a, b ->
        val left = sort.key(a)
        val right = sort.key(b)
        val primary = when {
            left.missing && right.missing -> 0
            left.missing -> 1
            right.missing -> -1
            else -> {
                val comparison = if (left.text != null && right.text != null) left.text.compareTo(right.text)
                    else requireNotNull(left.number).compareTo(requireNotNull(right.number))
                if (direction == PeopleDirection.ASC) comparison else -comparison
            }
        }
        if (primary != 0) primary else a.person.generalId.compareTo(b.person.generalId)
    }

/** Cursor positions grant no access. A changed directory must restart rather than silently skip rows. */
internal object PeopleCursor {
    data class Position(val revision: String, val lastId: Int, val key: String)

    fun digest(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun revision(rows: List<PeopleDirectoryRow>): String = digest(rows.sortedBy { it.person.generalId }.flatMap { row ->
        val p = row.person
        // Fixed field positions and explicit nullable markers avoid delimiter or name ambiguity.
        listOf(p.generalId.toString(), p.name, p.affiliation?.nationId?.toString(), p.affiliation?.name,
            p.affiliation?.color, p.stats?.leadership?.toString(), p.stats?.strength?.toString(),
            p.stats?.intel?.toString(), p.stats?.politics?.toString(), p.stats?.charm?.toString(),
            p.aptitudes?.command?.toString(), p.aptitudes?.administration?.toString(),
            p.aptitudes?.strategy?.toString(), p.aptitudes?.envoy?.toString(), row.age?.toString())
            .map { it?.let { value -> "1$value" } ?: "0" }
    })

    fun encode(context: String, revision: String, lastId: Int, key: PeopleSortValue): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("2|$context|$revision|$lastId|${key.wire()}".toByteArray(UTF_8))

    fun decode(value: String?, context: String): Position? {
        if (value == null) return null
        return try {
            require(value.length in 1..16384 && value.matches(Regex("[A-Za-z0-9_-]+")))
            val parts = String(Base64.getUrlDecoder().decode(value), UTF_8).split('|')
            require(parts.size == 5 && parts[0] == "2" && parts[1] == context)
            require(parts[2].matches(Regex("[0-9a-f]{64}")))
            Position(parts[2], parts[3].toInt().also { require(it > 0) }, parts[4])
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    fun invalid(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid people cursor")
    fun changed(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "People directory changed; restart pagination")
}
