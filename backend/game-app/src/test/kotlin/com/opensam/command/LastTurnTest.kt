package com.opensam.command

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LastTurnTest {

    // ========== isSameCommand ==========

    @Test
    fun `isSameCommand returns true for same command and arg`() {
        val lt = LastTurn(command = "출병", arg = mapOf("target" to 5), term = 1)
        assertTrue(lt.isSameCommand("출병", mapOf("target" to 5)))
    }

    @Test
    fun `isSameCommand returns false for different command`() {
        val lt = LastTurn(command = "출병", arg = null, term = 1)
        assertFalse(lt.isSameCommand("이동", null))
    }

    @Test
    fun `isSameCommand returns false for different arg`() {
        val lt = LastTurn(command = "출병", arg = mapOf("target" to 5), term = 1)
        assertFalse(lt.isSameCommand("출병", mapOf("target" to 10)))
    }

    @Test
    fun `isSameCommand treats null and empty arg as equal`() {
        val lt = LastTurn(command = "훈련", arg = null, term = 1)
        assertTrue(lt.isSameCommand("훈련", emptyMap()))
    }

    // ========== addTermStack ==========

    @Test
    fun `addTermStack increments term for same command`() {
        val lt = LastTurn(command = "농지개간", arg = null, term = 2)
        val next = lt.addTermStack("농지개간", null, 5)
        assertEquals(3, next.term)
        assertEquals("농지개간", next.command)
    }

    @Test
    fun `addTermStack caps at maxTerm`() {
        val lt = LastTurn(command = "농지개간", arg = null, term = 4)
        val next = lt.addTermStack("농지개간", null, 5)
        assertEquals(5, next.term)
    }

    @Test
    fun `addTermStack does not exceed maxTerm`() {
        val lt = LastTurn(command = "농지개간", arg = null, term = 5)
        val next = lt.addTermStack("농지개간", null, 5)
        assertEquals(5, next.term)
    }

    @Test
    fun `addTermStack resets to 1 for different command`() {
        val lt = LastTurn(command = "농지개간", arg = null, term = 3)
        val next = lt.addTermStack("훈련", null, 5)
        assertEquals(1, next.term)
        assertEquals("훈련", next.command)
    }

    @Test
    fun `addTermStack starts at 1 from null term`() {
        val lt = LastTurn(command = "훈련", arg = null, term = null)
        val next = lt.addTermStack("훈련", null, 3)
        assertEquals(1, next.term)
    }

    @Test
    fun `addTermStack preserves arg`() {
        val arg = mapOf("target" to 5)
        val lt = LastTurn(command = "출병", arg = arg, term = 1)
        val next = lt.addTermStack("출병", arg, 3)
        assertEquals(arg, next.arg)
    }

    // ========== getTermStack ==========

    @Test
    fun `getTermStack returns term for matching command`() {
        val lt = LastTurn(command = "훈련", arg = null, term = 3)
        assertEquals(3, lt.getTermStack("훈련", null))
    }

    @Test
    fun `getTermStack returns 0 for different command`() {
        val lt = LastTurn(command = "훈련", arg = null, term = 3)
        assertEquals(0, lt.getTermStack("이동", null))
    }

    @Test
    fun `getTermStack returns 0 when term is null`() {
        val lt = LastTurn(command = "훈련", arg = null, term = null)
        assertEquals(0, lt.getTermStack("훈련", null))
    }

    // ========== fromMap / toMap roundtrip ==========

    @Test
    fun `toMap and fromMap roundtrip preserves data`() {
        val original = LastTurn(command = "출병", arg = mapOf("target" to 5), term = 2)
        val map = original.toMap()
        val restored = LastTurn.fromMap(map)
        assertEquals(original.command, restored.command)
        assertEquals(original.term, restored.term)
    }

    @Test
    fun `fromMap with null returns default`() {
        val lt = LastTurn.fromMap(null)
        assertEquals("휴식", lt.command)
        assertNull(lt.arg)
        assertNull(lt.term)
    }

    @Test
    fun `fromMap with empty map returns default command`() {
        val lt = LastTurn.fromMap(emptyMap())
        assertEquals("휴식", lt.command)
    }
}
