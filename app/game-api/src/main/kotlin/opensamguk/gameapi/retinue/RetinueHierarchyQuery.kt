package opensamguk.gameapi.retinue

import org.springframework.stereotype.Service

@Service
class RetinueHierarchyQuery(private val reader: RetinueHierarchyReader) {
    fun hierarchy(generalId: Int, userId: Long): RetinueHierarchyDto = reader.hierarchy(generalId, userId)
}
