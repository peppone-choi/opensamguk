package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RecordKind
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownEntry
import opensamguk.logic.renown.RenownEventKind
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.renown.RenownRules
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.RefRole
import org.slf4j.LoggerFactory

/**
 * 월 경계의 「월단평」 — 명망을 갱신하고 순위를 발표한다(정본 설계 §2.8, §5.2 순 경계 4번).
 *
 * 갱신은 **사건 누적식**이다(2026-09-22 사용자 결정). 사건은 장수 meta 의 [TALLY_META_KEY] 에 쌓이고
 * ([RenownEvents] — 한 달에 종류당 한 번, 2026-09-23 사용자 결정), 월단평이 **이번 달 이전** 사건을
 * 한 번 적용한 뒤 그 줄만 지운다. 이번 달 도장의 사건(같은 경계에서 막 기록된 것 포함)은 다음 달로 넘긴다.
 * 가감값·상하한은 `data/curated/han/renown-assessment-v1.json` 에서 온다 — 코드에 박지 않는다.
 *
 * 도장([STAMP_KEY])으로 한 달에 한 번만 돈다. 징세와 같은 방식이고 같은 flush 에 실린다.
 *
 * ### 발표하는 것
 *
 * - 순위([RANKING_KEY]) — 명망 내림차순.
 * - 사유([REASONS_KEY]) — 장수별로 이번에 적용한 사건 **종류**와 건수·증감. 종류는 §2.8 발표에 실리는 공개
 *   정보다. 원인(어느 조우·어느 縣)은 싣지 않는다(#343) — 원인은 본인 집계에만 있다.
 * - 세계 공개 기록(중원 정세) 한 줄과, 사건이 적용된 장수 본인 앞 기록.
 *
 * ### 이탈
 *
 * 코스트 상한을 넘은 휘하는 판정해 주인 앞([RecordKind.DEPARTURE_JUDGED])과 판정된 인물 앞
 * ([RecordKind.RETINUE_DEPARTED])으로 기록한다. **이탈은 배신이 아니다**(2026-09-23 사용자 결정 「이탈과 배신은
 * 구분해야지」): 이탈은 명망 0 이고 월단평 사건을 쌓지 않는다. 배신(−8)은 실제 배반에만 쓴다. **실제 해방은 아직 하지 않는다**: 월드에 해방·이탈 경로가 없고 `releasePolicy`(MASTER_ONLY·MUTUAL)
 * 처리 규칙이 정해지지 않았다.
 */
