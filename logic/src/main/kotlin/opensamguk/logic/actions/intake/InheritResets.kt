package opensamguk.logic.actions.intake

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.math.floor

/**
 * Pure-logic ports of the 유산 (inheritance) reset/reserve actions —
 * `sammo/API/InheritAction/{ResetTurnTime,ResetSpecialWar,SetNextSpecialWar}.php`.
 *
 * Each spends inheritance points from the owner's `previous` balance, may bump
 * `rank_data.inherit_point_spent_dynamic`, pushes an `inheritance_log` line, and mutates the
 * acting general's aux/vars. NO Spring/DB/world here — each resolver takes the env inputs the PHP
 * reads (`previousPoint`, the current aux level, isunited, …) and returns an [InheritResetOutcome]
 * the engine handler applies (general aux/var mutate + ChangeRecorder inheritance KV + rank +
 * inheritance_log delta).
 *
 * RNG parity: ResetTurnTime draws exactly ONE `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,
 * 'ResetTurnTime', userID, nextTurnTimeBase|turnTime))).nextFloat1()`. ResetSpecialWar / nextSpecial
 * draw NOTHING. The draw order/count/args are the parity target (P8 golden).
 *
 * QUARANTINE (proven, not fabricated): the PHP cost ladder
 * `GameConst::$inheritResetAttrPointBase = [1000,1000,2000,3000]` is extended on demand by a Fibonacci
 * step (`base[] = base[len-1] + base[len-2]`) when `nextLevel >= count`. The Kotlin `GameConst.
 * inheritResetAttrPointBase` is a fixed `listOf(1000,1000,2000,3000)`; [requiredPointForLevel]
 * reproduces the Fibonacci extension purely (no const mutation), so levels ≥ 4 are exact.
 */
object InheritResets {

    /** PHP `inheritResetAttrPointBase[nextLevel]` with the on-demand Fibonacci extension reproduced. */
    fun requiredPointForLevel(nextLevel: Int): Int {
        val base = GameConst.inheritResetAttrPointBase.toMutableList()
        while (base.size <= nextLevel) {
            base.add(base[base.size - 1] + base[base.size - 2])
        }
        return base[nextLevel]
    }

