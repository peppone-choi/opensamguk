package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F2 Wave 1 — proves game-api verifies tokens minted with gateway-api's SHARED HS256 secret. We sign a
 * token here with the SAME default secret game-api defaults to (the gateway-api dev default), then assert
 * verify + subject(=userId) extraction. A token signed with a DIFFERENT secret must NOT verify (the
 * shared-secret trust boundary).
 */
class GameApiJwtVerifierTest {

    // identical to gateway-api's dev default + game-api application.yml default.
    private val sharedSecret = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    private val verifier = GameApiJwtVerifier(sharedSecret)

    private fun mint(secret: String, userId: Long, username: String? = null, expiresInMs: Long = 900_000): String {
        val key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
        val now = Date()
        val b = Jwts.builder().subject(userId.toString()).issuedAt(now).expiration(Date(now.time + expiresInMs)).signWith(key)
        if (username != null) b.claim("username", username)
        return b.compact()
    }

    @Test
    fun `verifies a token signed with the shared secret and extracts the userId subject`() {
        val token = mint(sharedSecret, userId = 42L, username = "alice")
        assertTrue(verifier.isValid(token))
        assertEquals(42L, verifier.getUserId(token))
        assertEquals("alice", verifier.getUsername(token))
    }

    @Test
    fun `rejects a token signed with a different secret`() {
        val other = "ZGlmZmVyZW50LXNlY3JldC1kaWZmZXJlbnQtc2VjcmV0LWRpZmZlcmVudC1zZWNyZXQ="
        val token = mint(other, userId = 42L)
        assertFalse(verifier.isValid(token))
        assertNull(verifier.getUserId(token))
    }

    @Test
    fun `rejects an expired token`() {
        val token = mint(sharedSecret, userId = 42L, expiresInMs = -1000)
        assertFalse(verifier.isValid(token))
    }

    @Test
    fun `rejects garbage`() {
        assertFalse(verifier.isValid("not-a-jwt"))
        assertNull(verifier.getUserId("not-a-jwt"))
    }
}
