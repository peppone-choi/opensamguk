package opensamguk.logic.economy

import kotlin.test.*

class CountyWarehouseTest {
    @Test fun `five resources conserve exact debit and reject partial payment or overflow`() {
        val stock = Resources(20, 30, 40, 50, 60)
        val cost = Resources(1, 2, 3, 4, 5)
        assertEquals(stock, assertNotNull(stock.debit(cost)).credit(cost))
        assertNull(stock.debit(Resources(money=1, horses=61)))
        assertEquals(Resources(), stock.debit(stock))
        assertFailsWith<ArithmeticException> { Resources(money=Long.MAX_VALUE).credit(cost) }
        assertFailsWith<IllegalArgumentException> { Resources(grain=-1) }
    }
    @Test fun `cold codec preserves long quantities and refuses malformed or missing authority`() {
        val state = CountyWarehouse(42, 9, Resources(grain=Int.MAX_VALUE.toLong()+1))
        fun read(row: Any?) = CountyWarehouse.read(mapOf(CountyWarehouse.META_KEY to row), 42)
        assertEquals(state, read(state.toMetaValue()))
        assertNull(CountyWarehouse.read(emptyMap(), 42))
        val row = state.toMetaValue()
        for (bad in listOf(null, row+("countyId" to 43), row+("revision" to -1), row+("revision" to 1.5),
            row+("version" to "1"), row+("extra" to 1), row-"stock",
            row+("stock" to (state.stock.toMetaValue()+("grain" to "100"))))) {
            assertFailsWith<IllegalArgumentException> { read(bad) }
        }
        assertFailsWith<ArithmeticException> { state.copy(revision=Long.MAX_VALUE).replace(state.stock) }
    }
}
