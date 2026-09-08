package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BattlefieldReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.seed.HistoricalBattlefieldCatalog
import opensamguk.logic.world.*
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class BattlefieldSiteResponse(val id: String, val name: String, val latitude: Double, val longitude: Double,
    val confidence: String, val canEnter: Boolean, val reason: String?)
data class BattlefieldsResponse(val generalId: Int, val catalogHash: String, val positionRevision: String,
    val currentSiteId: String?, val canExit: Boolean, val sites: List<BattlefieldSiteResponse>)

@RestController
class BattlefieldController(private val resolver: GeneralResolver, private val world: WorldStateReadRepository,
    private val positions: BattlefieldReadRepository) {
    @GetMapping("/api/battlefields")
    fun battlefields(@AuthenticationPrincipal userId: Long?): ResponseEntity<*> {
        if (userId == null) return ResponseEntity.status(401).build<Any>()
        val me = resolver.resolve(userId) ?: return ResponseEntity.notFound().build<Any>()
        val active = world.findProcessWorld() ?: return ResponseEntity.notFound().build<Any>()
        if (ActiveWorldMap.requireName(active.config, active.meta) != "han-world-v3") return ResponseEntity.notFound().build<Any>()
        val state = positions.read(me.general.id)
        val catalog = HistoricalBattlefieldCatalog.load()
        val request = BattlefieldMovementRequest(state.topologyRevision, state.topologyHash, me.general.id,
            me.general.cityId, state.position, state.position?.revision, catalog.contentHash, state.cityAnchors)
        val sites = HistoricalBattlefieldCatalog.sites().map { site ->
            val decision = BattlefieldMovementRules.enter(catalog, site.id, request)
            val allowed = me.general.crew > 0 && decision is BattlefieldMovementResult.Allowed
            BattlefieldSiteResponse(site.id, site.name, site.latitude, site.longitude, site.confidence, allowed,
                if (allowed) null else if (me.general.crew <= 0) "병력이 필요합니다." else "${HistoricalBattlefieldCatalog.cityName(catalog.entries.getValue(site.id).ingressCityId!!)}에 있는 부대만 진입할 수 있습니다.")
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BattlefieldsResponse(me.general.id,
            catalog.contentHash, state.position?.revision?.toString() ?: "", state.position?.battlefield?.siteId,
            BattlefieldMovementRules.exit(catalog, request) is BattlefieldMovementResult.Allowed, sites))
    }
}
