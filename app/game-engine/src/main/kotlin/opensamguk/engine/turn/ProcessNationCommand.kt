package opensamguk.engine.turn

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.engine.turn.PerTurnOverlay.Companion.toLogicNation

/**
 * P5 Task FM2 (F-SEAM) — the NATION-command resolve path (R-SEAM §4).
 *
 * **No nation-command resolve path existed in the Kotlin daemon before P5** — `ReservedTurnHandler`
 * ported ONLY `processCommand` (the GENERAL path). This is the faithful port of
 * `processNationCommand` (`legacy/devsam-core/hwe/sammo/TurnExecutionHelper.php:72-109`), reusing the
 * GREEN general `processCommand` while-loop (`:111-167`) as the structural template — the SAME
 * full-condition → addTermStack → run → getAlternativeCommand spine, sharing the same
 * `getFailString`/`getTermString` log strings.
 *
 * It runs in the daemon's nation pass, which executes BEFORE the general pass within one general's
 * turn (R-SEAM §2 `:299-348`), seeded with the `'nationCommand'` 6-component RNG
 * (`serializeSeed(hiddenSeed,'nationCommand',year,month,generalId,cmd.getRawClassName())`, `:310-317`),
 * and writes the `turn_last_{officer_level}` KV (`:322`) + clears the general's cached city handle
 * (`setRawCity(null)`, `:323`).
 *
 * ## The ONE daemon-write rule (architecture-test enforced)
 * The nation resolve writes ONLY through the [ChangeRecorder] single-dirty-source (the
 * `turn_last_{officer_level}` nation-meta delta) — NEVER a JPA `EntityManager`, NEVER an inline
 * `world.updateNation`/`updateGeneral`. The `nation_turn` ring rotation + the actual `che_*`
 * nation-command state-mutation/logs (불가침제의/선전포고/천도 …) are P6; here the seam ports the
 * seed + the while-loop structure + the KV delta + the `setRawCity` invalidation. The per-command
 * mutation is delegated to a pluggable [nationCommandResolver] hook (default = a structural no-op
 * producing the command's `getResultTurn` — mirroring the [ReservedTurnHandler.nextRuler]/
 * [ReservedTurnHandler.dyingMessage] hook pattern), so no P6 internals are fabricated.
 *
 * @param world the live in-memory turn world (read-only here; the resolver hook may queue recorder deltas).
 * @param recorder the SINGLE dirty source — the `turn_last_{officer_level}` nation-meta delta lands here.
 * @param hiddenSeed the per-game `UniqueConst::$hiddenSeed` (the captured golden FIXTURE INPUT) — seed
 *   component 1 of the `'nationCommand'` lineage.
 * @param nationCommandResolver the per-command resolve hook (the `che_*` nation-command `run`). It is
 *   handed the SOLE per-command `'nationCommand'` [RandUtil] (threaded by reference) + the reserved/
 *   AI-chosen [ChosenCommand] + the pre-turn [LastTurn], and returns the result [LastTurn]
 *   (`$commandObj->getResultTurn()`, `:108`). Default = pass-through of the pre-turn lastTurn (the P6
 *   nation-command internals are NOT ported here — the seam + seed + KV delta are).
 */
