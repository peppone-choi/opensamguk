package opensamguk.engine.hwiha

import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PlacementMarch
import opensamguk.logic.domestic.PolicySlot
import opensamguk.logic.domestic.PolicyApplication
import opensamguk.logic.domestic.CountyPolicyState
import opensamguk.logic.domestic.CorpsPolicyAssignments

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.CorpsPolicy
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.domestic.CountyLevels
import opensamguk.logic.domestic.SeatStats
import opensamguk.logic.domestic.DomesticEffects

import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticAssessment
import opensamguk.logic.domestic.DomesticRules

import opensamguk.engine.turn.*
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*

/**
 * 장수 한 명의 개인 턴에서 도는 내정 단계(§5.1 1단계 재검사, 이동 단계 앞). 레코더에만 쓰고 개인 턴의 단일 flush 에 실린다.
 * 배치·방침은 「해당 카드의 다음 턴부터」 효력이 생긴다(§4) — 여기서 대기를 현행으로 올린다.
 *
 * - 그 장수가 카드인 배치의 대기를 현행으로 올리거나(무효면 사유를 남기고 버림), 현행 배치를 재검사한다.
 * - 그 장수가 지휘하는 출전 군단의 방침 대기를 현행으로 올리고 반응 목록(`hwihaMarchReactions`)을 다시 쓴다.
 * - 그 장수가 앉은 縣令이면 縣 방침 대기를 현행으로 올린다.
 * 縣 방침의 지표 효과는 순 경계([HwihaDomesticBoundary])가 돌린다.
 */
