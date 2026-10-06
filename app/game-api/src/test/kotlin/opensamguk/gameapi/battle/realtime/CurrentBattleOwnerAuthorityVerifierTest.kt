package opensamguk.gameapi.battle.realtime

import opensamguk.common.auth.GatewayPrincipal
import opensamguk.common.world.WorldId
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityBinding
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityCurrentSource
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityFacts
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind
import opensamguk.logic.battle.realtime.TacticalV2UnitSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurrentBattleOwnerAuthorityVerifierTest {
    private val key = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "1")
    private fun binding(owner: Int = 7, account: Int = 42, revision: Long = 1, sourceRevision: Long = 1,
                        world: WorldId = WorldId(1), origin: String = "a".repeat(64)) =
        BattleOwnerAuthorityBinding.bind(world, "battle", origin,
            listOf(TacticalV2UnitSource(key, BattleSide.ATTACKER, owner, 9, 30, sourceRevision)),
            listOf(BattleOwnerAuthorityFacts(owner, account.toString(), 0, account, true, revision,
                "ACTIVE", BattleSide.ATTACKER, setOf(key)))).single()
    private fun request(account: Long = 42): MockHttpServletRequest = MockHttpServletRequest().also {
        it.setAttribute(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, GatewayPrincipal(account, "USER"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            account, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
    }
    @AfterEach fun clearContext() = SecurityContextHolder.clearContext()

    @Test fun `uninstalled or unavailable supplier cannot verify even an authenticated owner`() {
        val request = request()
        assertFailsWith<SecurityException> { CurrentBattleOwnerAuthorityVerifier().verify(request, binding()) }
        assertFailsWith<SecurityException> {
            CurrentBattleOwnerAuthorityVerifier(BattleOwnerAuthorityCurrentSource { null }).verify(request, binding())
        }
    }

    @Test fun `matching native reads verify owner facts without issuing a ticket`() {
        val expected = binding()
        var reads = 0
        val source = BattleOwnerAuthorityCurrentSource { reads++; expected }
        val observed = CurrentBattleOwnerAuthorityVerifier(source).verify(request(), expected)
        assertEquals(2, reads)
        assertTrue(expected.matches(observed))
    }

    @Test fun `commander or other same side account cannot borrow the owner binding`() {
        val expected = binding()
        var reads = 0
        val verifier = CurrentBattleOwnerAuthorityVerifier(BattleOwnerAuthorityCurrentSource { reads++; expected })
        for (account in listOf(9L, 43L)) {
            assertFailsWith<SecurityException> { verifier.verify(request(account), expected) }
        }
        assertEquals(0, reads)
    }

    @Test fun `request attribute alone or mismatched authenticated context is not a verified principal`() {
        val expected = binding()
        val verifier = CurrentBattleOwnerAuthorityVerifier(BattleOwnerAuthorityCurrentSource { expected })
        val request = request()
        SecurityContextHolder.clearContext()
        assertFailsWith<SecurityException> { verifier.verify(request, expected) }
        request(43)
        assertFailsWith<SecurityException> { verifier.verify(request, expected) }
        val anonymous = MockHttpServletRequest()
        assertFailsWith<SecurityException> { verifier.verify(anonymous, expected) }
        assertFailsWith<SecurityException> { verifier.verify(request(Int.MAX_VALUE.toLong() + 1), expected) }
    }

    @Test fun `current ownership authority ABA and origin or source revision drift are rejected`() {
        val expected = binding()
        val request = request()
        for (current in listOf(binding(owner = 9), binding(account = 43), binding(revision = 3),
            binding(sourceRevision = 2), binding(world = WorldId(2)), binding(origin = "b".repeat(64)))) {
            assertFailsWith<SecurityException> {
                CurrentBattleOwnerAuthorityVerifier(BattleOwnerAuthorityCurrentSource { current })
                    .verify(request, expected)
            }
        }
    }

    @Test fun `change between reads cannot pass as current even if first read matched`() {
        val expected = binding()
        var reads = 0
        val source = BattleOwnerAuthorityCurrentSource { if (++reads == 1) expected else binding(revision = 2) }
        assertFailsWith<SecurityException> { CurrentBattleOwnerAuthorityVerifier(source).verify(request(), expected) }
        assertEquals(2, reads)
    }

    @Test fun `supplier failure and principal replacement during reads remain denied`() {
        val expected = binding()
        val incoming = request()
        val failed = BattleOwnerAuthorityCurrentSource { throw IllegalStateException("synthetic read failure") }
        assertFailsWith<SecurityException> { CurrentBattleOwnerAuthorityVerifier(failed).verify(incoming, expected) }
        val changed = BattleOwnerAuthorityCurrentSource { request(43); expected }
        assertFailsWith<SecurityException> { CurrentBattleOwnerAuthorityVerifier(changed).verify(incoming, expected) }
    }
}
