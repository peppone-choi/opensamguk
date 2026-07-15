package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.LightActionWorld
import opensamguk.logic.util.phpRound
import kotlin.math.log2
import kotlin.math.min

/**
 * Runtime seam for `CreateManyNPC.php:15-95`, `RaiseNPCNation.php:23-278`, and
 * `Scenario/Nation.php:70-185`. Implementations stage rows through the daemon world and recorder.
 */
interface ScenarioStartEventContext : EventActionContext {
    fun hiddenSeed(): String
    fun year(): Int
    fun month(): Int
    fun startYear(): Int
    fun turnterm(): Int
    fun cityConst(): CityConstVariant
    fun generals(): List<General>
    fun cities(): List<City>
    fun nations(): List<Nation>
    fun generalNames(): List<String>
    fun shuffleNpcNationCandidates(cities: List<City>): List<City>
    fun allocateNationId(): Int

    fun stageGeneral(general: BuiltGeneral): Int
    fun stageNation(nation: Nation)
    fun stageDiplomacy(diplomacy: Diplomacy)
    fun stageNationTurn(turn: NationTurn)
    fun stageCity(city: City)
    fun stageNationEnv(nationId: Int, key: String, value: Any?)
    fun pushGlobalActionLog(msg: String)
    fun pushGlobalHistoryLog(msg: String)
    fun pushGlobalHistoryLog(msg: String, type: Int)
}

interface ScenarioStoredIconContext {
    fun storedIcons(): Map<String, Map<String, String>>
    fun iconPath(): String = "."
}

interface ScenarioGeneralPoolContext {
    fun pickGeneralPoolCandidates(rng: RandUtil, count: Int): List<ScenarioGeneralPoolCandidate>
}

interface ScenarioGeneralPoolCandidate {
    val name: String
    val firstStat: Int?
    val picture: String?

    fun generalBuilder(rng: RandUtil): GeneralBuilder
    fun occupyGeneralName(generalId: Int) = Unit
}

class RegNpcAction(
    private val affinity: Int,
    private val name: String,
    private val picture: String?,
    private val nationId: Int,
    private val locatedCity: String?,
    private val leadership: Int,
    private val strength: Int,
    private val intel: Int,
    private val officerLevel: Int,
    private val birth: Int,
    private val death: Int,
    private val ego: String?,
    private val special: String?,
    private val npcText: String?,
) : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx as? ScenarioStartEventContext
            ?: error("RegNPC requires ScenarioStartEventContext")
        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(world.hiddenSeed(), NAME, name, nationId, leadership, strength, intel)),
        )
        val effectiveNationId = nationId.takeIf { id -> id == 0 || world.nations().any { it.id == id } } ?: 0
        val normalizedEgo = ego?.takeIf { it.isNotBlank() && it != "None" }
            ?.let { if ('_' in it) it else "che_$it" }
        val builder = GeneralBuilder(rng, name, effectiveNationId)
            .setStat(leadership, strength, intel)
            .setOfficerLevel(officerLevel)
            .setEgo(normalizedEgo)
            .setSpecialSingle(special)
            .setNPCText(npcText ?: "")
            .setAffinity(affinity)
            .setLifeSpan(birth, death)
        resolveCityId(world, locatedCity)?.let(builder::setCityID)
        val built = builder
            .fillRemainSpecAsZero(world.year(), world.startYear())
            .build(
                world.year(),
                world.month(),
                world.turnterm(),
                cityPool(world.cities()),
                isFictionMode = phpTruthy(world.env["fiction"]),
                onAdultGeneral = { adultName -> pushAdultAppearanceLog(world, adultName) },
            )
            ?.copy(picture = ScenarioPictureResolver.resolve(world, picture, name))
            ?: return
        world.stageGeneral(built)
    }

    companion object {
        const val NAME = "RegNPC"

        fun register(factory: EventActionFactory): EventActionFactory = factory.register(NAME) { args ->
            RegNpcAction(
                affinity = intArg(args, 0, 0),
                name = requiredStringArg(args, 1, NAME),
                picture = nullableStringArg(args, 2),
                nationId = requiredIntArg(args, 3, NAME),
                locatedCity = nullableStringArg(args, 4),
                leadership = requiredIntArg(args, 5, NAME),
                strength = requiredIntArg(args, 6, NAME),
                intel = requiredIntArg(args, 7, NAME),
                officerLevel = requiredIntArg(args, 8, NAME),
                birth = intArg(args, 9, 160),
                death = intArg(args, 10, 300),
                ego = nullableStringArg(args, 11),
                special = nullableStringArg(args, 12),
                npcText = nullableStringArg(args, 13),
            )
        }

        fun resolveCityId(world: ScenarioStartEventContext, value: String?): Int? {
            if (value.isNullOrBlank()) return null
            return value.toIntOrNull() ?: world.cityConst().byName(value)?.id
        }
    }
}

