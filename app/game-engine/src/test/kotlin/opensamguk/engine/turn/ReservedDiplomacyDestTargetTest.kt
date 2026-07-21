package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 인간이 예약한 dest-타깃 명령(외교 수락 — che_불가침수락 / che_종전수락)이 [ReservedTurnHandler]의
 * 일반 패스를 통해 올바르게 평가되는지를 증명하는 회귀 테스트.
 *
 * 버그(수정 전): [ReservedTurnHandler.handle]가 [ConstraintContext]를 만들 때
 * `destGeneralID`/`destCityID`/`destNationID`를 ctx에 넣지 않아, dest-* 제약
 * (ExistsDestNation / ExistsDestGeneral / DisallowDiplomacyBetweenStatus 등)이 id 0에 대해
 * 평가되어 무조건 Deny → 휴식 폴백으로 떨어진다. 그 결과 외교 수락이 조용히 무시되어 외교 행이
 * 절대 전환되지 않는다.
 *
 * AI 패스([AiTurnAdapter])는 이미 동일한 dest id 3종을 ctx에 넣어 올바르게 동작한다
 * (AiTurnAdapter.kt:397-399). 본 테스트는 인간-예약 경로가 AI 패스 / PHP grand truth와
 * 동일하게 dest 제약을 평가하도록 보장한다.
 *
 * 실제 경로를 그대로 태운다(목 reserve 아님): 두 국가의 외교 상태(불가침수락=통상, 종전수락=교전)에서
 * 수락국 군주가 예약한 실제 ReservedTurn을 핸들러에 흘려보내고, 외교 행 전환을 단언한다.
 */
class ReservedDiplomacyDestTargetTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    // FIXTURE INPUT — dest-타깃 외교 명령은 결과(state/term) 결정에 RNG를 쓰지 않으므로 0-시드로 충분하다.
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    // 국가 1 = 수락국(불가침/종전을 수락) · 국가 2 = 제의국(proposer).
    private val ACCEPT_NATION = 1
    private val PROPOSER_NATION = 2

    /** 수락국 군주(외교 수락을 예약·실행하는 주체) — officer_level=12. */
    private fun accepterChief() = TurnGeneral(
        id = 10,
        name = "수락군주",
        nationId = ACCEPT_NATION,
        cityId = 7,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
        experience = 0,
        dedication = 0,
        officerLevel = 12,            // BeChief 통과(officer_level > 4)
        gold = 100_000,
        rice = 1000,
        turnTime = t0,
        meta = linkedMapOf("explevel" to 10),
    )

    /** 제의국 군주(che_불가침수락/종전수락의 destGeneralID 대상) — ExistsDestGeneral 통과용. */
    private fun proposerChief() = TurnGeneral(
        id = 20,
        name = "제의군주",
        nationId = PROPOSER_NATION,
        cityId = 8,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = 80),
        experience = 0,
        dedication = 0,
        officerLevel = 12,
        gold = 100_000,
        rice = 1000,
        turnTime = t0,
        meta = linkedMapOf("explevel" to 10),
    )

    private fun city(id: Int, nationId: Int) = City(
        id = id, name = "c$id", nationId = nationId, level = 5,
        agriculture = 1000, agricultureMax = 20000, commerce = 1000, commerceMax = 20000,
        supplyState = 1, frontState = 0, meta = linkedMapOf("trust" to 50),
    )

    private fun nation(id: Int, capital: Int) =
        Nation(id = id, name = "n$id", color = "#000", level = 2, capitalCityId = capital)

    private fun dip(from: Int, to: Int, state: Int, term: Int = 0) =
        TurnDiplomacy(fromNationId = from, toNationId = to, state = state, term = term)

    /** 두 국가가 [startState]로 마주한 월드 + 양방향 외교 행. */
    private fun worldInState(startState: Int) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
            ),
            generals = listOf(accepterChief(), proposerChief()),
            cities = listOf(city(7, ACCEPT_NATION), city(8, PROPOSER_NATION)),
            nations = listOf(nation(ACCEPT_NATION, 7), nation(PROPOSER_NATION, 8)),
            diplomacy = listOf(
                dip(ACCEPT_NATION, PROPOSER_NATION, state = startState),
                dip(PROPOSER_NATION, ACCEPT_NATION, state = startState),
            ),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
            )).id),
        ),
    )

    private fun handlerFor(world: InMemoryTurnWorld) =
        ReservedTurnHandler(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR)

    /** 외교 수락 명령의 예약 arg jsonb(destNationID/destGeneralID + 합의 year/month)를 만든다. */
    private fun acceptArgJson(includeYearMonth: Boolean): String {
        // 합의 시점 = 현재로부터 1년 뒤(불가침 term >= MIN_NON_AGGRESSION_MONTHS=6 보장).
        val agreedYear = YEAR + 1
        val agreedMonth = MONTH
        return if (includeYearMonth) {
            """{"destNationID":$PROPOSER_NATION,"destGeneralID":20,"year":$agreedYear,"month":$agreedMonth}"""
        } else {
            """{"destNationID":$PROPOSER_NATION,"destGeneralID":20}"""
        }
    }

    @Test
    fun `che_불가침수락 transitions both diplomacy rows to NON_AGGRESSION with the agreed term`() {
        // 불가침수락은 PHP DisallowDiplomacyBetweenStatus([WAR, DECLARATION])로 인해 교전/선포 중에는
        // 불가하다(che_불가침수락.php:136-138). 따라서 통상(TRADE=2) 관계에서 불가침을 수락한다.
        val world = worldInState(DiplomacyState.TRADE)
        val handler = handlerFor(world)

        val reserved = ReservedTurn(actionCode = "che_불가침수락", argJson = acceptArgJson(includeYearMonth = true))
        val outcome = handler.handle(generalId = 10, reserved = reserved, year = YEAR, month = MONTH, date = "12:34")

        // dest id가 ctx에 실리지 않으면 ExistsDestNation/ExistsDestGeneral가 id 0에 대해 Deny → 휴식 폴백한다.
        assertFalse(outcome.fellBack, "수락국 군주의 외교 수락은 폴백(휴식)이 아니라 실제로 실행되어야 한다: denyReason=${outcome.denyReason}")
        assertEquals("che_불가침수락", outcome.definition.key)

        // PHP che_불가침수락.php:203-204,210 byte-exact:
        //   currentMonth = env.year*12 + env.month - 1, reqMonth = year*12 + month (−1 없음),
        //   term = reqMonth - currentMonth. (이전 reqMonth의 −1은 PHP에 없는 off-by-one 날조였다.)
        //   = (201*12 + 1) - (200*12 + 1 - 1) = 2413 - 2400 = 13개월.
        val expectedTerm = (YEAR + 1) * 12 + MONTH - (YEAR * 12 + MONTH - 1)

        val forward = world.getDiplomacy(ACCEPT_NATION, PROPOSER_NATION)!!
        val reverse = world.getDiplomacy(PROPOSER_NATION, ACCEPT_NATION)!!
        assertEquals(DiplomacyState.NON_AGGRESSION, forward.state, "정방향 행이 불가침(7)으로 전환되어야 한다")
        assertEquals(DiplomacyState.NON_AGGRESSION, reverse.state, "역방향 행도 양방향으로 함께 전환되어야 한다")
        assertEquals(expectedTerm, forward.term, "정방향 term = 합의 시점까지의 개월 수")
        assertEquals(expectedTerm, reverse.term, "역방향 term도 동일")

        // ChangeRecorder = 유일 dirty 소스: 양방향 외교 패치가 기록되어야 한다.
        val patches = handler.recorder.diplomacyUpdateDirty()
        assertEquals(2, patches.size, "양방향(me,you)+(you,me) 외교 패치가 기록되어야 한다")
        assertEquals(
            setOf(ACCEPT_NATION to PROPOSER_NATION, PROPOSER_NATION to ACCEPT_NATION),
            patches.map { it.fromNationId to it.toNationId }.toSet(),
        )
    }

    @Test
    fun `che_종전수락 transitions both diplomacy rows to TRADE with term 0`() {
        // 종전수락은 PHP AllowDiplomacyBetweenStatus([WAR, DECLARATION])로 교전/선포 중에만 가능하다
        // (che_종전수락.php:101-103). 따라서 교전(WAR=0) 관계에서 종전을 수락한다.
        val world = worldInState(DiplomacyState.WAR)
        val handler = handlerFor(world)

        // che_종전수락은 destNationID만 받는다(year/month 없음).
        val reserved = ReservedTurn(actionCode = "che_종전수락", argJson = acceptArgJson(includeYearMonth = false))
        val outcome = handler.handle(generalId = 10, reserved = reserved, year = YEAR, month = MONTH, date = "12:34")

        assertFalse(outcome.fellBack, "수락국 군주의 종전 수락은 폴백이 아니라 실제로 실행되어야 한다: denyReason=${outcome.denyReason}")
        assertEquals("che_종전수락", outcome.definition.key)

        val forward = world.getDiplomacy(ACCEPT_NATION, PROPOSER_NATION)!!
        val reverse = world.getDiplomacy(PROPOSER_NATION, ACCEPT_NATION)!!
        assertEquals(DiplomacyState.TRADE, forward.state, "정방향 행이 통상(2)으로 전환되어야 한다")
        assertEquals(DiplomacyState.TRADE, reverse.state, "역방향 행도 통상(2)으로 전환되어야 한다")
        assertEquals(0, forward.term, "종전 term은 0")
        assertEquals(0, reverse.term, "종전 term은 0")

        val patches = handler.recorder.diplomacyUpdateDirty()
        assertEquals(2, patches.size, "양방향 외교 패치가 기록되어야 한다")
    }
}
