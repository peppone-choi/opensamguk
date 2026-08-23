package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.CommandFieldSpec
import opensamguk.logic.actions.CommandFormSpec
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.differentDestNation
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.message.Message
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.message.MessageType
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.LocalDateTime

/**
 * che_불가침제의 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침제의.php`.
 *
 * AI-emitted by `do불가침제의` (GeneralAI.php:1832). P5 ports the SELECTION + the boolean gate + the
 * argTest + the FULL constraint pack (decision #10/#11); the diplomatic-message state-mutation run()
 * internals are P6 (the G-GATE downstream-delta/log assertion EXCLUDES this family — m10), so the
 * resolve() body is a P6 seam stub.
 *
 * argTest (che_불가침제의.php:33-73): require `destNationID` (int, >= 1), `year` + `month` (both int,
 * 1 <= month <= 12, year >= env['startyear']) → canonical `{destNationID, year, month}` (note: the
 * dest nation need NOT exist — `멸망 직전에 턴을 넣을 수 있으므로`).
 *
 * fullConditionConstraints (che_불가침제의.php:90-127, computed in initWithArg from the year/month
 * 기한):
 *   - relYear/currentMonth/reqMonth math: `reqMonth = year*12 + month - 1`,
 *     `currentMonth = env.year*12 + env.month - 1`; when `reqMonth < currentMonth + 6` the WHOLE pack
 *     collapses to `[AlwaysFail('기한은 6개월 이상이어야 합니다.')]` (the 6-개월-이상 gate);
 *   - else the 5-constraint diplomacy pack, in PHP ORDER (first-deny-wins):
 *     `[BeChief, NotBeNeutral, ExistsDestNation, DifferentDestNation,
 *       DisallowDiplomacyBetweenStatus({0:'아국과 이미 교전중입니다.', 1:'아국과 이미 선포중입니다.'})]`.
 *
 * The 6-개월 gate reads the request year/month from `ctx.args` and the current year/month from
 * `ctx.env` (`year`/`month`). minConditionConstraints (BeChief, NotBeNeutral) are the reserve-time
 * preview; the AI bridge uses the FULL pack.
 */
fun cheBulgachimJeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimJeui =
    CheBulgachimJeui(pipeline)

class CheBulgachimJeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침제의"
    override val name: String get() = "불가침 제의"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int", "year" to "int", "month" to "int")
    override val formSpec: CommandFormSpec get() = CommandFormSpec(
        listOf(
            CommandFieldSpec("destNationID", "int", "select", "nations"),
            CommandFieldSpec("year", "int", "number"),
            CommandFieldSpec("month", "int", "number", min = 1, max = 12),
        ),
    )

    override fun getPreReqTurn(): Int = 0

    /**
     * che_불가침제의.php:33-73 argTest. Returns the canonical `{destNationID, year, month}` or null on a
     * missing/invalid required key. `startYear` is PHP `env['startyear']` (the year >= startyear gate).
     */
    fun argTest(raw: Map<String, Any?>, startYear: Int): Map<String, Any?>? {
        if (!raw.containsKey("destNationID")) return null
        val destNationID = (raw["destNationID"] as? Int) ?: return null  // PHP is_int
        if (destNationID < 1) return null
        if (!raw.containsKey("year") || !raw.containsKey("month")) return null
        val year = (raw["year"] as? Int) ?: return null
        val month = (raw["month"] as? Int) ?: return null
        if (month < 1 || month > 12) return null
        if (year < startYear) return null
        return linkedMapOf("destNationID" to destNationID, "year" to year, "month" to month)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // initWithArg (che_불가침제의.php:100-126): the 6-개월-이상 기한 gate.
        val envYear = (ctx.env["year"] as? Number)?.toInt() ?: 0
        val envMonth = (ctx.env["month"] as? Number)?.toInt() ?: 1
        val reqYear = (ctx.args["year"] as? Number)?.toInt() ?: 0
        val reqMonth0 = (ctx.args["month"] as? Number)?.toInt() ?: 0
        val currentMonth = envYear * 12 + envMonth - 1
        val reqMonth = reqYear * 12 + reqMonth0 - 1
        if (reqMonth < currentMonth + 6) {
            return listOf(alwaysFail("기한은 6개월 이상이어야 합니다."))
        }
        return listOf(
            beChief(),
            notBeNeutral(),
            existsDestNation(),
            differentDestNation(),
            disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다.", 1 to "아국과 이미 선포중입니다.")),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = run {
        // startyear unknown at pure parse time; the year >= startyear gate is re-checked at run via env.
        val destNationID = (raw["destNationID"] as? Int) ?: return@run emptyMap()
        if (destNationID < 1) return@run emptyMap()
        val year = (raw["year"] as? Int) ?: return@run emptyMap()
        val month = (raw["month"] as? Int) ?: return@run emptyMap()
        if (month < 1 || month > 12) return@run emptyMap()
        linkedMapOf("destNationID" to destNationID, "year" to year, "month" to month)
    }

    /**
     * che_불가침제의.php:154-229 run(). draw COUNT = 0 — no RNG.
     *
     * Resolve flow (PHP run 본체 그대로):
     *  1. Extract destNationID, year, month from args.
     *  2. 장수 액션 로그 `<D><b>{상대국}</b></>{로} 불가침 제의 서신을 보냈습니다.<1>{date}</>`.
     *     **동결 회귀 주의**: PHP `$josaRo = JosaUtil::pick($nationName, '로')`(che_불가침제의.php:170)는
     *     `로`/`으로` 형을 **행동 장수 자신의 국명**(`$nationName`)으로 고른 뒤, 표시는 `$destNationName`에
     *     대해 한다(:182). 즉 josa는 actor 국명, 텍스트는 상대국명 — quirk이지만 충실히 재현한다.
     *  3. DiplomaticMessage(action=no_aggression, year, month) buffer — title
     *     `{국명}{와} {year}년 {month}월까지 불가침 제의 서신`(che_불가침제의.php:212,
     *     josaWa = JosaUtil::pick($nationName, '와')), validUntil = date+max(30,turnterm*3)분([DiplomacySeam]).
     *  4. The diplomacy state mutation (state=7) happens ONLY when the recipient accepts via
     *     [CheBulgachimSuak]; this command only SENDS the proposal.
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val destNationID = (context.args["destNationID"] as? Int) ?: return
        val year = (context.args["year"] as? Int) ?: return
        val month = (context.args["month"] as? Int) ?: return

        val draft = context.draft
        val general = draft.general
        val nation = draft.nation ?: return

        val destNationName = draft.destNation?.name ?: context.destGeneralName.ifEmpty { "상대국" }
        // josaRo는 PHP대로 ACTOR 국명(nation.name)으로 고른다(che_불가침제의.php:170) — 표시는 상대국명(:182).
        val josaRo = JosaUtil.pick(nation.name, "로")
        val josaWa = JosaUtil.pick(nation.name, "과", "와")

        // Action log — `<D><b>{상대국}</b></>{로} 불가침 제의 서신을 보냈습니다.<1>{date}</>` (che_불가침제의.php:182)
        context.addLog("<D><b>$destNationName</b></>$josaRo 불가침 제의 서신을 보냈습니다.<1>${context.date}</>")

        // Build and send the diplomatic message (engine routes to mailbox channel).
        // validUntil = date + max(30, turnterm*3)분 — A2 공통 인프라([DiplomacySeam]) 단일 공식
        // (PHP che_불가침제의.php:202-204). turnterm은 엔진(ReservedTurnHandler)이 per-game 주입(기본 60).
        val now = LocalDateTime.now()
        val dateStr = now.format(DiplomacySeam.YMDHIS)
        val validUntilStr = DiplomacySeam.validUntil(now, context.turnterm)

        val src = MessageTarget(
            generalId = general.id,
            generalName = context.generalName,
            nationId = nation.id,
            nationName = nation.name,
            color = nation.color,
            icon = "",
        )
        val dest = MessageTarget(
            generalId = 0,
            generalName = "",
            nationId = destNationID,
            nationName = destNationName,
            color = draft.destNation?.color ?: "#000000",
            icon = "",
        )

        val msg = Message(
            msgType = MessageType.DIPLOMACY,
            src = src,
            dest = dest,
            msg = "${nation.name}$josaWa ${year}년 ${month}월까지 불가침 제의 서신",
            date = dateStr,
            validUntil = validUntilStr,
            msgOption = linkedMapOf(
                "action" to "no_aggression",
                "year" to year,
                "month" to month,
            ),
        )
        context.sendMessage(msg)

        // StaticEventHandler 외교 훅 (A2 공통 인프라) — PHP `run()` 말미
        // `StaticEventHandler::handleEvent($general, $destGeneral, $this::class, $env, $arg)`
        // (che_불가침제의.php:222). scenario 1010에서 핸들러 맵이 비어 no-op(게이트 동일) — 호출 지점만 정본화.
        DiplomacySeam.fireDiplomacyEvent(
            general = general,
            destGeneral = null, // 제의는 상대 장수 객체를 들고 있지 않음(국가 메일함으로 발송) — PHP destGeneralObj가 null일 수 있음
            commandKey = key,
            env = linkedMapOf("year" to context.env.year, "month" to context.month, "turnterm" to context.turnterm),
            args = context.args,
        )
    }
}
