package opensamguk.logic.input

import kotlin.test.*

class HwihaPersonalInputTest {
    @Test fun `field personal arguments accept only the declared shape`() {
        for (id in listOf(HwihaPersonalInput.TRAVEL, HwihaPersonalInput.RECUPERATE)) {
            assertEquals(HwihaPersonalRequest(1, id), HwihaPersonalInput.parse(1, id, "{}"))
            assertNull(HwihaPersonalInput.parse(1, id, """{"targetId":2}"""))
            assertEquals("{}", HwihaPersonalInput.canonicalJson(HwihaPersonalRequest(1, id)))
        }
        val training = HwihaPersonalRequest(1, HwihaPersonalInput.SELF_TRAIN, HwihaTrainingStat.STRENGTH)
        assertEquals(training, HwihaPersonalInput.parse(1, HwihaPersonalInput.SELF_TRAIN,
            """{"stat":"strength"}"""))
        assertEquals("""{"stat":"strength"}""", HwihaPersonalInput.canonicalJson(training))
    }

    @Test fun `duplicate unknown or trailing input cannot choose a training stat`() {
        for (raw in listOf("{}", """{"stat":"STR"}""", """{"stat":1}""",
            """{"stat":"strength","stat":"charm"}""", """{"stat":"strength","actorId":1}""",
            """{"stat":"strength"} trailing""")) {
            assertNull(HwihaPersonalInput.parse(1, HwihaPersonalInput.SELF_TRAIN, raw), raw)
        }
        assertNull(HwihaPersonalInput.parse(0, HwihaPersonalInput.TRAVEL, "{}"))
    }
}
