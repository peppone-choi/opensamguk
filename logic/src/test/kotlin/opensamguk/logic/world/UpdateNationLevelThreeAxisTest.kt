package opensamguk.logic.world

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.world.rank.NationRank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 3b — 3축 작위·관직(爵·官·天子)의 `UpdateNationLevel` 배선.
 * 스펙: `docs/superpowers/specs/2026-08-19-nation-rank-three-axis.md` §5·§10.3.
 *
 * **패러티 아님 · han 전용.** che 변종은 `supportsThreeAxisRank == false` 라 기존 단일 사다리
 * 경로를 그대로 타고 [UpdateNationLevelContext.applyNationRank] 를 한 번도 호출하지 않는다 —
 * 그 불변을 이 파일이 지킨다.
 */
class UpdateNationLevelThreeAxisTest {

    /** 필요한 seam 만 채운 fake — 로터리는 아이템 지급 없이 통과시킨다. */
    private class FakeWorld(
        private val variant: CityConstVariant,
        private val nationList: List<Nation>,
        private val ownership: List<Pair<Int, Int>>,
    ) : UpdateNationLevelContext {
        override val env: Map<String, Any?> = emptyMap()
        val levelUps = ArrayList<UpdateNationLevel.LevelUpEffects>()
        val rankUpdates = ArrayList<Nation>()

        override fun nations(): List<Nation> = nationList
        override fun cityOwnership(): List<Pair<Int, Int>> = ownership
        override fun cityConst(): CityConstVariant = variant
        override fun generals(): List<General> = emptyList()
        override fun hiddenSeed(): String = "test-seed"
        override fun year(): Int = 200
        override fun month(): Int = 1
        override fun startYear(): Int = 180
        override fun killturnEnv(): Int = 100
        override fun turnterm(): Int = 60
        override fun lordName(nationId: Int): String? = "장수"
        override fun applyNationLevelUp(effects: UpdateNationLevel.LevelUpEffects) {
            levelUps.add(effects)
        }
        override fun applyNationRank(nation: Nation) {
            rankUpdates.add(nation)
        }
        override fun giveRandomUniqueItem(rng: RandUtil, winnerId: Int): Boolean = false
        override fun applyLotteryResult(nationId: Int, result: UpdateNationLevel.LotteryResult) = Unit
    }

    private val han = CityConstRegistry.of("han")
    private val che = CityConstRegistry.of("che")

    /**
     * 東夷(14)를 뺀 han 郡治 id 를 [provinces] 개 州에서 州당 [perProvince] 개씩 고른다.
     * 州당 소수만 쥐므로 刺史(비율 50%)·州牧(80%) 문턱에 닿지 않아 官은 항상 太守다 —
     * 爵만 움직이는 케이스를 결정적으로 만든다.
     */
    private fun hanSeatIds(provinces: Int, perProvince: Int): List<Int> =
        han.all().values
            .filter { han.countsForNationLevel(it.level) && it.region != NationRank.DONGYI_REGION_ID }
            .groupBy { it.region }
            .toSortedMap()
            .values
            .take(provinces)
            .flatMap { cities -> cities.sortedBy { it.id }.take(perProvince) }
            .map { it.id }

    private fun nation(level: Int, typeCode: String = "che_한나라", meta: Map<String, Any?> = linkedMapOf()) =
        Nation(id = 1, level = level, capitalCityId = null, name = "촉", typeCode = typeCode, meta = meta)

    // ── nationTitleUnlockLevel ────────────────────────────────────────────────────────────────

    @Test
    fun `che 는 국호해금이 7 이고 han 은 5 다`() {
        assertEquals(7, che.nationTitleUnlockLevel)
        assertEquals(5, han.nationTitleUnlockLevel)
        assertEquals(7, CityConstRegistry.of("miniche").nationTitleUnlockLevel)
    }