class RegNeutralNpcAction(
    private val affinity: Int,
    private val name: String,
    private val picture: String?,
    private val nationId: Int,
    private val locatedCity: String?,
    private val leadership: Int,
    private val strength: Int,
    private val intel: Int,
    private val birth: Int,
    private val death: Int,
    private val ego: String?,
    private val special: String?,
    private val npcText: String?,
) : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx as? ScenarioStartEventContext
            ?: error("RegNeutralNPC requires ScenarioStartEventContext")
        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(world.hiddenSeed(), NAME, name, nationId, leadership, strength, intel)),
        )
        val effectiveNationId = nationId.takeIf { id -> id == 0 || world.nations().any { it.id == id } } ?: 0
        val normalizedEgo = ego?.takeIf { it.isNotBlank() && it != "None" }
            ?.let { if ('_' in it) it else "che_$it" }
        val builder = GeneralBuilder(rng, name, effectiveNationId)
            .setStat(leadership, strength, intel)
            .setEgo(normalizedEgo)
            .setSpecialSingle(special)
            .setNPCText(npcText ?: "")
            .setAffinity(affinity)
            .setLifeSpan(birth, death)
            .setNPCType(6)
        RegNpcAction.resolveCityId(world, locatedCity)?.let(builder::setCityID)
        val built = builder
            .fillRemainSpecAsZero(world.year(), world.startYear())
            .build(
                world.year(),
                world.month(),
                world.turnterm(),
                cityPool(world.cities()),
                isFictionMode = phpTruthy(world.env["fiction"]),
                onAdultGeneral = { adultName -> pushAdultAppearanceLog(world, adultName) },
            )
            ?.copy(picture = ScenarioPictureResolver.resolve(world, picture, name))
            ?: return
        world.stageGeneral(built)
    }

    companion object {
        const val NAME = "RegNeutralNPC"

        fun register(factory: EventActionFactory): EventActionFactory = factory.register(NAME) { args ->
            RegNeutralNpcAction(
                affinity = intArg(args, 0, 0),
                name = requiredStringArg(args, 1, NAME),
                picture = nullableStringArg(args, 2),
                nationId = requiredIntArg(args, 3, NAME),
                locatedCity = nullableStringArg(args, 4),
                leadership = requiredIntArg(args, 5, NAME),
                strength = requiredIntArg(args, 6, NAME),
                intel = requiredIntArg(args, 7, NAME),
                birth = intArg(args, 8, 160),
                death = intArg(args, 9, 300),
                ego = nullableStringArg(args, 10),
                special = nullableStringArg(args, 11),
                npcText = nullableStringArg(args, 12),
            )
        }
    }
}

