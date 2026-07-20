package opensamguk.gameapi.reserve

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W6e — [CommandQueueService] 결정성(deterministic) 단위 테스트.
 *
 * [RecordingReservedTurns]로 링 호출(reserve/reserveNationTurn/push·pull·repeat)을 캡처해
 * PHP `func_command.php` + `ReserveBulk/Push/Repeat`의 분기를 byte-for-byte deny + 호출 위임으로 검증한다.
 * RNG 없음 — 초기 입력을 핀 고정하고 연산을 돌린 뒤 캡처된 호출/예외를 비교한다.
 *
 * brief(R5 갭): 실제 [CommandRegistry]를 써서 액션 정의의 name이 brief로 실린다(예: che_농지개간 → "농지개간").
 */
class CommandQueueTest {

    private val registry = CommandRegistry(GeneralActionPipeline())
    private fun service(repo: ReservedTurnRepository) = CommandQueueService(repo, registry, GameApiProcessWorld(1))

    /** 링 호출을 기록하는 ReservedTurnRepository 페이크(JDBC 미사용 — 모든 메서드 오버라이드). */
    private class RecordingReservedTurns :
        ReservedTurnRepository(mock(NamedParameterJdbcTemplate::class.java)) {

        data class GeneralReserveCall(val worldId: WorldId, val generalId: Int, val turnIdx: Int, val action: String?, val arg: String?, val brief: String)
        data class NationReserveCall(val worldId: WorldId, val nationId: Int, val officerLevel: Int, val turnIdx: Int, val action: String?, val arg: String?, val brief: String)
        data class ShiftCall(val worldId: WorldId, val key: Int, val key2: Int, val cnt: Int)

        val generalReserves = mutableListOf<GeneralReserveCall>()
        val nationReserves = mutableListOf<NationReserveCall>()
        val pushGeneral = mutableListOf<ShiftCall>()
        val pullGeneral = mutableListOf<ShiftCall>()
        val repeatGeneral = mutableListOf<ShiftCall>()
        val pushNation = mutableListOf<ShiftCall>()
        val pullNation = mutableListOf<ShiftCall>()
        val repeatNation = mutableListOf<ShiftCall>()

        override fun reserve(
            worldId: WorldId,
            generalId: Int,
            turnIdx: Int,
            actionCode: String?,
            argJson: String?,
            brief: String,
        ) {
            generalReserves += GeneralReserveCall(worldId, generalId, turnIdx, actionCode, argJson, brief)
        }

        override fun reserveNationTurn(
            worldId: WorldId,
            nationId: Int,
            officerLevel: Int,
            turnIdx: Int,
            actionCode: String?,
            argJson: String?,
            brief: String,
        ) {
            nationReserves += NationReserveCall(worldId, nationId, officerLevel, turnIdx, actionCode, argJson, brief)
        }

        override fun pushGeneralTurn(worldId: WorldId, generalId: Int, turnCnt: Int) { pushGeneral += ShiftCall(worldId, generalId, 0, turnCnt) }
        override fun pullGeneralTurn(worldId: WorldId, generalId: Int, turnCnt: Int) { pullGeneral += ShiftCall(worldId, generalId, 0, turnCnt) }
        override fun repeatGeneralTurn(worldId: WorldId, generalId: Int, turnCnt: Int) { repeatGeneral += ShiftCall(worldId, generalId, 0, turnCnt) }
        override fun pushNationTurn(worldId: WorldId, nationId: Int, officerLevel: Int, turnCnt: Int) { pushNation += ShiftCall(worldId, nationId, officerLevel, turnCnt) }
        override fun pullNationTurn(worldId: WorldId, nationId: Int, officerLevel: Int, turnCnt: Int) { pullNation += ShiftCall(worldId, nationId, officerLevel, turnCnt) }
        override fun repeatNationTurn(worldId: WorldId, nationId: Int, officerLevel: Int, turnCnt: Int) { repeatNation += ShiftCall(worldId, nationId, officerLevel, turnCnt) }
    }

