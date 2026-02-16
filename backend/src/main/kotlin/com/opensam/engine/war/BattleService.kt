package com.opensam.engine.war

import com.opensam.engine.DeterministicRng
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BattleService(
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
) {
    private val logger = LoggerFactory.getLogger(BattleService::class.java)
    private val battleEngine = BattleEngine()

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

        // Get defenders in the city
        val defenders = generalRepository.findByCityId(targetCity.id)
            .filter { it.nationId == targetCity.nationId && it.crew > 0 }
            .map { gen ->
                val defNation = nationRepository.findById(gen.nationId).orElse(null)
                WarUnitGeneral(gen, defNation?.tech ?: 0f)
            }

        val result = battleEngine.resolveBattle(attackerUnit, defenders, targetCity, rng)

        // Handle city occupation
        if (result.cityOccupied) {
            occupyCity(targetCity, attacker, world)
        }

        // Update dead count
        val totalDead = result.attackerDamageDealt + result.defenderDamageDealt
        targetCity.dead = (targetCity.dead + totalDead / 100).coerceAtMost(Short.MAX_VALUE.toInt()).toShort()

        // Save entities
        cityRepository.save(targetCity)
        generalRepository.save(attacker)
        defenders.forEach { it.general.let { gen -> generalRepository.save(gen) } }

        return result
    }

    private fun occupyCity(city: City, attacker: General, world: WorldState) {
        val oldNationId = city.nationId
        city.nationId = attacker.nationId
        city.trust = 0
        city.def = (city.def * 0.3).toInt()  // Damage from siege

        // Check if old nation lost capital
        val oldNation = nationRepository.findById(oldNationId).orElse(null)
        if (oldNation != null && oldNation.capitalCityId == city.id) {
            // Relocate capital
            val remainingCities = cityRepository.findByNationId(oldNationId)
                .filter { it.id != city.id }
            if (remainingCities.isNotEmpty()) {
                oldNation.capitalCityId = remainingCities.maxByOrNull { it.pop }?.id
                nationRepository.save(oldNation)
            } else {
                // Nation destroyed - no cities left
                logger.info("Nation ${oldNation.name} destroyed - no cities remaining")
                // Reset all generals of destroyed nation
                generalRepository.findByNationId(oldNationId).forEach { gen ->
                    gen.nationId = 0
                    gen.officerLevel = 0
                    generalRepository.save(gen)
                }
            }
        }

        // Update conflict tracking
        val conflictMap = city.conflict.toMutableMap()
        val attackerKey = attacker.nationId.toString()
        val currentScore = (conflictMap[attackerKey] as? Number)?.toInt() ?: 0
        conflictMap[attackerKey] = currentScore + 1
        city.conflict = conflictMap
    }
}
