package opensamguk.engine.turn

import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView

/**
 * The daemon-side [StateView] (P1 Task F1) — implements the SAME `:logic` StateView the
 * game-api precheck uses, but resolves rows from the live [PerTurnOverlay]+[InMemoryTurnWorld]
 * (full mode). This is the structural proof of "one constraint library": the daemon evaluates the
 * EXACT constraints the precheck did, only the row source differs.
 *
 * Faithful port of `app/game-engine/src/turn/ai/generalAi/worldStateView.ts`:
 *  - general/city/nation reads go through the overlay (staged-first, fall through to world),
 *    converted engine -> logic by [PerTurnOverlay];
 *  - `env`/`arg` come from the maps supplied by the handler (the SAME shared env-builder as
 *    precheck — F3 wires that in);
 *  - `has` == `get != null` for row keys (TS `has` delegates to `get`); `Env`/`Arg` use map
 *    containment so a present-but-null env value is still "has".
 *
 * In FULL mode the overlay always has the slice rows, so `get` never returns null for the
 * actor's general/city/nation — matching the TS full-mode contract where the constraint `test()`
 * runs regardless.
 */
class WorldStateViewAdapter(
    private val overlay: PerTurnOverlay,
    private val env: Map<String, Any?> = emptyMap(),
    private val args: Map<String, Any?> = emptyMap(),
) : StateView {

    override fun has(req: RequirementKey): Boolean = when (req) {
        is RequirementKey.General -> overlay.getLogicGeneral(req.id) != null
        is RequirementKey.City -> overlay.getLogicCity(req.id) != null
        is RequirementKey.Nation -> overlay.getLogicNation(req.id) != null
        is RequirementKey.Env -> env.containsKey(req.key)
        is RequirementKey.Arg -> args.containsKey(req.key)
        // CD1 — dest-* family resolves through the SAME overlay reads; collections/diplomacy from the world.
        is RequirementKey.DestGeneral -> overlay.getLogicGeneral(req.id) != null
        is RequirementKey.DestCity -> overlay.getLogicCity(req.id) != null
        is RequirementKey.DestNation -> overlay.getLogicNation(req.id) != null
        is RequirementKey.NationList -> true
        is RequirementKey.GeneralList -> true
        is RequirementKey.Diplomacy -> overlay.getLogicDiplomacy(req.me, req.you) != null
    }

    override fun get(req: RequirementKey): Any? = when (req) {
        is RequirementKey.General -> overlay.getLogicGeneral(req.id)
        is RequirementKey.City -> overlay.getLogicCity(req.id)
        is RequirementKey.Nation -> overlay.getLogicNation(req.id)
        is RequirementKey.Env -> env[req.key]
        is RequirementKey.Arg -> args[req.key]
        is RequirementKey.DestGeneral -> overlay.getLogicGeneral(req.id)
        is RequirementKey.DestCity -> overlay.getLogicCity(req.id)
        is RequirementKey.DestNation -> overlay.getLogicNation(req.id)
        is RequirementKey.NationList -> overlay.listLogicNations()
        is RequirementKey.GeneralList -> overlay.listLogicGenerals()
        is RequirementKey.Diplomacy -> overlay.getLogicDiplomacy(req.me, req.you)
    }
}
