package com.opensam.service

import com.opensam.command.CommandEnv
import com.opensam.command.CommandExecutor
import com.opensam.command.CommandRegistry
import com.opensam.command.CommandResult
import com.opensam.command.constraint.ConstraintResult
import com.opensam.dto.CommandTableEntry
import com.opensam.dto.TurnEntry
import com.opensam.engine.RealtimeService
import com.opensam.entity.GeneralTurn
import com.opensam.entity.NationTurn
import com.opensam.repository.*
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CommandService(
    private val generalTurnRepository: GeneralTurnRepository,
    private val nationTurnRepository: NationTurnRepository,
    private val generalRepository: GeneralRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val worldStateRepository: WorldStateRepository,
    private val appUserRepository: AppUserRepository,
    private val commandExecutor: CommandExecutor,
    private val commandRegistry: CommandRegistry,
    private val realtimeService: RealtimeService,
) {
    fun verifyOwnership(generalId: Long, loginId: String): Boolean {
        val user = appUserRepository.findByLoginId(loginId) ?: return false
        val general = generalRepository.findById(generalId).orElse(null) ?: return false
        return general.userId == user.id
    }

    fun listGeneralTurns(generalId: Long): List<GeneralTurn> {
        return generalTurnRepository.findByGeneralIdOrderByTurnIdx(generalId)
    }

    @Transactional
    fun reserveGeneralTurns(generalId: Long, turns: List<TurnEntry>): List<GeneralTurn> {
        val general = generalRepository.findById(generalId).orElseThrow {
            IllegalArgumentException("General not found: $generalId")
        }
        val world = worldStateRepository.findById(general.worldId.toShort()).orElseThrow {
            IllegalArgumentException("World not found: ${general.worldId}")
        }
        if (world.realtimeMode) {
            throw IllegalStateException("실시간 모드에서는 예턴 예약을 사용할 수 없습니다.")
        }

        generalTurnRepository.deleteByGeneralId(generalId)
        return turns.map { entry ->
            generalTurnRepository.save(
                GeneralTurn(
                    worldId = general.worldId,
                    generalId = generalId,
                    turnIdx = entry.turnIdx,
                    actionCode = entry.actionCode,
                    arg = entry.arg?.toMutableMap() ?: mutableMapOf(),
                )
            )
        }
    }

    fun executeCommand(generalId: Long, actionCode: String, arg: Map<String, Any>?): CommandResult? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null

        if (world.realtimeMode) {
            return realtimeService.submitCommand(generalId, actionCode, arg)
        }

        val city = cityRepository.findById(general.cityId).orElse(null)
        val nation = if (general.nationId != 0L) {
            nationRepository.findById(general.nationId).orElse(null)
        } else null

        val env = CommandEnv(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            startYear = world.currentYear.toInt(),
            worldId = world.id.toLong(),
            realtimeMode = world.realtimeMode,
        )

        val result = runBlocking {
            commandExecutor.executeGeneralCommand(
                actionCode = actionCode,
                general = general,
                env = env,
                arg = arg,
                city = city,
                nation = nation,
            )
        }
        generalRepository.save(general)
        return result
    }

    fun executeNationCommand(generalId: Long, actionCode: String, arg: Map<String, Any>?): CommandResult? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        if (general.officerLevel < 5) {
            return CommandResult(success = false, logs = listOf("국가 명령 권한이 없습니다."))
        }
        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null

        if (world.realtimeMode) {
            return realtimeService.submitNationCommand(generalId, actionCode, arg)
        }

        val city = cityRepository.findById(general.cityId).orElse(null)
        val nation = if (general.nationId != 0L) {
            nationRepository.findById(general.nationId).orElse(null)
        } else null

        val env = CommandEnv(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            startYear = world.currentYear.toInt(),
            worldId = world.id.toLong(),
            realtimeMode = world.realtimeMode,
        )

        val result = runBlocking {
            commandExecutor.executeNationCommand(
                actionCode = actionCode,
                general = general,
                env = env,
                arg = arg,
                city = city,
                nation = nation,
            )
        }
        generalRepository.save(general)
        return result
    }

    fun getCommandTable(generalId: Long): Map<String, List<CommandTableEntry>>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null
        val city = cityRepository.findById(general.cityId).orElse(null)
        val nation = if (general.nationId != 0L) nationRepository.findById(general.nationId).orElse(null) else null
        val env = CommandEnv(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            startYear = world.currentYear.toInt(),
            worldId = world.id.toLong(),
            realtimeMode = world.realtimeMode,
        )

        val categories = linkedMapOf<String, MutableList<CommandTableEntry>>()
        val actionCodes = commandRegistry.getGeneralCommandNames().toList().sortedWith(
            compareBy<String>({ generalCategoryOrder(generalCategory(it)) }, { it })
        )

        for (actionCode in actionCodes) {
            val command = commandRegistry.createGeneralCommand(actionCode, general, env, null)
            command.city = city
            command.nation = nation

            val check = command.checkFullCondition()
            val enabled = check is ConstraintResult.Pass
            val reason = if (check is ConstraintResult.Fail) check.reason else null
            val category = generalCategory(actionCode)

            categories.getOrPut(category) { mutableListOf() }.add(
                CommandTableEntry(
                    actionCode = actionCode,
                    name = command.actionName,
                    category = category,
                    enabled = enabled,
                    reason = reason,
                    durationSeconds = command.getDuration(),
                    commandPointCost = command.getCommandPointCost(),
                )
            )
        }

        return categories
    }

    fun getNationCommandTable(generalId: Long): Map<String, List<CommandTableEntry>>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null
        if (general.officerLevel < 5) {
            return linkedMapOf()
        }

        val city = cityRepository.findById(general.cityId).orElse(null)
        val nation = if (general.nationId != 0L) nationRepository.findById(general.nationId).orElse(null) else null
        val env = CommandEnv(
            year = world.currentYear.toInt(),
            month = world.currentMonth.toInt(),
            startYear = world.currentYear.toInt(),
            worldId = world.id.toLong(),
            realtimeMode = world.realtimeMode,
        )

        val categories = linkedMapOf<String, MutableList<CommandTableEntry>>()
        val actionCodes = commandRegistry.getNationCommandNames().toList().sortedWith(
            compareBy<String>({ nationCategoryOrder(nationCategory(it)) }, { it })
        )

        for (actionCode in actionCodes) {
            val command = commandRegistry.createNationCommand(actionCode, general, env, null) ?: continue
            command.city = city
            command.nation = nation

            val check = command.checkFullCondition()
            val enabled = check is ConstraintResult.Pass
            val reason = if (check is ConstraintResult.Fail) check.reason else null
            val category = nationCategory(actionCode)

            categories.getOrPut(category) { mutableListOf() }.add(
                CommandTableEntry(
                    actionCode = actionCode,
                    name = command.actionName,
                    category = category,
                    enabled = enabled,
                    reason = reason,
                    durationSeconds = command.getDuration(),
                    commandPointCost = command.getCommandPointCost(),
                )
            )
        }

        return categories
    }

    @Transactional
    fun repeatTurns(generalId: Long, count: Int): List<GeneralTurn>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null
        if (world.realtimeMode) {
            throw IllegalStateException("실시간 모드에서는 예턴 반복을 사용할 수 없습니다.")
        }

        val existing = generalTurnRepository.findByGeneralIdOrderByTurnIdx(generalId)
        if (existing.isEmpty()) return null
        val lastTurn = existing.last()
        val maxIdx = existing.maxOf { it.turnIdx }
        val newTurns = (1..count).map { i ->
            generalTurnRepository.save(
                GeneralTurn(
                    worldId = general.worldId,
                    generalId = generalId,
                    turnIdx = (maxIdx + i).toShort(),
                    actionCode = lastTurn.actionCode,
                    arg = lastTurn.arg.toMutableMap(),
                )
            )
        }
        return existing + newTurns
    }

    @Transactional
    fun pushTurns(generalId: Long, amount: Int): List<GeneralTurn>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null
        if (world.realtimeMode) {
            throw IllegalStateException("실시간 모드에서는 예턴 밀기/당기기를 사용할 수 없습니다.")
        }

        val existing = generalTurnRepository.findByGeneralIdOrderByTurnIdx(generalId)
        if (existing.isEmpty()) return null
        generalTurnRepository.deleteByGeneralId(generalId)
        return existing.mapNotNull { turn ->
            val newIdx = turn.turnIdx + amount
            if (newIdx >= 0) {
                generalTurnRepository.save(
                    GeneralTurn(
                        worldId = general.worldId,
                        generalId = generalId,
                        turnIdx = newIdx.toShort(),
                        actionCode = turn.actionCode,
                        arg = turn.arg.toMutableMap(),
                    )
                )
            } else null
        }
    }

    fun listNationTurns(nationId: Long, officerLevel: Short): List<NationTurn> {
        return nationTurnRepository.findByNationIdAndOfficerLevelOrderByTurnIdx(nationId, officerLevel)
    }

    @Transactional
    fun reserveNationTurns(generalId: Long, nationId: Long, turns: List<TurnEntry>): List<NationTurn>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        if (general.nationId != nationId) {
            return null
        }
        if (general.officerLevel < 5) {
            return null
        }

        val world = worldStateRepository.findById(general.worldId.toShort()).orElse(null) ?: return null
        if (world.realtimeMode) {
            throw IllegalStateException("실시간 모드에서는 국가 예턴 예약을 사용할 수 없습니다.")
        }

        nationTurnRepository.deleteByNationIdAndOfficerLevel(nationId, general.officerLevel)
        return turns.map { entry ->
            nationTurnRepository.save(
                NationTurn(
                    worldId = general.worldId,
                    nationId = nationId,
                    officerLevel = general.officerLevel,
                    turnIdx = entry.turnIdx,
                    actionCode = entry.actionCode,
                    arg = entry.arg?.toMutableMap() ?: mutableMapOf(),
                )
            )
        }
    }

    private fun generalCategory(actionCode: String): String = when (actionCode) {
        "휴식", "농지개간", "상업투자", "치안강화", "수비강화", "성벽보수", "정착장려", "주민선정", "기술연구" -> "내정"
        "모병", "징병", "훈련", "사기진작", "소집해제", "숙련전환" -> "군사(모병/훈련)"
        "물자조달", "군량매매", "헌납" -> "경제"
        "출병", "이동", "집합", "귀환", "접경귀환", "강행", "거병", "전투태세" -> "군사(이동/전투)"
        "화계", "첩보", "선동", "탈취", "파괴" -> "특수전"
        "등용", "등용수락", "임관", "랜덤임관", "장수대상임관", "하야", "은퇴" -> "인사"
        "건국", "무작위건국", "모반시도", "선양", "해산" -> "국가"
        "단련", "요양", "방랑", "견문", "인재탐색", "증여", "장비매매", "내정특기초기화", "전투특기초기화" -> "개인"
        else -> "기타"
    }

    private fun generalCategoryOrder(category: String): Int = when (category) {
        "내정" -> 1
        "군사(모병/훈련)" -> 2
        "경제" -> 3
        "군사(이동/전투)" -> 4
        "특수전" -> 5
        "인사" -> 6
        "국가" -> 7
        "개인" -> 8
        else -> 99
    }

    private fun nationCategory(actionCode: String): String = when (actionCode) {
        "Nation휴식" -> "기본"
        "포상", "몰수", "감축", "증축", "발령", "천도", "백성동원", "물자원조", "국기변경", "국호변경" -> "자원/내정"
        "선전포고", "종전제의", "종전수락", "불가침제의", "불가침수락", "불가침파기제의", "불가침파기수락" -> "외교"
        "급습", "수몰", "허보", "초토화", "필사즉생", "이호경식", "피장파장", "의병모집" -> "전략"
        "극병연구", "대검병연구", "무희연구", "산저병연구", "상병연구", "원융노병연구", "음귀병연구", "화륜차연구", "화시병연구" -> "연구"
        else -> "특수"
    }

    private fun nationCategoryOrder(category: String): Int = when (category) {
        "기본" -> 1
        "자원/내정" -> 2
        "외교" -> 3
        "전략" -> 4
        "연구" -> 5
        else -> 99
    }
}
