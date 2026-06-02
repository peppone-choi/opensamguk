package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.FrontCityInfo
import opensamguk.gameapi.dto.FrontGeneralInfo
import opensamguk.gameapi.dto.FrontGlobalInfo
import opensamguk.gameapi.dto.FrontInfoResponse
import opensamguk.gameapi.dto.FrontNationInfo
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 1 — `GET /api/front-info` (spec §3): the per-refresh envelope the `/game` main screen renders.
 *
 * Identity resolution order (transition-friendly):
 *   1. the verified JWT principal (`@AuthenticationPrincipal userId`) → owned general via [GeneralResolver];
 *   2. else the legacy `?generalId=` query param (transition fallback per Task 6 — removed once web/game
 *      always carries the Bearer);
 *   3. else no character → `general.hasGeneral=false`, nation/city null.
 *
 * Public read (no auth required): an anonymous caller still gets `global` (the GameInfo header) and an
 * empty `general`, so the header renders before login. PHP-faithful gating fields
 * (officer_level/permission/showSecret/nation level) come from the resolved general (OQ4: the JWT does
 * not carry them). Assembled entirely from existing read repos — never a game-state write.
 */
@RestController
@RequestMapping("/api")
class FrontInfoController(
    private val resolver: GeneralResolver,
    private val world: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
) {

    @GetMapping("/front-info")
    fun frontInfo(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<FrontInfoResponse> {
        val global = buildGlobal()

        // resolve the caller's general: principal first, then the ?generalId= transition fallback.
        val resolved = userId?.let { resolver.resolve(it) }
        val general: GeneralReadEntity? = resolved?.general
            ?: generalId?.let { generals.findById(it).orElse(null) }

        if (general == null) {
            return ResponseEntity.ok(
                FrontInfoResponse(
                    result = true,
                    global = global,
                    general = emptyGeneral(),
                    nation = null,
                    city = null,
                    recentRecord = emptyList(),
                ),
            )
        }

        val officerLevel = general.officerLevel
        val permission = resolved?.permission ?: GeneralResolver.derivePermission(officerLevel)
        val nationEntity: NationReadEntity? =
            if (general.nationId != 0) nations.findById(general.nationId).orElse(null) else null
        val cityEntity: CityReadEntity? =
            if (general.cityId != 0) cities.findById(general.cityId).orElse(null) else null

        return ResponseEntity.ok(
            FrontInfoResponse(
                result = true,
                global = global,
                general = FrontGeneralInfo(
                    hasGeneral = true,
                    generalId = general.id,
                    name = general.name,
                    nationId = general.nationId,
                    officerLevel = officerLevel,
                    permission = permission,
                    showSecret = permission >= 2,
                    leadership = general.leadership,
                    strength = general.strength,
                    intel = general.intel,
                    injury = general.injury,
                    gold = general.gold,
                    rice = general.rice,
                    crew = general.crew,
                    cityId = general.cityId,
                ),
                nation = nationEntity?.let {
                    FrontNationInfo(
                        id = it.id,
                        name = it.name,
                        color = it.color,
                        level = it.level,
                        gold = it.gold,
                        rice = it.rice,
                        tech = it.tech,
                        capitalCityId = it.capitalCityId,
                    )
                },
                city = cityEntity?.let {
                    FrontCityInfo(
                        id = it.id,
                        name = it.name,
                        level = it.level,
                        nationId = it.nationId,
                        region = it.region,
                        population = it.population,
                        populationMax = it.populationMax,
                        agriculture = it.agriculture,
                        agricultureMax = it.agricultureMax,
                        commerce = it.commerce,
                        commerceMax = it.commerceMax,
                        security = it.security,
                        securityMax = it.securityMax,
                        defense = it.defense,
                        defenseMax = it.defenseMax,
                        wall = it.wall,
                        wallMax = it.wallMax,
                        trust = it.trust,
                        trade = it.trade,
                    )
                },
                recentRecord = emptyList(),
            ),
        )
    }

    private fun buildGlobal(): FrontGlobalInfo {
        val w = world.findAll().firstOrNull()
        val scenario = w?.scenarioCode ?: ""
        val scenarioText = (w?.config?.get("title") ?: w?.meta?.get("title"))?.toString()
            ?.takeIf { it.isNotBlank() } ?: scenario
        return FrontGlobalInfo(
            year = w?.currentYear ?: 0,
            month = w?.currentMonth ?: 0,
            turnterm = (w?.tickSeconds ?: 0) / 60,
            scenario = scenario,
            scenarioText = scenarioText,
            generalCount = generals.count().toInt(),
            nationCount = nations.findAll().count { it.id != 0 },
            cityCount = cities.count().toInt(),
            npcCount = generals.countByNpcState(2).toInt() + generals.countByNpcState(1).toInt(),
        )
    }

    private fun emptyGeneral() = FrontGeneralInfo(
        hasGeneral = false,
        generalId = null,
        name = null,
        nationId = 0,
        officerLevel = 0,
        permission = 0,
        showSecret = false,
        leadership = 0,
        strength = 0,
        intel = 0,
        injury = 0,
        gold = 0,
        rice = 0,
        crew = 0,
        cityId = 0,
    )
}
