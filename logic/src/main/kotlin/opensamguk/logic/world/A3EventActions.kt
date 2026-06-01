package opensamguk.logic.world

import opensamguk.logic.event.EventActionFactory

/**
 * A3 — the by-name registration of the AREA-A3 Action leaves into the F2-owned [EventActionFactory].
 *
 * Per the plan §append protocol, the lone per-family touch on F2-owned code is registering each
 * concrete leaf into the factory by its PHP class name. F2 already authors the EventStore row seed
 * (`month/1000` `true` → `[UpdateNationLevel, ProvideNPCTroopLeader]`); A3 only supplies + registers
 * the two leaves (it does NOT author rows or assign ids).
 *
 * **World-mutation seam:** the leaf `run(ctx)` bodies bind the GREEN, byte-tested pure cores
 * ([UpdateNationLevel] target/side-effect/lottery + [ProvideNPCTroopLeader] mint planner) to the live
 * world. That binding (nation/general/city reads + writes + history-log push + the GeneralBuilder mint
 * + KV counter) is the daemon/G1 world-context seam, which is NOT present in the A3 build wave (peer
 * families + the daemon wiring land later). Until G1 supplies the richer context, the leaf `run` is a
 * documented stub — the byte-match-critical mechanics are exercised through the pure cores' own tests.
 */
object A3EventActions {

    /** Register the `UpdateNationLevel` + `ProvideNPCTroopLeader` leaves into [factory] by name. */
    fun register(factory: EventActionFactory): EventActionFactory = factory
        .register("UpdateNationLevel") { UpdateNationLevelAction() }
        .register("ProvideNPCTroopLeader") { ProvideNPCTroopLeaderAction() }
}


