package opensamguk.logic.war.hwiha

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** 코드 상수와 PROVISIONAL 정본 파일이 갈라지지 않게 한다 — 한쪽만 고치면 빨개진다. */
class HwihaS3ProvisionalTest {
    private val root: JsonObject by lazy {
        var at: Path? = Path.of("").toAbsolutePath()
        while (at != null && !Files.isDirectory(at.resolve("data/curated"))) at = at.parent
        val file = requireNotNull(at).resolve("data/curated/han/hwiha-s3-provisional-v1.json")
        Json.parseToJsonElement(Files.readString(file)).jsonObject
    }
    private fun long(section: String, key: String) = root.getValue(section).jsonObject.getValue(key).jsonPrimitive.long

    @Test fun `every section is marked provisional pending the user's decision`() {
        val marker = "PROVISIONAL — 사용자 결정 대기"
        assertEquals(marker, root.getValue("status").jsonPrimitive.content)
        for (section in listOf("encounter", "encounterRecovery", "siege", "salary", "reward", "npcDeploy", "reactions", "resupply"))
            assertEquals(marker, root.getValue(section).jsonObject.getValue("status").jsonPrimitive.content, section)
    }

    @Test fun `runtime constants equal the provisional ledger`() {
        val p = HwihaS3Provisional
        assertEquals(p.GARRISON_RATION_PER_SOLDIER_TURN, long("siege", "garrisonRationPerSoldierTurn"))
        assertEquals(p.BESIEGER_MIN_PROVISION_MONTHS.toLong(), long("siege", "besiegerMinProvisionMonths"))
        assertEquals(p.SURRENDER_DEMAND_MAX_MORALE.toLong(), long("siege", "surrenderDemandMaxMorale"))
        assertEquals(p.SURRENDER_DEMAND_MAX_TRUST.toLong(), long("siege", "surrenderDemandMaxTrust"))
        assertEquals(p.ASSAULT_MAX_WALL_TOKENS.toLong(), long("siege", "assaultMaxWallTokens"))
        assertEquals(p.ASSAULT_MIN_SIEGE_TURNS.toLong(), long("siege", "assaultMinSiegeTurns"))
        assertEquals(p.DEPLOY_LOAD_MONTHS.toLong(), long("rations", "deployLoadMonths"))
        assertEquals(p.CONVOY_TARGET_MONTHS.toLong(), long("rations", "convoyTargetMonths"))
        assertEquals(p.CAPTURE_GARRISON_DEFENCE_MAX_PERCENT.toLong(), long("siege", "captureGarrisonDefenceMaxPercent"))
        assertEquals(p.CAPTURE_GARRISON_MAX_CORPS_PERCENT.toLong(), long("siege", "captureGarrisonMaxCorpsPercent"))
        assertEquals(p.ASSAULT_GARRISON_TRAINING.toLong(), long("siege", "assaultGarrisonTraining"))
        assertEquals(p.ASSAULT_GARRISON_LEADERSHIP.toLong(), long("siege", "assaultGarrisonLeadership"))
        assertEquals(p.ASSAULT_GARRISON_ATTACK.toLong(), long("siege", "assaultGarrisonAttack"))
        assertEquals(p.ASSAULT_GARRISON_DEFENCE.toLong(), long("siege", "assaultGarrisonDefence"))
        assertEquals(p.ASSAULT_GARRISON_RANGE.toLong(), long("siege", "assaultGarrisonRange"))
        assertEquals(p.ASSAULT_MAX_WALL_BONUS_PERCENT.toLong(), long("siege", "assaultMaxWallBonusPercent"))
        assertEquals(p.NPC_ASSAULT_MIN_RATIO.toLong(), long("siege", "npcAssaultMinRatio"))
        assertEquals(p.SIEGE_TIMELINE_MAX.toLong(), long("siege", "siegeTimelineMax"))
        assertEquals(p.SALARY_MONEY_PER_RENOWN_COST, long("salary", "salaryMoneyPerRenownCost"))
        assertEquals(p.UNIT_RESUPPLY_TARGET_MONTHS.toLong(), long("resupply", "unitResupplyTargetMonths"))
        assertEquals(p.GRAIN_PER_PROVISION, long("resupply", "grainPerProvision"))
        assertEquals(p.GRAIN_PER_PROVISION, p.GARRISON_RATION_PER_SOLDIER_TURN * 3, "one soldier-month of grain")
        assertEquals(p.UNPAID_SALARY_LOYALTY_LOSS.toLong(), long("salary", "unpaidSalaryLoyaltyLoss"))
        assertEquals(p.REWARD_MONEY_PER_LOYALTY, long("reward", "rewardMoneyPerLoyalty"))
        assertEquals(p.REWARD_MAX_LOYALTY_GAIN.toLong(), long("reward", "rewardMaxLoyaltyGain"))
        assertEquals(p.ENCOUNTER_UNAVAILABLE_RETRY_PHASES.toLong(), long("encounterRecovery", "unavailableRetryPhases"))
        assertEquals(p.NPC_DEPLOY_MAX_EDGES.toLong(), long("npcDeploy", "npcDeployMaxEdges"))
        assertEquals(p.NPC_DEPLOY_MIN_RATIO.toLong(), long("npcDeploy", "npcDeployMinRatio"))
        assertEquals(p.NPC_RELIEF_MIN_RATIO_PERCENT, long("npcDeploy", "npcReliefMinRatioPercent"))
    }

    @Test fun `npc deployment never asks for less than the approved encirclement ratio`() {
        assertTrue(HwihaS3Provisional.NPC_DEPLOY_MIN_RATIO >= HwihaSiegeRules.MINIMUM_ATTACKER_RATIO)
    }
}
