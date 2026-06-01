package opensamguk.engine.run

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.auction.AuctionBidHandler
import opensamguk.engine.auction.AuctionFinalizeHandler
import opensamguk.engine.turn.InMemoryTurnWorld

/**
 * Routes drained [TurnDaemonCommand]s to their engine handlers.
 *
 * **P6 keystone seam.** Before this, [opensamguk.engine.redis.RedisCommandStream.readCommands]'s
 * result was DISCARDED in [TurnRunService.runTick] (the inline comment admitted the dispatcher was
 * "assembled by the consuming P3 waves" and never built), so every command-intake feature — auction
 * bids/finalize, and the P6/P7 commands that follow — was inert. This dispatcher routes each drained
 * command to the handler that owns its type.
 *
 * **Partial by design (incremental P6 build).** Only the command types with a built engine handler
 * are routed; everything else returns `null` = "no engine handler wired yet". That covers two
 * distinct cases that both legitimately produce no result here:
 *  - **control commands** (Run/Pause/Resume/Shutdown/GetStatus) that gate *when* the daemon ticks —
 *    they are consumed by [opensamguk.engine.redis.RedisCommandStream] advancing its cursor, not by a
 *    state-mutating handler;
 *  - **not-yet-built handlers** (troop/tournament/permission/vote/… — most of the ~26 command types)
 *    that later P6/P7 waves will plug in by adding a `when` branch here.
 *
 * Handlers are per-run plain classes built against the live [InMemoryTurnWorld] (the snapshot source
 * of truth), mirroring the sibling turn handlers — NOT Spring beans (the world is per-run state).
 *
 * Result publishing (the events-stream `commandResult` channel) remains deferred per the P1 DECISION
 * in [TurnRunService]; the caller currently discards the returned result.
 */
class TurnDaemonCommandDispatcher(world: InMemoryTurnWorld) {
    private val auctionBid = AuctionBidHandler(world)
    private val auctionFinalize = AuctionFinalizeHandler(world)

    /**
     * Dispatch one command to its handler.
     *
     * @return the handler's [TurnDaemonCommandResult], or `null` when no engine handler is wired for
     *   this command type yet (control commands + not-yet-built handlers).
     */
    fun dispatch(command: TurnDaemonCommand): TurnDaemonCommandResult? = when (command) {
        is TurnDaemonCommand.AuctionBid -> auctionBid.handle(command)
        is TurnDaemonCommand.AuctionFinalize -> auctionFinalize.handle(command)
        else -> null
    }

    /** Dispatch a batch (one drained tick's worth), returning only the non-null results in order. */
    fun dispatchAll(commands: List<TurnDaemonCommand>): List<TurnDaemonCommandResult> =
        commands.mapNotNull { dispatch(it) }
}
