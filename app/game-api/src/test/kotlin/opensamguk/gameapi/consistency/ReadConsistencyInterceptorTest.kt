package opensamguk.gameapi.consistency

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.config.GameApiProcessWorld
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadConsistencyInterceptorTest {
    private val barrier = RecordingBarrier()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(ProbeController())
            .addInterceptors(ReadConsistencyInterceptor(barrier, ReadConsistencyClassifier(), ObjectMapper(), GameApiProcessWorld(1)))
            .build()

    @Test
    fun `authoritative read with minVersion passes after visible primary version`() {
        barrier.result = VersionVisibility(
            visible = true,
            requiredVersion = 12,
            currentVersion = 12,
            retryAfterMs = 75,
        )

        mockMvc().perform(get("/api/front-info").param("minVersion", "12"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))

        assertEquals(listOf(12L), barrier.requests)
    }

    @Test
    fun `stale minVersion returns VERSION_NOT_VISIBLE without invoking controller`() {
        barrier.result = VersionVisibility(
            visible = false,
            requiredVersion = 12,
            currentVersion = 10,
            retryAfterMs = 75,
        )

        mockMvc().perform(get("/api/my-page").param("minVersion", "12"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("VERSION_NOT_VISIBLE"))
            .andExpect(jsonPath("$.consistencyClass").value("AUTHORITATIVE"))
            .andExpect(jsonPath("$.worldId").value(1))
            .andExpect(jsonPath("$.currentVersion").value(10))
            .andExpect(jsonPath("$.requiredVersion").value(12))
            .andExpect(jsonPath("$.retryAfterMs").value(75))

        assertEquals(listOf(12L), barrier.requests)
    }

    @Test
    fun `eventual endpoint without minVersion does not consult barrier`() {
        mockMvc().perform(get("/api/rankings/generals"))
            .andExpect(status().isOk)

        assertTrue(barrier.requests.isEmpty())
    }

    @Test
    fun `eventual endpoint with minVersion proceeds without barrier wait`() {
        mockMvc().perform(get("/api/rankings/generals").param("minVersion", "12"))
            .andExpect(status().isOk)

        assertTrue(barrier.requests.isEmpty())
    }

    @Test
    fun `invalid minVersion returns contract error`() {
        mockMvc().perform(get("/api/front-info").param("minVersion", "abc"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("INVALID_MIN_VERSION"))

        assertTrue(barrier.requests.isEmpty())
    }

    private class RecordingBarrier : MinVersionBarrier {
        val requests = mutableListOf<Long>()
        var result = VersionVisibility(
            visible = true,
            requiredVersion = 0,
            currentVersion = 0,
            retryAfterMs = 1,
        )

        override fun await(requiredVersion: Long): VersionVisibility {
            requests += requiredVersion
            return result.copy(requiredVersion = requiredVersion)
        }
    }

    @RestController
    private class ProbeController {
        @GetMapping("/api/front-info")
        fun frontInfo(): Map<String, Any> = mapOf("result" to true)

        @GetMapping("/api/my-page")
        fun myPage(): Map<String, Any> = mapOf("result" to true)

        @GetMapping("/api/rankings/generals")
        fun rankings(): List<Map<String, Any>> = emptyList()
    }
}
