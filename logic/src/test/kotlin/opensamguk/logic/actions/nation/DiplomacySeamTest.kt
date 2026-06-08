package opensamguk.logic.actions.nation

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.domain.General
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A2 공통 인프라 SMOKE — [DiplomacySeam] + [InstantNationCommandRegistry]가 6개 외교 명령 에이전트가
 * 소비할 **공개 API**를 정확히 노출하는지 증명한다. (명령 본체 run()은 다음 페이즈 소유 — 여기선 seam만.)
 *
 *  - validUntil 공식 = PHP `max(30, turnterm*3)분` (che_*제의.php 공통) byte-동등.
 *  - StaticEventHandler 외교 훅 호출 지점 (PHP `run()` 말미 handleEvent).
 *  - instant-nation 키-집합 = 외교 수락 3종.
 */
class DiplomacySeamTest {

    @AfterTest
    fun cleanup() {
        StaticEventHandler.clear()
    }

    private fun general(id: Int = 1) = General(
        id = id, nationId = 1, cityId = 1,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
    )

    // ── validUntil 공식 = max(30, turnterm*3) ───────────────────────────────────────────────────

    @Test
    fun `validMinutes follows PHP max 30 turnterm times 3`() {
        // turnterm=60(prod 기본값) → max(30, 180) = 180
        assertEquals(180, DiplomacySeam.validMinutes(60))
        // turnterm=5 → max(30, 15) = 30 (floor 30분 발동)
        assertEquals(30, DiplomacySeam.validMinutes(5))
        // turnterm=10 → max(30, 30) = 30 (경계)
        assertEquals(30, DiplomacySeam.validMinutes(10))
        // turnterm=11 → max(30, 33) = 33 (floor 위)
        assertEquals(33, DiplomacySeam.validMinutes(11))
    }

    @Test
    fun `validUntil adds validMinutes to sendDate and formats Y-m-d H-i-s`() {
        val sendDate = LocalDateTime.of(2026, 6, 7, 12, 0, 0)
        // turnterm=60 → +180분 → 15:00:00
        assertEquals("2026-06-07 15:00:00", DiplomacySeam.validUntil(sendDate, 60))
        // turnterm=5 → +30분(floor) → 12:30:00
        assertEquals("2026-06-07 12:30:00", DiplomacySeam.validUntil(sendDate, 5))
    }

    // ── StaticEventHandler 외교 훅 ───────────────────────────────────────────────────────────────

    @Test
    fun `fireDiplomacyEvent is a no-op when no handler registered (scenario 1010 gate)`() {
        // 핸들러 미등록 → 예외 없이 no-op (게이트 동일). 호출만으로 통과.
        DiplomacySeam.fireDiplomacyEvent(
            general = general(),
            destGeneral = null,
            commandKey = "che_불가침제의",
            env = mapOf("year" to 2026, "month" to 6),
            args = mapOf("destNationID" to 5),
        )
    }

    @Test
    fun `fireDiplomacyEvent dispatches to a registered handler with the command key as eventType`() {
        var seenKey: String? = null
        var seenDestNull = false
        StaticEventHandler.register("che_종전제의") { _, destGeneral, _, params ->
            seenKey = params["__k"] as? String
            seenDestNull = destGeneral == null
        }
        DiplomacySeam.fireDiplomacyEvent(
            general = general(),
            destGeneral = null,
            commandKey = "che_종전제의",
            env = mapOf("year" to 2026, "month" to 6),
            args = mapOf("__k" to "che_종전제의", "destNationID" to 5),
        )
        assertEquals("che_종전제의", seenKey)
        assertTrue(seenDestNull)
    }

    // ── instant-nation 레지스트리 ────────────────────────────────────────────────────────────────

    @Test
    fun `instant-nation keys are exactly the three accept commands`() {
        assertEquals(
            linkedSetOf("che_불가침수락", "che_종전수락", "che_불가침파기수락"),
            InstantNationCommandRegistry.INSTANT_NATION_COMMAND_KEYS,
        )
        assertTrue(InstantNationCommandRegistry.isInstantNationCommand("che_종전수락"))
        assertFalse(InstantNationCommandRegistry.isInstantNationCommand("che_불가침제의"))
    }

    @Test
    fun `acceptCommandKeyFor maps option action to the accept command`() {
        assertEquals("che_불가침수락", InstantNationCommandRegistry.acceptCommandKeyFor("no_aggression"))
        assertEquals("che_종전수락", InstantNationCommandRegistry.acceptCommandKeyFor("stop_war"))
        assertEquals("che_불가침파기수락", InstantNationCommandRegistry.acceptCommandKeyFor("cancel_na"))
        assertNull(InstantNationCommandRegistry.acceptCommandKeyFor("unknown_action"))
    }

    @Test
    fun `loadInstantNationActionSpecs resolves all three accept commands from the registry`() {
        val registry = CommandRegistry(GeneralActionPipeline())
        val specs = InstantNationCommandRegistry.loadInstantNationActionSpecs(registry)
        // 세 수락 커맨드 모두 정식 등록(fallback 아님) → 3개 모두 해석되어야 한다.
        assertEquals(3, specs.size)
        assertEquals("che_불가침수락", specs["che_불가침수락"]?.key)
        assertEquals("che_종전수락", specs["che_종전수락"]?.key)
        assertEquals("che_불가침파기수락", specs["che_불가침파기수락"]?.key)
        // fallback(RestAction)이 섞이지 않았는지 — 모든 값이 NationCommand인지 확인.
        assertTrue(specs.values.all { it !== registry.fallback })
    }
}
