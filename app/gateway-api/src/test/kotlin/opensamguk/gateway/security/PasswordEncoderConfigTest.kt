package opensamguk.gateway.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class PasswordEncoderConfigTest {

    private val encoder = BCryptPasswordEncoder()

    @Test
    fun `encode password`() {
        val raw = "password123"
        val encoded = encoder.encode(raw)
        assertNotEquals(raw, encoded)
        assertTrue(encoder.matches(raw, encoded))
    }

    @Test
    fun `mismatch password returns false`() {
        val encoded = encoder.encode("password123")
        assertFalse(encoder.matches("wrongpassword", encoded))
    }
}
