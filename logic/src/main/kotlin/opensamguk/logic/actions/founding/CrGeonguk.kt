package opensamguk.logic.actions.founding

import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinAction
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.beOpeningPart
import opensamguk.logic.constraints.checkNationNameDuplicate
import opensamguk.logic.constraints.neutralCity
import opensamguk.logic.constraints.reqNationValue
import opensamguk.logic.constraints.wanderingNation
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * cr_건국 — faithful port of `cr_건국.php`. A DISTINCT class from che_건국 (its 6th `serializeSeed`
 * component is `cr_건국`, mb_strlen 5), differing from che_건국 in exactly two ways:
 *   - the city constraint is `NeutralCity` (not `ConstructableCity`) and the full list drops `NoPenalty`
 *     (cr_건국.php:98-106 vs che_건국.php:100-109);
 *   - run() grants **NO unifier** (cr_건국.php:202 omits the `unifier, 250` increment — the divergence).
 * Everything else (level 0→1 claim, aux can_국기변경=1, exp/ded +1000, active_action+1, same-month guard
 * → che_인재탐색, logs, setResultTurn, lottery) is identical and inherited from [CheGeonguk].
 */
class CrGeonguk(pipeline: GeneralActionPipeline) : CheGeonguk(pipeline) {

    override val key: String get() = "cr_건국"
    override val rawClassName: String get() = "cr_건국"

    // cr_건국.php:202 — NO `increaseInheritancePoint(unifier, 250)` (the divergence).
    override val unifierGrant: Int get() = 0

    // cr_건국.php:105 — NeutralCity instead of ConstructableCity.
    override fun cityConstraint(): Constraint = neutralCity()
    // cr_건국.php:98-106 — the full list has NO NoPenalty.
    override fun extraFullConstraints(): List<Constraint> = emptyList()

    // cr_건국.php:83-86 — min: BeOpeningPart(relYear+1), ReqNationValue('level',…,'==',0). NO NoPenalty.
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beOpeningPart { c, _ -> relYearOf(c) + 1 },
        reqNationValue("level", "국가규모", "==", 0, "정식 국가가 아니어야합니다."),
    )

    // cr_건국.php:98-106 — full: BeLord, WanderingNation, ReqNationValue('gennum',…,'>=',2),
    //   BeOpeningPart(relYear+1), CheckNationNameDuplicate(name), AllowJoinAction, NeutralCity.
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beLord(),
        wanderingNation(),
        reqNationValue("gennum", "수하 장수", ">=", 2),
        beOpeningPart { c, _ -> relYearOf(c) + 1 },
        checkNationNameDuplicate("nationName"),
        allowJoinAction(),
        neutralCity(),
    )
}
