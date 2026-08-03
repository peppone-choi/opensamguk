package opensamguk.logic.world

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import opensamguk.logic.event.WorldActions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegNpcActionTest {
    @Test
    fun `adult appearance stages one general and emits the PHP log`() {
        val context = FakeContext(year = 182, month = 1)

        RegNpcAction(
            affinity = 42,
            name = "미성년",
            picture = "npc.png",
            nationId = 1,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 3,
            birth = 168,
            death = 220,
            ego = "유지",
            special = "신산",
            npcText = "등장 문구",
        ).run(context)

        assertEquals(1, context.staged.size)
        val staged = context.staged.single()
        assertEquals("ⓝ미성년", staged.name)
        assertEquals(14, staged.age)
        assertEquals(1, staged.nation)
        assertEquals(1, staged.officerLevel)
        assertEquals("che_유지", staged.ego)
        assertEquals("che_신산", staged.specialWar)
        assertEquals("npc.png", staged.picture)
        assertEquals("등장 문구", staged.npcText)
        assertEquals(listOf("<Y>ⓝ미성년</>이 성인이 되어 <S>등장</>했습니다."), context.actionLogs)
        assertEquals(listOf("log", "stage"), context.operations)
    }

    @Test
    fun `appearance falls back to neutral when the scenario nation is extinct`() {
        val context = FakeContext(year = 182, month = 1, nationExists = false)

        RegNpcAction(
            affinity = 42,
            name = "유민",
            picture = null,
            nationId = 1,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 3,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(context)

        assertEquals(0, context.staged.single().nation)
    }

    @Test
    fun `appearance does not fabricate unresolved stored icon ids`() {
        val numericIconContext = FakeContext(year = 182, month = 1)
        RegNpcAction(
            affinity = 42,
            name = "아이콘",
            picture = "17",
            nationId = 0,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 0,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(numericIconContext)
        assertEquals("default.jpg", numericIconContext.staged.single().picture)

        val hiddenNpcImageContext = FakeContext(year = 182, month = 1, showImageLevel = 2)
        RegNpcAction(
            affinity = 42,
            name = "숨김",
            picture = "npc.png",
            nationId = 0,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 0,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(hiddenNpcImageContext)
        assertEquals("default.jpg", hiddenNpcImageContext.staged.single().picture)
    }

    @Test
    fun `appearance resolves stored icons with PHP GeneralBuilder rules`() {
        val numericIconContext = FakeContext(
            year = 182,
            month = 1,
            storedIcons = mapOf("." to mapOf("17" to "stored.png")),
        )
        RegNpcAction(
            affinity = 42,
            name = "숫자",
            picture = "17",
            nationId = 0,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 0,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(numericIconContext)
        assertEquals("stored.png", numericIconContext.staged.single().picture)

        val namedIconContext = FakeContext(
            year = 182,
            month = 1,
            iconPath = "che",
            storedIcons = mapOf("che" to mapOf("아이콘명" to "named.png")),
        )
        RegNpcAction(
            affinity = 42,
            name = "아이콘명",
            picture = null,
            nationId = 0,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 0,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(namedIconContext)
        assertEquals("che/named.png", namedIconContext.staged.single().picture)

        val fileIconContext = FakeContext(
            year = 182,
            month = 1,
            iconPath = "che",
            storedIcons = mapOf("che" to mapOf("다른이름" to "face.png")),
        )
        RegNpcAction(
            affinity = 42,
            name = "파일명",
            picture = "face.png",
            nationId = 0,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 0,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(fileIconContext)
        assertEquals("che/face.png", fileIconContext.staged.single().picture)
    }

    @Test
    fun `deferred raw tuple with null affinity draws through the RegNPC seed`() {
        val context = FakeContext(year = 182, month = 1)
        val factory = WorldActions.register(EventActionFactory())

        factory.create(
            RawAction(
                "RegNPC",
                listOf(
                    JsonNull,
                    JsonPrimitive("무혈연"),
                    JsonNull,
                    JsonPrimitive(1),
                    JsonNull,
                    JsonPrimitive(70),
                    JsonPrimitive(60),
                    JsonPrimitive(50),
                    JsonPrimitive(0),
                    JsonPrimitive(168),
                    JsonPrimitive(220),
                    JsonNull,
                    JsonNull,
                    JsonNull,
                ),
            ),
        ).run(context)

        assertTrue(context.staged.single().affinity in 1..150)
    }

    @Test
    fun `RegNeutralNPC preserves PHP raw tuple positions and uses its own seed scope`() {
        val factory = WorldActions.register(EventActionFactory())
        val neutralContext = FakeContext(year = 182, month = 1)
        val rawTuple = listOf(
            JsonPrimitive(42),
            JsonPrimitive("중립"),
            JsonNull,
            JsonPrimitive(1),
            JsonPrimitive("낙양"),
            JsonPrimitive(70),
            JsonPrimitive(60),
            JsonPrimitive(50),
            JsonPrimitive(168),
            JsonPrimitive(220),
            JsonPrimitive("유지"),
            JsonPrimitive("무쌍"),
            JsonPrimitive("중립 문구"),
            JsonPrimitive("ignored raw tail"),
        )

        factory.create(RawAction("RegNeutralNPC", rawTuple)).run(neutralContext)

        val neutral = neutralContext.staged.single()
        assertEquals("ⓤ중립", neutral.name)
        assertEquals(6, neutral.npc)
        assertEquals(1, neutral.nation)
        assertEquals(1, neutral.officerLevel)
        assertEquals(14, neutral.age)
        assertEquals(220, neutral.death)
        assertEquals("che_유지", neutral.ego)
        assertEquals("che_무쌍", neutral.specialWar)
        assertEquals("중립 문구", neutral.npcText)
        assertEquals(50, neutral.politics)
        assertEquals(50, neutral.charm)
        assertEquals(null, neutral.appearanceYear)
        assertTrue(neutral.rtkMetadata.isEmpty())
        assertEquals(listOf("<Y>ⓤ중립</>이 성인이 되어 <S>등장</>했습니다."), neutralContext.actionLogs)

        val regNpcContext = FakeContext(year = 182, month = 1)
        RegNpcAction(
            affinity = 42,
            name = "중립",
            picture = null,
            nationId = 1,
            locatedCity = "낙양",
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 1,
            birth = 168,
            death = 220,
            ego = "유지",
            special = "무쌍",
            npcText = "중립 문구",
        ).run(regNpcContext)

        assertNotEquals(regNpcContext.staged.single().turntimeSecond, neutral.turntimeSecond)
    }

    @Test
    fun `RTK tuple waits for its explicit appearance then carries five stats and source metadata`() {
        val factory = WorldActions.register(EventActionFactory())
        val tuple = rtkRegNpcTuple(appearanceYear = 190)
        val beforeAppearance = FakeContext(year = 189, month = 1)

        factory.create(RawAction("RegNPC", tuple)).run(beforeAppearance)

        assertTrue(beforeAppearance.staged.isEmpty(), "RTK appearance overrides the legacy birth plus fourteen threshold")

        val atAppearance = FakeContext(year = 190, month = 1)
        factory.create(RawAction("RegNPC", tuple)).run(atAppearance)

        val staged = atAppearance.staged.single()
        assertEquals(77, staged.politics)
        assertEquals(88, staged.charm)
        assertEquals(22, staged.age, "the source birth year is still retained even when appearance is later")
        assertEquals(190, staged.appearanceYear)
        assertEquals(
            linkedMapOf<String, Any?>(
                "rtk14_officer_number" to 17001,
                "rtk14_gender" to "female",
                "rtk14_birth_year" to 168,
                "rtk14_appearance_year" to 190,
                "rtk14_death_year" to 220,
                "rtk14_lifespan" to 52,
                "rtk14_activity_years" to 27,
                "rtk14_total" to 345,
                "rtk14_ideology" to "왕도",
            ),
            staged.rtkMetadata,
        )
        assertEquals(
            listOf(
                "rtk14_officer_number",
                "rtk14_gender",
                "rtk14_birth_year",
                "rtk14_appearance_year",
                "rtk14_death_year",
                "rtk14_lifespan",
                "rtk14_activity_years",
                "rtk14_total",
                "rtk14_ideology",
            ),
            staged.rtkMetadata.keys.toList(),
            "RTK metadata stays insertion ordered for the downstream JSON path",
        )
        assertEquals(
            listOf("<Y>ⓝRTK등장</>이 성인이 되어 <S>등장</>했습니다."),
            atAppearance.actionLogs,
            "the explicit appearance year is the new-general callback boundary",
        )

        val afterAppearance = FakeContext(year = 191, month = 1)
        factory.create(RawAction("RegNPC", tuple)).run(afterAppearance)

        assertEquals(23, afterAppearance.staged.single().age)
        assertTrue(afterAppearance.actionLogs.isEmpty(), "only the explicit appearance year is new-general")
    }

    @Test
    fun `RTK neutral tuple appears at its explicit year even when younger than fourteen`() {
        val factory = WorldActions.register(EventActionFactory())
        val context = FakeContext(year = 192, month = 1)

        factory.create(
            RawAction(
                "RegNeutralNPC",
                rtkRegNeutralNpcTuple(birth = 190, death = 220, appearanceYear = 192),
            ),
        ).run(context)

        val staged = context.staged.single()
        assertEquals("ⓤRTK중립", staged.name)
        assertEquals(6, staged.npc)
        assertEquals(2, staged.age)
        assertEquals(77, staged.politics)
        assertEquals(88, staged.charm)
        assertEquals(192, staged.appearanceYear)
        assertEquals(
            listOf("<Y>ⓤRTK중립</>이 성인이 되어 <S>등장</>했습니다."),
            context.actionLogs,
        )
    }

    @Test
    fun `RTK appearance allows death equality but rejects later years`() {
        val factory = WorldActions.register(EventActionFactory())
        val tuple = rtkRegNpcTuple(birth = 190, death = 195, appearanceYear = 195)
        val deathYear = FakeContext(year = 195, month = 1)

        factory.create(RawAction("RegNPC", tuple)).run(deathYear)

        assertEquals(1, deathYear.staged.size, "RTK death is inclusive on the explicit appearance path")

        val afterDeath = FakeContext(year = 196, month = 1)
        factory.create(RawAction("RegNPC", tuple)).run(afterDeath)

        assertTrue(afterDeath.staged.isEmpty(), "RTK rows do not reappear after their inclusive death year")
    }

    @Test
    fun `RTK death-year appearance clamps a zero-jitter derived killturn after the legacy three draws`() {
        val rng = ZeroRangeRandUtil()
        val appearances = mutableListOf<String>()

        val staged = GeneralBuilder(rng, "경계", 0)
            .setCityID(3)
            .setStat(70, 60, 50)
            .setAffinity(42)
            .setLifeSpan(195, 195)
            .setEgo("che_유지")
            .setSpecialSingle(null)
            .setAppearanceYear(195)
            .fillRemainSpecAsZero(year = 195, startYear = 181)
            .build(
                year = 195,
                month = 1,
                turnterm = 60,
                cityPool = listOf(GeneralBuilder.CityChoice(id = 3, nationId = 0)),
                onAdultGeneral = { appearances += it },
            )!!

        assertEquals(1, staged.killturn)
        assertEquals(listOf("ⓝ경계"), appearances)
        assertEquals(listOf(0 to 3599, 0 to 999999, 0 to 11), rng.rangeCalls)
    }

    @Test
    fun `legacy RegNPC tuple keeps fifty defaults and original adult and death boundaries`() {
        val factory = WorldActions.register(EventActionFactory())
        val legacyTuple = listOf(
            JsonPrimitive(42), JsonPrimitive("레거시"), JsonNull, JsonPrimitive(1), JsonNull,
            JsonPrimitive(70), JsonPrimitive(60), JsonPrimitive(50), JsonPrimitive(0),
            JsonPrimitive(168), JsonPrimitive(220), JsonPrimitive("유지"), JsonNull, JsonNull,
        )
        val adult = FakeContext(year = 182, month = 1)

        factory.create(RawAction("RegNPC", legacyTuple)).run(adult)

        val staged = adult.staged.single()
        assertEquals(50, staged.politics)
        assertEquals(50, staged.charm)
        assertEquals(null, staged.appearanceYear)
        assertTrue(staged.rtkMetadata.isEmpty())
        assertEquals(14, staged.age)
        assertEquals(listOf("<Y>ⓝ레거시</>가 성인이 되어 <S>등장</>했습니다."), adult.actionLogs)

        val deathYear = FakeContext(year = 220, month = 1)
        factory.create(RawAction("RegNPC", legacyTuple)).run(deathYear)
        assertTrue(deathYear.staged.isEmpty(), "legacy death remains exclusive")
    }

    @Test
    fun `adult appearance callback uses env fiction and runs before picture resolution and staging`() {
        val context = FakeContext(
            year = 182,
            month = 1,
            fiction = 1,
            recordPictureAccess = true,
            storedIcons = mapOf("." to mapOf("17" to "stored.png")),
        )

        RegNpcAction(
            affinity = 42,
            name = "성인",
            picture = "17",
            nationId = 1,
            locatedCity = null,
            leadership = 70,
            strength = 60,
            intel = 50,
            officerLevel = 3,
            birth = 168,
            death = 220,
            ego = "che_유지",
            special = null,
            npcText = null,
        ).run(context)

        val staged = context.staged.single()
        assertEquals(0, staged.nation, "truthy env fiction follows PHP GeneralBuilder newly-adult neutralization")
        assertEquals(0, staged.officerLevel, "newly-adult fiction-neutral general receives neutral officer level")
        assertEquals("stored.png", staged.picture)
        assertEquals(
            listOf("log", "picture", "stage"),
            context.operations,
            "PHP GeneralBuilder.php:596-638 logs adult appearance before picture resolution and row staging",
        )
    }

    @Test
    fun `CreateManyNPC with fillCnt zero does not subtract existing nation generals`() {
        val context = FakeContext(
            year = 182,
            month = 1,
            generals = listOf(
                general(id = 1, nationId = 1, officerLevel = 12),
                general(id = 2, nationId = 1, officerLevel = 1),
                general(id = 3, nationId = 1, officerLevel = 1),
                general(id = 4, nationId = 1, officerLevel = 1),
            ),
            generalNames = listOf("갑", "을", "병"),
        )

        CreateManyNPCAction(npcCount = 3, fillCnt = 0).run(context)

        assertEquals(3, context.staged.size)
    }

    @Test
    fun `CreateManyNPC keeps selected pool stats and occupies after staging`() {
        val occupied = mutableListOf<Pair<String, Int>>()
        val context = FakeContext(
            year = 182,
            month = 1,
            poolCandidates = listOf(
                PoolCandidate("고정", 11, 22, 33, occupied),
                PoolCandidate("랜덤", null, null, null, occupied),
            ),
        )

        CreateManyNPCAction(npcCount = 2, fillCnt = 0).run(context)

        assertEquals(2, context.staged.size)
        val fixed = context.staged[0]
        assertEquals("ⓜ고정", fixed.name)
        assertEquals(11, fixed.leadership)
        assertEquals(22, fixed.strength)
        assertEquals(33, fixed.intel)
        assertEquals("ⓜ랜덤", context.staged[1].name)
        assertEquals(listOf("고정" to 1, "랜덤" to 2), occupied)
    }

    private class FakeContext(
        private val year: Int,
        private val month: Int,
        private val nationExists: Boolean = true,
        private val generals: List<General> = emptyList(),
        private val generalNames: List<String> = emptyList(),
        showImageLevel: Int = 3,
        fiction: Any? = 0,
        private val iconPath: String = ".",
        private val storedIcons: Map<String, Map<String, String>> = emptyMap(),
        private val recordPictureAccess: Boolean = false,
        private val poolCandidates: List<ScenarioGeneralPoolCandidate>? = null,
    ) : ScenarioStartEventContext, ScenarioStoredIconContext, ScenarioGeneralPoolContext {
        override val env: Map<String, Any?> = linkedMapOf(
            "year" to year,
            "month" to month,
            "show_img_level" to showImageLevel,
            "fiction" to fiction,
            "icon_path" to iconPath,
            "stored_icons" to storedIcons,
        )
        val staged = mutableListOf<BuiltGeneral>()
        val actionLogs = mutableListOf<String>()
        val operations = mutableListOf<String>()
        var npcNationShuffleCalled = false

        override fun hiddenSeed(): String = "8ebfeb6fa932a181ec9ef43b7473f4c9"
        override fun year(): Int = year
        override fun month(): Int = month
        override fun startYear(): Int = 181
        override fun turnterm(): Int = 60
        override fun cityConst(): CityConstVariant = CityConstRegistry.of("che")
        override fun generals(): List<General> = generals
        override fun cities(): List<City> = listOf(
            City(3, 1, 8, 0, 0, 0, 0, 1, 0, 80.0),
        )
        override fun nations(): List<Nation> =
            if (nationExists) listOf(Nation(id = 1, level = 1, capitalCityId = 3)) else emptyList()
        override fun generalNames(): List<String> = generalNames
        override fun storedIcons(): Map<String, Map<String, String>> {
            if (recordPictureAccess) operations += "picture"
            return storedIcons
        }
        override fun iconPath(): String = iconPath
        override fun pickGeneralPoolCandidates(rng: RandUtil, count: Int): List<ScenarioGeneralPoolCandidate> =
            poolCandidates ?: generalNames.take(count).map { PoolCandidate(it) }
        override fun shuffleNpcNationCandidates(cities: List<City>): List<City> {
            npcNationShuffleCalled = true
            return cities
        }
        override fun allocateNationId(): Int = 1
        override fun stageGeneral(general: BuiltGeneral): Int {
            operations += "stage"
            staged += general
            return staged.size
        }
        override fun stageNation(nation: Nation) = Unit
        override fun stageDiplomacy(diplomacy: Diplomacy) = Unit
        override fun stageNationTurn(turn: NationTurn) = Unit
        override fun stageCity(city: City) = Unit
        override fun stageNationEnv(nationId: Int, key: String, value: Any?) = Unit
        override fun pushGlobalActionLog(msg: String) {
            operations += "log"
            actionLogs += msg
        }
        override fun pushGlobalHistoryLog(msg: String) = Unit
        override fun pushGlobalHistoryLog(msg: String, type: Int) = Unit
    }

    private fun general(id: Int, nationId: Int, officerLevel: Int): General = General(
        id = id,
        nationId = nationId,
        cityId = 3,
        leadership = 50,
        strength = 50,
        intel = 50,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = officerLevel,
        gold = 0,
        rice = 0,
        npcType = 2,
    )

    private data class PoolCandidate(
        override val name: String,
        val leadership: Int? = null,
        val strength: Int? = null,
        val intel: Int? = null,
        val occupied: MutableList<Pair<String, Int>> = mutableListOf(),
    ) : ScenarioGeneralPoolCandidate {
        override val firstStat: Int? = leadership
        override val picture: String? = null

        override fun generalBuilder(rng: RandUtil): GeneralBuilder =
            GeneralBuilder(rng, name, 0).also { builder ->
                if (leadership != null && strength != null && intel != null) {
                    builder.setStat(leadership, strength, intel)
                }
            }

        override fun occupyGeneralName(generalId: Int) {
            occupied += name to generalId
        }
    }

    private fun rtkRegNpcTuple(
        birth: Int = 168,
        death: Int = 220,
        appearanceYear: Int,
    ): List<JsonPrimitive> = listOf(
        JsonPrimitive(42), JsonPrimitive("RTK등장"), JsonPrimitive("rtk.png"), JsonPrimitive(1), JsonPrimitive("낙양"),
        JsonPrimitive(70), JsonPrimitive(60), JsonPrimitive(50), JsonPrimitive(3),
        JsonPrimitive(birth), JsonPrimitive(death), JsonPrimitive("유지"), JsonPrimitive("무쌍"), JsonPrimitive("RTK 문구"),
        JsonPrimitive(77), JsonPrimitive(88), JsonPrimitive(appearanceYear), JsonPrimitive(17001), JsonPrimitive("female"),
        JsonPrimitive(52), JsonPrimitive(27), JsonPrimitive(345), JsonPrimitive("왕도"),
    )

    private fun rtkRegNeutralNpcTuple(
        birth: Int,
        death: Int,
        appearanceYear: Int,
    ): List<JsonPrimitive> = listOf(
        JsonPrimitive(42), JsonPrimitive("RTK중립"), JsonPrimitive("rtk-neutral.png"), JsonPrimitive(1), JsonPrimitive("낙양"),
        JsonPrimitive(70), JsonPrimitive(60), JsonPrimitive(50), JsonPrimitive(birth), JsonPrimitive(death),
        JsonPrimitive("유지"), JsonPrimitive("무쌍"), JsonPrimitive("RTK 중립 문구"), JsonPrimitive("legacy-neutral-tail"),
        JsonPrimitive(77), JsonPrimitive(88), JsonPrimitive(appearanceYear), JsonPrimitive(17001), JsonPrimitive("female"),
        JsonPrimitive(52), JsonPrimitive(27), JsonPrimitive(345), JsonPrimitive("왕도"),
    )

    private class ZeroRangeRandUtil : RandUtil(LiteHashDrbg("rtk-zero-jitter")) {
        val rangeCalls = mutableListOf<Pair<Int, Int>>()

        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            rangeCalls += minInclusive to maxInclusive
            return minInclusive
        }
    }
}