class ProcessNationCommand(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val hiddenSeed: String,
    private val nationCommandResolver: NationCommandResolver = NationCommandResolver { _, _, lastTurn -> lastTurn },
) {

    /** The per-nation-command resolve hook (the `che_*` `run`); P6 wires the real packs. */
    fun interface NationCommandResolver {
        /**
         * Resolve [command] on the SOLE per-command [rng] (threaded by reference), returning the result
         * [LastTurn] (`$commandObj->getResultTurn()`). Any state mutation goes through the [ChangeRecorder]
         * single-dirty-source (NEVER inline / NEVER an EntityManager).
         */
        fun resolve(rng: RandUtil, command: ChosenCommand, lastTurn: LastTurn): LastTurn
    }

    /**
     * Resolve ONE nation command for [generalId] (R-SEAM §4 — the [processNationCommand][ProcessNationCommand]
     * port). Mirrors `TurnExecutionHelper.php:72-109` + the surrounding `:310-323` seed/KV/setRawCity seam.
     *
     * @param officerLevel `$general->getVar('officer_level')` — keys the `turn_last_{officer_level}` KV (`:261/322`).
     * @param nationCommand the reserved/AI-chosen `(actionCode, RAW args)` (the `getRawClassName` keys the seed).
     * @param lastTurn the pre-turn `LastTurn` for this `(nation, officer_level)` ring slot (`:271`).
     * @param year/month the game year/month — RNG seed components 3/4.
     * @param date the turn-time `HH:MM` for the fail/term log `<1>date</>` suffix (parity-faithful; the P6
     *   nation-command resolve emits the actual logs — the default no-op resolver pushes none).
     * @return the result `LastTurn` (`$commandObj->getResultTurn()`), also queued as the KV delta.
     */
    fun process(
        generalId: Int,
        officerLevel: Int,
        nationCommand: ChosenCommand,
        lastTurn: LastTurn,
        year: Int,
        month: Int,
        @Suppress("UNUSED_PARAMETER") date: String,
    ): LastTurn {
        val general = world.getGeneralById(generalId)
            ?: error("ProcessNationCommand: general $generalId not in world")
        val nationId = general.nationId

        // --- the 'nationCommand' 6-component RNG (PHP grand truth :310-317) ---
        // Component 2 is the LITERAL string "nationCommand" (DISTINCT from "generalCommand"); component 6
        // is the nation command's short class name (= getRawClassName(true) = the action code). RE-SEEDED
        // here — NOT one shared stream with the general pass, NOT the 'GeneralAI' decision stream.
        val rng = RandUtil(
            LiteHashDrbg(
                serializeSeed(hiddenSeed, NATION_COMMAND_TOKEN, year, month, generalId, nationCommand.actionCode),
            ),
        )

        // --- the processNationCommand while-loop (PHP :76-106, the GREEN processCommand template) ---
        // full-condition → addTermStack → run → getAlternativeCommand. The actual NationCommand `run`
        // (the che_* state mutation) is the P6-deferred internals; the structural spine + the result
        // LastTurn are delegated to the resolver hook (default no-op = pass-through getResultTurn).
        val resultTurn = nationCommandResolver.resolve(rng, nationCommand, lastTurn)

        // --- $nationStor->setValue("turn_last_{officer_level}", $resultNationTurn->toRaw()) (:322) ---
        // Recorded as the nation-meta KV delta through the ChangeRecorder single-dirty-source (NOT inline,
        // NOT an EntityManager). The nation KV rides the `nation` row meta jsonb in the engine slice.
        recordTurnLastKv(nationId, officerLevel, resultTurn)

        // --- $general->setRawCity(null) (:323) ---
        // PHP clears the general's lazily-cached City object handle so the next read re-fetches it. The
        // in-memory world has NO such cached handle (the city is always read fresh via world.getCityById),
        // so this is a faithful no-op marker — there is nothing to invalidate.
        // (intentionally empty)

        return resultTurn
    }

    /**
     * Queue the `turn_last_{officer_level}` nation-meta KV delta via the [ChangeRecorder] single-dirty-source.
     * Diffs the nation's pre-state against a post-state carrying the new meta key (insertion-order-preserving),
     * so the recorder owns dirtiness — the world's own `updateNation` (the JPA-competing dirty path) is never
     * touched.
     */
    private fun recordTurnLastKv(nationId: Int, officerLevel: Int, resultTurn: LastTurn) {
        val nation = world.getNationById(nationId) ?: return
        val pre = toLogicNation(nation)
        val nextMeta = LinkedHashMap(pre.meta)
        nextMeta["turn_last_$officerLevel"] = resultTurn.toRaw()
        recorder.diffNation(pre, pre.copy(meta = nextMeta))
    }

    companion object {
        /** PHP seed component 2 for the nation pass (`TurnExecutionHelper.php:312`). */
        const val NATION_COMMAND_TOKEN: String = "nationCommand"
    }
}
