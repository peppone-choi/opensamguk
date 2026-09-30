package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfflineDelegationLeaseTest {
    @Test
    fun `delegation starts only after two completed inactive phases across a month boundary`() {
        val meta = mapOf(OfflineDelegationLease.META_KEY to
            OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 2)).toMetaValue())

        assertFalse(OfflineDelegationLease.mayDelegate(meta, 7, 12, 19, DelegationPhase(190, 1, 3)))
        assertFalse(OfflineDelegationLease.mayDelegate(meta, 7, 12, 19, DelegationPhase(190, 2, 1)))
        assertTrue(OfflineDelegationLease.mayDelegate(meta, 7, 12, 19, DelegationPhase(190, 2, 2)))
    }

    @Test
    fun `owner return cancels delegation for the next execution and stale pulses do not renew it`() {
        val previous = OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 1))
        val next = DelegationPhase(190, 2, 1)
        assertTrue(OfflineDelegationLease.mayDelegate(
            mapOf(OfflineDelegationLease.META_KEY to previous.toMetaValue()), 7, 12, 19, next))

        val returned = previous.refreshedAt(next)
        assertFalse(OfflineDelegationLease.mayDelegate(
            mapOf(OfflineDelegationLease.META_KEY to returned.toMetaValue()), 7, 12, 19, next))
        assertSame(returned, returned.refreshedAt(DelegationPhase(190, 1, 3)))
        assertSame(returned, returned.refreshedAt(next))
    }

    @Test
    fun `missing corrupt foreign and future activity never delegates`() {
        val now = DelegationPhase(190, 2, 1)
        assertFalse(OfflineDelegationLease.mayDelegate(emptyMap(), 7, 12, 19, now))
        val old = OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 1)).toMetaValue()
        val meta = mapOf(OfflineDelegationLease.META_KEY to old)
        assertFalse(OfflineDelegationLease.mayDelegate(meta, 8, 12, 19, now))
        assertFalse(OfflineDelegationLease.mayDelegate(meta, 7, 13, 19, now))
        assertFalse(OfflineDelegationLease.mayDelegate(meta, 7, 12, 20, now))
        assertFalse(OfflineDelegationLease.mayDelegate(
            mapOf(OfflineDelegationLease.META_KEY to old + ("phase" to 4)), 7, 12, 19, now))
        assertFalse(OfflineDelegationLease.mayDelegate(
            mapOf(OfflineDelegationLease.META_KEY to old + ("version" to 2)), 7, 12, 19, now))
        assertNull(OfflineDelegationLease.activeIn(
            mapOf(OfflineDelegationLease.META_KEY to OfflineDelegationLease(7, 12, 19,
                DelegationPhase(190, 2, 2)).toMetaValue()), 7, 12, 19, now))
    }
}
