package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2SourceKey

/** The source ID remains a string: converting it to a JSON number would change the pinned identity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BattleV2WireSourceKey(val kind: String, val sourceId: String, val cityId: Int? = null) {
    constructor(key: TacticalV2SourceKey) : this(key.kind.name, key.sourceId, key.cityId)
}

data class BattleV2WireCell(val row: Int, val col: Int) {
    constructor(cell: TacticalV2Cell) : this(cell.row, cell.col)
}

data class BattleV2OwnUnit(val sourceKey: BattleV2WireSourceKey, val cell: BattleV2WireCell,
                           val troops: Int)
data class BattleV2OwnPosition(val sourceKey: BattleV2WireSourceKey, val cell: BattleV2WireCell)
data class BattleV2SnapshotField(val boardId: Int, val kind: String,
                                 val terrainInputSha256: String)
data class BattleV2SnapshotDeployment(val revision: String,
                                      val allowedCells: List<BattleV2WireCell>,
                                      val ownPositions: List<BattleV2OwnPosition>)
data class BattleV2JoiningSnapshot(
    val schemaVersion: Int = 2,
    val t: String = "SNAPSHOT",
    val worldId: Int,
    val battleId: String,
    val sessionEpoch: String,
    val tick: Int,
    val eventSeq: String,
    val phase: String = "JOINING",
    val joinDeadlineAt: String,
    val authorityRevision: String,
    val field: BattleV2SnapshotField,
    val ownUnits: List<BattleV2OwnUnit>,
    val deployment: BattleV2SnapshotDeployment,
)

/**
 * The only v2 state presently pinned by a real ticket is JOINING placement at tick zero.
 * A RUNNING snapshot requires a durable v2 actor state and tactical LOS observations.
 * This publisher therefore fails closed on any event or tick it cannot replay.
 */
class BattleV2JoiningSnapshotPublisher(
    private val tickets: BattleJoinTicketService,
    private val generals: GeneralResolver,
    private val store: BattleSessionStore,
    private val frozen: BattleV2FrozenPlacementCodec,
    private val catalog: TacticalBoardCatalog,
    private val mapper: ObjectMapper,
) {
    fun snapshot(identity: BattleJoinIdentity): String {
        check(tickets.isCurrent(identity) &&
            generals.resolveGeneralId(identity.accountId.toLong()) == identity.generalId) {
            "battle socket authority expired"
        }
        val ticket = requireNotNull(store.ticket(identity.worldId, identity.battleId))
        val head = requireNotNull(store.head(identity.worldId, identity.battleId))
        require(ticket.worldId == identity.worldId && ticket.battleId == identity.battleId &&
            head.worldId == identity.worldId && head.battleId == identity.battleId &&
            head.sessionEpoch == identity.sessionEpoch && head.phase == BattleSessionPhase.JOINING &&
            head.currentTick == 0 && head.latestEventSeq == 0L && head.latestSnapshotSeq == 0L &&
            head.joinDeadlineAt == ticket.joinDeadlineAt) {
            "v2 joining state is unavailable"
        }
        val participant = ticket.participants.singleOrNull {
            it.participantId == identity.participantId && it.accountId == identity.accountId &&
                it.generalId == identity.generalId && it.side == identity.side &&
                it.authorityRevision == identity.authorityRevision
        } ?: throw SecurityException("battle participant unavailable")
        val deployment = frozen.initialDeployment(ticket)
        val board = catalog.boards.singleOrNull { it.battlefield.id == deployment.boardId }
            ?: error("pinned battle board unavailable")
        val side = BattleSide.valueOf(participant.side)
        val ownedKeys = BattleV2AuthorityScopes.resolve(ticket.participants, deployment.units)
            .getValue(participant.participantId)
        val ownUnits = deployment.units.filter { it.key in ownedKeys }.map { unit ->
            BattleV2OwnUnit(BattleV2WireSourceKey(unit.key),
                BattleV2WireCell(deployment.cells.getValue(unit.key)), unit.initialTroops)
        }
        val deploymentView = BattleV2SnapshotDeployment(deployment.revision.toString(),
            deployment.allowedCells.getValue(side).sorted().map(::BattleV2WireCell),
            ownUnits.map { BattleV2OwnPosition(it.sourceKey, it.cell) })
        val frame = BattleV2JoiningSnapshot(worldId = identity.worldId.value,
            battleId = identity.battleId, sessionEpoch = head.sessionEpoch.toString(),
            tick = head.currentTick, eventSeq = head.latestEventSeq.toString(),
            joinDeadlineAt = head.joinDeadlineAt.toString(),
            authorityRevision = participant.authorityRevision.toString(),
            field = BattleV2SnapshotField(deployment.boardId, board.battlefield.kind,
                deployment.terrainSha256), ownUnits = ownUnits, deployment = deploymentView)
        val latest = store.head(identity.worldId, identity.battleId)
        check(latest != null && latest.sessionEpoch == head.sessionEpoch &&
            latest.phase == head.phase && latest.currentTick == head.currentTick &&
            latest.latestEventSeq == head.latestEventSeq && latest.latestSnapshotSeq == head.latestSnapshotSeq &&
            tickets.isCurrent(identity)) { "v2 joining state changed during projection" }
        return mapper.writeValueAsString(frame)
    }
}
