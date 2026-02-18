package com.opensam.engine

import com.opensam.entity.General
import com.opensam.engine.modifier.ItemMeta
import org.springframework.stereotype.Service
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

@Service
class UniqueLotteryService {
    enum class UniqueAcquireType(val label: String) {
        ITEM("아이템"),
        SURVEY("설문조사"),
        RANDOM_RECRUIT("랜덤 임관"),
        FOUNDING("건국"),
    }

    data class UniqueLotteryConfig(
        val allItems: Map<String, Map<String, Int>>,
        val maxUniqueItemLimit: List<Pair<Int, Int>>,
        val uniqueTrialCoef: Double,
        val maxUniqueTrialProb: Double,
        val minMonthToAllowInheritItem: Int,
    )

    data class GeneralItemSlots(
        val horse: String?,
        val weapon: String?,
        val book: String?,
        val item: String?,
    )

    data class UniqueLotteryInput(
        val rng: Random,
        val config: UniqueLotteryConfig,
        val itemRegistry: Map<String, ItemMeta>,
        val generalItems: GeneralItemSlots,
        val occupiedUniqueCounts: Map<String, Int>,
        val scenarioId: Int,
        val userCount: Int,
        val currentYear: Int,
        val currentMonth: Int,
        val startYear: Int,
        val initYear: Int,
        val initMonth: Int,
        val acquireType: UniqueAcquireType = UniqueAcquireType.ITEM,
        val inheritRandomUnique: Boolean = false,
    )

    data class UniqueGainLogContext(
        val nationName: String,
        val generalName: String,
        val itemName: String,
        val itemRawName: String,
        val acquireType: UniqueAcquireType,
    )

    companion object {
        private val DEFAULT_MAX_UNIQUE_ITEM_LIMIT = listOf(-1 to 1, 3 to 2, 10 to 3, 20 to 4)
        private const val DEFAULT_UNIQUE_TRIAL_COEF = 1.0
        private const val DEFAULT_MAX_UNIQUE_TRIAL_PROB = 0.25
        private const val DEFAULT_MIN_MONTH_TO_ALLOW_INHERIT_ITEM = 4
    }

    fun resolveUniqueConfig(configConst: Map<String, Any?>): UniqueLotteryConfig {
        val allItems = normalizeItemPool(configConst["allItems"])
        return UniqueLotteryConfig(
            allItems = allItems,
            maxUniqueItemLimit = normalizeLimitTable(configConst["maxUniqueItemLimit"]),
            uniqueTrialCoef = readNumber(configConst["uniqueTrialCoef"], DEFAULT_UNIQUE_TRIAL_COEF),
            maxUniqueTrialProb = readNumber(configConst["maxUniqueTrialProb"], DEFAULT_MAX_UNIQUE_TRIAL_PROB),
            minMonthToAllowInheritItem = floor(
                readNumber(
                    configConst["minMonthToAllowInheritItem"],
                    DEFAULT_MIN_MONTH_TO_ALLOW_INHERIT_ITEM.toDouble(),
                )
            ).toInt().coerceAtLeast(0),
        )
    }

    fun countOccupiedUniqueItems(generalItemSlots: List<GeneralItemSlots>, itemRegistry: Map<String, ItemMeta>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (items in generalItemSlots) {
            val values = listOf(items.horse, items.weapon, items.book, items.item)
            for (itemKey in values) {
                if (itemKey.isNullOrBlank()) {
                    continue
                }
                val module = itemRegistry[itemKey]
                if (module == null || module.buyable) {
                    continue
                }
                counts[itemKey] = (counts[itemKey] ?: 0) + 1
            }
        }
        return counts
    }

    fun buildGenericUniqueSeed(
        hiddenSeed: String,
        year: Int,
        month: Int,
        generalId: Long,
        reason: String? = null,
    ): String {
        return if (reason != null) {
            serializeSeed(hiddenSeed, "unique", year, month, generalId, reason)
        } else {
            serializeSeed(hiddenSeed, "unique", year, month, generalId)
        }
    }

    fun buildVoteUniqueSeed(hiddenSeed: String, voteId: Long, generalId: Long): String {
        return serializeSeed(hiddenSeed, "voteUnique", voteId, generalId)
    }

    fun createDeterministicRng(seed: String): Random {
        return DeterministicRng.create(seed, "unique_lottery")
    }

