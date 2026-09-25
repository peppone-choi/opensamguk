package opensamguk.engine.campaign

import opensamguk.common.josa.JosaUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.RecordKind
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.renown.RenownHooks
import org.slf4j.LoggerFactory

/**
 * 월단평 사건을 월드에 쌓는 엔진 어댑터 — 순수 훅([RenownHooks]·[RenownEvents])이 돌려준 meta 를
 * ChangeRecorder 경로로 저장하고, 새로 쌓인 사건만 그 장수의 개인 기록에 남긴다.
 *
 * ### 다른 흐름(hwiha-s3-core)이 부르는 자리
 *
 * - 조우 전투 한 판을 판정한 **뒤**: [onEncounterResolved] — 이긴 쪽·진 쪽 지휘 장수 id.
 * - 縣 함락을 정산하고 소유를 넘긴 **뒤**: [onCountyCaptured] — 점령 전 소유 세력과 점령한 장수 id.
 *
 * 같은 달 같은 종류는 한 번만 쌓인다(순수 규칙). 같은 턴에 두 번 불러도 두 번째는 무동작이다.
 * 쓰기는 호출한 턴의 flush 에 함께 실린다 — 따로 flush 하지 않는다.
 */
class RenownEventRecorder(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    /**
     * 한 장수에게 사건 한 건. @return 새로 쌓였으면 true. 없는 장수·이미 쌓인 달이면 false.
     *
     * @param stamp 사건이 일어난 달. 기본은 지금 세계의 달이다. 월 경계에서 지난 달을 닫는 쪽(치적)만 넘긴다.
     */
    fun record(generalId: Int, source: RenownEventSource, stamp: String = currentStamp(world)): Boolean {
        val before = world.getGeneralById(generalId) ?: return false
        val result = RenownEvents.recordRenownEvent(before.meta, source, stamp)
        if (!result.recorded) return false
        if (!apply(generalId, result.meta)) return false
        announce(world, generalId, source, stamp)
        return true
    }

    /**
     * 조우 판정 뒤 한 번. 이긴 쪽 → 전공(조우 승리), 진 쪽 → 패전(조우 패배). 무승부면 부르지 않는다.
     * @return 새로 사건이 쌓인 장수 id(오름차순).
     */
    fun onEncounterResolved(winnerIds: Collection<Int>, loserIds: Collection<Int>): List<Int> {
        val state = world.getState()
        return applyAll(RenownHooks.onEncounterResolved(winnerIds, loserIds, state.currentYear, state.currentMonth) {
            world.getGeneralById(it)?.meta
        })
    }

    /**
     * 縣 함락 정산 뒤 한 번. 점령한 장수 → 전공(縣 점령), 그 縣 을 관할하던 장수([RenownHooks.countyHolderIds],
     * [previousNationId] 기준) → 패전(縣 상실). 두 세력 앞으로 공개 기록(점령·상실)을 남긴다 — 縣 소유는 지도에
     * 드러나는 공개 정보다.
     *
     * @param previousNationId 점령 **전** 소유 세력. 0 이면 무주지라 상실 기록·패전이 없다.
     * @return 새로 사건이 쌓인 장수 id(오름차순).
     */
    fun onCountyCaptured(countyId: Int, previousNationId: Int, captorNationId: Int, capturerIds: Collection<Int>): List<Int> {
        if (previousNationId == captorNationId) {
            log.warn("hwiha_renown_capture_skipped county={} reason=SAME_OWNER nation={}", countyId, captorNationId)
            return emptyList()
        }
        val holders = if (previousNationId == 0) emptyList() else RenownHooks.countyHolderIds(countyId,
            previousNationId, world.listGenerals().associate { it.id to it.meta })
        val state = world.getState()
        val updated = applyAll(RenownHooks.onCountyCaptured(capturerIds, holders - capturerIds.toSet(),
            state.currentYear, state.currentMonth) { world.getGeneralById(it)?.meta })
        val name = world.getCityById(countyId)?.name ?: "縣 $countyId"
        val refs = linkedMapOf<String, Any?>("countyId" to countyId, "fromNationId" to previousNationId,
            "toNationId" to captorNationId)
        if (captorNationId != 0) Records.nation(world, captorNationId, RecordKind.COUNTY_CAPTURED,
            "${JosaUtil.put(name, "을")} 점령했습니다.", refs)
        if (previousNationId != 0) Records.nation(world, previousNationId, RecordKind.COUNTY_LOST,
            "${JosaUtil.put(name, "을")} 잃었습니다.", refs)
        return updated
    }

    private fun applyAll(updates: List<RenownHooks.MetaUpdate>): List<Int> = updates.mapNotNull { update ->
        val source = update.entry.source
        if (source == null) {
            log.warn("hwiha_renown_event_skipped general={} reason=MISSING_SOURCE", update.generalId)
            return@mapNotNull null
        }
        if (!apply(update.generalId, update.meta)) return@mapNotNull null
        announce(world, update.generalId, source, update.entry.stamp)
        update.generalId
    }

    private fun apply(generalId: Int, meta: Map<String, Any?>): Boolean {
        val before = world.getGeneralById(generalId)
        if (before == null) {
            log.warn("hwiha_renown_event_skipped general={} reason=MISSING_GENERAL", generalId)
            return false
        }
        val after = before.copy(meta = meta)
        if (world.applyGeneralDirtyFree(after) == null) {
            log.warn("hwiha_renown_event_skipped general={} reason=APPLY_REJECTED", generalId)
            return false
        }
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(RenownEventRecorder::class.java)
        /** 새로 쌓인 사건을 본인 개인 기록에 남긴다 — 종류·원인은 본인 것이다. */
        internal fun announce(
            world: InMemoryTurnWorld,
            generalId: Int,
            source: RenownEventSource,
            stamp: String = currentStamp(world),
        ) = Records.general(world, generalId, RecordKind.RENOWN_EVENT,
            "월단평 사건 「${source.kind.label}」(${source.label})이 기록되었습니다. 다음 월단평에 반영됩니다.",
            linkedMapOf("kind" to source.kind.key, "source" to source.name, "stamp" to stamp))

        internal fun currentStamp(world: InMemoryTurnWorld): String =
            world.getState().let { RenownEvents.stampOf(it.currentYear, it.currentMonth) }
    }
}
