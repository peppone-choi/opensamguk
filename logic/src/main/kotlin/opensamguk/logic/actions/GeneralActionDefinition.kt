package opensamguk.logic.actions

import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext

/**
 * A single reservable command (one PHP `che_*`/`cr_*` class).
 *
 * P2 (Task FS1) widens the P1 minimal iface with:
 *  - a min-vs-full constraint split (`buildMinConstraints` — the cheap `testMinConditionMet`
 *    preview list; defaults to [buildConstraints] for commands without a split),
 *  - `parseArgs` / `argsSchema` (reqArg commands normalize their raw arg map; default identity),
 *  - `reservable` (등용수락 is registered but non-reservable — PHP `che_등용수락.php:66`),
 *  - `category` (the UI/registry grouping: 내정/군사/인사/국가/…),
 *  - **`rawClassName`** — the PHP short class name INCLUDING the `che_`/`cr_` prefix
 *    (`getRawClassName(true)` = `Util::getClassNameFromObj`, BaseCommand.php:261-266). It is the
 *    6th `serializeSeed` component of the per-action RNG seed, carried EXPLICITLY per command and
 *    NOT derived from [key]/[name] — most commands have `class == che_ + de-spaced name == key`, so
 *    the default is [key], but the divergent commands (che_랜덤임관, cr_맹훈련, cr_건국) pin it.
 *  - **`lotteryActionName`** — the SEPARATE trailing unique-item-lottery seed token
 *    (`static::$actionName`, the SPACED action name), kept distinct from [rawClassName]
 *    (e.g. che_랜덤임관: rawClassName `che_랜덤임관` vs lotteryActionName `무작위 국가로 임관`).
 *    Defaults to [name].
 */
interface GeneralActionDefinition {
    val key: String
    val name: String

    /** UI/registry grouping (내정/군사/인사/국가/외교/자원교역/…). Default `기타`. */
    val category: String get() = "기타"

    /** Whether the command can be queued in the reserved-turn list. 등용수락 is NOT (PHP che_등용수락.php:66). */
    val reservable: Boolean get() = true

    /** The declared arg shape (key → type tag). Empty = no args. Drives parseArgs / the UI form. */
    val argsSchema: Map<String, Any?> get() = emptyMap()

    val formSpec: CommandFormSpec get() = CommandFormSpec.fromArgsSchema(argsSchema)

    /**
     * The 6th `serializeSeed` token = PHP `getRawClassName(true)` = the short class name WITH the
     * `che_`/`cr_` prefix. Carried explicitly; defaults to [key] (most classes: che_+de-spaced-name).
     * Pin it for the divergent commands (che_랜덤임관, cr_맹훈련, cr_건국 — distinct from che_건국).
     */
    val rawClassName: String get() = key

    /** The trailing unique-item-lottery seed token = PHP `static::$actionName` (the SPACED name). */
    val lotteryActionName: String get() = name

    /**
     * The FULL-condition constraint list (`testFullConditionMet`, BaseCommand.php:377-413).
     * Evaluated when the command actually runs / a full availability check is requested.
     */
    fun buildConstraints(ctx: ConstraintContext): List<Constraint>

    /**
     * The MIN-condition constraint list (`testMinConditionMet`) — the cheap availability preview.
     * Defaults to [buildConstraints] for commands without a min/full split.
     */
    fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = buildConstraints(ctx)

    /** Normalize the raw reserved arg map into the command's canonical arg map. Default identity. */
    fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        return if (matchesArgsSchema(raw)) raw else emptyMap()
    }

    fun parseArgsForGeneral(raw: Map<String, Any?>, generalId: Int): Map<String, Any?> = parseArgs(raw)

    fun matchesArgsSchema(args: Map<String, Any?>): Boolean = argsSchema.all { (key, type) ->
        val value = args[key] ?: return@all false
        when (type) {
            "int" -> value is Int
            "bool" -> value is Boolean
            "string" -> value is String
            "intList" -> value is List<*> && value.all { it is Int }
            else -> true
        }
    }

    fun bindArgs(parsed: Map<String, Any?>): GeneralActionDefinition = this

    fun resolve(context: GeneralActionResolveContext)
}
