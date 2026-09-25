package opensamguk.engine.hwiha

import opensamguk.logic.vision.ScoutInputCodec
import opensamguk.logic.vision.ScoutFailure
import opensamguk.logic.vision.ScoutAssessment
import opensamguk.logic.vision.ScoutRules
import opensamguk.logic.vision.ScoutReports
import opensamguk.logic.vision.ScoutCityFact
import opensamguk.logic.vision.ScoutCapture

import opensamguk.logic.vision.VisionRules

import opensamguk.engine.turn.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Pinned geography the scout action needs; absent outside a Han HWIHA world. */
data class HwihaVisionContext(
    val topology: StrategicTopologySnapshot,
    val metrics: LandMarchMetricSnapshot,
    val commanderies: HanCommanderyIndex,
    val rules: VisionRules.Rules = VisionRules.CANON,
)

/**
 * `action.scout` — §12.1 direct scouting, resolved at §5.1 step 7 from the actor's current position.
 * A reserved field action suppresses the automatic movement stage for that turn
 * ([HwihaAssignmentMarchTurn.onTurn]), so the position read here is the position of the whole turn.
 *
 * The snapshot is written only to the actor's own meta (`hwihaScoutReports`) through the recorder, together
 * with the personal-turn stamp; it is never shared with the nation (spec §7 has no sharing rule).
 */
class HwihaScoutHandler(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaVisionContext?) {
    fun handle(actorId: Int, argJson: String?, reservationOwnerUserId: Int?): HwihaTurnOutcome {
        fun reject(reason: ScoutFailure) =
            HwihaTurnOutcome.Rejected(ScoutInputCodec.INPUT_ID, reason.name, ScoutRules.reason(reason))
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(ScoutFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(ScoutFailure.STATE_UNAVAILABLE)
        if (reservationOwnerUserId == null || reservationOwnerUserId <= 0 ||
            actor.userId?.toLongOrNull() != reservationOwnerUserId.toLong())
            return HwihaTurnOutcome.Rejected(ScoutInputCodec.INPUT_ID, "FORBIDDEN", "예약한 장수의 소유권이 변경되어 첩보할 수 없습니다.")
        val input = ScoutInputCodec.parse(actorId, argJson) ?: return reject(ScoutFailure.INVALID_INPUT)
        val context = context ?: return reject(ScoutFailure.STATE_UNAVAILABLE)
        val projection = HwihaDeploymentExecutor(world, recorder, context.topology, context.metrics).projection()
            ?: return reject(ScoutFailure.STATE_UNAVAILABLE)
        val assessed = ScoutRules.assess(world.ruleProfile, world.positionOf(actorId), input.commanderyId, context.commanderies)
        if (assessed is ScoutAssessment.Rejected) return reject(assessed.reason)
        assessed as ScoutAssessment.Eligible
        // A corrupt notebook is not overwritten with a fresh one (that would silently discard sightings).
        val previous = try { ScoutReports.read(actor.meta) } catch (_: IllegalArgumentException) {
            return reject(ScoutFailure.STATE_UNAVAILABLE)
        }
        val state = world.getState()
        val now = HwihaPhase(state.currentYear, state.currentMonth, state.currentPhase)
        val cities = world.listCities().sortedBy { it.id }.mapNotNull { city ->
            val province = (world.landNodeOfCity(city.id) as? StrategicNodeRef.LandProvince)?.id ?: return@mapNotNull null
            ScoutCityFact(city.id, province, city.nationId, CountyWarehouse.META_KEY in city.meta)
        }
        val report = ScoutCapture.capture(assessed.target, context.commanderies, cities, projection, context.rules, now)
        val notebook = (previous?.takeIf { it.tilesContentHash == context.commanderies.tilesContentHash }
            ?: ScoutReports(context.commanderies.tilesContentHash, emptyList())).with(report)
        val after = actor.copy(meta = actor.meta + (ScoutReports.META_KEY to notebook.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
        world.pushLog(LogEntryDraft(scope = "general", category = "action",
            text = "${assessed.target.name}의 형세를 몸소 살폈습니다.", generalId = actorId, nationId = after.nationId))
        return HwihaTurnOutcome.Applied(ScoutInputCodec.INPUT_ID)
    }
}
