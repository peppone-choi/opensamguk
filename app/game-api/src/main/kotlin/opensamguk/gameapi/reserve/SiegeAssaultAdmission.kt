package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.SiegeReader
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.InputRejection
import opensamguk.logic.input.SiegeAssaultInput
import opensamguk.logic.war.SiegeRules
import org.springframework.stereotype.Service

/** Reservation checks the exact county the player selected; execution checks it again. */
@Service
class SiegeAssaultAdmission(private val sieges: SiegeReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun canonicalArguments(generalId: Int, ownerUserId: Int?, turnIdx: Int, raw: String?): String {
        if (ownerUserId == null || ownerUserId <= 0) deny("UNAUTHORIZED", "제출자 인증이 필요합니다.")
        if (turnIdx !in 0..11) deny("INVALID_TURN_SLOT", "예약 순은 0부터 11까지입니다.")
        val targetCountyId = SiegeAssaultInput.parse(raw)
            ?: deny("INVALID_INPUT", "강공할 縣을 골라 주세요.")
        val response = try { sieges.sieges(generalId, ownerUserId.toLong()) }
            catch (_: CampForbidden) { deny("FORBIDDEN", "자신의 장수만 예약할 수 있습니다.") }
            catch (_: RuntimeException) { deny("STATE_UNAVAILABLE", "공성 상태를 확인할 수 없습니다.") }
        if (response.status != "READY") deny("STATE_UNAVAILABLE", "공성 상태를 확인할 수 없습니다.")
        val mine = response.sieges.filter { it.status == "ACTIVE" && it.besieger.generalId == generalId }
        if (mine.isEmpty()) deny(SiegeRules.AssaultBlock.NOT_BESIEGING.name,
            SiegeRules.AssaultBlock.NOT_BESIEGING.message)
        if (mine.size != 1) deny("STATE_UNAVAILABLE", "포위 상태를 확인할 수 없습니다.")
        val selected = mine.singleOrNull { it.countyId == targetCountyId }
            ?: deny(SiegeRules.AssaultBlock.TARGET_CHANGED.name, SiegeRules.AssaultBlock.TARGET_CHANGED.message)
        if (!selected.canAssault) deny(selected.assaultCode ?: "STATE_UNAVAILABLE",
            selected.assaultReason ?: "강공 조건을 확인할 수 없습니다.")
        if (catalog[SiegeAssaultInput.INPUT_ID]?.deliveryState?.hasHandler != true)
            deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return SiegeAssaultInput.canonicalJson(targetCountyId)
    }

    private fun deny(code: String, reason: String): Nothing = throw AdmissionDenied(code, reason)
}
