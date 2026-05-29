package opensamguk.logic.actions.founding

import opensamguk.common.constants.CityConst
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinAction
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.beOpeningPart
import opensamguk.logic.constraints.checkNationNameDuplicate
import opensamguk.logic.constraints.reqNationValue
import opensamguk.logic.constraints.wanderingNation
import opensamguk.logic.domain.City
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_무작위건국 — faithful port of `che_무작위건국.php`. Like che_건국 it raises the actor's wandering
 * nation to level 1, but it picks the capital RANDOMLY (rng->choice over neutral level-5/6 cities) and
 * relocates EVERY general of the nation to it, then flags BOTH aux `can_국기변경=1` AND
 * `can_무작위수도이전=1`. It grants NO unifier (only che_건국 does), has NO city constraint in init()
 * (the city is chosen at run-time), and its full list drops both ConstructableCity/NeutralCity AND
 * NoPenalty.
 *
 * Run sequence (`che_무작위건국.php` run() lines 129-224), preserved exactly:
 *   1. same-month guard → che_인재탐색 (inherited block path).
 *   2. candidate cities = `SELECT city WHERE level>=5 AND level<=6 AND nation=0` (DB-ascending) — the
 *      engine-scanned [GeneralActionResolveContext.candidateCityIds]. Empty → push
 *      "건국할 수 있는 도시가 없습니다." + alternative che_해산, return WITHOUT any write.
 *   3. cityID = `rng->choice(cities)` — the ONLY action-stream RNG draw.
 *   4. relocate: actor → chosen city; every OTHER nation general (candidateGenerals) → chosen city
 *      (PHP `UPDATE general SET city=cityID WHERE nation=me`). draft.city ← the chosen city.
 *   5. the shared founding write-set ([CheGeonguk.foundNation]) with the extra aux `can_무작위수도이전=1`.
 *
 * `static::$actionName = '무작위 도시 건국'` (the lottery seed token, distinct from the `che_무작위건국`
 * rawClassName).
 */
class CheMujakwiGeonguk(pipeline: GeneralActionPipeline) : CheGeonguk(pipeline) {

    override val key: String get() = "che_무작위건국"
    override val name: String get() = "무작위 도시 건국"
    override val rawClassName: String get() = "che_무작위건국"
    override val lotteryActionName: String get() = "무작위 도시 건국"

    // che_무작위건국.php:217 — NO unifier grant.
    override val unifierGrant: Int get() = 0

    // che_무작위건국.php:82-85 — min: BeOpeningPart(relYear+1), ReqNationValue('level',…,'==',0). NO NoPenalty.
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beOpeningPart { c, _ -> relYearOf(c) + 1 },
        reqNationValue("level", "국가규모", "==", 0, "정식 국가가 아니어야합니다."),
    )

    // che_무작위건국.php:97-104 — full: BeLord, WanderingNation, ReqNationValue('gennum',…,'>=',2),
    //   BeOpeningPart(relYear+1), CheckNationNameDuplicate(name), AllowJoinAction. NO city / NoPenalty.
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beLord(),
        wanderingNation(),
        reqNationValue("gennum", "수하 장수", ">=", 2),
        beOpeningPart { c, _ -> relYearOf(c) + 1 },
        checkNationNameDuplicate("nationName"),
        allowJoinAction(),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        lastUnifierGrant = 0
        lastAlternative = null

        val d = context.draft
        val nation = d.nation ?: return

        // 1. same-month guard (che_무작위건국.php:145-149).
        if (context.args["sameMonthOrBefore"] == true) {
            context.addLog("다음 턴부터 건국할 수 있습니다. <1>${context.date}</>")
            lastAlternative = ALTERNATIVE_BLOCKED
            return
        }

        // 2. no candidate city → che_해산 (che_무작위건국.php:152-157), no write.
        if (context.candidateCityIds.isEmpty()) {
            context.addLog("건국할 수 있는 도시가 없습니다. <1>${context.date}</>")
            lastAlternative = ALTERNATIVE_NO_CITY
            return
        }

        // 3. rng->choice over the DB-ascending neutral level-5/6 cities — the ONLY action-stream draw.
        val chosenCityId = context.rng.choice(context.candidateCityIds)

        // 4. relocate the actor + every OTHER nation general to the chosen city
        //    (che_무작위건국.php:159-168 `UPDATE general SET city=cityID WHERE nation=me`).
        d.general = d.general.copy(cityId = chosenCityId)
        for (follower in context.candidateGenerals) {
            d.cascadeGenerals.add(follower.copy(cityId = chosenCityId))
        }

        // draft.city ← the chosen city (PHP `$this->setCity()` after the choice). A minimal logic City;
        // foundNation claims it (nation=me, conflict={}).
        d.city = chosenCity(chosenCityId)

        // 5. shared founding write-set + the extra aux can_무작위수도이전=1 (che_무작위건국.php:198-199).
        foundNation(context, nation, chosenCityId, extraAux = listOf("can_무작위수도이전" to 1))
    }

    /** A minimal logic City for the rng-chosen capital (the full row is not on the draft). */
    private fun chosenCity(cityId: Int): City = City(
        id = cityId, nationId = 0, level = CityConst.byId(cityId)?.level ?: 5,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = 1, frontState = 0, trust = 0.0,
    )

    companion object {
        /** The alternative command swapped in when there is no constructable city (che_무작위건국.php:155). */
        const val ALTERNATIVE_NO_CITY: String = "che_해산"
    }
}
