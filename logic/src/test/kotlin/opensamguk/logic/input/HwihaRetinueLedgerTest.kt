package opensamguk.logic.input

import kotlin.test.*

class HwihaRetinueLedgerTest {
    @Test fun `nested followers travel together while each holder pays only direct costs`() {
        val people = listOf(
            RetinueOwner(10, 1, true, true, 30, 6),
            RetinueOwner(2, 1, false, true, 9, 5),
            RetinueOwner(3, 1, false, false, 8, 4),
        )
        val ledger = HwihaRetinueLedger.assess(people,
            listOf(RetinuePersonLink(101, 10, 2), RetinuePersonLink(102, 2, 3)),
            listOf(RetinueNamedUnit("named:1", 2, 2)))
        assertEquals(listOf(10, 2, 3), ledger.followersByOwner.getValue(10))
        assertEquals(10, ledger.topOwnerByPerson.getValue(3))
        assertEquals(25, ledger.freeRenownByOwner.getValue(10))
        assertEquals(3, ledger.freeRenownByOwner.getValue(2))
        assertEquals(8, ledger.freeRenownByOwner.getValue(3))
    }

    @Test fun `cycles duplicate ownership human subordinate and owner overflow fail closed`() {
        val a = RetinueOwner(1, 1, true, true, 5, 5)
        val b = RetinueOwner(2, 1, false, false, 4, 4)
        assertFailsWith<IllegalArgumentException> { HwihaRetinueLedger.assess(listOf(a, b),
            listOf(RetinuePersonLink(1, 1, 2), RetinuePersonLink(2, 2, 1))) }
        assertFailsWith<IllegalArgumentException> { HwihaRetinueLedger.assess(listOf(a, b),
            listOf(RetinuePersonLink(1, 1, 2), RetinuePersonLink(2, 1, 2))) }
        assertFailsWith<IllegalArgumentException> { HwihaRetinueLedger.assess(listOf(a, b.copy(isLord = false, isHuman = true)),
            listOf(RetinuePersonLink(1, 2, 1))) }
        assertFailsWith<IllegalArgumentException> { HwihaRetinueLedger.assess(listOf(a, b),
            listOf(RetinuePersonLink(1, 1, 2)), listOf(RetinueNamedUnit("named:1", 1, 2))) }
    }
}
