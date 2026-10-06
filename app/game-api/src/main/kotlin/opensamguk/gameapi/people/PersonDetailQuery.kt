package opensamguk.gameapi.people

import org.springframework.stereotype.Service

/** Person detail application read; ownership, world identity and relation are checked by the reader. */
@Service
class PersonDetailQuery(private val reader: PersonDetailReader) {
    fun person(targetGeneralId: Int, generalId: Int, userId: Long): PersonDetailDto? =
        reader.person(targetGeneralId, generalId, userId)
}
