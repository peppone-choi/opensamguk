package com.opensam.engine

import com.opensam.engine.UniqueLotteryService.GeneralItemSlots
import com.opensam.engine.UniqueLotteryService.UniqueAcquireType
import com.opensam.engine.modifier.ItemMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class UniqueLotteryServiceTest {

    private val service = UniqueLotteryService()

    @Test
    fun `returns deterministic item for fixed seed`() {
        val itemRegistry = buildSingleWeaponRegistry()
        val config = buildConfig()
        val seed = service.buildVoteUniqueSeed("seed", 1L, 1L)

        val first = service.rollUniqueLottery(
            buildInput(
                rng = service.createDeterministicRng(seed),
                config = config,
                itemRegistry = itemRegistry,
                acquireType = UniqueAcquireType.SURVEY,
            )
        )

        val second = service.rollUniqueLottery(
            buildInput(
                rng = service.createDeterministicRng(seed),
                config = config,
                itemRegistry = itemRegistry,
                acquireType = UniqueAcquireType.SURVEY,
            )
        )

        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `supports acquire types that can yield unique items`() {
        val itemRegistry = buildSingleWeaponRegistry()
        val config = buildConfig()
        val acquireTypes = listOf(
            UniqueAcquireType.ITEM,
            UniqueAcquireType.SURVEY,
            UniqueAcquireType.RANDOM_RECRUIT,
        )

        for (acquireType in acquireTypes) {
            val seed = service.buildVoteUniqueSeed("seed", 2L, 2L)
            val result = service.rollUniqueLottery(
                buildInput(
                    rng = service.createDeterministicRng(seed),
                    config = config,
                    itemRegistry = itemRegistry,
                    acquireType = acquireType,
                )
            )
            assertNotNull(result)
        }
    }

    @Test
    fun `guarantees unique on founding acquire type`() {
        val itemRegistry = buildSingleWeaponRegistry()
        val config = buildConfig(uniqueTrialCoef = 0.0, maxUniqueTrialProb = 0.0)
        val seed = service.buildVoteUniqueSeed("seed", 3L, 3L)

        val result = service.rollUniqueLottery(
            buildInput(
                rng = service.createDeterministicRng(seed),
                config = config,
                itemRegistry = itemRegistry,
                acquireType = UniqueAcquireType.FOUNDING,
            )
        )

        assertNotNull(result)
    }

    @Test
    fun `counts only non-buyable equipped items`() {
        val itemRegistry = mapOf(
            "uniqueItem" to buildItem(code = "uniqueItem", category = "weapon", buyable = false),
            "buyableItem" to buildItem(code = "buyableItem", category = "book", buyable = true),
        )
        val generals = listOf(
            GeneralItemSlots(horse = null, weapon = "uniqueItem", book = null, item = null),
            GeneralItemSlots(horse = null, weapon = null, book = "buyableItem", item = null),
        )

        val counts = service.countOccupiedUniqueItems(generals, itemRegistry)

        assertEquals(1, counts["uniqueItem"])
        assertEquals(null, counts["buyableItem"])
    }

    private fun buildSingleWeaponRegistry(): Map<String, ItemMeta> {
        return mapOf("itemB" to buildItem(code = "itemB", category = "weapon", buyable = false))
    }

    private fun buildItem(code: String, category: String, buyable: Boolean): ItemMeta {
        return ItemMeta(
            code = code,
            rawName = code,
            category = category,
            grade = 0,
            cost = 0,
            buyable = buyable,
            rarity = 0,
            consumable = false,
            info = code,
        )
    }

    private fun buildConfig(
        uniqueTrialCoef: Double = 10.0,
        maxUniqueTrialProb: Double = 10.0,
    ): UniqueLotteryService.UniqueLotteryConfig {
        val rawConfig: Map<String, Any?> = mapOf(
            "allItems" to mapOf(
                "weapon" to mapOf("itemB" to 1),
            ),
            "maxUniqueItemLimit" to listOf(listOf(-1, 1)),
            "uniqueTrialCoef" to uniqueTrialCoef,
            "maxUniqueTrialProb" to maxUniqueTrialProb,
            "minMonthToAllowInheritItem" to 0,
        )
        return service.resolveUniqueConfig(rawConfig)
    }

    private fun buildInput(
        rng: kotlin.random.Random,
        config: UniqueLotteryService.UniqueLotteryConfig,
        itemRegistry: Map<String, ItemMeta>,
        acquireType: UniqueAcquireType,
    ): UniqueLotteryService.UniqueLotteryInput {
        return UniqueLotteryService.UniqueLotteryInput(
            rng = rng,
            config = config,
            itemRegistry = itemRegistry,
            generalItems = GeneralItemSlots(horse = null, weapon = null, book = null, item = null),
            occupiedUniqueCounts = emptyMap(),
            scenarioId = 200,
            userCount = 1,
            currentYear = 200,
            currentMonth = 1,
            startYear = 180,
            initYear = 180,
            initMonth = 1,
            acquireType = acquireType,
        )
    }
}