class MonthlyAssessment(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val curve: RenownAssessment.Curve,
) {
    /**
     * @property moved 명망이 실제로 움직인 장수 수.
     * @property overCap 갱신 뒤에도 휘하 코스트가 명망을 넘어 이탈 판정을 받은 장수 수.
     * @property ranking 명망 내림차순 순위(동점은 id 오름차순).
     */
    data class Outcome(
        val stamp: String,
        val alreadyStamped: Boolean = false,
        val assessed: Int = 0,
        val moved: Int = 0,
        val overCap: Int = 0,
        val ranking: List<Int> = emptyList(),
    )

    fun assess(year: Int, month: Int): Outcome? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = stampOf(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return Outcome(stamp, alreadyStamped = true)

        val retainersByMaster = world.listRetainers().groupBy { it.masterGeneralId }
        var assessed = 0
        var moved = 0
        var overCap = 0
        val renownByGeneral = LinkedHashMap<Int, Int>()
        val reasons = LinkedHashMap<String, Any?>()

        for (id in world.listGenerals().map { it.id }.sorted()) {
            // 매번 새로 읽는다 — 앞 차례가 이 장수를 바꿨을 수 있다.
            val general = world.getGeneralById(id) ?: continue
            val policy = try { PersonPolicyState.read(general.meta) } catch (_: IllegalArgumentException) { null }
                ?: continue
            assessed++
            val retinue = (retainersByMaster[general.id] ?: emptyList()).mapNotNull { card ->
                val person = card.generalId?.let { world.getGeneralById(it) } ?: return@mapNotNull null
                RenownAssessment.RetainerCard(
                    retainerId = card.id,
                    cost = RenownRules.personCost(
                        person.stats.leadership, person.stats.strength, person.stats.intelligence,
                        person.stats.politics, person.stats.charm,
                    ),
                    loyalty = card.loyalty,
                )
            }
            val split = RenownEvents.split(general.meta, stamp)
            val before = policy.renownCapacity.coerceIn(curve.floor, curve.ceiling)
            val outcome = RenownAssessment.assess(
                generalId = general.id,
                renown = before,
                tally = RenownEvents.tallyOf(split.applied),
                curve = curve,
                retinue = retinue,
            )
            renownByGeneral[general.id] = outcome.renown
            if (outcome.released.isNotEmpty()) overCap++

            // 적용한 줄만 지운다 — 남기면 다음 달에 또 적용되고, 이번 달 줄을 지우면 사라진다.
            val nextMeta = LinkedHashMap(RenownEvents.withEntries(general.meta, split.remaining))
            nextMeta[PersonPolicyState.META_KEY] = policy.copy(renownCapacity = outcome.renown).toMetaValue()
            if (nextMeta != general.meta) {
                if (!apply(general, nextMeta)) {
                    renownByGeneral.remove(general.id)
                    assessed--
                    continue
                }
                if (outcome.renown != policy.renownCapacity) moved++
            }
            if (split.applied.isNotEmpty()) {
                val summary = summarize(split.applied)
                reasons[general.id.toString()] = summary
                world.recordEvent(
                    kind = EventKind.YUEDAN_ASSESSED,
                    audience = AudienceTarget.Self(general.id),
                    eventKey = EventKey.derive(EventKind.YUEDAN_ASSESSED.code,
                        world.worldId.value.toString(), stamp, general.id.toString()),
                    refs = mapOf(RefRole.ACTOR to EventRef.General(general.id)),
                    facts = mapOf(
                        FactRole.RENOWN_BEFORE to EventFact.Amount(before.toLong()),
                        FactRole.RENOWN_AFTER to EventFact.Amount(outcome.renown.toLong()),
                        FactRole.RENOWN_CHANGE to EventFact.Change(outcome.delta.toLong()),
                    ),
                )
                Records.general(world, general.id, RecordKind.YUEDAN_ASSESSED,
                    "월단평: 명망 $before → ${outcome.renown} (${summary.joinToString("·") { labelOf(it) }})",
                    linkedMapOf("stamp" to stamp, "before" to before, "after" to outcome.renown,
                        "delta" to outcome.delta, "kinds" to summary.map { it["kind"] }),
                    nationId = general.nationId)
            }
            if (outcome.released.isNotEmpty()) {
                Records.general(world, general.id, RecordKind.DEPARTURE_JUDGED,
                    "월단평 뒤 휘하 코스트가 명망을 넘어 이탈 판정을 받았습니다: ${outcome.released.joinToString()}",
                    linkedMapOf("stamp" to stamp, "retainerIds" to outcome.released, "renown" to outcome.renown,
                        "retainedCost" to outcome.retainedCost),
                    nationId = general.nationId)
                // The judged person learns about its own departure judgement; it carries no renown (not betrayal).
                val cards = world.listRetainers().associateBy { it.id }
                for (card in outcome.released) {
                    val person = cards[card]?.generalId?.let { world.getGeneralById(it) } ?: continue
                    Records.general(world, person.id, RecordKind.RETINUE_DEPARTED,
                        "${general.name}의 명망이 휘하 코스트에 모자라 이탈 판정을 받았습니다. 명망에는 영향이 없습니다.",
                        linkedMapOf("stamp" to stamp, "masterId" to general.id, "retainerId" to card),
                        nationId = person.nationId)
                }
            }
        }

        val ranking = RenownAssessment.ranking(renownByGeneral)
        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        world.setGameEnvValue(RANKING_KEY, ranking)
        recorder.recordKv("game_env", "game_env", RANKING_KEY, ranking)
        val published = linkedMapOf<String, Any?>("stamp" to stamp, "byGeneral" to reasons)
        world.setGameEnvValue(REASONS_KEY, published)
        recorder.recordKv("game_env", "game_env", REASONS_KEY, published)
        announce(year, month, stamp, ranking, renownByGeneral)
        return Outcome(stamp, alreadyStamped = false, assessed, moved, overCap, ranking)
    }

    private fun announce(year: Int, month: Int, stamp: String, ranking: List<Int>, renown: Map<Int, Int>) {
        val top = ranking.firstOrNull()?.let { world.getGeneralById(it) }
        val head = top?.let { " 1위 ${it.name}(명망 ${renown[it.id]})" } ?: ""
        Records.world(world, RecordKind.YUEDAN_ANNOUNCED,
            "【월단평】 ${year}년 ${month}월 월단평이 발표되었습니다.$head",
            linkedMapOf("stamp" to stamp, "top" to ranking.take(ANNOUNCED_TOP)))
        world.recordEvent(
            kind = EventKind.YUEDAN_ANNOUNCED,
            audience = AudienceTarget.Public,
            eventKey = EventKey.derive(EventKind.YUEDAN_ANNOUNCED.code,
                world.worldId.value.toString(), stamp),
        )
    }

    /** 종류별 건수와 증감(이번 곡선 기준). 종류 순서는 [RenownEventKind] 선언 순. */
    private fun summarize(applied: List<RenownEntry>): List<Map<String, Any?>> =
        applied.groupBy { it.kind }.entries.sortedBy { it.key.ordinal }.map { (kind, rows) ->
            linkedMapOf("kind" to kind.key, "count" to rows.size, "amount" to kind.amountIn(curve) * rows.size)
        }

    private fun labelOf(row: Map<String, Any?>): String {
        val kind = RenownEventKind.ofKey(row["kind"] as? String ?: "")
            ?: run {
                log.warn("campaign_monthly_assessment_label_unavailable kind={}", row["kind"])
                return "알 수 없는 사건"
            }
        val count = row["count"] as? Int ?: 1
        return if (count > 1) "${kind.label}×$count" else kind.label
    }

    private fun apply(before: TurnGeneral, meta: Map<String, Any?>): Boolean {
        val after = before.copy(meta = meta)
        if (world.applyGeneralDirtyFree(after) == null) {
            log.warn("campaign_monthly_assessment_skipped general={} reason=APPLY_REJECTED", before.id)
            return false
        }
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(MonthlyAssessment::class.java)
        const val STAMP_KEY = RenownAssessment.STAMP_KEY
        const val RANKING_KEY = RenownAssessment.RANKING_KEY
        const val REASONS_KEY = RenownAssessment.REASONS_KEY
        const val TALLY_META_KEY = RenownEvents.META_KEY

        /** 발표 기록에 싣는 상위 순위 수. 전체 순위는 [RANKING_KEY] 에 있다. */
        const val ANNOUNCED_TOP = 10

        fun stampOf(year: Int, month: Int): String = RenownEvents.stampOf(year, month)
    }
}
