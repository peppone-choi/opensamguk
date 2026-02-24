package com.opensam.command

import com.opensam.engine.DiplomacyService
import com.opensam.engine.modifier.ModifierService
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.service.GeneralPoolService
import com.opensam.service.MessageService
import com.opensam.service.NationService

/**
 * 커맨드에서 접근 가능한 서비스/리포지토리 홀더.
 * CommandExecutor가 커맨드 실행 전 주입한다.
 */
data class CommandServices(
    val generalRepository: GeneralRepository,
    val cityRepository: CityRepository,
    val nationRepository: NationRepository,
    val diplomacyService: DiplomacyService,
    val messageService: MessageService? = null,
    val nationService: NationService? = null,
    val generalPoolService: GeneralPoolService? = null,
    val modifierService: ModifierService? = null,
) {
    /**
     * Resolve city name by ID. Returns null if not found.
     */
    suspend fun getCityName(cityId: Long): String? {
        return try {
            cityRepository.findById(cityId).orElse(null)?.name
        } catch (_: Exception) {
            null
        }
    }
}
