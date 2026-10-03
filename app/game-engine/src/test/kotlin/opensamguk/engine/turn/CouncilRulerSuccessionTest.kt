package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.logic.council.CurrentRulerBinding
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.PoliticalInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class CouncilRulerSuccessionTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private fun person(id: Int, level: Int, lord: Boolean) = TurnGeneral(id = id, name = "인물$id",
        nationId = 1, cityId = 5, troopId = 0, stats = GeneralStats(70, 70, 70),
        experience = 0, dedication = 0, officerLevel = level, npcState = 0, turnTime = at,
        meta = mapOf(LordStatus.META_KEY to lord))
    private fun world(profile: String, sequence: Any? = null): InMemoryTurnWorld {
        var meta = CurrentRulerBinding.with(mapOf("보존" to true), 10, "rise-1", PoliticalInput.RISE)
        if (sequence != null) meta = meta + (CurrentRulerBinding.SUCCESSION_SEQUENCE_KEY to sequence)
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, at, config = mapOf("ruleProfile" to profile)),
            generals = listOf(person(10, 12, true), person(11, 9, false)),
            nations = listOf(Nation(1, "본국", "#111111", chiefGeneralId = 10, meta = meta))))
    }
    private val env = LifecycleEnv(100, 200, 1, 60)

    @Test fun `휘하 승계는 실제 선택한 후계와 지속 순번을 군주 meta에 저장한다`() {
        val world = world("HWIHA", 7L)
        val recorder = ChangeRecorder()
        RulerSuccessionHandler(world, recorder, "test-seed").succeed(10, env)
        val nation = world.getNationById(1)!!
        assertEquals(11, nation.chiefGeneralId)
        assertEquals(8L, nation.meta[CurrentRulerBinding.SUCCESSION_SEQUENCE_KEY])
        assertEquals(CurrentRulerBinding(11, "succession:1:1:8", CurrentRulerBinding.SUCCESSION_SOURCE),
            CurrentRulerBinding.read(nation.meta))
        assertTrue(LordStatus.read(world.getGeneralById(11)!!.meta))
        assertEquals(true, nation.meta["보존"])
        // 콜드 projection은 직함을 추론하지 않고 동일 durable binding을 사용한다.
        val cold = PerTurnOverlay.toEngineNation(PerTurnOverlay.toLogicNation(nation))
        assertEquals(nation.chiefGeneralId, cold.chiefGeneralId)
        assertEquals(CurrentRulerBinding.read(nation.meta), CurrentRulerBinding.read(cold.meta))
        assertEquals(8L, cold.meta[CurrentRulerBinding.SUCCESSION_SEQUENCE_KEY])
        assertEquals(true, cold.meta["보존"])
    }

    @Test fun `삼모는 기존 선택 승격만 수행하고 새 군주 binding이나 주공 표지를 쓰지 않는다`() {
        val world = world(opensamguk.logic.input.RuleProfile.fromWorldConfig(null).name)
        val original = world.getNationById(1)!!
        RulerSuccessionHandler(world, ChangeRecorder(), "test-seed").succeed(10, env)
        assertEquals(12, world.getGeneralById(11)!!.officerLevel)
        assertFalse(LordStatus.read(world.getGeneralById(11)!!.meta))
        assertEquals(original, world.getNationById(1))
    }

    @Test fun `손상된 지속 순번은 후계나 국가를 변경하기 전에 실패한다`() {
        listOf(-1L, "7", 7.5, Long.MAX_VALUE).forEach { value ->
            val world = world("HWIHA", value)
            val before = world.getNationById(1)
            assertThrows(IllegalArgumentException::class.java) {
                RulerSuccessionHandler(world, ChangeRecorder(), "test-seed").succeed(10, env)
            }
            assertEquals(9, world.getGeneralById(11)!!.officerLevel)
            assertEquals(before, world.getNationById(1))
        }
    }
    @Test fun `군주가 아닌 주공 사망 hook은 직함이12여도 현재세력을 교체하거나 멸망시키지 않는다`() {
        val world = world("HWIHA")
        val otherLord = world.getGeneralById(11)!!
        world.applyGeneralDirtyFree(otherLord.copy(officerLevel = 12, meta = mapOf("lord" to true)))
        val beforeNation = world.getNationById(1)
        val beforePeople = world.listGenerals()
        val recorder = ChangeRecorder()
        RulerSuccessionHandler(world, recorder, "test-seed").succeed(11, env)
        assertEquals(beforeNation, world.getNationById(1))
        assertEquals(beforePeople, world.listGenerals())
        assertFalse(recorder.isDirty)
    }

    @Test fun `군주 근거가 없으면 메모리 chief나 직함만으로 승계 권한을 생산하지 않는다`() {
        val world = world("HWIHA")
        val original = world.getNationById(1)!!
        world.applyNationDirtyFree(original.copy(meta = original.meta - CurrentRulerBinding.META_KEY))
        val before = world.getNationById(1)
        val recorder = ChangeRecorder()
        RulerSuccessionHandler(world, recorder, "test-seed").succeed(10, env)
        assertEquals(before, world.getNationById(1))
        assertEquals(9, world.getGeneralById(11)!!.officerLevel)
        assertFalse(recorder.isDirty)
    }

}
