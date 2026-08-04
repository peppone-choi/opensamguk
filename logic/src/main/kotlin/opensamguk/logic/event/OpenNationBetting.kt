package opensamguk.logic.event

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.SelectItem

data class NationBettingCandidate(
    val nationId: Int,
    val name: String,
    val power: Int,
    val generalCount: Int,
    val cityCount: Int,
    val aux: Map<String, Any?>,
)

interface OpenNationBettingContext : EventActionContext {
    fun year(): Int
    fun month(): Int
    fun nationBettingCandidates(): List<NationBettingCandidate>
    fun nextBettingId(): Int
    fun saveBettingInfo(info: BettingInfo)
    fun scheduleNationBettingFinish(bettingId: Int, nationCnt: Int)
    fun placeNationBettingBonus(bettingId: Int, amount: Int)
    fun pushGlobalHistoryLog(msg: String, type: Int)
    fun notifyNationBettingOpened(name: String)
}

class OpenNationBettingAction(
    private val nationCnt: Int = 1,
    private val bonusPoint: Int = 0,
) : EventAction {
    init {
        require(nationCnt >= 1) { "1 미만의 숫자" }
        require(bonusPoint >= 0) { "0 미만의 보너스 포인트" }
    }

    override fun run(ctx: EventActionContext) {
        val world = ctx as? OpenNationBettingContext
            ?: error("OpenNationBettingAction requires an OpenNationBettingContext")
        val year = world.year()
        val month = world.month()
        val bettingName = if (nationCnt == 1) "천통국" else "최후 ${nationCnt}국"
        val openYearMonth = year * 12 + month - 1
        val candidates = linkedMapOf<Int, SelectItem>()

        world.nationBettingCandidates()
            .sortedByDescending { it.power }
            .forEachIndexed { index, nation ->
                candidates[index] = SelectItem(
                    title = nation.name,
                    info = listOf(
                        "국력: ${nation.power}",
                        "장수 수: ${nation.generalCount}",
                        "도시 수: ${nation.cityCount}",
                    ).joinToString("<br>"),
                    isHtml = true,
                    aux = nation.aux,
                )
            }

        val bettingId = world.nextBettingId()
        world.saveBettingInfo(
            BettingInfo(
                id = bettingId,
                type = "bettingNation",
                name = "$bettingName 예상",
                finished = false,
                selectCnt = nationCnt,
                isExclusive = null,
                reqInheritancePoint = true,
                openYearMonth = openYearMonth,
                closeYearMonth = openYearMonth + 24,
                candidates = candidates,
                winner = null,
            ),
        )
        world.scheduleNationBettingFinish(bettingId, nationCnt)
        if (bonusPoint > 0) {
            world.placeNationBettingBonus(bettingId, bonusPoint)
        }
        val history = if (nationCnt > 1) {
            "<B><b>【내기】</b></>중원의 강자를 점치는 <C>내기</>가 진행중입니다! 호사가의 참여를 기다립니다!"
        } else {
            "<B><b>【내기】</b></>천하통일 후보를 점치는 <C>내기</>가 진행중입니다! 호사가의 참여를 기다립니다!"
        }
        world.pushGlobalHistoryLog(history, LightActionWorld.EVENT_YEAR_MONTH)
        world.notifyNationBettingOpened(bettingName)
    }

    companion object {
        const val NAME = "OpenNationBetting"

        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                OpenNationBettingAction(
                    nationCnt = (args.getOrNull(0) as? JsonPrimitive)?.content?.toIntOrNull() ?: 1,
                    bonusPoint = (args.getOrNull(1) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                )
            }
    }
}
