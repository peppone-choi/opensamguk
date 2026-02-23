package com.opensam.service

import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service

/**
 * Exports general data for the battle simulator.
 * Legacy: j_export_simulator_object.php
 */
@Service
class SimulatorExportService(
    private val generalRepository: GeneralRepository,
) {
    data class ExportResult(val result: Boolean, val reason: String? = null, val data: Map<String, Any?>? = null)

    fun exportGeneralForSimulator(requesterId: Long, targetId: Long): ExportResult {
        if (targetId <= 0) {
            return ExportResult(false, "올바르지 않은 장수 코드입니다")
        }

        val requester = generalRepository.findById(requesterId).orElse(null)
            ?: return ExportResult(false, "요청자를 찾을 수 없습니다.")

        val target = generalRepository.findById(targetId).orElse(null)
            ?: return ExportResult(false, "대상 장수를 찾을 수 없습니다.")

        // Only same-nation generals can export (or self)
        if (requester.nationId != target.nationId && requester.id != target.id) {
            return ExportResult(false, "같은 국가의 장수만 추출할 수 있습니다.")
        }

        val data = mapOf<String, Any?>(
            "name" to target.name,
            "nation" to target.nationId,
            "officerLevel" to target.officerLevel.toInt(),
            "leadership" to target.leadership.toInt(),
            "strength" to target.strength.toInt(),
            "intel" to target.intel.toInt(),
            "experience" to target.experience,
            "crew" to target.crew,
            "crewtype" to target.crewType,
            "train" to target.train.toInt(),
            "atmos" to target.atmos.toInt(),
            "weapon" to target.weaponCode,
            "book" to target.bookCode,
            "horse" to target.horseCode,
            "item" to target.itemCode,
            "personal" to target.personalCode,
            "specialWar" to target.special2Code,
            "defenceTrain" to target.defenceTrain.toInt(),
            "rice" to target.rice,
            "injury" to target.injury.toInt(),
            "dex" to target.meta["dex"],
            "rank" to target.meta["rank"],
        )

        return ExportResult(true, data = data)
    }
}
