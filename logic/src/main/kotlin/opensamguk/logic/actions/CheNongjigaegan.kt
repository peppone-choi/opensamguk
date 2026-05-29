package opensamguk.logic.actions

import opensamguk.logic.stats.GeneralActionPipeline

/**
 * The 9-line-equivalent che actions: thin factory functions over the shared CommerceInvestment
 * algorithm (PHP che_상업투자.php / che_농지개간.php differ ONLY in cityKey/actionKey/name).
 *
 *   - che_상업투자: cityKey="comm", actionKey="상업", name="상업 투자" → mutates city.commerce
 *   - che_농지개간: cityKey="agri", actionKey="농업", name="농지 개간" → mutates city.agriculture
 *
 * statKey is "intel" for both (intelligence-driven domestic). name is WITH a space (PHP).
 */
fun cheSangeobTuja(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
    CommerceInvestment(pipeline, "comm", "intel", "상업", "상업 투자", maxLevel)

fun cheNongjigaegan(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
    CommerceInvestment(pipeline, "agri", "intel", "농업", "농지 개간", maxLevel)