    fun rollUniqueLottery(input: UniqueLotteryInput): String? {
        if (input.userCount <= 0) {
            return null
        }

        val itemTypes = input.config.allItems.keys.toList()
        val itemTypeCnt = itemTypes.size
        if (itemTypeCnt <= 0) {
            return null
        }

        val relYear = input.currentYear - input.startYear
        var maxTrialCountByYear = 1
        for ((targetYear, targetTrialCnt) in input.config.maxUniqueItemLimit) {
            if (relYear < targetYear) {
                break
            }
            maxTrialCountByYear = targetTrialCnt
        }

        var trialCnt = minOf(itemTypeCnt, maxTrialCountByYear)
        var maxCnt = itemTypeCnt

        val invalidItemTypes = mutableSetOf<String>()
        val equippedItems = listOf(
            "horse" to input.generalItems.horse,
            "weapon" to input.generalItems.weapon,
            "book" to input.generalItems.book,
            "item" to input.generalItems.item,
        )

        for ((slot, itemKey) in equippedItems) {
            if (itemKey.isNullOrBlank()) {
                continue
            }
            val module = input.itemRegistry[itemKey]
            if (module == null || module.buyable) {
                continue
            }
            invalidItemTypes.add(slot)
            trialCnt -= 1
            maxCnt -= 1
        }

        if (trialCnt <= 0 || maxCnt <= 0) {
            return null
        }

        val relMonthByInit = joinYearMonth(input.currentYear, input.currentMonth) - joinYearMonth(input.initYear, input.initMonth)
        val availableBuyUnique = relMonthByInit >= input.config.minMonthToAllowInheritItem

        var prob = if (input.scenarioId < 100) {
            1.0 / (input.userCount * 3.0 * itemTypeCnt)
        } else {
            1.0 / (input.userCount * itemTypeCnt)
        }

        when (input.acquireType) {
            UniqueAcquireType.SURVEY -> {
                prob = 1.0 / ((input.userCount * itemTypeCnt * 0.7) / 3.0)
            }
            UniqueAcquireType.RANDOM_RECRUIT -> {
                prob = 1.0 / ((input.userCount * itemTypeCnt) / 10.0 / 2.0)
            }
            else -> Unit
        }

        prob *= input.config.uniqueTrialCoef
        if (prob > input.config.maxUniqueTrialProb) {
            prob = input.config.maxUniqueTrialProb
        }

        prob /= sqrt(7.0)
        val moreProb = 10.0.pow(1.0 / 4.0)

        if (input.inheritRandomUnique && availableBuyUnique) {
            prob = 1.0
        } else if (input.acquireType == UniqueAcquireType.FOUNDING) {
            prob = 1.0
        }

        var success = false
        for (i in 0 until maxCnt) {
            if (nextBool(input.rng, prob)) {
                success = true
                break
            }
            prob *= moreProb
        }

        if (!success) {
            return null
        }

        val availableUnique = mutableListOf<Pair<String, Int>>()
        for (itemType in itemTypes) {
            if (itemType in invalidItemTypes) {
                continue
            }
            val itemEntries = input.config.allItems[itemType] ?: emptyMap()
            for ((itemKey, rawCount) in itemEntries) {
                val count = rawCount
                if (count <= 0) {
                    continue
                }
                val module = input.itemRegistry[itemKey]
                if (module == null || module.buyable) {
                    continue
                }
                val remain = count - (input.occupiedUniqueCounts[itemKey] ?: 0)
                if (remain > 0) {
                    availableUnique.add(itemKey to remain)
                }
            }
        }

        if (availableUnique.isEmpty()) {
            return null
        }

        return choiceUsingWeightPair(input.rng, availableUnique)
    }

    fun applyUniqueItemGain(general: General, itemKey: String, slot: String): Boolean {
        when (slot) {
            "horse" -> general.horseCode = itemKey
            "weapon" -> general.weaponCode = itemKey
            "book" -> general.bookCode = itemKey
            "item" -> general.itemCode = itemKey
            else -> return false
        }
        return true
    }

    fun buildUniqueGainLogs(ctx: UniqueGainLogContext): List<String> {
        val josaYi = pickJosa(ctx.generalName, "이")
        val josaUl = pickJosa(ctx.itemRawName, "을")
        return listOf(
            "<C>${ctx.itemName}</>${josaUl} 습득했습니다!",
            "<C>${ctx.itemName}</>${josaUl} 습득",
            "<Y>${ctx.generalName}</>${josaYi} <C>${ctx.itemName}</>${josaUl} 습득했습니다!",
            "<C><b>【${ctx.acquireType.label}】</b></><D><b>${ctx.nationName}</b></>의 <Y>${ctx.generalName}</>${josaYi} <C>${ctx.itemName}</>${josaUl} 습득했습니다!",
        )
    }

