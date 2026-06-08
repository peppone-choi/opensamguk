package opensamguk.gameapi.controller

import opensamguk.common.constants.GameUnitConst
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.getExpLevel
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.BattleSimGeneralInput
import opensamguk.logic.war.BattleSimInput
import opensamguk.logic.war.BattleSimPreview
import opensamguk.logic.war.BattleSimResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * D3-01 — read-only battle simulator adapter.
 *
 * PHP grand truth: `legacy/devsam-core/hwe/j_simulate_battle.php:243-249,366-476,500-582`.
 * The old endpoint fabricated `winner`/damage/turns/log with raw Kotlin `.random()`. This adapter instead
 * resolves live read rows and delegates to [BattleSimPreview], which reuses the same outer `processWar`
 * wrapper as real `che_출병`. It never writes; invalid or missing rows are surfaced honestly.
 */
@RestController
@RequestMapping("/api")
class SimulatorController(
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val nations: NationReadRepository,
    private val world: WorldStateReadRepository,
    private val pipeline: GeneralActionPipeline,
) {

    @PostMapping("/simulate-battle")
    fun simulateBattle(@RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val attackerId = body.intArg("attackerGeneralId") ?: body.intArg("attackerId")
            ?: return badRequest("attackerGeneralId required")
        val defenderId = body.intArg("defenderGeneralId") ?: body.intArg("defenderId")
            ?: return badRequest("defenderGeneralId required")
        if (attackerId == defenderId) return badRequest("attacker and defender must differ")

        val attacker = generals.findById(attackerId).orElse(null)
            ?: return notFound("attacker not found")
        val defender = generals.findById(defenderId).orElse(null)
            ?: return notFound("defender not found")
        val attackerCity = cities.findById(attacker.cityId).orElse(null)
            ?: return notFound("attacker city not found")
        val defenderCity = cities.findById(defender.cityId).orElse(null)
            ?: return notFound("defender city not found")
        val attackerNation = nations.findById(attacker.nationId).orElse(null)
            ?: return notFound("attacker nation not found")
        val defenderNation = nations.findById(defender.nationId).orElse(null)
            ?: return notFound("defender nation not found")
        val worldState = world.findAll().firstOrNull()
            ?: return status(HttpStatus.CONFLICT, "world state not found")

        val attackerCrewType = GameUnitConst.byId(attacker.crewTypeId)
            ?: return badRequest("attacker crewtype unknown")
        val defenderCrewType = GameUnitConst.byId(defender.crewTypeId)
            ?: return badRequest("defender crewtype unknown")

        val warSeed = (body["warSeed"] ?: body["seed"])?.toString()?.takeIf { it.isNotBlank() }
        val repeatCnt = if (warSeed != null) 1 else body.intArg("repeatCnt") ?: 1

        val input = BattleSimInput(
            attacker = attacker.toBattleSimInput(),
            attackerCity = attackerCity.toLogic(),
            attackerNation = attackerNation.toLogic(),
            defenders = listOf(defender.toBattleSimInput()),
            defenderCity = defenderCity.toLogic(),
            defenderNation = defenderNation.toLogic(),
            year = worldState.currentYear,
            month = worldState.currentMonth,
            startYear = worldState.startYear(),
            warSeed = warSeed,
            attackerCrewType = attackerCrewType,
            defenderCrewType = defenderCrewType,
            repeatCnt = repeatCnt,
        )

        return try {
            ResponseEntity.ok(BattleSimPreview(pipeline).simulateBattle(input).toResponse())
        } catch (e: IllegalArgumentException) {
            badRequest(e.message ?: "invalid simulator input")
        }
    }

    private fun GeneralReadEntity.toBattleSimInput(): BattleSimGeneralInput {
        val explevel =
            if (meta.containsKey("explevel")) metaInt(meta, "explevel") else getExpLevel(experience.toDouble())
        return BattleSimGeneralInput(
            no = id,
            nationId = nationId,
            cityId = cityId,
            leadership = leadership,
            strength = strength,
            intel = intel,
            injury = injury,
            explevel = explevel,
            experience = experience,
            dedication = dedication,
            officerLevel = officerLevel,
            gold = gold,
            rice = rice,
            crew = crew,
            train = train,
            atmos = atmos,
            crewTypeId = crewTypeId,
            horse = horseCode,
            weapon = weaponCode,
            book = bookCode,
            item = itemCode,
            meta = meta,
        )
    }

    private fun WorldStateReadEntity.startYear(): Int =
        listOf(config["startyear"], config["startYear"], meta["startyear"], meta["startYear"])
            .firstNotNullOfOrNull { (it as? Number)?.toInt() }
            ?: currentYear

    private fun BattleSimResult.toResponse(): Map<String, Any?> = linkedMapOf(
        "result" to true,
        "reason" to "success",
        "repeatCnt" to repeatCnt,
        "avgWar" to avgWar,
        "phase" to avgPhase,
        "killed" to attackerKilled,
        "maxKilled" to attackerMaxKilled,
        "minKilled" to attackerMinKilled,
        "dead" to attackerDead,
        "maxDead" to attackerMaxDead,
        "minDead" to attackerMinDead,
        "attackerSkills" to attackerSkills,
        "defendersSkills" to defendersSkills,
        "attackerCrew" to attacker.getCrew(),
        "defenderCityDefense" to city.state.defense,
        "defenderCityWall" to city.state.wall,
        "conquerCity" to (city.getPhase() <= 0),
    )

    private fun Map<String, Any?>.intArg(key: String): Int? =
        when (val raw = this[key]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }

    private fun badRequest(message: String): ResponseEntity<Map<String, Any?>> =
        status(HttpStatus.BAD_REQUEST, message)

    private fun notFound(message: String): ResponseEntity<Map<String, Any?>> =
        status(HttpStatus.NOT_FOUND, message)

    private fun status(status: HttpStatus, message: String): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(status).body(linkedMapOf("result" to false, "error" to message))
}
