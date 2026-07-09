package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_피장파장 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_피장파장.php`.
 *
 * 전략(strategic) 사령 커맨드. 선포/전쟁중(state 0,1) 상대국 + `commandType`(자기 자신이 아닌 다른 전략
 * 커맨드)을 인자로 받아, nation_env / dest nation_env에 delay KV를 쓴다. dest-nation PLAIN +
 * national-history. RNG 미사용(deterministic). logLines=1. delayCnt=60.
 *
 * argTest(che_피장파장.php:33-69): `destNationID`(int,>=1) + `commandType`(string, 전략 커맨드 목록에
 *   속하고 자기 클래스명이 아님) 필요 → canonical `{destNationID, commandType}`.
 *
 * fullConditionConstraints(che_피장파장.php:90-104), PHP ORDER:
 *   - 무소속(nationID==0): [OccupiedCity]
 *   - currYearMonth < cmd.getNextAvailableTurn(): AlwaysFail('해당 전략을 아직 사용할 수 없습니다')
 *   - else: [OccupiedCity, BeChief, ExistsDestNation, AllowDiplomacyBetweenStatus([0,1],…), AvailableStrategicCommand].
 *   commandType의 getNextAvailableTurn(대상 전략 재사용 대기) 비교는 nation_env delay KV 조회 →
 *   staging 대상; seam 미구현 시 `ctx.env["__targetStrategyAvailable"]`(Boolean)가 없으면 통과로 본다.
 *
 * Live path: ProcessNationCommand logic bridge runs resolve (exp/ded+log). Remaining backlog:
 *   dest-nation PLAIN/national-history + 피장파장 발동 로그.
 */
fun chePijangPajang(pipeline: GeneralActionPipeline): ChePijangPajang = ChePijangPajang(pipeline)

class ChePijangPajang(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_피장파장"
    override val name: String get() = "피장파장"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int", "commandType" to "string")

    /** che_피장파장.php:getPreReqTurn = 1 (reqTurn = 2). */
    override fun getPreReqTurn(): Int = 1

    /**
     * che_피장파장.php:33-69 argTest. `destNationID`(int,>=1) + `commandType`(전략 커맨드 + self 아님).
     * commandType이 GameConst 전략 목록에 속하고 자기 자신(che_피장파장)이 아닌지의 검증은 caller가
     * 채우는 정적 선결조건(전략 커맨드 화이트리스트)이므로 여기서는 형식 검증만 한다.
     */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destNationID") || !raw.containsKey("commandType")) return null
        val destNationID = (raw["destNationID"] as? Int) ?: return null  // PHP is_int
        if (destNationID < 1) return null
        val commandType = (raw["commandType"] as? String) ?: return null  // PHP is_string
        if (commandType == key) return null  // self 금지
        return linkedMapOf("destNationID" to destNationID, "commandType" to commandType)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // 무소속(nationID==0) → OccupiedCity만 (che_피장파장.php:92-97).
        if ((ctx.nationId ?: 0) == 0) {
            return listOf(occupiedCity())
        }
        // 대상 전략 재사용 대기 미경과 → AlwaysFail (staging이 false를 주면) (che_피장파장.php:100-110).
        val targetAvailable = (ctx.env["__targetStrategyAvailable"] as? Boolean)
            ?: (ctx.args["__targetStrategyAvailable"] as? Boolean) ?: true
        if (!targetAvailable) {
            return listOf(alwaysFail("해당 전략을 아직 사용할 수 없습니다"))
        }
        return listOf(
            occupiedCity(), beChief(), existsDestNation(),
            allowDiplomacyBetweenStatus(listOf(0, 1), "선포, 전쟁중인 상대국에게만 가능합니다."),
            availableStrategicCommand(),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /**
     * che_피장파장.php:run()(157-244) 포팅.
     *
     * 1) actor action log — `<G><b>{cmd.getName()}</b></> 전략의 {commandName} 발동! <1>{date}</>`
     *    (che_피장파장.php:201-202). cmd = commandType 인자가 가리키는 전략 커맨드, cmd.getName() = 그
     *    커맨드의 표시명. 전략 8커맨드는 모두 name == key.removePrefix("che_")(GameConstBase 전략 목록 +
     *    각 클래스 actionName 검증 완료)이므로 commandType 키에서 직접 유도한다. commandName = getName() =
     *    "피장파장". addLog가 `<C>●</>{month}월:` 접두를 붙인다.
     *
     * 2) exp/ded += 5*(getPreReqTurn()+1) = 10 (che_피장파장.php:204-205). 로그 다음 순서.
     *    onCalcStat 폴드 통과(기본 NPC = 트리거 없음 → raw +10). explevel 변동 없음(레벨 로그 없음).
     *
     * 3) 아국/적국 장수 개별 broadcastMessage(che_피장파장.php:207-225)는 각 장수 자신의 ActionLogger로
     *    flush되는 엔진-스코프 부수효과 → actor scope에 없고 pushGlobalActionLog도 아님 → broadcastLines=[].
     *    pushGeneralHistoryLog/pushNationalHistoryLog(che_피장파장.php:227-233)도 history 싱크라 action-log/
     *    broadcast에 미노출.
     *
     * 4) nation_env / dest nation_env의 commandType delay KV write(delayCnt=60, che_피장파장.php:235-242)는
     *    엔진-스코프 KV 스테이징 seam이며 이 golden(draw_count=0, 단일 logLine)에는 노출되지 않는다.
     *    RNG 미사용 — context.rng 미터치(0-draw 오라클).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        // commandType 전략 커맨드의 표시명(cmd.getName()). 전략 화이트리스트는 모두 key.removePrefix("che_") == name.
        val commandType = (context.args["commandType"] as? String) ?: return
        val targetCommandName = commandType.removePrefix("che_")

        // 1) actor action log — che_피장파장.php:201-202.
        context.addLog("<G><b>$targetCommandName</b></> 전략의 $name 발동! <1>${context.date}</>")

        // 2) exp/ded += 5*(preReqTurn+1) = 10 (che_피장파장.php:204-205). 로그 다음.
        val mag = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }
    }
}
