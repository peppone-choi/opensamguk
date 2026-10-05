package opensamguk.gameapi.county

import org.springframework.stereotype.Service

/** County application read; ownership, selected world and vision are checked by the reader. */
@Service
class CountyDetailQuery(private val reader: CountyDetailReader) {
    fun county(cityId: Int, generalId: Int, userId: Long): CountyDetailDto? =
        reader.county(cityId, generalId, userId)
}
