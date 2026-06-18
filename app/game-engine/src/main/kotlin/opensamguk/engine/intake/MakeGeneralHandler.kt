package opensamguk.engine.intake

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.common.wire.MakeGeneralFail
import opensamguk.common.wire.MakeGeneralOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.world.MakeGeneral
import opensamguk.logic.world.SpecialityHelper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * B1 장수생성(재야→일반/Join) 데몬 핸들러.
 *
 * PHP `hwe/sammo/API/General/Join.php::launch()` (225–545)의 충실 포팅 — RNG draw-for-draw +
 * INSERT 순서 + 로그 순서. MakeGeneral.draw()가 순수 로직 RNG 산출을 담당하고, 이 핸들러가
 * 상수 결합(gold/rice/killturn/crewtype/officer_level/nation=0) + world create + 로그를 처리한다.
 */
class MakeGeneralHandler(
    private val world: InMemoryTurnWorld,
    @Suppress("unused") private val recorder: ChangeRecorder,
) {

    fun handle(command: TurnDaemonCommand.MakeGeneral): TurnDaemonCommandResult {
        val state = world.getState()
        val hiddenSeed = state.meta["hiddenSeed"] as? String ?: ""

        // --- seed (Join.php:225–231) ---------------------------------------------------------------
        val now = Instant.now()
        val nowStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("UTC"))
            .format(now)
        val seed = serializeSeed(hiddenSeed, "MakeGeneral", command.userId, nowStr)
        val rng = RandUtil(LiteHashDrbg(seed))

        // --- city pool (Join.php:279–281) ----------------------------------------------------------
        val birthCities = world.listCities()
            .filter { it.level in 5..6 }
        val neutralCityPool = birthCities
            .filter { it.nationId == 0 }
            .map { it.id }
        val cityPool = neutralCityPool.ifEmpty { birthCities.map { it.id } }
        if (cityPool.isEmpty()) {
            return MakeGeneralFail(reason = "공백지가 없습니다.")
        }

        // --- RNG draw (Join.php:264–392) -----------------------------------------------------------
        val turntermMin = state.tickSeconds / 60
        val drawResult = MakeGeneral.draw(
            rng = rng,
            formLeadership = command.leadership,
            formStrength = command.strength,
            formIntel = command.intel,
            cityPool = cityPool,
            availablePersonality = GameConst.availablePersonality,
            character = command.character,
            turnterm = turntermMin,
        )

        // --- turntime (getRandTurn + base now) -----------------------------------------------------
        val turntime = now
            .plusSeconds(drawResult.turntimeSecond.toLong())
            .plusNanos(drawResult.turntimeFraction.toLong() * 1000L)

        // --- build TurnGeneral (Join.php:404–438) --------------------------------------------------
        val generalId = world.allocateGeneralId()
        val bornYear = state.currentYear - drawResult.age
        val turnGeneral = TurnGeneral(
            id = generalId,
            userId = command.userId.toString(),
            name = command.name,
            nationId = 0,
            cityId = drawResult.cityId,
            troopId = 0,
            stats = GeneralStats(
                leadership = drawResult.leadership,
                strength = drawResult.strength,
                intelligence = drawResult.intel,
            ),
            experience = 0,
            dedication = 0,
            officerLevel = 0,
            role = GeneralRole(
                personality = drawResult.personal,
                specialWar = drawResult.special2,
            ),
            gold = 1000,
            rice = 1000,
            crew = 0,
            crewTypeId = 1100,
            train = 0,
            atmos = 0,
            age = drawResult.age,
            npcState = 0,
            turnTime = turntime,
            meta = linkedMapOf(
                "killturn" to 6,
                "affinity" to drawResult.affinity,
                "born_year" to bornYear,
                "dead_year" to 300,
                "picture" to (command.picture ?: "default.jpg"),
                "image_server" to 0,
                "start_age" to drawResult.age,
                "specage" to (drawResult.age + 3),
                "specage2" to if (drawResult.genius) drawResult.age else (drawResult.age + 3),
                "betray" to 0,
                "penalty" to emptyMap<String, Any?>(),
            ),
        )

        world.createGeneral(turnGeneral)

        // --- logs (Join.php:502–528) — 로그 문자열은 PHP grand truth와 byte-match 대상. ---------
        val cityName = world.getCityById(drawResult.cityId)?.name ?: ""
        val josaRa = JosaUtil.pick(command.name, "라")
        val specialWarName = SpecialityHelper.warName(drawResult.special2)

        // PHP pushGlobalActionLog (genius vs non-genius) — Join.php:506-513
        if (drawResult.genius) {
            world.pushLog(LogEntryDraft(
                scope = "global", category = "action",
                text = "<G><b>${cityName}</b></>에서 <Y>${command.name}</>${josaRa}는 기재가 천하에 이름을 알립니다.",
                generalId = generalId, nationId = 0,
            ))
            world.pushLog(LogEntryDraft(
                scope = "global", category = "action",
                text = "<C>${specialWarName}</> 특기를 가진 <C>천재</>의 등장으로 온 천하가 떠들썩합니다.",
                generalId = generalId, nationId = 0,
            ))
            world.pushLog(LogEntryDraft(
                scope = "global", category = "history",
                text = "<L><b>【천재】</b></><G><b>${cityName}</b></>에 천재가 등장했습니다.",
                generalId = generalId, nationId = 0,
            ))
        } else {
            world.pushLog(LogEntryDraft(
                scope = "global", category = "action",
                text = "<G><b>${cityName}</b></>에서 <Y>${command.name}</>${josaRa}는 호걸이 천하에 이름을 알립니다.",
                generalId = generalId, nationId = 0,
            ))
        }

        // PHP pushGeneralHistoryLog — Join.php:515
        world.pushLog(LogEntryDraft(
            scope = "general", category = "history",
            text = "<Y>${command.name}</>, <G>${cityName}</>에서 큰 뜻을 품다.",
            generalId = generalId, nationId = 0,
        ))

        // PHP pushGeneralActionLog (PLAIN) — Join.php:516-521
        world.pushLog(LogEntryDraft(
            scope = "general", category = "action",
            text = "삼국지 모의전투 PHP의 세계에 오신 것을 환영합니다 ^o^",
            generalId = generalId, nationId = 0,
        ))
        world.pushLog(LogEntryDraft(
            scope = "general", category = "action",
            text = "처음 하시는 경우에는 <D>도움말</>을 참고하시고,",
            generalId = generalId, nationId = 0,
        ))
        world.pushLog(LogEntryDraft(
            scope = "general", category = "action",
            text = "문의사항이 있으시면 게시판에 글을 남겨주시면 되겠네요~",
            generalId = generalId, nationId = 0,
        ))
        world.pushLog(LogEntryDraft(
            scope = "general", category = "action",
            text = "부디 즐거운 삼모전 되시길 바랍니다 ^^",
            generalId = generalId, nationId = 0,
        ))
        world.pushLog(LogEntryDraft(
            scope = "general", category = "action",
            text = "통솔 <C>${drawResult.bonusLeadership}</> 무력 <C>${drawResult.bonusStrength}</> 지력 <C>${drawResult.bonusIntel}</> 의 보너스를 받으셨습니다.",
            generalId = generalId, nationId = 0,
        ))
        world.pushLog(LogEntryDraft(
            scope = "general", category = "action",
            text = "연령은 <C>${drawResult.age}</>세로 시작합니다.",
            generalId = generalId, nationId = 0,
        ))

        // PHP pushGeneralActionLog/HistoryLog (genius-only) — Join.php:523-526
        if (drawResult.genius) {
            world.pushLog(LogEntryDraft(
                scope = "general", category = "action",
                text = "축하합니다! 천재로 태어나 처음부터 <C>${specialWarName}</> 특기를 가지게 됩니다!",
                generalId = generalId, nationId = 0,
            ))
            world.pushLog(LogEntryDraft(
                scope = "general", category = "history",
                text = "<C>${specialWarName}</> 특기를 가진 천재로 탄생.",
                generalId = generalId, nationId = 0,
            ))
        }

        return MakeGeneralOk(generalId = generalId)
    }
}