    private fun normalizeItemPool(value: Any?): Map<String, Map<String, Int>> {
        if (value !is Map<*, *>) {
            return emptyMap()
        }

        val result = mutableMapOf<String, Map<String, Int>>()
        for ((itemType, rawItems) in value) {
            if (itemType !is String || rawItems !is Map<*, *>) {
                continue
            }
            val entries = mutableMapOf<String, Int>()
            for ((itemKey, rawCount) in rawItems) {
                if (itemKey !is String) {
                    continue
                }
                val count = readNumber(rawCount, Double.NaN)
                if (!count.isFinite()) {
                    continue
                }
                entries[itemKey] = floor(count).toInt()
            }
            result[itemType] = entries
        }
        return result
    }

    private fun normalizeLimitTable(value: Any?): List<Pair<Int, Int>> {
        if (value !is List<*>) {
            return DEFAULT_MAX_UNIQUE_ITEM_LIMIT
        }

        val result = mutableListOf<Pair<Int, Int>>()
        for (entry in value) {
            if (entry !is List<*> || entry.size < 2) {
                continue
            }
            val year = readNumber(entry[0], Double.NaN)
            val limit = readNumber(entry[1], Double.NaN)
            if (!year.isFinite() || !limit.isFinite()) {
                continue
            }
            result.add(floor(year).toInt() to floor(limit).toInt())
        }

        return if (result.isNotEmpty()) result else DEFAULT_MAX_UNIQUE_ITEM_LIMIT
    }

    private fun readNumber(value: Any?, fallback: Double): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun joinYearMonth(year: Int, month: Int): Int {
        return year * 12 + month - 1
    }

    private fun serializeSeed(vararg values: Any): String {
        return values.joinToString("|") { value ->
            when (value) {
                is String -> "str(${value.length},$value)"
                is Number -> "int(${floor(value.toDouble()).toLong()})"
                else -> "str(${value.toString().length},${value})"
            }
        }
    }

    private fun nextBool(rng: Random, prob: Double): Boolean {
        if (prob >= 1.0) {
            return true
        }
        if (prob <= 0.0) {
            return false
        }
        return rng.nextDouble() < prob
    }

    private fun choiceUsingWeightPair(rng: Random, items: List<Pair<String, Int>>): String {
        val sum = items.filter { it.second > 0 }.sumOf { it.second.toLong() }
        var rd = rng.nextDouble() * sum

        for ((item, weightInt) in items) {
            val weight = weightInt.toLong()
            if (weight <= 0L) {
                if (rd <= 0.0) {
                    return item
                }
                continue
            }
            if (rd <= weight.toDouble()) {
                return item
            }
            rd -= weight
        }

        return items.last().first
    }

    private fun pickJosa(text: String?, wJongsung: String, woJongsung: String = ""): String {
        val normalized = text ?: ""
        val withFinal = wJongsung
        val withoutFinal = if (woJongsung.isBlank()) {
            when (wJongsung) {
                "은" -> "는"
                "이" -> "가"
                "과" -> "와"
                "이나" -> "나"
                "을" -> "를"
                "으로" -> "로"
                "이라" -> "라"
                "이랑" -> "랑"
                else -> ""
            }
        } else {
            woJongsung
        }

        if (withoutFinal.isEmpty()) {
            return withFinal
        }

        return if (hasJongsung(normalized, withFinal == "으로")) withFinal else withoutFinal
    }

    private fun hasJongsung(text: String, isRo: Boolean): Boolean {
        val lastChar = text.trim().lastOrNull() ?: return false
        val code = lastChar.code

        if (code in 0xAC00..0xD7A3) {
            val jongsung = (code - 0xAC00) % 28
            if (jongsung == 0) {
                return false
            }
            if (isRo && jongsung == 8) {
                return false
            }
            return true
        }

        if (lastChar in 'ㄱ'..'ㅎ') {
            if (isRo && lastChar == 'ㄹ') {
                return false
            }
            return true
        }

        if (lastChar in '0'..'9') {
            val digit = lastChar.digitToInt()
            val has = digit == 0 || digit == 1 || digit == 3 || digit == 6 || digit == 7 || digit == 8
            val rieul = digit == 1 || digit == 7 || digit == 8
            if (isRo && rieul) {
                return false
            }
            return has
        }

        val lower = lastChar.lowercaseChar()
        val isVowel = lower == 'a' || lower == 'e' || lower == 'i' || lower == 'o' || lower == 'u' || lower == 'y'
        return !isVowel
    }
}
