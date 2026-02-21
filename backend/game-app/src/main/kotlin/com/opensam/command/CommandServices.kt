package com.opensam.command

import com.opensam.engine.DiplomacyService
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository

/**
 * 커맨드에서 접근 가능한 서비스/리포지토리 홀더.
 * CommandExecutor가 커맨드 실행 전 주입한다.
 */
data class CommandServices(
    val generalRepository: GeneralRepository,
    val cityRepository: CityRepository,
    val nationRepository: NationRepository,
    val diplomacyService: DiplomacyService,
)
