package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldModeSettingsTest {
    @Test fun `missing timing preserves general turns for an explicit permadeath choice`() {
        for (choice in listOf(false, true)) {
            val settings = ready(mapOf("allowPlayerPermadeath" to choice))
            assertEquals(WorldTimeMode.GENERAL_TURN, settings.timeMode)
            assertEquals(choice, settings.allowPlayerPermadeath)
        }
    }

    @Test fun `null timing also preserves general turns`() {
        val settings = ready(mapOf("timeMode" to null, "allowPlayerPermadeath" to false))
        assertEquals(WorldTimeMode.GENERAL_TURN, settings.timeMode)
    }

    @Test fun `both known timing modes preserve both explicit permadeath choices`() {
        for (mode in WorldTimeMode.entries) {
            for (choice in listOf(false, true)) {
                val settings = ready(mapOf("timeMode" to mode.name, "allowPlayerPermadeath" to choice))
                assertEquals(mode, settings.timeMode)
                assertEquals(choice, settings.allowPlayerPermadeath)
            }
        }
    }

    @Test fun `unknown timing never silently falls back or normalizes`() {
        for (value in listOf("", "FUTURE", "HALF_TURN", "realtime", "REAL_TIME", " GENERAL_TURN", "GENERAL_TURN ")) {
            val error = assertFailsWith<IllegalArgumentException> {
                WorldModeSettings.fromWorldConfig(mapOf("timeMode" to value, "allowPlayerPermadeath" to false))
            }
            assertTrue(error.message.orEmpty().contains("unknown timeMode"))
        }
    }

    @Test fun `non string timing is invalid rather than absent`() {
        for (value in listOf(0, false, listOf("GENERAL_TURN"), mapOf("value" to "REALTIME"))) {
            val error = assertFailsWith<IllegalArgumentException> {
                WorldModeSettings.fromWorldConfig(mapOf("timeMode" to value, "allowPlayerPermadeath" to true))
            }
            assertEquals("timeMode must be a string", error.message)
        }
    }

    @Test fun `missing and null permadeath have no invented default or required choice`() {
        for (mode in listOf(null, "GENERAL_TURN", "REALTIME")) {
            for (config in listOf(mapOf("timeMode" to mode), mapOf("timeMode" to mode, "allowPlayerPermadeath" to null))) {
                val result = assertIs<WorldModeSettingsLoad.NotDecided>(WorldModeSettings.fromWorldConfig(config))
                assertTrue(result.reason.startsWith("NOT_DECIDED:"))
                assertTrue(result.reason.contains("allowPlayerPermadeath"))
            }
        }
        assertIs<WorldModeSettingsLoad.NotDecided>(WorldModeSettings.fromWorldConfig(emptyMap()))
    }

    @Test fun `permadeath must be an actual boolean without truthy coercion`() {
        for (value in listOf("true", "false", "", 0, 1, emptyList<Boolean>(), mapOf("value" to true))) {
            val error = assertFailsWith<IllegalArgumentException> {
                WorldModeSettings.fromWorldConfig(mapOf("allowPlayerPermadeath" to value))
            }
            assertEquals("allowPlayerPermadeath must be a boolean", error.message)
        }
    }

    @Test fun `undecided permadeath does not hide an unknown timing failure`() {
        for (config in listOf(mapOf("timeMode" to "FUTURE"), mapOf("timeMode" to "FUTURE", "allowPlayerPermadeath" to null))) {
            assertFailsWith<IllegalArgumentException> { WorldModeSettings.fromWorldConfig(config) }
        }
    }

    @Test fun `parsing ignores unrelated world keys without changing the input`() {
        val config = linkedMapOf<String, Any?>("worldId" to 17, "allowPlayerPermadeath" to false, "timeMode" to "REALTIME")
        val before = config.toMap()
        val settings = ready(config)
        assertEquals(before, config)
        config["timeMode"] = "GENERAL_TURN"
        config["allowPlayerPermadeath"] = true
        assertEquals(WorldTimeMode.REALTIME, settings.timeMode)
        assertEquals(false, settings.allowPlayerPermadeath)
    }

    @Test fun `same input and different map order produce the same decision`() {
        val first = mapOf("timeMode" to "REALTIME", "allowPlayerPermadeath" to true)
        val reversed = first.entries.reversed().associate { it.toPair() }
        val expected = ready(first)
        for (config in listOf(first, reversed, first)) {
            val actual = ready(config)
            assertEquals(expected.timeMode, actual.timeMode)
            assertEquals(expected.allowPlayerPermadeath, actual.allowPlayerPermadeath)
        }
        assertEquals(WorldModeSettings.fromWorldConfig(emptyMap()), WorldModeSettings.fromWorldConfig(emptyMap()))
    }

    @Test fun `standalone timing parser preserves absent null and both explicit modes`() {
        for (config in listOf(emptyMap(), mapOf("timeMode" to null))) {
            assertEquals(WorldTimeMode.GENERAL_TURN, WorldModeSettings.timeModeFrom(config))
        }
        for (mode in WorldTimeMode.entries) {
            assertEquals(mode, WorldModeSettings.timeModeFrom(mapOf("timeMode" to mode.name)))
        }
    }

    @Test fun `standalone timing parser never reads or decides permadeath`() {
        for (value in listOf(null, false, true, "not a boolean", 1)) {
            val config = linkedMapOf<String, Any?>("timeMode" to "REALTIME", "allowPlayerPermadeath" to value)
            val before = config.toMap()
            assertEquals(WorldTimeMode.REALTIME, WorldModeSettings.timeModeFrom(config))
            assertEquals(before, config)
        }
    }

    @Test fun `standalone timing parser preserves exact invalid value messages`() {
        for (value in listOf("SEMI", "", "realtime", " GENERAL_TURN", "GENERAL_TURN ")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                WorldModeSettings.timeModeFrom(mapOf("timeMode" to value))
            }
            assertEquals("unknown timeMode in world config: '$value'", failure.message)
        }
        for (value in listOf(0, false, listOf("GENERAL_TURN"), mapOf("name" to "REALTIME"))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                WorldModeSettings.timeModeFrom(mapOf("timeMode" to value))
            }
            assertEquals("timeMode must be a string", failure.message)
        }
    }

    private fun ready(config: Map<String, Any?>): WorldModeSettings =
        assertIs<WorldModeSettingsLoad.Ready>(WorldModeSettings.fromWorldConfig(config)).settings
}
