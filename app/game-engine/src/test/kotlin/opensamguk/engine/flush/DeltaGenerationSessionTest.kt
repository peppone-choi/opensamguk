package opensamguk.engine.flush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-130 — drives the real [DeltaGenerationSession] prepare/commit/abort path.
 */
class DeltaGenerationSessionTest {

    @Test
    fun `prepare freezes generation and blocks mutation until commit`() {
        val session = DeltaGenerationSession()
        assertTrue(session.allowsMutation())
        val n = session.prepare()
        assertEquals(1L, n)
        assertEquals(DeltaGenerationSession.Phase.PREPARED, session.phase())
        assertFalse(session.allowsMutation())
        assertFalse(session.allowsIntakeOrTick())
        assertFailsWith<IllegalStateException> { session.requireMutationAllowed("diffGeneral") }
        session.commit(n)
        assertEquals(DeltaGenerationSession.Phase.IDLE, session.phase())
        assertTrue(session.allowsMutation())
        assertEquals(n, session.snapshot().lastCommitted)
    }

    @Test
    fun `abort restores idle without committing and allows retry of same batch`() {
        val session = DeltaGenerationSession()
        val n = session.prepare()
        session.abort(n)
        assertEquals(DeltaGenerationSession.Phase.IDLE, session.phase())
        assertEquals(n, session.snapshot().lastAborted)
        assertEquals(null, session.snapshot().lastCommitted)
        // New prepare allocates next generation; deltas are caller-owned (not drained by abort).
        val n2 = session.prepare()
        assertEquals(2L, n2)
        session.abort(n2)
    }

    @Test
    fun `duplicate commit is idempotent and wrong generation is illegal`() {
        val session = DeltaGenerationSession()
        val n = session.prepare()
        session.commit(n)
        session.commit(n) // idempotent
        assertFailsWith<IllegalStateException> { session.commit(n + 1) }
        assertFailsWith<IllegalArgumentException> {
            val m = session.prepare()
            session.commit(m + 99)
        }
    }

    @Test
    fun `duplicate abort is idempotent and prepare while prepared is illegal`() {
        val session = DeltaGenerationSession()
        val n = session.prepare()
        assertFailsWith<IllegalStateException> { session.prepare() }
        session.abort(n)
        session.abort(n) // idempotent
        assertFailsWith<IllegalStateException> { session.abort(n + 1) }
    }

    @Test
    fun `commit after successful prepare clears only that generation and unblocks next`() {
        val session = DeltaGenerationSession()
        val a = session.prepare()
        session.commit(a)
        val b = session.prepare()
        assertEquals(a + 1, b)
        session.commit(b)
        assertEquals(b, session.snapshot().lastCommitted)
        assertTrue(session.allowsIntakeOrTick())
    }
}
