package opensamguk.logic.actions

import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.evaluateConstraints
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CommandContractMatrixTest {
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)
    private val env = WorldEnvBuilder.envMap(YEAR, START_YEAR) + mapOf(
        "join_mode" to "normal",
        "__isNeighbor" to true,
        "__atWarWithDest" to true,
        "__atWar" to true,
        "__disallowDiplomacyHit" to false,
        "__strategicCmdLimit" to 99,
        "__nearCityIds" to listOf(DEST_CITY_ID, OWN_DEST_CITY_ID),
    )

    @Test
    fun `every registered command has a success and failure contract row`() {
        val missing = registryKeys().filterNot { it in COMMAND_CONTRACTS }
        assertTrue(
            missing.isEmpty(),
            "Every CommandRegistry key needs an explicit success/failure contract row: $missing",
        )
    }

    @Test
    fun `contract rows point at real registry commands`() {
        val keys = registryKeys().toSet()
        val stale = COMMAND_CONTRACTS.keys.filterNot { it in keys }
        assertTrue(stale.isEmpty(), "Command contract rows must not reference stale commands: $stale")
    }

    @Test
    fun `every command resolves to its own definition and parses valid and invalid args safely`() {
        val failures = mutableListOf<String>()
        for (key in registryKeys()) {
            val definition = registry.resolve(key)
            if (definition === RestAction || definition.key != key) {
                failures += "$key resolved to ${definition.key}"
                continue
            }
            val contract = COMMAND_CONTRACTS.getValue(key)
            runCatching { definition.parseArgs(contract.validArgs) }
                .onSuccess { parsed ->
                    if (definition.argsSchema.isNotEmpty() && !parsed.keys.containsAll(definition.argsSchema.keys)) {
                        failures += "$key valid parse dropped schema keys ${definition.argsSchema.keys - parsed.keys}"
                    }
                }
                .onFailure { failures += "$key valid parse threw ${it::class.simpleName}: ${it.message}" }
            runCatching { definition.parseArgs(contract.invalidArgs) }
                .onFailure { failures += "$key invalid parse threw ${it::class.simpleName}: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @TestFactory
    fun `each command has an allowing full-condition success fixture`(): List<DynamicTest> =
        COMMAND_CONTRACTS.map { (key, contract) ->
            DynamicTest.dynamicTest("$key success allows FULL constraints") {
                assertSuccessContract(key, contract)
            }
        }

    @TestFactory
    fun `each command has a failure or no-local-failure contract`(): List<DynamicTest> =
        COMMAND_CONTRACTS.map { (key, contract) ->
            DynamicTest.dynamicTest("$key failure contract is enforced") {
                assertFailureContract(key, contract)
            }
        }

    private fun assertSuccessContract(key: String, contract: CommandContract) {
        val definition = registry.resolve(key)
        val args = definition.parseArgs(contract.validArgs)
        val ctx = constraintContext(args = args, mode = ConstraintMode.FULL, envOverride = contract.envOverride)
        val result = evaluateConstraints(definition.buildConstraints(ctx), ctx, viewFor(contract.successFixture))
        assertEquals(ConstraintResult.Allow, result)
    }

    private fun assertFailureContract(key: String, contract: CommandContract) {
        val definition = registry.resolve(key)
        val invalidArgs = definition.parseArgs(contract.invalidArgs)
        when (contract.failureMode) {
            FailureMode.CONSTRAINT_DENY -> {
                val ctx = constraintContext(args = invalidArgs, mode = ConstraintMode.PRECHECK, envOverride = contract.envOverride)
                val constraints = definition.buildConstraints(ctx)
                val result = evaluateConstraints(constraints, ctx, viewFor(contract.failureFixture))
                assertTrue(constraints.isNotEmpty(), "$key has no constraints, so it needs ARG_REJECTED or NO_LOCAL_FAILURE")
                assertNotEquals(ConstraintResult.Allow, result)
            }
            FailureMode.ARG_REJECTED -> {
                val validArgs = definition.parseArgs(contract.validArgs)
                assertNotEquals(validArgs, invalidArgs, "$key invalid args must be rejected by parseArgs")
            }
            FailureMode.NO_LOCAL_FAILURE -> {
                val ctx = constraintContext(args = invalidArgs, mode = ConstraintMode.PRECHECK, envOverride = contract.envOverride)
                val constraints = definition.buildConstraints(ctx)
                assertTrue(constraints.isEmpty(), "$key marked no-local-failure but declares constraints")
                assertEquals(ConstraintResult.Allow, evaluateConstraints(constraints, ctx, viewFor(contract.successFixture)))
            }
        }
    }

    @Test
    fun `every command success fixture is covered by the dynamic tests`() {
        for ((key, contract) in COMMAND_CONTRACTS) {
            val definition = registry.resolve(key)
            val args = definition.parseArgs(contract.validArgs)
            val ctx = constraintContext(args = args, mode = ConstraintMode.FULL, envOverride = contract.envOverride)
            assertEquals(ConstraintResult.Allow, evaluateConstraints(definition.buildConstraints(ctx), ctx, viewFor(contract.successFixture)), key)
        }
    }

    @Test
    fun `command test sources name every command at least once`() {
        val testSource = Files.walk(repoRoot())
            .filter { it.toString().endsWith(".kt") }
            .filter { it.toString().contains("/src/test/") }
            .use { paths -> paths.map { Files.readString(it) }.toList().joinToString("\n") }
        val missing = registryKeys().filterNot { it in testSource }
        assertTrue(missing.isEmpty(), "Every command needs at least one command-specific test reference: $missing")
    }

    private fun registryKeys(): List<String> {
        val text = Files.readString(repoRoot().resolve("logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt"))
        val regex = Regex("\"((?:che|cr|event)_[^\"]+)\"\\s*->")
        return regex.findAll(text).map { it.groupValues[1] }.toList().distinct()
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repo root from ${Path.of("").toAbsolutePath()}")
        }
        return path
    }

    private fun constraintContext(
        args: Map<String, Any?>,
        mode: ConstraintMode,
        envOverride: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(
        actorId = ACTOR_ID,
        cityId = (args["cityID"] as? Number)?.toInt() ?: CITY_ID,
        nationId = (args["nationID"] as? Number)?.toInt() ?: NATION_ID,
        destGeneralId = (args["destGeneralID"] as? Number)?.toInt() ?: DEST_GENERAL_ID,
        destCityId = (args["destCityID"] as? Number)?.toInt() ?: DEST_CITY_ID,
        destNationId = (args["destNationID"] as? Number)?.toInt() ?: DEST_NATION_ID,
        args = args,
        env = env + envOverride,
        mode = mode,
    )

    private fun viewFor(fixture: StateFixture): MemoryStateView = when (fixture) {
        StateFixture.DEFAULT_SUCCESS -> defaultSuccessView()
        StateFixture.NEUTRAL_SUCCESS -> neutralSuccessView()
        StateFixture.WANDERING_SUCCESS -> wanderingSuccessView()
        StateFixture.PEACE_DIPLOMACY_SUCCESS -> defaultSuccessView(diplomacyState = 2)
        StateFixture.NON_AGGRESSION_SUCCESS -> defaultSuccessView(diplomacyState = 7)
        StateFixture.DECLARATION_SUCCESS -> defaultSuccessView(diplomacyState = 1)
        StateFixture.STRATEGIC_READY_SUCCESS -> defaultSuccessView(strategicCmdLimit = 0)
        StateFixture.STRATEGIC_DECLARATION_SUCCESS -> defaultSuccessView(diplomacyState = 1, strategicCmdLimit = 0)
        StateFixture.OFFICER_SUCCESS -> defaultSuccessView(actorOfficerLevel = 5)
        StateFixture.AWAY_FROM_CAPITAL_SUCCESS -> defaultSuccessView(capitalCityId = OWN_DEST_CITY_ID)
        StateFixture.DEFAULT_FAILURE -> defaultFailureView()
        StateFixture.NO_CREW_FAILURE -> defaultSuccessView(actorCrew = 0)
        StateFixture.NO_TRADER_FAILURE -> defaultSuccessView(actorNpcType = 0, cityTrade = null)
    }

    private fun defaultSuccessView(
        actorCrew: Int = 10_000,
        actorNpcType: Int = 2,
        cityTrade: Int? = 100,
        diplomacyState: Int = 0,
        actorOfficerLevel: Int = 12,
        capitalCityId: Int? = CITY_ID,
        strategicCmdLimit: Int = 99,
    ): MemoryStateView = MemoryStateView(
        generals = linkedMapOf(
            ACTOR_ID to general(ACTOR_ID, nationId = NATION_ID, cityId = CITY_ID, officerLevel = actorOfficerLevel, crew = actorCrew, npcType = actorNpcType),
            DEST_GENERAL_ID to general(DEST_GENERAL_ID, nationId = NATION_ID, cityId = CITY_ID, officerLevel = 1),
            3 to general(3, nationId = DEST_NATION_ID, cityId = DEST_CITY_ID, officerLevel = 12),
        ),
        cities = linkedMapOf(
            CITY_ID to city(CITY_ID, nationId = NATION_ID, level = 6, frontState = 1, trade = cityTrade),
            DEST_CITY_ID to city(DEST_CITY_ID, nationId = DEST_NATION_ID, level = 6, frontState = 1),
            OWN_DEST_CITY_ID to city(OWN_DEST_CITY_ID, nationId = NATION_ID, level = 7, frontState = 1),
        ),
        nations = linkedMapOf(
            NATION_ID to nation(NATION_ID, capitalCityId = capitalCityId, level = 9, strategicCmdLimit = strategicCmdLimit),
            DEST_NATION_ID to nation(DEST_NATION_ID, capitalCityId = DEST_CITY_ID, level = 9),
        ),
        env = env,
        diplomacy = listOf(
            Diplomacy(NATION_ID, DEST_NATION_ID, state = diplomacyState, term = 12),
            Diplomacy(DEST_NATION_ID, NATION_ID, state = diplomacyState, term = 12),
        ),
    )

    private fun neutralSuccessView(): MemoryStateView = MemoryStateView(
        generals = linkedMapOf(
            ACTOR_ID to general(ACTOR_ID, nationId = 0, cityId = CITY_ID, officerLevel = 0, npcType = 2),
            DEST_GENERAL_ID to general(DEST_GENERAL_ID, nationId = NATION_ID, cityId = CITY_ID, officerLevel = 12),
            3 to general(3, nationId = DEST_NATION_ID, cityId = DEST_CITY_ID, officerLevel = 12),
        ),
        cities = linkedMapOf(
            CITY_ID to city(CITY_ID, nationId = 0, level = 6, frontState = 0),
            DEST_CITY_ID to city(DEST_CITY_ID, nationId = DEST_NATION_ID, level = 6, frontState = 1),
            OWN_DEST_CITY_ID to city(OWN_DEST_CITY_ID, nationId = NATION_ID, level = 7, frontState = 1),
        ),
        nations = linkedMapOf(
            NATION_ID to nation(NATION_ID, capitalCityId = CITY_ID, level = 9),
            DEST_NATION_ID to nation(DEST_NATION_ID, capitalCityId = DEST_CITY_ID, level = 9),
        ),
        env = env,
        diplomacy = listOf(
            Diplomacy(NATION_ID, DEST_NATION_ID, state = 0, term = 12),
            Diplomacy(DEST_NATION_ID, NATION_ID, state = 0, term = 12),
        ),
    )

    private fun wanderingSuccessView(): MemoryStateView = MemoryStateView(
        generals = linkedMapOf(
            ACTOR_ID to general(ACTOR_ID, nationId = NATION_ID, cityId = CITY_ID, officerLevel = 12),
            DEST_GENERAL_ID to general(DEST_GENERAL_ID, nationId = NATION_ID, cityId = CITY_ID, officerLevel = 1),
            3 to general(3, nationId = NATION_ID, cityId = CITY_ID, officerLevel = 1),
        ),
        cities = linkedMapOf(
            CITY_ID to city(CITY_ID, nationId = 0, level = 6, frontState = 0),
            DEST_CITY_ID to city(DEST_CITY_ID, nationId = DEST_NATION_ID, level = 6, frontState = 1),
            OWN_DEST_CITY_ID to city(OWN_DEST_CITY_ID, nationId = NATION_ID, level = 7, frontState = 1),
        ),
        nations = linkedMapOf(
            NATION_ID to nation(NATION_ID, capitalCityId = 0, level = 0, gennum = 3),
            DEST_NATION_ID to nation(DEST_NATION_ID, capitalCityId = DEST_CITY_ID, level = 9),
        ),
        env = env,
    )

    private fun defaultFailureView(): MemoryStateView = MemoryStateView(
        generals = linkedMapOf(
            ACTOR_ID to general(
                ACTOR_ID,
                nationId = 0,
                cityId = CITY_ID,
                officerLevel = 0,
                gold = 0,
                rice = 0,
                age = 0,
                special = "None",
                special2 = "None",
            ),
        ),
        cities = linkedMapOf(CITY_ID to city(CITY_ID, nationId = DEST_NATION_ID, level = 1, supplyState = 0, frontState = 0)),
        nations = emptyMap(),
        env = emptyMap(),
        diplomacy = emptyList(),
    )

    private fun general(
        id: Int,
        nationId: Int,
        cityId: Int,
        officerLevel: Int,
        gold: Int = 100_000,
        rice: Int = 100_000,
        crew: Int = 10_000,
        npcType: Int = 2,
        troop: Int? = null,
        age: Int = 60,
        special: String = "상재",
        special2: String = "일기",
    ) = General(
        id = id,
        nationId = nationId,
        cityId = cityId,
        leadership = 90,
        strength = 90,
        intel = 90,
        injury = 0,
        experience = 20_000.0,
        dedication = 20_000.0,
        officerLevel = officerLevel,
        gold = gold,
        rice = rice,
        crew = crew,
        train = 0.0,
        atmos = 0.0,
        crewTypeId = 1100,
        troop = troop ?: id,
        npcType = npcType,
        officerCity = cityId,
        meta = linkedMapOf(
            "explevel" to 300,
            "dedlevel" to 300,
            "leadership_exp" to 10_000,
            "strength_exp" to 10_000,
            "intel_exp" to 10_000,
            "age" to age,
            "special" to special,
            "special2" to special2,
            "makelimit" to 0,
        ),
    )

    private fun city(
        id: Int,
        nationId: Int,
        level: Int,
        supplyState: Int = 1,
        frontState: Int = 1,
        trade: Int? = 100,
    ) = City(
        id = id,
        nationId = nationId,
        level = level,
        commerce = 1_000,
        commerceMax = 9_000,
        agriculture = 1_000,
        agricultureMax = 9_000,
        supplyState = supplyState,
        frontState = frontState,
        trust = 80.0,
        security = 1_000,
        securityMax = 9_000,
        defense = 1_000,
        defenseMax = 9_000,
        wall = 1_000,
        wallMax = 9_000,
        population = 100_000,
        populationMax = 300_000,
        trade = trade,
        region = 1,
    )

    private fun nation(
        id: Int,
        capitalCityId: Int?,
        level: Int,
        gennum: Int = 20,
        strategicCmdLimit: Int = 99,
    ) = Nation(
        id = id,
        level = level,
        capitalCityId = capitalCityId,
        name = "세력$id",
        color = "1",
        gold = 100_000_000,
        rice = 100_000_000,
        power = 10_000,
        tech = 10_000.0,
        gennum = gennum,
        capset = 0,
        meta = linkedMapOf(
            "gennum" to gennum,
            "capset" to 0,
            "strategic_cmd_limit" to strategicCmdLimit,
            "surlimit" to 0,
            "secretlimit" to 99,
            "war" to 0,
            "scout" to 0,
            "aux" to linkedMapOf(
                "can_국호변경" to 1,
                "can_국기변경" to 1,
                "can_무작위수도이전" to 1,
            ),
        ),
    )

    private data class CommandContract(
        val validArgs: Map<String, Any?> = emptyMap(),
        val invalidArgs: Map<String, Any?> = mapOf("destCityID" to 0, "destGeneralID" to 0, "destNationID" to 0, "amount" to -1),
        val successFixture: StateFixture = StateFixture.DEFAULT_SUCCESS,
        val failureMode: FailureMode = FailureMode.CONSTRAINT_DENY,
        val failureFixture: StateFixture = StateFixture.DEFAULT_FAILURE,
        val envOverride: Map<String, Any?> = emptyMap(),
    )

    private enum class StateFixture {
        DEFAULT_SUCCESS,
        NEUTRAL_SUCCESS,
        WANDERING_SUCCESS,
        PEACE_DIPLOMACY_SUCCESS,
        NON_AGGRESSION_SUCCESS,
        DECLARATION_SUCCESS,
        STRATEGIC_READY_SUCCESS,
        STRATEGIC_DECLARATION_SUCCESS,
        OFFICER_SUCCESS,
        AWAY_FROM_CAPITAL_SUCCESS,
        DEFAULT_FAILURE,
        NO_CREW_FAILURE,
        NO_TRADER_FAILURE,
    }

    private enum class FailureMode {
        CONSTRAINT_DENY,
        ARG_REJECTED,
        NO_LOCAL_FAILURE,
    }

    companion object {
        private const val YEAR = 200
        private const val START_YEAR = 181
        private const val ACTOR_ID = 1
        private const val DEST_GENERAL_ID = 2
        private const val FOREIGN_GENERAL_ID = 3
        private const val CITY_ID = 1
        private const val DEST_CITY_ID = 18
        private const val OWN_DEST_CITY_ID = 36
        private const val NATION_ID = 1
        private const val DEST_NATION_ID = 2
        private const val LATE_REL_YEAR = 20

        private val COMMON_DEST_CITY = mapOf("destCityID" to DEST_CITY_ID)
        private val COMMON_OWN_DEST_CITY = mapOf("destCityID" to OWN_DEST_CITY_ID)
        private val COMMON_DEST_GENERAL = mapOf("destGeneralID" to DEST_GENERAL_ID)
        private val COMMON_FOREIGN_GENERAL = mapOf("destGeneralID" to FOREIGN_GENERAL_ID)
        private val COMMON_DEST_NATION = mapOf("destNationID" to DEST_NATION_ID)
        private val COMMON_AMOUNT = mapOf("amount" to 1_000)
        private val COMMON_LATE_REL_YEAR = mapOf("relYear" to LATE_REL_YEAR)

        private val COMMAND_CONTRACTS: Map<String, CommandContract> = linkedMapOf(
            "che_상업투자" to CommandContract(),
            "che_농지개간" to CommandContract(),
            "che_성벽보수" to CommandContract(),
            "che_수비강화" to CommandContract(),
            "che_치안강화" to CommandContract(),
            "che_기술연구" to CommandContract(),
            "che_정착장려" to CommandContract(),
            "che_주민선정" to CommandContract(),
            "che_물자조달" to CommandContract(COMMON_DEST_CITY + COMMON_AMOUNT),
            "che_군량매매" to CommandContract(mapOf("buyRice" to true, "amount" to 1_000)),
            "che_징병" to CommandContract(mapOf("crewType" to 1100, "amount" to 1_000)),
            "che_모병" to CommandContract(mapOf("crewType" to 1100, "amount" to 1_000)),
            "che_훈련" to CommandContract(),
            "cr_맹훈련" to CommandContract(),
            "che_사기진작" to CommandContract(),
            "che_소집해제" to CommandContract(failureFixture = StateFixture.NO_CREW_FAILURE),
            "che_이동" to CommandContract(COMMON_DEST_CITY),
            "che_집합" to CommandContract(COMMON_DEST_CITY),
            "che_임관" to CommandContract(COMMON_DEST_NATION + COMMON_LATE_REL_YEAR, successFixture = StateFixture.NEUTRAL_SUCCESS),
            "che_장수대상임관" to CommandContract(COMMON_FOREIGN_GENERAL + COMMON_DEST_NATION + COMMON_LATE_REL_YEAR, successFixture = StateFixture.NEUTRAL_SUCCESS),
            "che_하야" to CommandContract(successFixture = StateFixture.OFFICER_SUCCESS),
            "che_방랑" to CommandContract(COMMON_LATE_REL_YEAR + mapOf("wanderableDiplomacyExists" to true)),
            "che_랜덤임관" to CommandContract(
                successFixture = StateFixture.NEUTRAL_SUCCESS,
                failureFixture = StateFixture.DEFAULT_SUCCESS,
            ),
            "che_은퇴" to CommandContract(),
            "che_등용" to CommandContract(COMMON_FOREIGN_GENERAL),
            "che_거병" to CommandContract(
                successFixture = StateFixture.NEUTRAL_SUCCESS,
                failureFixture = StateFixture.DEFAULT_SUCCESS,
            ),
            "che_건국" to CommandContract(mapOf("nationName" to "새나라", "colorType" to 1), successFixture = StateFixture.WANDERING_SUCCESS),
            "cr_건국" to CommandContract(mapOf("nationName" to "새나라", "colorType" to 1), successFixture = StateFixture.WANDERING_SUCCESS),
            "che_무작위건국" to CommandContract(mapOf("nationName" to "새나라"), successFixture = StateFixture.WANDERING_SUCCESS),
            "che_감축" to CommandContract(COMMON_OWN_DEST_CITY),
            "che_증축" to CommandContract(),
            "che_발령" to CommandContract(COMMON_DEST_GENERAL + COMMON_OWN_DEST_CITY),
            "che_포상" to CommandContract(COMMON_DEST_GENERAL + mapOf("isGold" to true, "amount" to 1_000)),
            "che_국호변경" to CommandContract(mapOf("nationName" to "새국호")),
            "che_국기변경" to CommandContract(mapOf("colorType" to 1)),
            "che_천도" to CommandContract(COMMON_OWN_DEST_CITY),
            "che_무작위수도이전" to CommandContract(envOverride = mapOf("year" to START_YEAR, "startYear" to START_YEAR)),
            "che_급습" to CommandContract(
                COMMON_DEST_NATION,
                successFixture = StateFixture.STRATEGIC_DECLARATION_SUCCESS,
            ),
            "che_몰수" to CommandContract(
                COMMON_DEST_GENERAL + mapOf("isGold" to true, "amount" to 1_000),
                envOverride = COMMON_LATE_REL_YEAR,
            ),
            "che_물자원조" to CommandContract(COMMON_DEST_NATION + mapOf("amountList" to listOf(1_000, 0))),
            "che_백성동원" to CommandContract(COMMON_OWN_DEST_CITY, successFixture = StateFixture.STRATEGIC_READY_SUCCESS),
            "che_부대탈퇴지시" to CommandContract(COMMON_DEST_GENERAL),
            "che_수몰" to CommandContract(COMMON_DEST_CITY, successFixture = StateFixture.STRATEGIC_READY_SUCCESS),
            "che_의병모집" to CommandContract(
                COMMON_DEST_CITY,
                successFixture = StateFixture.STRATEGIC_READY_SUCCESS,
                envOverride = COMMON_LATE_REL_YEAR,
            ),
            "che_이호경식" to CommandContract(COMMON_DEST_NATION, successFixture = StateFixture.STRATEGIC_READY_SUCCESS),
            "che_초토화" to CommandContract(COMMON_OWN_DEST_CITY),
            "che_피장파장" to CommandContract(
                COMMON_DEST_NATION + mapOf("commandType" to "che_몰수"),
                successFixture = StateFixture.STRATEGIC_READY_SUCCESS,
            ),
            "che_필사즉생" to CommandContract(successFixture = StateFixture.STRATEGIC_READY_SUCCESS),
            "che_허보" to CommandContract(COMMON_DEST_CITY, successFixture = StateFixture.STRATEGIC_READY_SUCCESS),
            "event_극병연구" to CommandContract(),
            "event_무희연구" to CommandContract(),
            "event_상병연구" to CommandContract(),
            "event_화륜차연구" to CommandContract(),
            "event_원융노병연구" to CommandContract(),
            "event_대검병연구" to CommandContract(),
            "event_화시병연구" to CommandContract(),
            "event_음귀병연구" to CommandContract(),
            "event_산저병연구" to CommandContract(),
            "che_출병" to CommandContract(COMMON_DEST_CITY),
            "che_증여" to CommandContract(COMMON_DEST_GENERAL + mapOf("isGold" to true, "amount" to 1_000)),
            "che_헌납" to CommandContract(mapOf("isGold" to true, "amount" to 1_000)),
            "che_장비매매" to CommandContract(
                mapOf("itemType" to "weapon", "itemCode" to "che_무기_01_단도"),
                failureMode = FailureMode.ARG_REJECTED,
            ),
            "che_불가침제의" to CommandContract(
                COMMON_DEST_NATION + mapOf("year" to YEAR + 1, "month" to 1),
                successFixture = StateFixture.PEACE_DIPLOMACY_SUCCESS,
            ),
            "che_종전제의" to CommandContract(COMMON_DEST_NATION),
            "che_불가침파기제의" to CommandContract(COMMON_DEST_NATION, successFixture = StateFixture.NON_AGGRESSION_SUCCESS),
            "che_선전포고" to CommandContract(COMMON_DEST_NATION, successFixture = StateFixture.PEACE_DIPLOMACY_SUCCESS),
            "che_불가침수락" to CommandContract(
                COMMON_DEST_NATION + COMMON_FOREIGN_GENERAL + mapOf("srcMessageId" to 1, "year" to YEAR + 1, "month" to 1),
                successFixture = StateFixture.PEACE_DIPLOMACY_SUCCESS,
            ),
            "che_종전수락" to CommandContract(COMMON_DEST_NATION + COMMON_FOREIGN_GENERAL + mapOf("srcMessageId" to 1)),
            "che_불가침파기수락" to CommandContract(
                COMMON_DEST_NATION + COMMON_FOREIGN_GENERAL + mapOf("srcMessageId" to 1),
                successFixture = StateFixture.NON_AGGRESSION_SUCCESS,
            ),
            "che_NPC능동" to CommandContract(
                mapOf("optionText" to "순간이동", "destCityID" to DEST_CITY_ID),
                failureMode = FailureMode.ARG_REJECTED,
            ),
            "che_귀환" to CommandContract(successFixture = StateFixture.AWAY_FROM_CAPITAL_SUCCESS),
            "che_인재탐색" to CommandContract(),
            "che_견문" to CommandContract(failureMode = FailureMode.NO_LOCAL_FAILURE),
            "che_해산" to CommandContract(successFixture = StateFixture.WANDERING_SUCCESS),
            "che_요양" to CommandContract(failureMode = FailureMode.NO_LOCAL_FAILURE),
            "che_선양" to CommandContract(COMMON_DEST_GENERAL),
            "che_화계" to CommandContract(COMMON_DEST_CITY),
            "che_파괴" to CommandContract(COMMON_DEST_CITY),
            "che_탈취" to CommandContract(COMMON_DEST_CITY),
            "che_선동" to CommandContract(COMMON_DEST_CITY),
            "che_첩보" to CommandContract(COMMON_DEST_CITY),
            "che_단련" to CommandContract(),
            "che_접경귀환" to CommandContract(),
            "che_강행" to CommandContract(COMMON_DEST_CITY),
            "che_숙련전환" to CommandContract(mapOf("srcArmType" to 1, "destArmType" to 2)),
            "che_전투태세" to CommandContract(),
            "che_모반시도" to CommandContract(successFixture = StateFixture.OFFICER_SUCCESS),
            "che_전투특기초기화" to CommandContract(),
            "che_내정특기초기화" to CommandContract(),
            "che_등용수락" to CommandContract(
                COMMON_FOREIGN_GENERAL + COMMON_DEST_NATION + COMMON_LATE_REL_YEAR + mapOf("srcMessageId" to 1),
                successFixture = StateFixture.NEUTRAL_SUCCESS,
            ),
            "cr_인구이동" to CommandContract(COMMON_OWN_DEST_CITY + COMMON_AMOUNT),
        )
    }
}
