package com.opensam.engine

import com.opensam.entity.General
import com.opensam.entity.HallOfFame
import com.opensam.entity.WorldState
import com.opensam.repository.HallOfFameRepository
import com.opensam.repository.NationRepository
import com.opensam.service.GameConstService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 장수 유지보수: 매 턴 장수 나이/경험/헌신/부상/은퇴/사망 처리
 * Legacy parity: TurnExecutionHelper.php lines 180-229
 */
@Service
class GeneralMaintenanceService(
    private val gameConstService: GameConstService,
    private val hallOfFameRepository: HallOfFameRepository,
    private val nationRepository: NationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun processGeneralMaintenance(world: WorldState, generals: List<General>) {
        val isUnited = (world.config["isUnited"] as? Number)?.toInt() ?: 0
        val retirementYear = try {
            gameConstService.getInt("retirementYear")
        } catch (_: Exception) {
            80 // 기본값
        }

        for (general in generals) {
            // 이미 사망/은퇴한 장수는 스킵
            if (general.npcState.toInt() == 5 || general.npcState.toInt() == -1) continue

            // === 삭턴 장수 처리 (killturn <= 0) ===
            // legacy TurnExecutionHelper.php lines 184-206
            if (general.killTurn != null && general.killTurn!! <= 0) {
                if (general.npcState.toInt() == 1 && general.deadYear > world.currentYear) {
                    // NPC유저(npcType==1): 유체이탈 → 원래 NPC로 전환
                    val remainingYears = general.deadYear - world.currentYear
                    general.killTurn = (remainingYears * 12).toShort()
                    general.npcState = (general.npcOrg ?: 2).toShort()
                    general.userId = null
                    general.defenceTrain = 80
                    general.ownerName = ""
                    log.info("장수 {} (id={}): NPC유저 유체이탈, NPC로 전환 (npcState={})",
                        general.name, general.id, general.npcState)
                } else {
                    // 그 외: 사망 처리
                    killGeneral(general)
                    log.info("장수 {} (id={}): 삭턴 만료로 사망", general.name, general.id)
                    continue
                }
            }

            // === 은퇴 처리 ===
            // legacy TurnExecutionHelper.php lines 208-216
            if (general.age >= retirementYear && general.npcState.toInt() == 0) {
                if (isUnited == 0) {
                    // 통일 전에만 은퇴 처리
                    if (general.userId != null && general.npcState < 2) {
                        checkHall(general, world)
                    }
                    rebirthGeneral(general, world)
                    log.info("장수 {} (id={}): 은퇴 (나이={}, 은퇴나이={})",
                        general.name, general.id, general.age, retirementYear)
                }
            }

            // === 나이 증가 (1월) ===
            if (world.currentMonth.toInt() == 1) {
                general.age = (general.age + 1).toShort()
            }

            // === 기본 월간 경험치 ===
            general.experience += 10

            // === 헌신도 감쇠 ===
            if (general.dedication > 0) {
                general.dedication = (general.dedication * 0.99).toInt()
            }

            // === 부상 자연회복 ===
            if (general.injury > 0) {
                general.injury = (general.injury - 1).coerceAtLeast(0).toShort()
            }

            // === NPC 장수 수명 체크 (deadYear) ===
            if (general.npcState >= 2 && world.currentYear >= general.deadYear) {
                killGeneral(general)
                log.info("장수 {} (id={}): 수명 만료로 사망 (deadYear={})",
                    general.name, general.id, general.deadYear)
            }
        }
    }

    /**
     * 장수 사망 처리: 국가에서 해제하고 npcState=5로 설정
     * legacy: General::kill() 패러티
     */
    private fun killGeneral(general: General) {
        general.npcState = 5
        general.nationId = 0
        general.officerLevel = 0
        general.officerCity = 0
        general.killTurn = null
        general.blockState = 0
    }

    /**
     * 장수 환생 처리: 은퇴 후 새 캐릭터로 재시작할 수 있도록 초기화
     * legacy: General::rebirth() 패러티
     */
    private fun rebirthGeneral(general: General, world: WorldState) {
        general.nationId = 0
        general.officerLevel = 0
        general.officerCity = 0
        general.npcState = (-1).toShort() // 은퇴 상태
        general.blockState = 0
        general.killTurn = null
        general.meta["rebirth_available"] = true
        general.meta["retired_year"] = world.currentYear.toInt()
        general.meta["retired_month"] = world.currentMonth.toInt()
    }

    private fun checkHall(general: General, world: WorldState) {
        val nation = nationRepository.findById(general.nationId).orElse(null)
        val serverId = readString(world.config, "serverId").ifBlank { world.name }
        val scenario = readNumber(world.meta, "scenarioId")
        val season = readNumber(world.meta, "season").takeIf { it > 0 } ?: 1
        val rank = asMap(general.meta["rank"])
        val warnum = readNumber(rank, "warnum")
        val killnum = readNumber(rank, "killnum")
        val firenum = readNumber(rank, "firenum")
        val killcrew = readNumber(rank, "killcrew")
        val deathcrew = readNumber(rank, "deathcrew")

        val hallValues = linkedMapOf(
            "experience" to general.experience.toDouble(),
            "dedication" to general.dedication.toDouble(),
            "warnum" to warnum.toDouble(),
            "killnum" to killnum.toDouble(),
            "firenum" to firenum.toDouble(),
            "winrate" to rate(killnum, warnum),
            "killrate" to rate(killcrew, deathcrew),
        )

        for ((type, value) in hallValues) {
            if ((type == "winrate" || type == "killrate") && warnum < 10) {
                continue
            }
            if (value <= 0.0) {
                continue
            }

            val aux = mutableMapOf<String, Any>(
                "name" to general.name,
                "nationName" to (nation?.name ?: "재야"),
                "bgColor" to (nation?.color ?: "#000000"),
                "fgColor" to (nation?.color ?: "#000000"),
                "picture" to general.picture,
                "imgsvr" to general.imageServer,
            )

            val existing = hallOfFameRepository.findByServerIdAndTypeAndGeneralNo(serverId, type, general.id)
            if (existing == null) {
                hallOfFameRepository.save(
                    HallOfFame(
                        serverId = serverId,
                        season = season,
                        scenario = scenario,
                        generalNo = general.id,
                        type = type,
                        value = value,
                        owner = general.userId?.toString(),
                        aux = aux,
                    )
                )
            } else if (value > existing.value) {
                existing.value = value
                existing.owner = general.userId?.toString()
                existing.aux = aux
                hallOfFameRepository.save(existing)
            }
        }
    }

    private fun rate(numerator: Int, denominator: Int): Double {
        if (denominator <= 0) {
            return 0.0
        }
        return numerator.toDouble() / denominator.toDouble()
    }

    private fun readNumber(map: Map<String, Any>, key: String): Int {
        return (map[key] as? Number)?.toInt() ?: 0
    }

    private fun readString(map: Map<String, Any>, key: String): String {
        return map[key] as? String ?: ""
    }

    private fun asMap(value: Any?): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any> ?: emptyMap()
    }
}