class CreateManyNPCAction(
    private val npcCount: Int = 10,
    private val fillCnt: Int = 0,
) : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx as? ScenarioStartEventContext
            ?: error("CreateManyNPC requires ScenarioStartEventContext")
        if (npcCount <= 0 && fillCnt <= 0) return

        val generals = world.generals()
        val rulerNationIds = generals.asSequence()
            .filter { it.npcType < 3 && it.officerLevel == 12 }
            .map { it.nationId }
            .toList()
        val registeredCount = if (fillCnt == 0 || rulerNationIds.isEmpty()) {
            0
        } else {
            val nationSet = rulerNationIds.toSet()
            generals.count { it.nationId in nationSet && it.npcType < 4 }
        }
        val moreGeneralCount = if (fillCnt == 0) 0 else rulerNationIds.size * fillCnt - registeredCount
        val createCount = (npcCount + moreGeneralCount).coerceAtLeast(0)

        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(world.hiddenSeed(), NAME, world.year(), world.month())),
        )
        val pickedNPCs = pickGeneralPoolCandidates(world, rng, createCount)
        val cityPool = cityPool(world.cities())
        val created = ArrayList<BuiltGeneral>(createCount)
        for (pickedNPC in pickedNPCs) {
            val age = rng.nextRangeInt(20, 25)
            val builder = pickedNPC.generalBuilder(rng)
                .setNPCType(3)
                .setNationID(0)
                .setMoney(1000, 1000)
                .setExpDed(0, 0)
                .setLifeSpan(world.year() - age, world.year() + rng.nextRangeInt(10, 50))
            if (pickedNPC.firstStat == null) {
                builder.fillRandomStat(CREATE_PICK_TYPES)
            }
            val built = builder
                .fillRemainSpecAsZero(world.year(), world.startYear())
                .build(
                    world.year(),
                    world.month(),
                    world.turnterm(),
                    cityPool,
                    isFictionMode = phpTruthy(world.env["fiction"]),
                )
                ?.copy(picture = ScenarioPictureResolver.resolve(world, pickedNPC.picture, pickedNPC.name))
                ?: continue
            val generalId = world.stageGeneral(built)
            pickedNPC.occupyGeneralName(generalId)
            created += built
        }

        if (created.size == 1) {
            val npcName = created.single().name
            world.pushGlobalActionLog(
                "<Y>$npcName</>${JosaUtil.pick(npcName, "라")}는 장수가 <S>등장</>하였습니다.",
            )
        } else {
            world.pushGlobalActionLog("장수 <C>${created.size}</>명이 <S>등장</>하였습니다.")
        }
        world.pushGlobalHistoryLog(
            "장수 <C>${created.size}</>명이 <S>등장</>했습니다.",
            LightActionWorld.NOTICE_YEAR_MONTH,
        )
    }

    companion object {
        const val NAME = "CreateManyNPC"
        private val CREATE_PICK_TYPES = linkedMapOf("무" to 0.333, "지" to 0.333, "무지" to 0.334)

        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                CreateManyNPCAction(intArg(args, 0, 10), intArg(args, 1, 0))
            }
    }
}

private fun pushAdultAppearanceLog(world: ScenarioStartEventContext, name: String) {
    world.pushGlobalActionLog("<Y>$name</>${JosaUtil.pick(name, "이")} 성인이 되어 <S>등장</>했습니다.")
}

private fun phpTruthy(value: Any?): Boolean = when (value) {
    null -> false
    JsonNull -> false
    is JsonPrimitive -> phpTruthy(value.content)
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    is String -> value.isNotEmpty() && value != "0"
    else -> true
}

private object ScenarioPictureResolver {
    fun resolve(world: ScenarioStartEventContext, picture: String?, generalName: String): String {
        val showImageLevel = phpInt(world.env["show_img_level"]) ?: 3
        if (showImageLevel < 3) return "default.jpg"

        val iconPath = (world as? ScenarioStoredIconContext)?.iconPath()
            ?: world.env["icon_path"]?.toString()
            ?: "."
        val storedIcons = (world as? ScenarioStoredIconContext)?.storedIcons()
            ?: storedIconsFromEnv(world.env["stored_icons"])

        var picturePath = picture
        val numericPicture = picturePath?.toDoubleOrNull()
        if (numericPicture != null) {
            picturePath = if (numericPicture < 0.0) {
                null
            } else {
                storedIcons["."]?.get(picturePath) ?: null
            }
        } else if (picturePath != null && storedIcons[iconPath]?.values?.contains(picturePath) == true) {
            picturePath = "$iconPath/$picturePath"
        } else if (picturePath == null && storedIcons.isNotEmpty()) {
            picturePath = storedIcons[iconPath]?.get(generalName)?.let { "$iconPath/$it" }
        }
        return picturePath ?: "default.jpg"
    }

