package opensamguk.boardapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource

class BoardApiRuntimeConfigurationTest {
    private val properties = YamlPropertySourceLoader()
        .load(
            "board-api",
            FileSystemResource("${System.getProperty("boardApiProjectDir")}/src/main/resources/application.yml"),
        )
        .single()

    @Test
    fun `board runtime validates schema without owning Flyway`() {
        assertEquals(false, properties.getProperty("spring.flyway.enabled"))
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("\${BOARD_API_PORT:8083}", properties.getProperty("server.port"))
    }
}
