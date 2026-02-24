package com.opensam.engine

import com.opensam.entity.General
import org.springframework.stereotype.Service

/**
 * Checks and applies stat level-ups/level-downs based on accumulated experience.
 *
 * Legacy parity: General::checkStatChange() in legacy/hwe/sammo/General.php
 *
 * When statExp reaches upgradeLimit (default 30), the stat increases by 1.
 * When statExp goes below 0, the stat decreases by 1.
 * Stats are capped at maxLevel (default 255).
 */
@Service
class StatChangeService {

    companion object {
        /** Experience threshold for stat level change. Legacy: GameConst::$upgradeLimit */
        const val UPGRADE_LIMIT: Int = 30

        /** Maximum stat value. Legacy: GameConst::$maxLevel */
        const val MAX_LEVEL: Int = 255
    }

    data class StatChange(
        val statName: String,
        val displayName: String,
        val oldValue: Int,
        val newValue: Int,
        val delta: Int, // +1 or -1
    )

    data class StatChangeResult(
        val changes: List<StatChange>,
        val logs: List<String>,
    ) {
        val hasChanges: Boolean get() = changes.isNotEmpty()
    }

    private data class StatEntry(
        val displayName: String,
        val statName: String,
        val expName: String,
    )

    private val statTable = listOf(
        StatEntry("통솔", "leadership", "leadershipExp"),
        StatEntry("무력", "strength", "strengthExp"),
        StatEntry("지력", "intel", "intelExp"),
    )

    /**
     * Check all three stats for level changes and apply them to the general entity.
     *
     * Legacy: General::checkStatChange()
     * Called after command execution when statExp values have been modified.
     */
    fun checkStatChange(general: General, upgradeLimit: Int = UPGRADE_LIMIT): StatChangeResult {
        val changes = mutableListOf<StatChange>()
        val logs = mutableListOf<String>()

        for (entry in statTable) {
            val exp = getExp(general, entry.expName)
            val stat = getStat(general, entry.statName)

            if (exp < 0) {
                // Stat decreases
                val newStat = maxOf(0, stat - 1)
                if (newStat != stat) {
                    changes.add(StatChange(entry.statName, entry.displayName, stat, newStat, -1))
                    logs.add("<R>${entry.displayName}</>이 <C>1</> 떨어졌습니다!")
                }
                setStat(general, entry.statName, newStat)
                setExp(general, entry.expName, (exp + upgradeLimit).toShort())
            } else if (exp >= upgradeLimit) {
                // Stat increases (capped at maxLevel)
                if (stat < MAX_LEVEL) {
                    val newStat = stat + 1
                    changes.add(StatChange(entry.statName, entry.displayName, stat, newStat, 1))
                    logs.add("<S>${entry.displayName}</>이 <C>1</> 올랐습니다!")
                    setStat(general, entry.statName, newStat)
                }
                // Exp is always consumed even if stat is at max
                setExp(general, entry.expName, (exp - upgradeLimit).toShort())
            }
        }

        return StatChangeResult(changes, logs)
    }

    private fun getExp(general: General, expName: String): Int = when (expName) {
        "leadershipExp" -> general.leadershipExp.toInt()
        "strengthExp" -> general.strengthExp.toInt()
        "intelExp" -> general.intelExp.toInt()
        else -> 0
    }

    private fun setExp(general: General, expName: String, value: Short) {
        when (expName) {
            "leadershipExp" -> general.leadershipExp = value
            "strengthExp" -> general.strengthExp = value
            "intelExp" -> general.intelExp = value
        }
    }

    private fun getStat(general: General, statName: String): Int = when (statName) {
        "leadership" -> general.leadership.toInt()
        "strength" -> general.strength.toInt()
        "intel" -> general.intel.toInt()
        else -> 0
    }

    private fun setStat(general: General, statName: String, value: Int) {
        when (statName) {
            "leadership" -> general.leadership = value.toShort()
            "strength" -> general.strength = value.toShort()
            "intel" -> general.intel = value.toShort()
        }
    }
}