    private fun phpInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun storedIconsFromEnv(value: Any?): Map<String, Map<String, String>> =
        when (value) {
            is Map<*, *> -> value.mapNotNull { (key, nested) ->
                key?.toString()?.let { it to storedIconBucket(nested) }
            }.toMap()
            else -> emptyMap()
        }

    private fun storedIconBucket(value: Any?): Map<String, String> =
        when (value) {
            is Map<*, *> -> value.mapNotNull { (key, icon) ->
                if (key == null || icon == null) null else key.toString() to icon.toString()
            }.toMap()
            is List<*> -> value.mapIndexedNotNull { index, icon ->
                icon?.toString()?.let { index.toString() to it }
            }.toMap()
            else -> emptyMap()
        }
}

class RaiseNPCNationAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx as? ScenarioStartEventContext
            ?: error("RaiseNPCNation requires ScenarioStartEventContext")
        val targetCities = world.cities().filter { it.level in 5..6 }.sortedBy { it.id }
        require(targetCities.isNotEmpty()) { "RaiseNPCNation requires level 5-6 cities" }

        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(world.hiddenSeed(), NAME, world.year(), world.month())),
        )
        val averageCity = averageNationCity(rng, targetCities)
        val emptyCities = targetCities.filter { it.nationId == 0 }
        val occupiedCityIds = targetCities.filter { it.nationId != 0 }.map { it.id }
        val averageGeneralCount = averageNationGeneralCount(world)
        val activeNations = world.nations().filter { it.level > 0 }
        val averageTech = if (activeNations.isEmpty()) 0 else {
            (activeNations.sumOf { it.tech } / activeNations.size).toInt()
        }

        val raisedCityIds = mutableListOf<Int>()
        for (emptyCity in world.shuffleNpcNationCandidates(emptyCities)) {
            if (minimumDistance(emptyCity.id, occupiedCityIds) < MIN_DIST_USER_NATION) continue
            if (minimumDistance(emptyCity.id, raisedCityIds) < MIN_DIST_NPC_NATION) continue

            buildNation(
                world = world,
                rng = rng,
                nationId = world.allocateNationId(),
                tech = averageTech,
                baseCity = emptyCity,
                averageCity = averageCity,
                generalCount = averageGeneralCount,
            )
            raisedCityIds += emptyCity.id
        }

        if (raisedCityIds.isNotEmpty()) {
            world.pushGlobalHistoryLog("<L><b>【공지】</b></>공백지에 임의의 국가가 생성되었습니다.")
        }
    }

    private fun averageNationCity(rng: RandUtil, cities: List<City>): AverageCity {
        val occupied = cities.filter { it.nationId != 0 }
        if (occupied.isEmpty()) {
            val picked = rng.choice(cities)
            return AverageCity(
                population = picked.populationMax,
                agriculture = picked.agricultureMax,
                commerce = picked.commerceMax,
                security = picked.securityMax,
                defense = picked.defenseMax,
                wall = picked.wallMax,
            )
        }

        val sorted = occupied.sortedBy {
            it.agriculture + it.commerce + it.security + it.defense + it.wall
        }
        val averaged = if (sorted.size >= 3) {
            val reduceCount = maxOf(1, phpRound(sorted.size / 6.0).toInt())
            sorted.drop(reduceCount).dropLast(reduceCount)
        } else {
            sorted
        }
        return AverageCity(
            population = averaged.sumOf { it.population } / averaged.size,
            agriculture = averaged.sumOf { it.agriculture } / averaged.size,
            commerce = averaged.sumOf { it.commerce } / averaged.size,
            security = averaged.sumOf { it.security } / averaged.size,
            defense = averaged.sumOf { it.defense } / averaged.size,
            wall = averaged.sumOf { it.wall } / averaged.size,
        )
    }

    private fun averageNationGeneralCount(world: ScenarioStartEventContext): Int {
        val generalCounts = world.nations().filter { it.level > 0 }.map { it.gennum }.sorted()
        if (generalCounts.isEmpty()) return GameConst.initialNationGenLimit
        val averaged = if (generalCounts.size >= 3) {
            val reduceCount = maxOf(1, phpRound(generalCounts.size / 6.0).toInt())
            generalCounts.drop(reduceCount).dropLast(reduceCount)
        } else {
            generalCounts
        }
        return phpRound(averaged.sum().toDouble() / averaged.size).toInt()
    }

    private fun buildNation(
        world: ScenarioStartEventContext,
        rng: RandUtil,
        nationId: Int,
        tech: Int,
        baseCity: City,
        averageCity: AverageCity,
        generalCount: Int,
    ) {
        val cityName = world.cityConst().byId(baseCity.id)?.name
            ?: error("unknown city ${baseCity.id}")
        val nationName = "ⓤ$cityName"
        val otherNationIds = world.nations().map { it.id }
        val color = rng.choice(GetNationColors())
        val type = rng.choice(GameConst.availableNationType)
        val scoutMessage = "우리도 할 수 있다! ${cityName}군"

        world.stageNation(
            Nation(
                id = nationId,
                level = NPC_NATION_LEVEL,
                capitalCityId = baseCity.id,
                name = nationName,
                color = color,
                typeCode = type,
                gold = 0,
                rice = GameConst.baserice,
                tech = tech.toDouble(),
                gennum = generalCount,
                meta = linkedMapOf(
                    "bill" to 100,
                    "rate" to 15,
                    "scout" to 0,
                    "war" to 0,
                    "strategic_cmd_limit" to 24,
                    "surlimit" to 72,
                    "aux" to linkedMapOf("can_국기변경" to 1),
                    "gennum" to generalCount,
                ),
            ),
        )
        world.stageCity(baseCity.copy(nationId = nationId))
        world.stageNationEnv(nationId, "scout_msg", scoutMessage)
        for (otherNationId in otherNationIds) {
            world.stageDiplomacy(Diplomacy(me = nationId, you = otherNationId, state = 2, term = 0))
            world.stageDiplomacy(Diplomacy(me = otherNationId, you = nationId, state = 2, term = 0))
        }

        val cityPool = cityPool(world.cities())
        val ruler = GeneralBuilder(rng, "${cityName}태수", nationId)
            .setOfficerLevel(12)
            .setCityID(baseCity.id)
            .setNPCType(6)
            .setMoney(1000, 1000)
            .also { it.fillRandomStat(NATION_PICK_TYPES) }
            .setKillturn(240)
            .fillRemainSpecAsZero(world.year(), world.startYear())
            .build(
                world.year(),
                world.month(),
                world.turnterm(),
                cityPool,
                isFictionMode = phpTruthy(world.env["fiction"]),
            )
            ?: error("NPC nation ruler was not created")
        world.stageGeneral(ruler)

        val pickedNames = pickRandomNames(rng, (generalCount - 1).coerceAtLeast(0), world.generalNames())
        val birthYear = world.year() - 20
        val deadYearMin = world.year() + 10
        for (name in pickedNames) {
            val deadYear = deadYearMin + (60 * (1 - log2(rng.nextRange(1.0, 1024.0)) / 10)).toInt()
            val built = GeneralBuilder(rng, name, nationId)
                .setCityID(baseCity.id)
                .setNPCType(6)
                .setMoney(1000, 1000)
                .setLifeSpan(birthYear, deadYear)
                .also { it.fillRandomStat(NATION_PICK_TYPES) }
                .fillRemainSpecAsZero(world.year(), world.startYear())
                .build(
                    world.year(),
                    world.month(),
                    world.turnterm(),
                    cityPool,
                    isFictionMode = phpTruthy(world.env["fiction"]),
                )
                ?: continue
            world.stageGeneral(built)
        }

        val minChiefLevel = GameConst.getNationChiefLevel(NPC_NATION_LEVEL)
        for (chiefLevel in 12 downTo minChiefLevel) {
            for (turnIndex in 0 until GameConst.maxChiefTurn) {
                world.stageNationTurn(
                    NationTurn(
                        nationId = nationId,
                        officerLevel = chiefLevel,
                        turnIdx = turnIndex,
                        action = "휴식",
                        arg = null,
                        brief = "휴식",
                    ),
                )
            }
        }

        world.stageCity(
            world.cities().single { it.id == baseCity.id }.copy(
                trust = 100.0,
                population = min(baseCity.populationMax, averageCity.population),
                agriculture = min(baseCity.agricultureMax, averageCity.agriculture),
                commerce = min(baseCity.commerceMax, averageCity.commerce),
                security = min(baseCity.securityMax, averageCity.security),
                defense = min(baseCity.defenseMax, averageCity.defense),
                wall = min(baseCity.wallMax, averageCity.wall),
            ),
        )
    }

    private fun minimumDistance(cityId: Int, targets: List<Int>): Int {
        var result = 999
        for (target in targets) {
            result = min(result, CalcCityDistance.calcCityDistance(cityId, target) ?: 999)
        }
        return result
    }

    private data class AverageCity(
        val population: Int,
        val agriculture: Int,
        val commerce: Int,
        val security: Int,
        val defense: Int,
        val wall: Int,
    )

    companion object {
        const val NAME = "RaiseNPCNation"
        private const val NPC_NATION_LEVEL = 2
        private const val MIN_DIST_USER_NATION = 3
        private const val MIN_DIST_NPC_NATION = 2
        private val NATION_PICK_TYPES = linkedMapOf("무" to 1.0, "지" to 1.0)

        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { RaiseNPCNationAction() }
    }
}

