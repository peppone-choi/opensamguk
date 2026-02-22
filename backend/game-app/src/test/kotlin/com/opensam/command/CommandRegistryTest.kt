package com.opensam.command

import com.opensam.entity.General
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class CommandRegistryTest {

    private lateinit var registry: CommandRegistry

    @BeforeEach
    fun setUp() {
        registry = CommandRegistry()
    }

    private fun createTestGeneral(): General {
        return General(
            id = 1,
            worldId = 1,
            name = "테스트",
            nationId = 1,
            cityId = 1,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createTestEnv(): CommandEnv {
        return CommandEnv(
            year = 200,
            month = 1,
            startYear = 190,
            worldId = 1,
        )
    }

    // ========== General Commands ==========

    @Test
    fun `registry should have 55 general commands`() {
        val names = registry.getGeneralCommandNames()
        assertEquals(55, names.size, "Expected 55 general commands but got ${names.size}: $names")
    }

    @Test
    fun `general commands should include all default commands`() {
        assertTrue(registry.hasGeneralCommand("휴식"))
    }

    @Test
    fun `general commands should include all civil commands`() {
        val civilCommands = listOf(
            "농지개간", "상업투자", "치안강화", "수비강화", "성벽보수",
            "정착장려", "주민선정", "기술연구", "모병", "징병",
            "훈련", "사기진작", "소집해제", "숙련전환", "물자조달",
            "군량매매", "헌납", "단련"
        )
        for (cmd in civilCommands) {
            assertTrue(registry.hasGeneralCommand(cmd), "Missing general command: $cmd")
        }
    }

    @Test
    fun `general commands should include all military commands`() {
        val militaryCommands = listOf(
            "출병", "이동", "집합", "귀환", "접경귀환",
            "강행", "거병", "전투태세", "화계", "첩보",
            "선동", "탈취", "파괴", "요양", "방랑"
        )
        for (cmd in militaryCommands) {
            assertTrue(registry.hasGeneralCommand(cmd), "Missing general command: $cmd")
        }
    }

    @Test
    fun `general commands should include all political commands`() {
        val politicalCommands = listOf(
            "등용", "등용수락", "임관", "랜덤임관", "장수대상임관",
            "하야", "은퇴", "건국", "무작위건국", "모반시도",
            "선양", "해산", "견문", "인재탐색", "증여",
            "장비매매", "내정특기초기화", "전투특기초기화"
        )
        for (cmd in politicalCommands) {
            assertTrue(registry.hasGeneralCommand(cmd), "Missing general command: $cmd")
        }
    }

    @Test
    fun `general commands should include NPC and CR special commands`() {
        val specialCommands = listOf("NPC능동", "CR건국", "CR맹훈련")
        for (cmd in specialCommands) {
            assertTrue(registry.hasGeneralCommand(cmd), "Missing general command: $cmd")
        }
    }

    // ========== Nation Commands ==========

    @Test
    fun `registry should have 38 nation commands`() {
        val names = registry.getNationCommandNames()
        assertEquals(38, names.size, "Expected 38 nation commands but got ${names.size}: $names")
    }

    @Test
    fun `nation commands should include default nation command`() {
        assertTrue(registry.hasNationCommand("Nation휴식"))
    }

    @Test
    fun `nation commands should include resource management commands`() {
        val resourceCommands = listOf(
            "포상", "몰수", "감축", "증축", "발령",
            "천도", "백성동원", "물자원조", "국기변경", "국호변경"
        )
        for (cmd in resourceCommands) {
            assertTrue(registry.hasNationCommand(cmd), "Missing nation command: $cmd")
        }
    }

    @Test
    fun `nation commands should include diplomacy commands`() {
        val diplomacyCommands = listOf(
            "선전포고", "종전제의", "종전수락",
            "불가침제의", "불가침수락", "불가침파기제의", "불가침파기수락"
        )
        for (cmd in diplomacyCommands) {
            assertTrue(registry.hasNationCommand(cmd), "Missing nation command: $cmd")
        }
    }

    @Test
    fun `nation commands should include strategic commands`() {
        val strategicCommands = listOf(
            "급습", "수몰", "허보", "초토화",
            "필사즉생", "이호경식", "피장파장", "의병모집"
        )
        for (cmd in strategicCommands) {
            assertTrue(registry.hasNationCommand(cmd), "Missing nation command: $cmd")
        }
    }

    @Test
    fun `nation commands should include research commands`() {
        val researchCommands = listOf(
            "극병연구", "대검병연구", "무희연구", "산저병연구",
            "상병연구", "원융노병연구", "음귀병연구", "화륜차연구", "화시병연구"
        )
        for (cmd in researchCommands) {
            assertTrue(registry.hasNationCommand(cmd), "Missing nation command: $cmd")
        }
    }

    @Test
    fun `nation commands should include special commands`() {
        val specialCommands = listOf("무작위수도이전", "부대탈퇴지시", "인구이동")
        for (cmd in specialCommands) {
            assertTrue(registry.hasNationCommand(cmd), "Missing nation command: $cmd")
        }
    }

    // ========== Command Creation ==========

    @Test
    fun `createGeneralCommand should create command by action code`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = registry.createGeneralCommand("휴식", general, env)

        assertEquals("휴식", cmd.actionName)
    }

    @Test
    fun `createGeneralCommand should fallback to 휴식 for unknown action`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = registry.createGeneralCommand("존재하지않는명령", general, env)

        assertEquals("휴식", cmd.actionName)
    }

    @Test
    fun `createGeneralCommand should pass arguments`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 1)
        val cmd = registry.createGeneralCommand("모병", general, env, arg)

        assertEquals("모병", cmd.actionName)
    }

    @Test
    fun `createNationCommand should create command by action code`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = registry.createNationCommand("Nation휴식", general, env)

        assertNotNull(cmd)
        assertEquals("휴식", cmd!!.actionName)
    }

    @Test
    fun `createNationCommand should return null for unknown action`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = registry.createNationCommand("존재하지않는명령", general, env)

        assertNull(cmd)
    }

    // ========== Has Command ==========

    @Test
    fun `hasGeneralCommand returns true for known command`() {
        assertTrue(registry.hasGeneralCommand("농지개간"))
    }

    @Test
    fun `hasGeneralCommand returns false for unknown command`() {
        assertFalse(registry.hasGeneralCommand("없는명령"))
    }

    @Test
    fun `hasNationCommand returns true for known command`() {
        assertTrue(registry.hasNationCommand("선전포고"))
    }

    @Test
    fun `hasNationCommand returns false for unknown command`() {
        assertFalse(registry.hasNationCommand("없는명령"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `general and nation command keys should not overlap except for intentional names`() {
        val generalKeys = registry.getGeneralCommandNames()
        val nationKeys = registry.getNationCommandNames()
        val overlap = generalKeys.intersect(nationKeys)
        // There should be no overlap between general and nation command keys
        assertTrue(overlap.isEmpty(), "Unexpected overlap: $overlap")
    }
}
