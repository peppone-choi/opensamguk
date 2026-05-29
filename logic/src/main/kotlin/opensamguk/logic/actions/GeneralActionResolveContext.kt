package opensamguk.logic.actions

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv

/** Mutable per-turn draft. The resolver mutates these in place (the Immer-draft replacement);
 *  ChangeRecorder (game-engine) diffs pre/post to derive patch+dirty. */
class GeneralActionDraft(var general: General, var city: City, var nation: Nation?)

class GeneralActionResolveContext(
    val draft: GeneralActionDraft,
    val rng: RandUtil,
    val env: WorldEnv,
    val month: Int,                        // game month — the ActionLogger MONTH-format prefix companion to env.year
    val date: String,                      // turn-time HH:MM for the log <1>date</>
    private val logs: MutableList<String> = mutableListOf(),
) {
    /**
     * Buffer an action-log line, applying PHP `ActionLogger::pushGeneralActionLog`'s DEFAULT
     * `MONTH` format (ActionLogger.php:135 + formatText:250): `<C>●</>{month}월:{body}`.
     * The resolver passes the BODY (mirroring che `run()` which passes the body to
     * `pushGeneralActionLog`); the logger boundary owns the month prefix. (G2 byte oracle.)
     */
    fun addLog(text: String) { if (text.isNotEmpty()) logs.add("<C>●</>${month}월:$text") }
    fun logs(): List<String> = logs.toList()
}
