package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticBugok
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticRules

import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.content.TreasureDefinition
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources

enum class DirectFailure(val message: String) {
    WRONG_RULE_PROFILE("휘하 규칙에서만 사용할 수 있습니다."), INVALID_INPUT("직접 행동 인자가 올바르지 않습니다."),
    ACTOR_NOT_FOUND("장수를 찾을 수 없습니다."), BATTLE_PENDING("조우 처리가 끝나야 행동할 수 있습니다."),
    POSITION_UNAVAILABLE("현재 육상 위치를 확인할 수 없습니다."), COUNTY_UNAVAILABLE("현재 省의 행정 縣을 찾을 수 없습니다."),
    FOREIGN_COUNTY("현재 縣이 아군 소유가 아닙니다."), WAREHOUSE_NOT_READY("현재 縣 창고를 확인할 수 없습니다."),
    BUGOK_UNAVAILABLE("직접 소유한 부곡을 찾을 수 없습니다."), INVALID_CREW_TYPE("전환할 병종을 사용할 수 없습니다."),
    SAME_CREW_TYPE("이미 같은 병종입니다."), TREASURE_UNAVAILABLE("발행이 확정된 보물을 찾을 수 없습니다."),
    TREASURE_ISSUED("해당 보물은 이미 발행되었습니다."), TREASURE_NOT_OWNED("소유한 보물이 아닙니다."),
    INSUFFICIENT_STOCK("보유 자원이 부족합니다."), STOCK_OVERFLOW("자원 보유 한도를 넘습니다."),
    TARGET_COUNTY_UNAVAILABLE("인접한 아군 縣을 선택해 주세요."), STATE_UNAVAILABLE("저장 상태를 확인할 수 없습니다."),
    ALREADY_PROCESSED("이 순에는 이미 거래·수송 행동을 실행했습니다."),
}

sealed interface DirectAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty? = null,
        val targetCounty: DomesticCounty? = null, val bugok: DomesticBugok? = null,
        val treasure: TreasureDefinition? = null, val inventory: Set<String> = emptySet(),
        val actorStock: Resources? = null, val warehouse: CountyWarehouse? = null,
        val targetWarehouse: CountyWarehouse? = null) : DirectAssessment
    data class Rejected(val reason: DirectFailure) : DirectAssessment
}