    /**
     * ResetTurnTime.php. Spends `requiredPointForLevel(currentLevel+1)` from [previousPoint], draws ONE
     * float for the new turn-time offset, bumps `inherit_point_spent_dynamic`, sets aux
     * `inheritResetTurnTime`/`nextTurnTimeBase`, pushes an `inheritance_log` line.
     *
     * @param userId the owner id (string namespace `inheritance_{userId}`)
     * @param currentLevel `aux.inheritResetTurnTime` (PHP `?? -1`)
     * @param previousPoint the spendable `previous` balance
     * @param isUnited the game_env isunited flag (천하통일 ⇒ deny)
     * @param turnTerm game_env turnterm (minutes per turn)
     * @param hiddenSeed UniqueConst hiddenSeed (the per-game RNG seed)
     * @param currentTurnTimeOrBase PHP `aux.nextTurnTimeBase ?? general.turnTime` — the RNG seed tail
     */
    fun resetTurnTime(
        userId: Int,
        currentLevel: Int,
        previousPoint: Double,
        isUnited: Boolean,
        turnTerm: Int,
        hiddenSeed: String,
        currentTurnTimeOrBase: String,
    ): InheritResetOutcome {
        if (isUnited) return InheritResetOutcome.Denied("이미 천하가 통일되었습니다.")
        val nextLevel = currentLevel + 1
        val reqPoint = requiredPointForLevel(nextLevel)
        if (previousPoint < reqPoint) return InheritResetOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")

        // ONE draw — RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'ResetTurnTime',userID,base))).nextFloat1()
        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "ResetTurnTime", userId, currentTurnTimeOrBase)))
        val afterTurn = rng.nextFloat1() * turnTerm * 60

        // PHP log: "{req} 포인트로 턴 시간을 바꾸어 다다음 턴부터 %02d:%02d 적용", intdiv(toInt(afterTurn),60), afterTurn%60
        val afterTurnInt = floor(afterTurn).toInt()        // Util::toInt = truncate-toward-zero (afterTurn ≥ 0)
        val mm = afterTurnInt / 60
        val ss = afterTurnInt % 60
        val log = "$reqPoint 포인트로 턴 시간을 바꾸어 다다음 턴부터 %02d:%02d 적용".format(mm, ss)

        return InheritResetOutcome.Applied(
            spent = reqPoint,
            remainingPrevious = previousPoint - reqPoint,
            auxUpdates = linkedMapOf(
                "inheritResetTurnTime" to nextLevel,
                "nextTurnTimeBase" to afterTurn,
            ),
            varUpdates = linkedMapOf(),
            log = log,
        )
    }

    /**
     * ResetSpecialWar.php. Requires a non-empty current `special2`. Spends
     * `requiredPointForLevel(currentLevel+1)`, archives `special2` into `aux.prev_types_special2`,
     * sets `special2 = 'None'`, bumps `inherit_point_spent_dynamic`, pushes an `inheritance_log` line.
     * Draws NOTHING.
     *
     * @param currentSpecialWar `general.special2` (null/'None' ⇒ already empty deny)
     * @param prevTypesSpecial2 the existing `aux.prev_types_special2` archive list (PHP `?? []`)
     */
    fun resetSpecialWar(
        currentLevel: Int,
        previousPoint: Double,
        isUnited: Boolean,
        currentSpecialWar: String?,
        prevTypesSpecial2: List<String>,
    ): InheritResetOutcome {
        if (currentSpecialWar == null || currentSpecialWar == "None") {
            return InheritResetOutcome.Denied("이미 전투 특기가 공란입니다.")
        }
        val nextLevel = currentLevel + 1
        val reqPoint = requiredPointForLevel(nextLevel)
        if (isUnited) return InheritResetOutcome.Denied("이미 천하가 통일되었습니다.")
        if (previousPoint < reqPoint) return InheritResetOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")

        val nextArchive = prevTypesSpecial2 + currentSpecialWar
        return InheritResetOutcome.Applied(
            spent = reqPoint,
            remainingPrevious = previousPoint - reqPoint,
            auxUpdates = linkedMapOf(
                "prev_types_special2" to nextArchive,
                "inheritResetSpecialWar" to nextLevel,
            ),
            varUpdates = linkedMapOf("special2" to "None"),
            log = "$reqPoint 포인트로 전투 특기 초기화",
        )
    }

    /**
     * SetNextSpecialWar.php ("nextSpecial"). Reserves a specific NEXT 전투 특기. Spends
     * `inheritSpecificSpecialPoint` (4000), sets `aux.inheritSpecificSpecialWar = type`, bumps
     * `inherit_point_spent_dynamic`, pushes an `inheritance_log` line. Draws NOTHING.
     *
     * @param type the requested special-war key (must be in [GameConst.availableSpecialWar])
     * @param currentSpecialWar `general.special2`
     * @param reservedSpecialWar `aux.inheritSpecificSpecialWar` (null = none reserved)
     * @param specialWarName the display name of [type] (handler resolves via the special-war registry)
     */
    fun setNextSpecialWar(
        type: String,
        previousPoint: Double,
        isUnited: Boolean,
        currentSpecialWar: String?,
        reservedSpecialWar: String?,
        specialWarName: String,
    ): InheritResetOutcome {
        if (type !in GameConst.availableSpecialWar) return InheritResetOutcome.Denied("'type' 항목이 올바르지 않습니다.")
        if (currentSpecialWar == type) return InheritResetOutcome.Denied("이미 그 특기를 보유하고 있습니다.")
        if (reservedSpecialWar == type) return InheritResetOutcome.Denied("이미 그 특기를 예약하였습니다.")
        if (reservedSpecialWar != null) return InheritResetOutcome.Denied("이미 예약한 특기가 있습니다.")

        val reqAmount = GameConst.inheritSpecificSpecialPoint
        if (isUnited) return InheritResetOutcome.Denied("이미 천하가 통일되었습니다.")
        if (previousPoint < reqAmount) return InheritResetOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")

        return InheritResetOutcome.Applied(
            spent = reqAmount,
            remainingPrevious = previousPoint - reqAmount,
            auxUpdates = linkedMapOf("inheritSpecificSpecialWar" to type),
            varUpdates = linkedMapOf(),
            log = "$reqAmount 포인트로 다음 전투 특기로 $specialWarName 지정",
        )
    }

    fun resetStat(
        userId: Int,
        leadership: Int,
        strength: Int,
        intel: Int,
        inheritBonusStat: List<Int>?,
        previousPoint: Double,
        isUnited: Boolean,
        season: Int,
        lastStatReset: List<Int>,
        npcType: Int,
        hiddenSeed: String,
    ): ResetStatOutcome {
        if (
            listOf(leadership, strength, intel).any {
                it < GameConst.defaultStatMin || it > GameConst.defaultStatMax
            }
        ) {
            return ResetStatOutcome.Denied("능력치가 잘못 지정되었습니다. 다시 입력해주세요!")
        }
        if (leadership + strength + intel != GameConst.defaultStatTotal) {
            return ResetStatOutcome.Denied("능력치 총합이 ${GameConst.defaultStatTotal}이 아닙니다. 다시 입력해주세요!")
        }

        val explicitBonus = when (val b = normalizeBonus(inheritBonusStat)) {
            is BonusInput.Denied -> return ResetStatOutcome.Denied(b.reason)
            is BonusInput.Empty -> null
            is BonusInput.Explicit -> b.values
        }

        if (npcType != 0) return ResetStatOutcome.Denied("NPC는 능력치 초기화를 할 수 없습니다.")
        if (isUnited) return ResetStatOutcome.Denied("이미 천하가 통일되었습니다.")
        if (season in lastStatReset) return ResetStatOutcome.Denied("이번 시즌에 이미 능력치를 초기화하셨습니다.")

        val reqAmount = if (explicitBonus != null) GameConst.inheritBornStatPoint else 0
        if (previousPoint < reqAmount) {
            return ResetStatOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")
        }

        val bonus = explicitBonus ?: drawResetStatBonus(userId, leadership, strength, intel, hiddenSeed)
        val bonusLog = if (explicitBonus != null) {
            "${reqAmount}로 통솔 ${bonus[0]}, 무력 ${bonus[1]}, 지력 ${bonus[2]} 보너스 능력치 적용"
        } else {
            "통솔 ${bonus[0]}, 무력 ${bonus[1]}, 지력 ${bonus[2]} 보너스 능력치 적용"
        }

        return ResetStatOutcome.Applied(
            spent = reqAmount,
            remainingPrevious = previousPoint - reqAmount,
            nextLeadership = leadership + bonus[0],
            nextStrength = strength + bonus[1],
            nextIntel = intel + bonus[2],
            nextLastStatReset = lastStatReset + season,
            logs = listOf(
                "통솔 $leadership, 무력 $strength, 지력 $intel 스탯 재설정",
                bonusLog,
            ),
        )
    }

    private sealed interface BonusInput {
        data object Empty : BonusInput
        data class Explicit(val values: List<Int>) : BonusInput
        data class Denied(val reason: String) : BonusInput
    }

    private fun normalizeBonus(inheritBonusStat: List<Int>?): BonusInput {
        if (inheritBonusStat == null) return BonusInput.Empty
        if (inheritBonusStat.size != 3) {
            return BonusInput.Denied("보너스 능력치가 잘못 지정되었습니다. 다시 입력해주세요!")
        }
        if (inheritBonusStat.any { it < 0 }) {
            return BonusInput.Denied("보너스 능력치가 음수입니다. 다시 입력해주세요!")
        }
        val sum = inheritBonusStat.sum()
        if (sum == 0) return BonusInput.Empty
        if (sum < GameConst.bornMinStatBonus || sum > GameConst.bornMaxStatBonus) {
            return BonusInput.Denied("보너스 능력치 합이 잘못 지정되었습니다. 다시 입력해주세요!")
        }
        return BonusInput.Explicit(inheritBonusStat)
    }

    private fun drawResetStatBonus(
        userId: Int,
        leadership: Int,
        strength: Int,
        intel: Int,
        hiddenSeed: String,
    ): List<Int> {
        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "ResetStat", userId)))
        val bonus = IntArray(3)
        repeat(rng.nextRangeInt(GameConst.bornMinStatBonus, GameConst.bornMaxStatBonus)) {
            when (rng.choiceUsingWeight(linkedMapOf(
                "0" to leadership.toDouble(),
                "1" to strength.toDouble(),
                "2" to intel.toDouble(),
            ))) {
                "0" -> bonus[0]++
                "1" -> bonus[1]++
                "2" -> bonus[2]++
            }
        }
        return bonus.toList()
    }
}

/** The resolved effect an inherit reset produces (applied by the engine handler). */
sealed interface InheritResetOutcome {
    /** Gate/validation denied — the PHP-faithful reason string (the API `launch` return). */
    data class Denied(val reason: String) : InheritResetOutcome

    /**
     * Success: spend [spent] points (new `previous` = [remainingPrevious]), apply the general
     * [auxUpdates] (nested `aux` map) + [varUpdates] (top-level general vars), bump
     * `inherit_point_spent_dynamic` by [spent], and push the [log] line to `inheritance_log`
     * (tag = "inheritPoint").
     */
    data class Applied(
        val spent: Int,
        val remainingPrevious: Double,
        val auxUpdates: Map<String, Any?>,
        val varUpdates: Map<String, Any?>,
        val log: String,
    ) : InheritResetOutcome
}

sealed interface ResetStatOutcome {
    data class Denied(val reason: String) : ResetStatOutcome

    data class Applied(
        val spent: Int,
        val remainingPrevious: Double,
        val nextLeadership: Int,
        val nextStrength: Int,
        val nextIntel: Int,
        val nextLastStatReset: List<Int>,
        val logs: List<String>,
    ) : ResetStatOutcome
}
