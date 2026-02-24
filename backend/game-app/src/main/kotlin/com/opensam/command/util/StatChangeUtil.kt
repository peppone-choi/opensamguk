package com.opensam.command.util

import com.opensam.entity.General

/**
 * Legacy parity: General::checkStatChange() from General.php
 *
 * Checks if any stat experience has crossed the upgrade/downgrade threshold.
 * When leadershipExp/strengthExp/intelExp >= upgradeLimit, the stat increases by 1.
 * When < 0, the stat decreases by 1.
 *
 * Returns a list of log messages describing stat changes.
 */
object StatChangeUtil {

    /** Legacy GameConst::$upgradeLimit — default 100 */
    private const val DEFAULT_UPGRADE_LIMIT: Int = 100

    /** Legacy GameConst::$maxLevel — default 100 */
    private const val DEFAULT_MAX_LEVEL: Int = 100

    data class StatChangeResult(
        val changed: Boolean,
        val logs: List<String>,
    )

    /**
     * Check and apply stat changes to the general based on accumulated experience.
     *
     * @param general The general entity to check/modify
     * @param upgradeLimit The experience threshold for stat increase (default 100)
     * @param maxLevel Maximum stat value (default 100)
     * @return StatChangeResult with whether any change occurred and log messages
     */
    fun checkStatChange(
        general: General,
        upgradeLimit: Int = DEFAULT_UPGRADE_LIMIT,
        maxLevel: Int = DEFAULT_MAX_LEVEL,
    ): StatChangeResult {
        val logs = mutableListOf<String>()
        var changed = false

        data class StatEntry(val displayName: String, val getValue: () -> Short, val setValue: (Short) -> Unit,
                             val getExp: () -> Short, val setExp: (Short) -> Unit)

        val stats = listOf(
            StatEntry("통솔",
                { general.leadership }, { general.leadership = it },
                { general.leadershipExp }, { general.leadershipExp = it }),
            StatEntry("무력",
                { general.strength }, { general.strength = it },
                { general.strengthExp }, { general.strengthExp = it }),
            StatEntry("지력",
                { general.intel }, { general.intel = it },
                { general.intelExp }, { general.intelExp = it }),
        )

        for (stat in stats) {
            val exp = stat.getExp().toInt()
            if (exp < 0) {
                // Stat decrease
                logs.add("<R>${stat.displayName}</>이 <C>1</> 떨어졌습니다!")
                stat.setExp((exp + upgradeLimit).toShort())
                stat.setValue((stat.getValue() - 1).toShort())
                changed = true
            } else if (exp >= upgradeLimit) {
                // Stat increase
                if (stat.getValue() < maxLevel) {
                    logs.add("<S>${stat.displayName}</>이 <C>1</> 올랐습니다!")
                    stat.setValue((stat.getValue() + 1).toShort())
                }
                stat.setExp((exp - upgradeLimit).toShort())
                changed = true
            }
        }

        return StatChangeResult(changed, logs)
    }
}