object DirectRules {
    fun assess(request: DirectRequest, state: DomesticProjection): DirectAssessment {
        fun reject(reason: DirectFailure) = DirectAssessment.Rejected(reason)
        val design = DirectDesign.CANON
        if (state.profile != RuleProfile.HWIHA) return reject(DirectFailure.WRONG_RULE_PROFILE)
        val actor = state.person(request.actorId) ?: return reject(DirectFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(DirectFailure.BATTLE_PENDING)
        val node = actor.node?.takeIf { it in (state.landProvinceIds ?: emptySet()) }
            ?: return reject(DirectFailure.POSITION_UNAVAILABLE)
        if (request is DirectRequest.Convert) {
            val unit = state.bugoks.singleOrNull { it.id == request.bugokId && it.masterGeneralId == actor.id }
                ?: return reject(DirectFailure.BUGOK_UNAVAILABLE)
            val local = state.counties.singleOrNull { it.provinceId == node }
                ?: return reject(DirectFailure.COUNTY_UNAVAILABLE)
            if (local.nationId != actor.nationId || local.nationId <= 0)
                return reject(DirectFailure.FOREIGN_COUNTY)
            val deployed = try { DomesticRules.deployedCorps(state) }
                catch (_: IllegalArgumentException) { return reject(DirectFailure.STATE_UNAVAILABLE) }
            if (deployed.any { unit.id in it.bugokIds }) return reject(DirectFailure.BUGOK_UNAVAILABLE)
            if (request.crewTypeId !in state.supportedCrewTypeIds)
                return reject(DirectFailure.INVALID_CREW_TYPE)
            if (unit.crewTypeId == request.crewTypeId) return reject(DirectFailure.SAME_CREW_TYPE)
            return DirectAssessment.Eligible(actor, bugok = unit)
        }
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.size != 1) return reject(DirectFailure.COUNTY_UNAVAILABLE)
        val county = counties.single()
        if (county.nationId <= 0 || county.nationId != actor.nationId)
            return reject(DirectFailure.FOREIGN_COUNTY)
        val warehouse = try { CountyWarehouse.read(county.meta, county.id) }
            catch (_: IllegalArgumentException) { return reject(DirectFailure.STATE_UNAVAILABLE) }
            ?: return reject(DirectFailure.WAREHOUSE_NOT_READY)
        if (warehouse.revision == Long.MAX_VALUE) return reject(DirectFailure.STATE_UNAVAILABLE)
        val actorStock = try { PortableStock.read(actor.meta, actor.gold, actor.rice) }
            catch (_: IllegalArgumentException) { return reject(DirectFailure.STATE_UNAVAILABLE) }
        when (request) {
            is DirectRequest.Convert -> error("handled above")
            is DirectRequest.Equipment -> {
                val treasure = ItemCatalogJson.CANON.treasures.singleOrNull {
                    it.sourceRowIndex == request.treasureId && it.issuedCopies != null && it.purchaseCost > 0
                } ?: return reject(DirectFailure.TREASURE_UNAVAILABLE)
                val allInventories = try { state.people.associate { it.id to TreasureInventory.read(it.meta) } }
                    catch (_: IllegalArgumentException) { return reject(DirectFailure.STATE_UNAVAILABLE) }
                val cards = allInventories.values.flatten()
                if (cards.size != cards.toSet().size) return reject(DirectFailure.STATE_UNAVAILABLE)
                val inventory = allInventories[actor.id].orEmpty()
                val price = treasure.purchaseCost.toLong()
                if (request.side == TradeSide.BUY) {
                    if (treasure.header.id in cards) return reject(DirectFailure.TREASURE_ISSUED)
                    if (actorStock.money < price) return reject(DirectFailure.INSUFFICIENT_STOCK)
                    try { warehouse.stock.credit(Resources(money = price)) }
                    catch (_: ArithmeticException) { return reject(DirectFailure.STOCK_OVERFLOW) }
                } else {
                    if (treasure.header.id !in inventory) return reject(DirectFailure.TREASURE_NOT_OWNED)
                    if (warehouse.stock.money < price) return reject(DirectFailure.INSUFFICIENT_STOCK)
                    if (actorStock.money + price > Int.MAX_VALUE) return reject(DirectFailure.STOCK_OVERFLOW)
                }
                return DirectAssessment.Eligible(actor, county, treasure = treasure, inventory = inventory,
                    actorStock = actorStock, warehouse = warehouse)
            }
            is DirectRequest.Grain -> {
                if (request.amount != 1) return reject(DirectFailure.INVALID_INPUT)
                if (request.side == TradeSide.BUY) {
                    if (actorStock.money < design.grainTradeMoney || warehouse.stock.grain < design.grainTradeGrain)
                        return reject(DirectFailure.INSUFFICIENT_STOCK)
                    if (actorStock.grain + design.grainTradeGrain > Int.MAX_VALUE) return reject(DirectFailure.STOCK_OVERFLOW)
                    try { warehouse.stock.credit(Resources(money = design.grainTradeMoney.toLong())) }
                    catch (_: ArithmeticException) { return reject(DirectFailure.STOCK_OVERFLOW) }
                } else {
                    if (actorStock.grain < design.grainTradeGrain || warehouse.stock.money < design.grainTradeMoney)
                        return reject(DirectFailure.INSUFFICIENT_STOCK)
                    if (actorStock.money + design.grainTradeMoney > Int.MAX_VALUE) return reject(DirectFailure.STOCK_OVERFLOW)
                    try { warehouse.stock.credit(Resources(grain = design.grainTradeGrain.toLong())) }
                    catch (_: ArithmeticException) { return reject(DirectFailure.STOCK_OVERFLOW) }
                }
                return DirectAssessment.Eligible(actor, county, actorStock = actorStock, warehouse = warehouse)
            }
            is DirectRequest.Transport -> {
                if (request.amount !in 1..design.transportMaxAmount) return reject(DirectFailure.INVALID_INPUT)
                val target = state.county(request.targetCountyId)?.takeIf {
                    it.id in state.countyAdjacency[county.id].orEmpty() && it.nationId == county.nationId
                } ?: return reject(DirectFailure.TARGET_COUNTY_UNAVAILABLE)
                val targetWarehouse = try { CountyWarehouse.read(target.meta, target.id) }
                    catch (_: IllegalArgumentException) { return reject(DirectFailure.STATE_UNAVAILABLE) }
                    ?: return reject(DirectFailure.WAREHOUSE_NOT_READY)
                if (targetWarehouse.revision == Long.MAX_VALUE) return reject(DirectFailure.STATE_UNAVAILABLE)
                val cargo = request.cargo.amount(request.amount.toLong())
                if (warehouse.stock.debit(cargo) == null) return reject(DirectFailure.INSUFFICIENT_STOCK)
                try { targetWarehouse.stock.credit(cargo) }
                catch (_: ArithmeticException) { return reject(DirectFailure.STOCK_OVERFLOW) }
                return DirectAssessment.Eligible(actor, county, target, actorStock = actorStock,
                    warehouse = warehouse, targetWarehouse = targetWarehouse)
            }
        }
    }

    fun Cargo.amount(value: Long): Resources = when (this) {
        Cargo.MONEY -> Resources(money = value)
        Cargo.GRAIN -> Resources(grain = value)
        Cargo.IRON -> Resources(iron = value)
        Cargo.TIMBER -> Resources(timber = value)
        Cargo.HORSES -> Resources(horses = value)
    }
}
