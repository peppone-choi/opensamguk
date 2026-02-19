package com.opensam.engine.turn.cqrs.memory

class DirtyTracker {
    enum class EntityType {
        GENERAL,
        CITY,
        NATION,
        TROOP,
        DIPLOMACY,
    }

    val dirtyGeneralIds: MutableSet<Long> = mutableSetOf()
    val dirtyCityIds: MutableSet<Long> = mutableSetOf()
    val dirtyNationIds: MutableSet<Long> = mutableSetOf()
    val dirtyTroopIds: MutableSet<Long> = mutableSetOf()
    val dirtyDiplomacyIds: MutableSet<Long> = mutableSetOf()

    val createdGeneralIds: MutableSet<Long> = mutableSetOf()
    val createdCityIds: MutableSet<Long> = mutableSetOf()
    val createdNationIds: MutableSet<Long> = mutableSetOf()
    val createdTroopIds: MutableSet<Long> = mutableSetOf()
    val createdDiplomacyIds: MutableSet<Long> = mutableSetOf()

    val deletedGeneralIds: MutableSet<Long> = mutableSetOf()
    val deletedCityIds: MutableSet<Long> = mutableSetOf()
    val deletedNationIds: MutableSet<Long> = mutableSetOf()
    val deletedTroopIds: MutableSet<Long> = mutableSetOf()
    val deletedDiplomacyIds: MutableSet<Long> = mutableSetOf()

    fun markDirty(type: EntityType, id: Long) {
        when (type) {
            EntityType.GENERAL -> dirtyGeneralIds += id
            EntityType.CITY -> dirtyCityIds += id
            EntityType.NATION -> dirtyNationIds += id
            EntityType.TROOP -> dirtyTroopIds += id
            EntityType.DIPLOMACY -> dirtyDiplomacyIds += id
        }
    }

    fun markCreated(type: EntityType, id: Long) {
        when (type) {
            EntityType.GENERAL -> createdGeneralIds += id
            EntityType.CITY -> createdCityIds += id
            EntityType.NATION -> createdNationIds += id
            EntityType.TROOP -> createdTroopIds += id
            EntityType.DIPLOMACY -> createdDiplomacyIds += id
        }
    }

    fun markDeleted(type: EntityType, id: Long) {
        when (type) {
            EntityType.GENERAL -> deletedGeneralIds += id
            EntityType.CITY -> deletedCityIds += id
            EntityType.NATION -> deletedNationIds += id
            EntityType.TROOP -> deletedTroopIds += id
            EntityType.DIPLOMACY -> deletedDiplomacyIds += id
        }
    }

    fun consumeAll(): DirtyChanges {
        val changes = DirtyChanges(
            dirtyGeneralIds = dirtyGeneralIds.toSet(),
            dirtyCityIds = dirtyCityIds.toSet(),
            dirtyNationIds = dirtyNationIds.toSet(),
            dirtyTroopIds = dirtyTroopIds.toSet(),
            dirtyDiplomacyIds = dirtyDiplomacyIds.toSet(),
            createdGeneralIds = createdGeneralIds.toSet(),
            createdCityIds = createdCityIds.toSet(),
            createdNationIds = createdNationIds.toSet(),
            createdTroopIds = createdTroopIds.toSet(),
            createdDiplomacyIds = createdDiplomacyIds.toSet(),
            deletedGeneralIds = deletedGeneralIds.toSet(),
            deletedCityIds = deletedCityIds.toSet(),
            deletedNationIds = deletedNationIds.toSet(),
            deletedTroopIds = deletedTroopIds.toSet(),
            deletedDiplomacyIds = deletedDiplomacyIds.toSet(),
        )
        clearAll()
        return changes
    }

    private fun clearAll() {
        dirtyGeneralIds.clear()
        dirtyCityIds.clear()
        dirtyNationIds.clear()
        dirtyTroopIds.clear()
        dirtyDiplomacyIds.clear()

        createdGeneralIds.clear()
        createdCityIds.clear()
        createdNationIds.clear()
        createdTroopIds.clear()
        createdDiplomacyIds.clear()

        deletedGeneralIds.clear()
        deletedCityIds.clear()
        deletedNationIds.clear()
        deletedTroopIds.clear()
        deletedDiplomacyIds.clear()
    }
}

data class DirtyChanges(
    val dirtyGeneralIds: Set<Long>,
    val dirtyCityIds: Set<Long>,
    val dirtyNationIds: Set<Long>,
    val dirtyTroopIds: Set<Long>,
    val dirtyDiplomacyIds: Set<Long>,
    val createdGeneralIds: Set<Long>,
    val createdCityIds: Set<Long>,
    val createdNationIds: Set<Long>,
    val createdTroopIds: Set<Long>,
    val createdDiplomacyIds: Set<Long>,
    val deletedGeneralIds: Set<Long>,
    val deletedCityIds: Set<Long>,
    val deletedNationIds: Set<Long>,
    val deletedTroopIds: Set<Long>,
    val deletedDiplomacyIds: Set<Long>,
)
