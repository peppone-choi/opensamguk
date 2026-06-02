package opensamguk.logic.event

/**
 * By-name registration of the national-betting event leaves into the F2-owned [EventActionFactory]
 * (the lone per-family touch, plan §append protocol — mirrors [LightActions] / `A3EventActions`).
 *
 * Registers the two betting leaves that [EventStore.withDefaults] NAMES at their slots:
 *  - `OpenNationBetting` (month/3000 DateRelative row) — PHP `Event/Action/OpenNationBetting.php`.
 *  - `FinishNationBetting` (the DESTROY_NATION follow-up row OpenNationBetting inserts) — PHP
 *    `Event/Action/FinishNationBetting.php`.
 *
 * Without this family registered, the monthly dispatch of the seeded `OpenNationBetting` row throws
 * `존재하지 않는 Action입니다 :OpenNationBetting` from [EventActionFactory.create] (the P6 betting-crash
 * gap). Registration here makes the names resolvable; the leaf BODIES' PHP fidelity (ctor shape,
 * candidate ranking, per-general messages, the DESTROY_NATION follow-up params) is the P6 betting
 * rework (Betting service / step 5), tracked separately.
 *
 * NOTE: the engine does not yet assemble a full [EventActionFactory] + [EventDispatcher] +
 * MonthlyPipeline (several world leaves — UpdateNationLevel/AssignGeneralSpeciality/… — are still
 * daemon-seam stubs that throw `NotImplementedError`, a P3-completion concern). This registrar is the
 * P6-owned input that the eventual assembler chains alongside [LightActions]/`A3EventActions`.
 */
object BettingActions {
    fun register(factory: EventActionFactory): EventActionFactory {
        OpenNationBettingAction.register(factory)
        FinishNationBettingAction.register(factory)
        return factory
    }
}
