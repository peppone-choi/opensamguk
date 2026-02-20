package com.opensam.engine

import com.opensam.entity.General
import com.opensam.entity.WorldState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class GeneralMaintenanceService {
    private val log = LoggerFactory.getLogger(javaClass)

    fun processGeneralMaintenance(world: WorldState, generals: List<General>) {
        for (general in generals) {
            // Age increment on January
            if (world.currentMonth.toInt() == 1) {
                general.age = (general.age + 1).toShort()
            }

            // Base monthly experience
            general.experience += 10

            // Dedication decay
            if (general.dedication > 0) {
                general.dedication = (general.dedication * 0.99).toInt()
            }

            // Injury natural recovery
            if (general.injury > 0) {
                general.injury = (general.injury - 1).coerceAtLeast(0).toShort()
            }

            // Retirement check (compare current year against death year)
            if (world.currentYear >= general.deadYear) {
                general.npcState = (-1).toShort()
                log.info("General {} (id={}) retired at year {} (deadYear={})", general.name, general.id, world.currentYear, general.deadYear)
            }
        }
    }
}
