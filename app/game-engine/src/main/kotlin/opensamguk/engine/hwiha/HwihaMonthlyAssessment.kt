package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.input.HwihaPersonPolicyState
import opensamguk.logic.input.HwihaRenownAssessment
import opensamguk.logic.input.HwihaRenownRules
import opensamguk.logic.input.RuleProfile

/**
 * 월 경계의 「월단평」 — 명망을 갱신하고 순위를 발표한다(정본 설계 §2.8, §5.2 순 경계 4번).
 *
 * 갱신은 **사건 누적식**이다(2026-09-22 사용자 결정). 지난 달 사건은 장수 meta 의
 * [TALLY_META_KEY] 에 쌓이고, 월단평이 그것을 한 번 적용한 뒤 비운다. 가감값·상하한은
 * `data/curated/han/hwiha-renown-assessment-v1.json` 에서 온다 — 코드에 박지 않는다.
 *
 * 도장([STAMP_KEY])으로 한 달에 한 번만 돈다. 징세와 같은 방식이고 같은 flush 에 실린다.
 *
 * ### 아직 이 단계가 하지 않는 것
 *
 * - **사건을 만들지 않는다.** 전공·치적·관직·결속·패전·배신·실정을 기록하는 쪽이 아직 없어서 집계는
 *   대개 비어 있고, 그때 월단평은 명망을 **보존**하고 순위만 발표한다(§2.8: 초기화하지 않는다).
 * - **발령 거절은 집계에 넣지 않는다.** [HwihaDispatchExecutor] 가 거절 시점에 이미 명망을 깎는다.
 *   같은 사건을 집계에도 넣으면 두 번 깎인다. 어느 쪽을 정본으로 둘지는 미결이다.
 * - **이탈을 실제로 반영하지 않는다.** 코스트 상한을 넘은 휘하를 판정해 기록까지 한다. 월드에
 *   해방·이탈 경로가 없고 `releasePolicy`(MASTER_ONLY·MUTUAL)의 처리 규칙이 정해지지 않아,
 *   여기서 행을 지우면 규칙을 지어내는 것이 된다.
 */
class HwihaMonthlyAssessment(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val curve: HwihaRenownAssessment.Curve,
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
        val generalsById = world.listGenerals().associateBy { it.id }
        var assessed = 0
        var moved = 0
        var overCap = 0
        val renownByGeneral = LinkedHashMap<Int, Int>()

        for (general in world.listGenerals().sortedBy { it.id }) {
            val policy = try { HwihaPersonPolicyState.read(general.meta) } catch (_: IllegalArgumentException) { null }
                ?: continue
            assessed++
            val retinue = (retainersByMaster[general.id] ?: emptyList()).mapNotNull { card ->
                val person = card.generalId?.let { generalsById[it] } ?: return@mapNotNull null
                HwihaRenownAssessment.RetainerCard(
                    retainerId = card.id,
                    cost = HwihaRenownRules.personCost(
                        person.stats.leadership, person.stats.strength, person.stats.intelligence,
                        person.stats.politics, person.stats.charm,
                    ),
                    loyalty = card.loyalty,
                )
            }
            val outcome = HwihaRenownAssessment.assess(
                generalId = general.id,
                renown = policy.renownCapacity.coerceIn(curve.floor, curve.ceiling),
                tally = tallyOf(general.meta),
                curve = curve,
                retinue = retinue,
            )
            renownByGeneral[general.id] = outcome.renown
            if (outcome.released.isNotEmpty()) overCap++

            val nextMeta = LinkedHashMap(general.meta)
            nextMeta[HwihaPersonPolicyState.META_KEY] =
                policy.copy(renownCapacity = outcome.renown).toMetaValue()
            // 집계는 적용했으니 비운다 — 남겨 두면 다음 달에 같은 사건이 또 적용된다.
            nextMeta.remove(TALLY_META_KEY)
            if (nextMeta != general.meta) {
                apply(general, nextMeta)
                if (outcome.renown != policy.renownCapacity) moved++
            }
            if (outcome.released.isNotEmpty()) {
                world.pushLog(
                    opensamguk.engine.turn.LogEntryDraft(
                        scope = "general", category = "action",
                        text = "월단평 뒤 휘하 코스트가 명망을 넘어 이탈 판정을 받았습니다: ${outcome.released.joinToString()}",
                        generalId = general.id, nationId = general.nationId,
                    ),
                )
            }
        }

        val ranking = HwihaRenownAssessment.ranking(renownByGeneral)
        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        world.setGameEnvValue(RANKING_KEY, ranking)
        recorder.recordKv("game_env", "game_env", RANKING_KEY, ranking)
        return Outcome(stamp, alreadyStamped = false, assessed, moved, overCap, ranking)
    }

    private fun apply(before: TurnGeneral, meta: Map<String, Any?>) {
        val after = before.copy(meta = meta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        checkNotNull(world.applyGeneralDirtyFree(after))
    }

    companion object {
        const val STAMP_KEY = "hwihaRenownAssessmentStamp"
        const val RANKING_KEY = "hwihaRenownRanking"
        const val TALLY_META_KEY = "hwihaRenownTally"

        fun stampOf(year: Int, month: Int): String = "%04d-%02d".format(year, month)

        /** 집계가 없거나 읽히지 않으면 빈 집계다 — 월 경계에서 던지면 턴 루프가 영구히 멈춘다. */
        fun tallyOf(meta: Map<String, Any?>): HwihaRenownAssessment.Tally {
            val raw = meta[TALLY_META_KEY] as? Map<*, *> ?: return HwihaRenownAssessment.Tally()
            fun at(key: String) = (raw[key] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            return HwihaRenownAssessment.Tally(
                warMerit = at("warMerit"), domesticMerit = at("domesticMerit"),
                office = at("office"), bondEvent = at("bondEvent"),
                defeat = at("defeat"), betrayal = at("betrayal"),
                misrule = at("misrule"), dispatchRefusal = at("dispatchRefusal"),
            )
        }

        /** 사건을 기록하는 쪽이 쓰는 표현 — 월단평이 읽어 비운다. */
        fun tallyToMetaValue(tally: HwihaRenownAssessment.Tally): Map<String, Any?> = linkedMapOf(
            "warMerit" to tally.warMerit, "domesticMerit" to tally.domesticMerit,
            "office" to tally.office, "bondEvent" to tally.bondEvent,
            "defeat" to tally.defeat, "betrayal" to tally.betrayal,
            "misrule" to tally.misrule, "dispatchRefusal" to tally.dispatchRefusal,
        )
    }
}
