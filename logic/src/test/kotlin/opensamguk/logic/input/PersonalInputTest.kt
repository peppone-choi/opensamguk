package opensamguk.logic.input

import kotlin.test.*

class PersonalInputTest {
    @Test fun `field personal arguments accept only the declared shape`() {
        for (id in listOf(PersonalInput.TRAVEL, PersonalInput.RECUPERATE)) {
            assertEquals(PersonalRequest(1, id), PersonalInput.parse(1, id, "{}"))
            assertNull(PersonalInput.parse(1, id, """{"targetId":2}"""))
            assertEquals("{}", PersonalInput.canonicalJson(PersonalRequest(1, id)))
        }
        val training = PersonalRequest(1, PersonalInput.SELF_TRAIN, TrainingStat.STRENGTH)
        assertEquals(training, PersonalInput.parse(1, PersonalInput.SELF_TRAIN,
            """{"stat":"strength"}"""))
        assertEquals("""{"stat":"strength"}""", PersonalInput.canonicalJson(training))
    }

    @Test fun `duplicate unknown or trailing input cannot choose a training stat`() {
        for (raw in listOf("{}", """{"stat":"STR"}""", """{"stat":1}""",
            """{"stat":"strength","stat":"charm"}""", """{"stat":"strength","actorId":1}""",
            """{"stat":"strength"} trailing""")) {
            assertNull(PersonalInput.parse(1, PersonalInput.SELF_TRAIN, raw), raw)
        }
        assertNull(PersonalInput.parse(0, PersonalInput.TRAVEL, "{}"))
    }
}