private fun intArg(args: List<JsonElement>, index: Int, default: Int): Int =
    (args.getOrNull(index) as? JsonPrimitive)?.content?.toIntOrNull() ?: default

private fun requiredIntArg(args: List<JsonElement>, index: Int, action: String): Int =
    (args.getOrNull(index) as? JsonPrimitive)?.content?.toIntOrNull()
        ?: error("$action argument $index must be an integer")

private fun requiredStringArg(args: List<JsonElement>, index: Int, action: String): String =
    (args.getOrNull(index) as? JsonPrimitive)?.content
        ?: error("$action argument $index must be a string")

private fun nullableStringArg(args: List<JsonElement>, index: Int): String? = when (val arg = args.getOrNull(index)) {
    null, JsonNull -> null
    is JsonPrimitive -> arg.content
    else -> error("argument $index must be scalar or null")
}

private fun cityPool(cities: List<City>): List<GeneralBuilder.CityChoice> =
    cities.sortedBy { it.id }.map { GeneralBuilder.CityChoice(it.id, it.nationId) }

private fun pickRandomNames(rng: RandUtil, count: Int, existingNames: List<String>): List<String> =
    List(count) {
        var loopCount = 0
        while (true) {
            var name = rng.choice(GameConst.randGenFirstName) +
                rng.choice(GameConst.randGenMiddleName) +
                rng.choice(GameConst.randGenLastName)
            val duplicateCount = GENERAL_PREFIXES.sumOf { prefix ->
                existingNames.count { it.startsWith(prefix + name) }
            }
            if (duplicateCount == 0) return@List name
            if (loopCount >= 99 || duplicateCount < 2) {
                name += duplicateCount + 1
                return@List name
            }
            loopCount += 1
        }
        error("unreachable")
    }

private val GENERAL_PREFIXES = listOf("", "ⓝ", "ⓝ", "ⓜ", "ⓖ", "㉥", "ⓤ", "ⓞ")

private fun pickGeneralPoolCandidates(
    world: ScenarioStartEventContext,
    rng: RandUtil,
    count: Int,
): List<ScenarioGeneralPoolCandidate> =
    (world as? ScenarioGeneralPoolContext)?.pickGeneralPoolCandidates(rng, count)
        ?: pickRandomNames(rng, count, world.generalNames()).map(::RandomNameGeneralPoolCandidate)

private data class RandomNameGeneralPoolCandidate(
    override val name: String,
) : ScenarioGeneralPoolCandidate {
    override val firstStat: Int? = null
    override val picture: String? = null

    override fun generalBuilder(rng: RandUtil): GeneralBuilder = GeneralBuilder(rng, name, 0)
}
