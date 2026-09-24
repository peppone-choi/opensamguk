package opensamguk.logic.input

import opensamguk.logic.content.HwihaItemCatalogJson
import opensamguk.logic.content.HwihaTreasureDefinition
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources

enum class HwihaLegacyDirectFailure(val message: String) {
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

sealed interface HwihaLegacyDirectAssessment {
    data class Eligible(val actor: DomesticPerson, val county: DomesticCounty? = null,
        val targetCounty: DomesticCounty? = null, val bugok: DomesticBugok? = null,
        val treasure: HwihaTreasureDefinition? = null, val inventory: Set<String> = emptySet(),
        val actorStock: HwihaResources? = null, val warehouse: HwihaCountyWarehouse? = null,
        val targetWarehouse: HwihaCountyWarehouse? = null) : HwihaLegacyDirectAssessment
    data class Rejected(val reason: HwihaLegacyDirectFailure) : HwihaLegacyDirectAssessment
}

object HwihaLegacyDirectRules {
    fun assess(request: HwihaLegacyDirectRequest, state: HwihaDomesticProjection): HwihaLegacyDirectAssessment {
        fun reject(reason: HwihaLegacyDirectFailure) = HwihaLegacyDirectAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return reject(HwihaLegacyDirectFailure.WRONG_RULE_PROFILE)
        val actor = state.person(request.actorId) ?: return reject(HwihaLegacyDirectFailure.ACTOR_NOT_FOUND)
        if (actor.inBattle) return reject(HwihaLegacyDirectFailure.BATTLE_PENDING)
        val node = actor.node?.takeIf { it in (state.landProvinceIds ?: emptySet()) }
            ?: return reject(HwihaLegacyDirectFailure.POSITION_UNAVAILABLE)
        if (request is HwihaLegacyDirectRequest.Convert) {
            val unit = state.bugoks.singleOrNull { it.id == request.bugokId && it.masterGeneralId == actor.id }
                ?: return reject(HwihaLegacyDirectFailure.BUGOK_UNAVAILABLE)
            if (request.crewTypeId !in state.supportedCrewTypeIds)
                return reject(HwihaLegacyDirectFailure.INVALID_CREW_TYPE)
            if (unit.crewTypeId == request.crewTypeId) return reject(HwihaLegacyDirectFailure.SAME_CREW_TYPE)
            return HwihaLegacyDirectAssessment.Eligible(actor, bugok = unit)
        }
        val counties = state.counties.filter { it.provinceId == node }
        if (counties.size != 1) return reject(HwihaLegacyDirectFailure.COUNTY_UNAVAILABLE)
        val county = counties.single()
        if (county.nationId <= 0 || county.nationId != actor.nationId)
            return reject(HwihaLegacyDirectFailure.FOREIGN_COUNTY)
        val warehouse = try { HwihaCountyWarehouse.read(county.meta, county.id) }
            catch (_: IllegalArgumentException) { return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE) }
            ?: return reject(HwihaLegacyDirectFailure.WAREHOUSE_NOT_READY)
        if (warehouse.revision == Long.MAX_VALUE) return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE)
        val actorStock = try { HwihaPortableStock.read(actor.meta, actor.gold, actor.rice) }
            catch (_: IllegalArgumentException) { return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE) }
        when (request) {
            is HwihaLegacyDirectRequest.Convert -> error("handled above")
            is HwihaLegacyDirectRequest.Equipment -> {
                val treasure = HwihaItemCatalogJson.CANON.treasures.singleOrNull {
                    it.sourceRowIndex == request.treasureId && it.issuedCopies != null && it.purchaseCost > 0
                } ?: return reject(HwihaLegacyDirectFailure.TREASURE_UNAVAILABLE)
                val allInventories = try { state.people.associate { it.id to HwihaTreasureInventory.read(it.meta) } }
                    catch (_: IllegalArgumentException) { return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE) }
                val cards = allInventories.values.flatten()
                if (cards.size != cards.toSet().size) return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE)
                val inventory = allInventories[actor.id].orEmpty()
                val price = treasure.purchaseCost.toLong()
                if (request.side == HwihaTradeSide.BUY) {
                    if (treasure.header.id in cards) return reject(HwihaLegacyDirectFailure.TREASURE_ISSUED)
                    if (actorStock.money < price) return reject(HwihaLegacyDirectFailure.INSUFFICIENT_STOCK)
                    try { warehouse.stock.credit(HwihaResources(money = price)) }
                    catch (_: ArithmeticException) { return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW) }
                } else {
                    if (treasure.header.id !in inventory) return reject(HwihaLegacyDirectFailure.TREASURE_NOT_OWNED)
                    if (warehouse.stock.money < price) return reject(HwihaLegacyDirectFailure.INSUFFICIENT_STOCK)
                    if (actorStock.money + price > Int.MAX_VALUE) return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW)
                }
                return HwihaLegacyDirectAssessment.Eligible(actor, county, treasure = treasure, inventory = inventory,
                    actorStock = actorStock, warehouse = warehouse)
            }
            is HwihaLegacyDirectRequest.Grain -> {
                if (request.amount != 1) return reject(HwihaLegacyDirectFailure.INVALID_INPUT)
                if (request.side == HwihaTradeSide.BUY) {
                    if (actorStock.money < 100 || warehouse.stock.grain < 300)
                        return reject(HwihaLegacyDirectFailure.INSUFFICIENT_STOCK)
                    if (actorStock.grain + 300 > Int.MAX_VALUE) return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW)
                    try { warehouse.stock.credit(HwihaResources(money = 100)) }
                    catch (_: ArithmeticException) { return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW) }
                } else {
                    if (actorStock.grain < 300 || warehouse.stock.money < 100)
                        return reject(HwihaLegacyDirectFailure.INSUFFICIENT_STOCK)
                    if (actorStock.money + 100 > Int.MAX_VALUE) return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW)
                    try { warehouse.stock.credit(HwihaResources(grain = 300)) }
                    catch (_: ArithmeticException) { return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW) }
                }
                return HwihaLegacyDirectAssessment.Eligible(actor, county, actorStock = actorStock, warehouse = warehouse)
            }
            is HwihaLegacyDirectRequest.Transport -> {
                if (request.amount !in 1..1000) return reject(HwihaLegacyDirectFailure.INVALID_INPUT)
                val target = state.county(request.targetCountyId)?.takeIf {
                    it.id in state.countyAdjacency[county.id].orEmpty() && it.nationId == county.nationId
                } ?: return reject(HwihaLegacyDirectFailure.TARGET_COUNTY_UNAVAILABLE)
                val targetWarehouse = try { HwihaCountyWarehouse.read(target.meta, target.id) }
                    catch (_: IllegalArgumentException) { return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE) }
                    ?: return reject(HwihaLegacyDirectFailure.WAREHOUSE_NOT_READY)
                if (targetWarehouse.revision == Long.MAX_VALUE) return reject(HwihaLegacyDirectFailure.STATE_UNAVAILABLE)
                val cargo = request.cargo.amount(request.amount.toLong())
                if (warehouse.stock.debit(cargo) == null) return reject(HwihaLegacyDirectFailure.INSUFFICIENT_STOCK)
                try { targetWarehouse.stock.credit(cargo) }
                catch (_: ArithmeticException) { return reject(HwihaLegacyDirectFailure.STOCK_OVERFLOW) }
                return HwihaLegacyDirectAssessment.Eligible(actor, county, target, actorStock = actorStock,
                    warehouse = warehouse, targetWarehouse = targetWarehouse)
            }
        }
    }

    fun HwihaCargo.amount(value: Long): HwihaResources = when (this) {
        HwihaCargo.MONEY -> HwihaResources(money = value)
        HwihaCargo.GRAIN -> HwihaResources(grain = value)
        HwihaCargo.IRON -> HwihaResources(iron = value)
        HwihaCargo.TIMBER -> HwihaResources(timber = value)
        HwihaCargo.HORSES -> HwihaResources(horses = value)
    }
}
