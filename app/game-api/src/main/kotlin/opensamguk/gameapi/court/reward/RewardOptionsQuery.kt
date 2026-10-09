package opensamguk.gameapi.court.reward

import opensamguk.gameapi.read.CampForbidden
import org.springframework.stereotype.Service

class RewardOptionsForbidden : RuntimeException()

@Service
class RewardOptionsQuery(private val reader: RewardOptionsReader) {
    private val projection = RewardOptionsProjection()

    fun options(generalId: Int, userId: Long, retainerId: Int? = null, money: String? = null): RewardOptionsDto {
        val selected = try { reader.read(generalId, userId) }
            catch (_: CampForbidden) { throw RewardOptionsForbidden() }
        return projection.project(selected, retainerId, money)
    }
}