class HwihaDomesticTurn(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
) {
    fun beforeMovement(generalId: Int) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        val general = world.getGeneralById(generalId) ?: return
        // Cheap key checks first: most personal turns hold no domestic record and never build a projection.
        if (PlacementState.META_KEY in general.meta) activatePlacement(generalId)
        if (world.listGenerals().any { CorpsPolicyAssignments.META_KEY in it.meta }) activateCorpsPolicies(generalId)
        val candidate = seatCandidate(generalId) ?: return
        val state = context.projection(world)
        val county = state.county(candidate) ?: return
        val seated = try { DomesticRules.seatedMagistrate(county, state) } catch (_: IllegalArgumentException) { null }
        if (seated?.personId == generalId) activateCountyPolicy(county.id, state.now)
    }

    /** 이 장수가 앉을 수 있는 縣(현행 縣令 배치, 또는 발령 부임 목표). */
    private fun seatCandidate(generalId: Int): Int? {
        val meta = world.getGeneralById(generalId)?.meta ?: return null
        val placed = try { PlacementState.read(meta)?.active?.order } catch (_: IllegalArgumentException) { null }
        if (placed?.post == PlacementPost.MAGISTRATE) return (placed.target as PlacementTarget.County).countyId
        return try { HwihaCountyAssignment.read(meta)?.countyId } catch (_: IllegalArgumentException) { null }
    }

    private fun activatePlacement(generalId: Int) {
        val card = world.getGeneralById(generalId) ?: return
        val stored = try { PlacementState.read(card.meta) } catch (_: IllegalArgumentException) {
            log(generalId, "배치 상태를 확인할 수 없어 부임하지 않았습니다."); return
        } ?: return
        val state = context.projection(world)
        var active = stored.active
        val pending = stored.pending
        if (pending != null) {
            when (val check = DomesticRules.assessPlacementOrder(generalId, pending, state)) {
                is DomesticAssessment.Rejected -> {
                    log(generalId, "새 배치(${pending.post.label})가 무효가 되어 적용하지 않았습니다: ${check.reason.message}")
                    log(pending.ownerGeneralId, "${card.name}의 새 배치(${pending.post.label})가 무효가 되었습니다: ${check.reason.message}")
                }
                is DomesticAssessment.Eligible -> {
                    active = if (pending.post == PlacementPost.NONE) null else ActivePlacement(pending, state.now, null)
                    log(generalId, if (pending.post == PlacementPost.NONE) "배치를 풀었습니다." else "${pending.post.label} 자리로 부임을 시작합니다.")
                    write(card, active, clearMarch = true)
                    return
                }
            }
        }
        if (active != null) {
            val check = DomesticRules.assessPlacementOrder(generalId, active.order, state)
            if (check is DomesticAssessment.Rejected) {
                log(generalId, "${active.order.post.label} 배치가 풀렸습니다: ${check.reason.message}")
                log(active.order.ownerGeneralId, "${card.name}의 ${active.order.post.label} 배치가 풀렸습니다: ${check.reason.message}")
                active = null
            }
        }
        write(card, active, clearMarch = active == null)
    }

    private fun write(card: TurnGeneral, active: ActivePlacement?, clearMarch: Boolean) {
        val before = checkNotNull(world.getGeneralById(card.id))
        val previous = try { PlacementState.read(before.meta) } catch (_: IllegalArgumentException) { null }
        var meta = before.meta.withKey(PlacementState.META_KEY, active?.let { PlacementState(it, null).toMetaValue() })
        if (clearMarch) meta = meta - PlacementMarch.META_KEY
        world.updateGeneralMeta(recorder, before, meta)
        // Republish scout posts for every owner this card's placement named (old and new).
        setOfNotNull(previous?.active?.order?.ownerGeneralId, previous?.pending?.ownerGeneralId, active?.order?.ownerGeneralId)
            .sorted().forEach { world.syncScoutPosts(recorder, it) }
    }

    private fun activateCorpsPolicies(commanderId: Int) {
        val state = context.projection(world)
        val deployed = try { DomesticRules.deployedCorps(state) } catch (_: IllegalArgumentException) { return }
        var changed = false
        for (owner in world.listGenerals().sortedBy { it.id }) {
            val policies = try { CorpsPolicyAssignments.read(owner.meta) } catch (_: IllegalArgumentException) { null } ?: continue
            val entry = policies.entries.firstOrNull { it.commanderGeneralId == commanderId } ?: continue
            val live = deployed.any { it.orderId == entry.orderId && it.ownerGeneralId == owner.id && it.commanderGeneralId == commanderId }
            val next = if (!live) policies.with(entry.orderId, commanderId, PolicySlot(null, null))
                else policies.with(entry.orderId, commanderId, entry.slot.activate(state.now))
            if (next == policies) continue
            val pendingOrder = entry.slot.pending
            if (live && pendingOrder != null) {
                val label = pendingOrder.policy?.let { p -> CorpsPolicy.valueOf(p).label }
                log(commanderId, if (label == null) "군단 방침을 거두었습니다." else "군단 방침을 「$label」(으)로 바꾸었습니다.")
            }
            world.updateGeneralMeta(recorder, owner, owner.meta.withKey(CorpsPolicyAssignments.META_KEY,
                next.takeIf { it.entries.isNotEmpty() }?.toMetaValue()))
            changed = true
        }
        if (changed) HwihaReactionInventory(world, recorder).rebuild()
    }

    private fun activateCountyPolicy(countyId: Int, now: HwihaPhase) {
        val city = world.getCityById(countyId) ?: return
        val current = try { CountyPolicyState.read(city.meta) } catch (_: IllegalArgumentException) { return } ?: return
        if (current.slot.pending == null) return
        val slot = current.slot.activate(now)
        val next = if (slot.isEmpty && current.lastApplied == null) null else CountyPolicyState(slot, current.lastApplied)
        world.updateCityMeta(recorder, countyId, city.meta.withKey(CountyPolicyState.META_KEY, next?.toMetaValue()))
    }

    private fun log(generalId: Int, text: String) {
        val general = world.getGeneralById(generalId) ?: return
        world.pushLog(LogEntryDraft(scope = "general", category = "action", text = text, generalId = generalId, nationId = general.nationId))
    }
}

