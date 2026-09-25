package opensamguk.logic.economy

import kotlin.test.*

class HwihaCountyWarehouseTest {
    @Test fun `five resources conserve exact debit and reject partial payment or overflow`() {
        val stock = HwihaResources(20, 30, 40, 50, 60)
        val cost = HwihaResources(1, 2, 3, 4, 5)
        assertEquals(stock, assertNotNull(stock.debit(cost)).credit(cost))
        assertNull(stock.debit(HwihaResources(money=1, horses=61)))
        assertEquals(HwihaResources(), stock.debit(stock))
        assertFailsWith<ArithmeticException> { HwihaResources(money=Long.MAX_VALUE).credit(cost) }
        assertFailsWith<IllegalArgumentException> { HwihaResources(grain=-1) }
    }
    @Test fun `cold codec preserves long quantities and refuses malformed or missing authority`() {
        val state = HwihaCountyWarehouse(42, 9, HwihaResources(grain=Int.MAX_VALUE.toLong()+1))
        fun read(row: Any?) = HwihaCountyWarehouse.read(mapOf(HwihaCountyWarehouse.META_KEY to row), 42)
        assertEquals(state, read(state.toMetaValue()))
        assertNull(HwihaCountyWarehouse.read(emptyMap(), 42))
        val row = state.toMetaValue()
        for (bad in listOf(null, row+("countyId" to 43), row+("revision" to -1), row+("revision" to 1.5),
            row+("version" to "1"), row+("extra" to 1), row-"stock",
            row+("stock" to (state.stock.toMetaValue()+("grain" to "100"))))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
        assertFailsWith<ArithmeticException> { state.copy(revision=Long.MAX_VALUE).replace(state.stock) }
    }
}
