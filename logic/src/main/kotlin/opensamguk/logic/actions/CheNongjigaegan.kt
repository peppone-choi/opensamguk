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

// DV1 — the strength-stat develop family: PHP che_성벽보수/수비강화/치안강화 all `extends che_상업투자`
// with statKey='strength' and a distinct cityKey/actionKey/name (the 농지개간-extends-상업투자 pattern):
//   - che_성벽보수: cityKey="wall", actionKey="성벽", name="성벽 보수" → mutates city.wall
//   - che_수비강화: cityKey="def",  actionKey="수비", name="수비 강화" → mutates city.defense
//   - che_치안강화: cityKey="secu", actionKey="치안", name="치안 강화" → mutates city.security
fun cheSeongbyeokBosu(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
    CommerceInvestment(pipeline, "wall", "strength", "성벽", "성벽 보수", maxLevel)

fun cheSubiGanghwa(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
    CommerceInvestment(pipeline, "def", "strength", "수비", "수비 강화", maxLevel)

fun cheChianGanghwa(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
    CommerceInvestment(pipeline, "secu", "strength", "치안", "치안 강화", maxLevel)