/**
 * 縣 방침 한 번 적용. 순 경계(§5.2 3단계 「내정 진행」)가 縣 id 순으로 부르고, 한 순에 한 번은 경계의 월드 도장이 보장한다.
 * 앉은 縣令이 있으면 그 능력치로(§8.2), 없으면 빈자리 배율로 유효 방침을 돌린다. 방침·자리 기록이 있거나 건너뛴 사유가
 * 있을 때만 縣 meta 의 `lastApplied` 를 적는다 — 아무도 손대지 않은 縣은 지표만 바뀐다.
 */
internal class HwihaDomesticCountyEffects(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
) {
    fun apply(countyId: Int, state: DomesticProjection) {
        val county = state.county(countyId) ?: return
        if (county.nationId <= 0) return
        val city = world.getCityById(countyId) ?: return
        val stored = try { CountyPolicyState.read(city.meta) } catch (_: IllegalArgumentException) {
            // A corrupt policy record is not silently replaced by the default policy.
            return
        }
        val effective = try { DomesticRules.effectivePolicy(county, state, context.design) } catch (_: IllegalArgumentException) {
            write(city, levelsOf(city), city.meta, stored,
                PolicyApplication(state.now, context.design.defaultCountyPolicy.name, "EMPTY", "STATE_UNAVAILABLE"))
            return
        }
        val seat = effective.seat?.let { seat ->
            val person = checkNotNull(state.person(seat.personId))
            SeatStats(person.leadership, person.strength, person.intelligence, person.politics, person.charm,
                state.homeCountyByGeneral[person.id] == countyId)
        }
        val levels = levelsOf(city)
        val outcome = DomesticEffects.applyPolicy(context.design, effective.policy, levels, seat)
        var resultCode = "APPLIED"
        if (outcome.credit != HwihaResources() || outcome.debit != HwihaResources()) {
            // Resource flows go through the warehouse settlement boundary (owner and revision rechecked).
            val warehouse = try { HwihaCountyWarehouse.read(city.meta, countyId) } catch (_: IllegalArgumentException) { null }
            resultCode = if (warehouse == null) "WAREHOUSE_NOT_READY" else when (val settled = HwihaWarehouseSettlement(world, recorder)
                .settle(countyId, city.nationId, warehouse.revision, outcome.debit, outcome.credit)) {
                HwihaWarehouseSettlement.Result.APPLIED -> "APPLIED"
                HwihaWarehouseSettlement.Result.NOT_READY -> "WAREHOUSE_NOT_READY"
                else -> settled.name
            }
        }
        val current = checkNotNull(world.getCityById(countyId))
        val application = PolicyApplication(state.now, effective.policy.name, if (seat == null) "EMPTY" else "SEATED", resultCode)
        val record = stored != null || seat != null || resultCode != "APPLIED"
        write(current, if (resultCode == "APPLIED") outcome.levels else levels, current.meta, stored, application.takeIf { record })
    }

    private fun write(before: City, levels: CountyLevels, baseMeta: Map<String, Any?>, stored: CountyPolicyState?,
        application: PolicyApplication?) {
        var meta = baseMeta
        if (application != null) meta = meta.withKey(CountyPolicyState.META_KEY,
            CountyPolicyState(stored?.slot ?: PolicySlot(null, null), application).toMetaValue())
        // Trust lives in meta (the engine City has no trust column) and persists as a float column.
        val trust = ReservedTurnHandler.materializeMariaDbFloat(levels.trust)
        if (trust != trustOf(before)) meta = meta.withKey("trust", trust)
        val after = before.copy(population = levels.population, agriculture = levels.agriculture, commerce = levels.commerce,
            security = levels.security, defence = levels.defence, wall = levels.wall, meta = meta)
        if (after == before) return
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
        checkNotNull(world.applyCityDirtyFree(after))
    }

    companion object {
        fun trustOf(city: City): Double = (city.meta["trust"] as? Number)?.toDouble() ?: 0.0
        fun levelsOf(city: City) = CountyLevels(city.population, city.populationMax, city.agriculture, city.agricultureMax,
            city.commerce, city.commerceMax, city.security, city.securityMax, trustOf(city), city.defence, city.defenceMax,
            city.wall, city.wallMax)
    }
}