    @Test
    fun `titleUnlockLevel 5 면 레벨5에서 국호변경이 풀리고 기본 7 이면 안 풀린다`() {
        val n = nation(level = 4)
        val lu = UpdateNationLevel.LevelUp(oldLevel = 4, newLevel = 5, levelDiff = 1)

        val unlocked = UpdateNationLevel.applyLevelUp(n, "유비", 200, 1, lu, titleUnlockLevel = 5)
        @Suppress("UNCHECKED_CAST")
        val aux = unlocked.nation.meta["aux"] as Map<String, Any?>
        assertEquals(1, aux["can_국호변경"])
        assertEquals(1, aux["can_국기변경"])

        val locked = UpdateNationLevel.applyLevelUp(n, "유비", 200, 1, lu)
        assertNull(locked.nation.meta["aux"])
    }

    // ── 3축 경로 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `han 에서 레벨업하면 meta 에 3축이 실린다`() {
        val seats = hanSeatIds(provinces = 3, perProvince = 2)
        val world = FakeWorld(han, listOf(nation(level = 0)), seats.map { it to 1 })

        UpdateNationLevelAction().run(world)

        assertEquals(1, world.levelUps.size)
        assertTrue(world.rankUpdates.isEmpty())
        val meta = world.levelUps[0].nation.meta
        assertEquals("HYANGHU", meta["rankPeerage"])          // 郡治 5 이상 = 鄉侯
        assertTrue(meta.containsKey("rankProvincialOffice"))
        assertTrue(meta.containsKey("rankProvinceId"))
        assertEquals("NONE", meta["rankCentralOffice"])       // 天子 미옹립
        assertEquals("ORTHODOX", meta["legitimacy"])
        assertFalse(meta.containsKey("rankBanditLabel"))      // 정통 세력은 자칭 라벨 없음
    }

    @Test
    fun `han 에서 레벨업이 없어도 축이 바뀌면 applyNationRank 가 불린다`() {
        val seats = hanSeatIds(provinces = 3, perProvince = 2)
        // 이미 spine 2(太守)에 있으므로 郡治 6 개로는 등급이 오르지 않는다 — 爵만 올라간다.
        val world = FakeWorld(han, listOf(nation(level = 2)), seats.map { it to 1 })

        UpdateNationLevelAction().run(world)

        assertTrue(world.levelUps.isEmpty())
        assertEquals(1, world.rankUpdates.size)
        assertEquals("HYANGHU", world.rankUpdates[0].meta["rankPeerage"])
    }

    @Test
    fun `축이 그대로면 아무것도 하지 않는다`() {
        val seats = hanSeatIds(provinces = 3, perProvince = 2)
        val first = FakeWorld(han, listOf(nation(level = 2)), seats.map { it to 1 })
        UpdateNationLevelAction().run(first)
        val settled = first.rankUpdates[0]

        val second = FakeWorld(han, listOf(settled), seats.map { it to 1 })
        UpdateNationLevelAction().run(second)

        assertTrue(second.levelUps.isEmpty())
        assertTrue(second.rankUpdates.isEmpty())
    }

    @Test
    fun `che 에서는 applyNationRank 가 한 번도 불리지 않는다`() {
        val cheSeats = che.all().values
            .filter { che.countsForNationLevel(it.level) }
            .sortedBy { it.id }
            .take(12)
            .map { it.id }
        val world = FakeWorld(che, listOf(nation(level = 0)), cheSeats.map { it to 1 })

        UpdateNationLevelAction().run(world)

        assertTrue(world.rankUpdates.isEmpty())
        assertEquals(1, world.levelUps.size)
        // che 는 PHP 문턱 표를 그대로 쓴다 — 城 12 → 등급 5.
        assertEquals(UpdateNationLevel.targetLevelByCityCnt(12), world.levelUps[0].nation.level)
        // 3축 키는 하나도 실리지 않는다.
        assertFalse(world.levelUps[0].nation.meta.containsKey("rankPeerage"))
    }
}