    // ── Bulk (General) ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `bulk valid - reserves each turn per item with brief = action name`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(0, 5, 10), arg = mapOf("amount" to 100)),
                CommandQueueService.CommandBulkItem(action = "che_상업투자", turnList = listOf(7), arg = null),
            ),
        )
        assertTrue(result.result)
        assertEquals("success", result.reason)
        // briefList = 액션 정의 name(R5 대체). che_농지개간 → "농지 개간", che_상업투자 → "상업 투자"(공백 포함, PHP).
        assertEquals(listOf("농지 개간", "상업 투자"), result.briefList)
        // 슬롯별 reserve: item0(3슬롯) + item1(1슬롯) = 4건.
        assertEquals(4, repo.generalReserves.size)
        assertTrue(repo.generalReserves.all { it.worldId == WorldId(1) })
        assertEquals(listOf(0, 5, 10, 7), repo.generalReserves.map { it.turnIdx })
        assertEquals("che_농지개간", repo.generalReserves[0].action)
        assertEquals("""{"amount":100}""", repo.generalReserves[0].arg)
        assertEquals(null, repo.generalReserves[3].arg) // arg=null → repository가 {}로 정규화
    }

    @Test
    fun `bulk empty turnList - indexed deny`() {
        val repo = RecordingReservedTurns()
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).reserveBulkGeneral(
                generalId = 10,
                commands = listOf(
                    CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(1), arg = null),
                    CommandQueueService.CommandBulkItem(action = "che_상업투자", turnList = emptyList(), arg = null),
                ),
            )
        }
        assertEquals("1: 턴이 입력되지 않았습니다", ex.reason)
    }

    @Test
    fun `bulk invalid command - indexed deny`() {
        val repo = RecordingReservedTurns()
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).reserveBulkGeneral(
                generalId = 10,
                commands = listOf(
                    CommandQueueService.CommandBulkItem(action = "che_없는커맨드", turnList = listOf(1), arg = null),
                ),
            )
        }
        assertEquals("0: 사용할 수 없는 커맨드입니다.", ex.reason)
    }

    @Test
    fun `bulk non-array arg - indexed deny`() {
        val repo = RecordingReservedTurns()
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).reserveBulkGeneral(
                generalId = 10,
                commands = listOf(
                    // argIsArray=false → 클라이언트가 arg 를 객체가 아닌 값으로 보낸 경우(PHP `!is_array`).
                    CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(1), arg = null, argIsArray = false),
                ),
            )
        }
        assertEquals("0: 올바른 arg 형태가 아닙니다.", ex.reason)
    }

    @Test
    fun `bulk partial failure - returns errorIdx and partial briefList`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(0), arg = null),
                // 두 번째 원소: 범위를 벗어난 turnIdx(30) → setGeneralCommand 부분 실패.
                CommandQueueService.CommandBulkItem(action = "che_상업투자", turnList = listOf(30), arg = null),
            ),
        )
        assertFalse(result.result)
        assertEquals(1, result.errorIdx)
        assertEquals(listOf("농지 개간"), result.briefList) // 첫 원소 brief만 누적(공백 포함 name)
        assertEquals("올바른 턴이 아닙니다. : 30", result.reason)
    }

    @Test
    fun `bulk negative turnIdx expands -3 to all 30 slots`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(-3), arg = null),
            ),
        )
        assertTrue(result.result)
        assertEquals(30, repo.generalReserves.size) // -3 = 전체(0..29)
        assertEquals((0 until 30).toList(), repo.generalReserves.map { it.turnIdx })
    }

    @Test
    fun `bulk -1 expands to odd-from-zero, -2 to even-from-one, dedup preserved`() {
        val repo = RecordingReservedTurns()
        service(repo).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(-1), arg = null),
            ),
        )
        // -1 = (0,2,4,…,28) — PHP `range(0, MAX, 2)`.
        assertEquals((0 until 30 step 2).toList(), repo.generalReserves.map { it.turnIdx })

        val repo2 = RecordingReservedTurns()
        service(repo2).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(-2, 1), arg = null),
            ),
        )
        // -2 = (1,3,…,29) + 명시 1 → 중복 제거되어 (1,3,…,29) 그대로.
        assertEquals((1 until 30 step 2).toList(), repo2.generalReserves.map { it.turnIdx })
    }

    @Test
    fun `bulk turnIdx below -3 is denied`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(-4), arg = null),
            ),
        )
        assertFalse(result.result)
        assertEquals("올바른 턴이 아닙니다. : -4", result.reason)
    }

    // ── Push (General) ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `push positive delegates to pushGeneralTurn`() {
        val repo = RecordingReservedTurns()
        service(repo).pushGeneral(generalId = 10, amount = 3)
        assertEquals(listOf(3), repo.pushGeneral.map { it.cnt })
        assertTrue(repo.pullGeneral.isEmpty())
    }

    @Test
    fun `push negative delegates to pullGeneralTurn with abs`() {
        val repo = RecordingReservedTurns()
        service(repo).pushGeneral(generalId = 10, amount = -4)
        assertEquals(listOf(4), repo.pullGeneral.map { it.cnt })
        assertTrue(repo.pushGeneral.isEmpty())
    }

    @Test
    fun `push zero is denied`() {
        val repo = RecordingReservedTurns()
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).pushGeneral(generalId = 10, amount = 0)
        }
        assertEquals("0은 불가능합니다", ex.reason)
        assertTrue(repo.pushGeneral.isEmpty() && repo.pullGeneral.isEmpty())
    }

    // ── Repeat (General) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `repeat delegates to repeatGeneralTurn (no precheck, no zero gate)`() {
        val repo = RecordingReservedTurns()
        service(repo).repeatGeneral(generalId = 10, amount = 5)
        assertEquals(listOf(5), repo.repeatGeneral.map { it.cnt })
    }

    // ── Nation: Push / Repeat / Bulk ──────────────────────────────────────────────────────────────

    @Test
    fun `nation push positive delegates with nation and officer key`() {
        val repo = RecordingReservedTurns()
        service(repo).pushNation(generalId = 10, nationId = 7, officerLevel = 5, amount = 2)
        assertEquals(1, repo.pushNation.size)
        assertEquals(7, repo.pushNation[0].key)
        assertEquals(5, repo.pushNation[0].key2)
        assertEquals(2, repo.pushNation[0].cnt)
    }

    @Test
    fun `nation push negative delegates to pullNationTurn`() {
        val repo = RecordingReservedTurns()
        service(repo).pushNation(generalId = 10, nationId = 7, officerLevel = 6, amount = -3)
        assertEquals(listOf(3), repo.pullNation.map { it.cnt })
        assertTrue(repo.pushNation.isEmpty())
    }

    @Test
    fun `nation push zero is denied`() {
        val repo = RecordingReservedTurns()
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).pushNation(generalId = 10, nationId = 7, officerLevel = 5, amount = 0)
        }
        assertEquals("0은 불가능합니다", ex.reason)
    }

    @Test
    fun `nation repeat delegates to repeatNationTurn`() {
        val repo = RecordingReservedTurns()
        service(repo).repeatNation(generalId = 10, nationId = 7, officerLevel = 5, amount = 4)
        assertEquals(1, repo.repeatNation.size)
        assertEquals(7, repo.repeatNation[0].key)
        assertEquals(5, repo.repeatNation[0].key2)
        assertEquals(4, repo.repeatNation[0].cnt)
    }

    @Test
    fun `nation bulk reserves each chief turn slot keyed by nation and officer`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkNation(
            generalId = 10,
            nationId = 7,
            officerLevel = 5,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_몰수", turnList = listOf(0, 3), arg = null),
            ),
        )
        assertTrue(result.result)
        assertEquals(2, repo.nationReserves.size)
        assertTrue(repo.nationReserves.all { it.worldId == WorldId(1) })
        assertEquals(listOf(0, 3), repo.nationReserves.map { it.turnIdx })
        assertTrue(repo.nationReserves.all { it.nationId == 7 && it.officerLevel == 5 })
        assertEquals("che_몰수", repo.nationReserves[0].action)
    }

    @Test
    fun `nation bulk rejects general-only command code`() {
        val repo = RecordingReservedTurns()
        // che_농지개간 은 availableGeneralCommand 에는 있으나 availableChiefCommand 에는 없다.
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).reserveBulkNation(
                generalId = 10, nationId = 7, officerLevel = 5,
                commands = listOf(
                    CommandQueueService.CommandBulkItem(action = "che_농지개간", turnList = listOf(0), arg = null),
                ),
            )
        }
        assertEquals("0: 사용할 수 없는 커맨드입니다.", ex.reason)
    }

    @Test
    fun `nation bulk rejects event research command code`() {
        val repo = RecordingReservedTurns()
        val ex = assertFailsWith<CommandQueueService.CommandQueueDenied> {
            service(repo).reserveBulkNation(
                generalId = 10, nationId = 7, officerLevel = 5,
                commands = listOf(
                    CommandQueueService.CommandBulkItem(action = "event_극병연구", turnList = listOf(0), arg = null),
                ),
            )
        }
        assertEquals("0: 사용할 수 없는 커맨드입니다.", ex.reason)
    }

    @Test
    fun `nation bulk turnIdx out of chief range (12) is denied`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkNation(
            generalId = 10, nationId = 7, officerLevel = 5,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_몰수", turnList = listOf(12), arg = null),
            ),
        )
        assertFalse(result.result)
        assertEquals("올바른 턴이 아닙니다. : 12", result.reason)
    }

    @Test
    fun `nation bulk negative turnIdx is denied (no expansion on chief ring)`() {
        val repo = RecordingReservedTurns()
        val result = service(repo).reserveBulkNation(
            generalId = 10, nationId = 7, officerLevel = 5,
            commands = listOf(
                CommandQueueService.CommandBulkItem(action = "che_몰수", turnList = listOf(-1), arg = null),
            ),
        )
        assertFalse(result.result)
        assertEquals("올바른 턴이 아닙니다. : -1", result.reason)
    }

    // ── JSON arg 직렬화(삽입 순서 보존) ─────────────────────────────────────────────────────────────

    @Test
    fun `arg encodes preserving insertion order`() {
        val repo = RecordingReservedTurns()
        service(repo).reserveBulkGeneral(
            generalId = 10,
            commands = listOf(
                CommandQueueService.CommandBulkItem(
                    action = "che_농지개간",
                    turnList = listOf(0),
                    arg = linkedMapOf("destCityID" to 123, "isGold" to true, "note" to "a\"b"),
                ),
            ),
        )
        assertEquals("""{"destCityID":123,"isGold":true,"note":"a\"b"}""", repo.generalReserves[0].arg)
    }
}
