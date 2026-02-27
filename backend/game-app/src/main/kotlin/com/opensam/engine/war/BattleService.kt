package com.opensam.engine.war

import com.opensam.engine.DeterministicRng
import com.opensam.engine.DiplomacyService
import com.opensam.engine.EventService
import com.opensam.engine.modifier.ActionModifier
import com.opensam.engine.modifier.ModifierService
import com.opensam.engine.modifier.StatContext
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Message
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

@Service
class BattleService(
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
    private val messageRepository: MessageRepository,
    private val eventService: EventService,
    private val diplomacyService: DiplomacyService,
    private val modifierService: ModifierService,
) {
    private val logger = LoggerFactory.getLogger(BattleService::class.java)
    private val battleEngine = BattleEngine()

    companion object {
        /** Legacy GameConst values */
        const val BASE_GOLD = 0
        const val BASE_RICE = 2000
        const val JOIN_RUINED_NPC_PROP = 0.1
        const val NPC_JOIN_MAX_DELAY = 12  // turns

        /** NPC states eligible for auto-join (npcState 2-8 except 5) */
        val NPC_AUTO_JOIN_STATES = setOf<Short>(2, 3, 4, 6, 7, 8)
    }

    @Transactional
    fun executeBattle(
        attacker: General,
        targetCity: City,
        world: WorldState,
    ): BattleResult {
        val hiddenSeed = (world.config["hiddenSeed"] as? String) ?: "default"
        val rng = DeterministicRng.create(
            hiddenSeed, "ConquerCity",
            world.currentYear, world.currentMonth,
            attacker.nationId, attacker.id, targetCity.id
        )

        val attackerNation = nationRepository.findById(attacker.nationId).orElse(null)
        val attackerUnit = WarUnitGeneral(attacker, attackerNation?.tech ?: 0f)
        val attackerModifiers = modifierService.getModifiers(attacker, attackerNation)

        // Get defenders in the city
        val defenderEntries = generalRepository.findByCityId(targetCity.id)
            .filter { it.nationId == targetCity.nationId && it.crew > 0 }
            .map { gen ->
                val defNation = nationRepository.findById(gen.nationId).orElse(null)
                val unit = WarUnitGeneral(gen, defNation?.tech ?: 0f)
                val modifiers = modifierService.getModifiers(gen, defNation)
                Triple(unit, gen, modifiers)
            }

        val primaryDefender = defenderEntries.firstOrNull()
        applyWarModifiers(
            unit = attackerUnit,
            modifiers = attackerModifiers,
            opponentCrewType = primaryDefender?.first?.crewType?.toString().orEmpty(),
            opposeModifiers = primaryDefender?.third ?: emptyList(),
            isAttacker = true,
        )

        for ((unit, _, modifiers) in defenderEntries) {
            applyWarModifiers(
                unit = unit,
                modifiers = modifiers,
                opponentCrewType = attackerUnit.crewType.toString(),
                opposeModifiers = attackerModifiers,
                isAttacker = false,
            )
        }

        val defenders = defenderEntries.map { it.first }

        val result = battleEngine.resolveBattle(attackerUnit, defenders, targetCity, rng)

        // Handle city occupation
        if (result.cityOccupied) {
            occupyCity(targetCity, attacker, world, rng)
        }

        // Update dead count
        val totalDead = result.attackerDamageDealt + result.defenderDamageDealt
        targetCity.dead += totalDead / 100

        // Save entities
        cityRepository.save(targetCity)
        generalRepository.save(attacker)
        defenders.forEach { it.general.let { gen -> generalRepository.save(gen) } }

        return result
    }

    private fun occupyCity(city: City, attacker: General, world: WorldState, rng: Random) {
        val oldNationId = city.nationId
        city.nationId = attacker.nationId
        city.trust = 0F
        city.def = (city.def * 0.3).toInt()  // Damage from siege

        // Reset city post-occupation (legacy: supply=1, term=0, conflict={}, officer_set=0)
        city.supplyState = 1
        city.term = 0
        city.conflict = mutableMapOf()
        city.officerSet = 0

        // Reduce agri/comm/secu by 30% (legacy: multiply by 0.7)
        city.agri = (city.agri * 0.7).toInt()
        city.comm = (city.comm * 0.7).toInt()
        city.secu = (city.secu * 0.7).toInt()

        // Dispatch OCCUPY_CITY event
        eventService.dispatchEvents(world, "OCCUPY_CITY")

        // Log conquest
        logConquest(city, attacker, world)

        // Demote city officers of the old nation in this city
        demoteCityOfficers(city.id, oldNationId)

        // Check if old nation lost capital or is destroyed
        val oldNation = nationRepository.findById(oldNationId).orElse(null)
        if (oldNation != null) {
            val remainingCities = cityRepository.findByNationId(oldNationId)
                .filter { it.id != city.id }

            if (remainingCities.isEmpty()) {
                // Nation destroyed
                destroyNation(oldNationId, attacker, world, rng)
            } else if (oldNation.capitalCityId == city.id) {
                // Capital lost - relocate
                relocateCapital(oldNation, remainingCities, world)
            }
        }

        // Update conflict tracking
        val conflictMap = city.conflict.toMutableMap()
        val attackerKey = attacker.nationId.toString()
        val currentScore = (conflictMap[attackerKey] as? Number)?.toInt() ?: 0
        conflictMap[attackerKey] = currentScore + 1
        city.conflict = conflictMap
    }

    /**
     * Handle nation destruction (legacy ConquerCity nation collapse path).
     * - Release all generals (gold/rice/exp/dedication penalties)
     * - NPC generals queue for auto-join to attacker
     * - Distribute conquest rewards
     * - Kill all diplomatic relations
     * - Dispatch DESTROY_NATION event
     */
    private fun destroyNation(
        destroyedNationId: Long,
        attacker: General,
        world: WorldState,
        rng: Random,
    ) {
        val destroyedNation = nationRepository.findById(destroyedNationId).orElse(null) ?: return
        logger.info("Nation {} ({}) destroyed by nation {}", destroyedNation.name, destroyedNationId, attacker.nationId)

        val generals = generalRepository.findByNationId(destroyedNationId)
        var totalGoldLoss = 0
        var totalRiceLoss = 0

        // Apply losses to all defender generals (legacy: 20-50% gold/rice, -10% exp, -50% dedication)
        for (gen in generals) {
            val lossRatio = 0.2 + rng.nextDouble() * 0.3  // 20-50%
            val goldLoss = (gen.gold * lossRatio).toInt()
            val riceLoss = (gen.rice * lossRatio).toInt()
            val expLoss = (gen.experience * 0.1).toInt()
            val dedLoss = (gen.dedication * 0.5).toInt()

            gen.gold -= goldLoss
            gen.rice -= riceLoss
            gen.experience -= expLoss
            gen.dedication -= dedLoss

            totalGoldLoss += goldLoss
            totalRiceLoss += riceLoss

            // Release from nation
            gen.nationId = 0
            gen.officerLevel = 0
            gen.officerCity = 0

            // NPC auto-join to attacker nation (legacy: npcState 2-8 except 5, gated by joinRuinedNPCProp)
            if (gen.npcState in NPC_AUTO_JOIN_STATES && rng.nextDouble() < JOIN_RUINED_NPC_PROP) {
                val delay = rng.nextInt(0, NPC_JOIN_MAX_DELAY + 1)
                gen.meta["autoJoinNationId"] = attacker.nationId
                gen.meta["autoJoinDelay"] = delay
            }

            generalRepository.save(gen)
        }

        // Distribute conquest rewards to attacker nation
        // Legacy: half of nation gold/rice above base + half of general losses
        val nationGoldAboveBase = maxOf(0, destroyedNation.gold - BASE_GOLD)
        val nationRiceAboveBase = maxOf(0, destroyedNation.rice - BASE_RICE)
        val goldReward = nationGoldAboveBase / 2 + totalGoldLoss / 2
        val riceReward = nationRiceAboveBase / 2 + totalRiceLoss / 2

        val attackerNation = nationRepository.findById(attacker.nationId).orElse(null)
        if (attackerNation != null && (goldReward > 0 || riceReward > 0)) {
            attackerNation.gold += goldReward
            attackerNation.rice += riceReward
            nationRepository.save(attackerNation)

            // Log reward to all chiefs (officer_level >= 5)
            logConquestReward(world, attacker.nationId, destroyedNation.name, goldReward, riceReward)
        }

        // Kill all diplomatic relations for the destroyed nation
        diplomacyService.killAllRelationsForNation(world.id.toLong(), destroyedNationId)

        // Dispatch DESTROY_NATION event
        eventService.dispatchEvents(world, "DESTROY_NATION")
    }

    /**
     * Relocate capital when the capital city is captured but nation survives.
     * Legacy: pick closest city with highest pop, halve nation gold/rice, 20% morale loss.
     */
    private fun relocateCapital(
        nation: com.opensam.entity.Nation,
        remainingCities: List<City>,
        world: WorldState,
    ) {
        val newCapital = remainingCities.maxByOrNull { it.pop } ?: return

        logger.info("Nation {} relocates capital to {}", nation.name, newCapital.name)

        nation.capitalCityId = newCapital.id
        nation.gold /= 2
        nation.rice /= 2
        nationRepository.save(nation)

        // 20% morale loss to all generals
        val nationals = generalRepository.findByNationId(nation.id)
        for (gen in nationals) {
            gen.atmos = (gen.atmos * 0.8).toInt().toShort()
            generalRepository.save(gen)
        }

        // Log emergency relocation
        messageRepository.save(
            Message(
                worldId = world.id.toLong(),
                mailboxCode = "national",
                messageType = "capital_relocated",
                destId = nation.id,
                payload = mutableMapOf(
                    "nationName" to nation.name,
                    "newCapital" to newCapital.name,
                    "year" to world.currentYear.toInt(),
                    "month" to world.currentMonth.toInt(),
                ),
            )
        )
    }

    /**
     * Demote city officers (태수/군사/종사) to regular generals when city is captured.
     * Legacy: officer_level = 1, officer_city = 0 for generals who had officer_city == capturedCityId.
     */
    private fun demoteCityOfficers(cityId: Long, oldNationId: Long) {
        val generals = generalRepository.findByCityId(cityId)
            .filter { it.nationId == oldNationId && it.officerCity == cityId.toInt() }
        for (gen in generals) {
            gen.officerLevel = 1
            gen.officerCity = 0
            generalRepository.save(gen)
        }
    }

    private fun logConquest(city: City, attacker: General, world: WorldState) {
        messageRepository.save(
            Message(
                worldId = world.id.toLong(),
                mailboxCode = "world_history",
                messageType = "city_conquered",
                payload = mutableMapOf(
                    "cityName" to city.name,
                    "attackerName" to attacker.name,
                    "attackerNationId" to attacker.nationId,
                    "year" to world.currentYear.toInt(),
                    "month" to world.currentMonth.toInt(),
                ),
            )
        )
    }

    private fun logConquestReward(
        world: WorldState,
        attackerNationId: Long,
        destroyedNationName: String,
        goldReward: Int,
        riceReward: Int,
    ) {
        messageRepository.save(
            Message(
                worldId = world.id.toLong(),
                mailboxCode = "national",
                messageType = "conquest_reward",
                destId = attackerNationId,
                payload = mutableMapOf(
                    "destroyedNation" to destroyedNationName,
                    "goldReward" to goldReward,
                    "riceReward" to riceReward,
                    "year" to world.currentYear.toInt(),
                    "month" to world.currentMonth.toInt(),
                ),
            )
        )
    }

    /**
     * 전투 전 모디파이어 적용: 국가 타입, 성격, 특기, 아이템 보너스를 WarUnit에 반영.
     * ModifierService에서 수집한 StatContext를 WarUnit 필드에 매핑.
     */
    private fun applyWarModifiers(
        unit: WarUnitGeneral,
        modifiers: List<ActionModifier>,
        opponentCrewType: String = "",
        opposeModifiers: List<ActionModifier> = emptyList(),
        isAttacker: Boolean = false,
    ) {
        if (modifiers.isEmpty() && opposeModifiers.isEmpty()) return

        val hpRatio = if (unit.maxHp <= 0) 1.0 else unit.hp.toDouble() / unit.maxHp.toDouble()
        val baseCtx = StatContext(
            crewType = unit.crewType.toString(),
            opponentCrewType = opponentCrewType,
            hpRatio = hpRatio,
            leadership = unit.leadership.toDouble(),
            strength = unit.strength.toDouble(),
            intel = unit.intel.toDouble(),
            criticalChance = unit.criticalChance,
            dodgeChance = unit.dodgeChance,
            magicChance = unit.magicChance,
            isAttacker = isAttacker,
        )
        var modified = modifierService.applyStatModifiers(modifiers, baseCtx)
        if (opposeModifiers.isNotEmpty()) {
            val opposeCtx = modified.copy(
                crewType = opponentCrewType,
                opponentCrewType = unit.crewType.toString(),
            )
            modified = modifierService.applyOpposeStatModifiers(opposeModifiers, opposeCtx)
        }

        // 스탯 반영 (0-100 범위 클램핑)
        unit.leadership = modified.leadership.toInt().coerceIn(0, 100)
        unit.strength = modified.strength.toInt().coerceIn(0, 100)
        unit.intel = modified.intel.toInt().coerceIn(0, 100)
        unit.criticalChance = modified.criticalChance
        unit.dodgeChance = modified.dodgeChance
        unit.magicChance = modified.magicChance
        unit.magicDamageMultiplier = modified.magicSuccessDamage

        // 훈련/사기 보너스
        if (modified.bonusTrain != 0.0) {
            unit.train = (unit.train + modified.bonusTrain.toInt()).coerceIn(0, 100)
        }
        if (modified.bonusAtmos != 0.0) {
            unit.atmos = (unit.atmos + modified.bonusAtmos.toInt()).coerceIn(0, 100)
        }

        if (modified.warPower != 1.0) {
            unit.attackMultiplier *= modified.warPower
        }

        // 전투력 배율 (warPower multiplier)
        val warPowerMult = modifierService.getTotalWarPowerMultiplier(modifiers)
        if (warPowerMult != 1.0) {
            unit.attackMultiplier *= warPowerMult
        }
    }
}
