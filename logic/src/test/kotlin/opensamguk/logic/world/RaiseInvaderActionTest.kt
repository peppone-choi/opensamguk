package opensamguk.logic.world

import kotlinx.serialization.json.Json
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RaiseInvaderActionTest {
    @Test
    fun `factory preserves the four PHP numeric arguments`() {
        var received: RaiseInvaderSpec? = null
        val context = object : RaiseInvaderContext {
            override val env: Map<String, Any?> = emptyMap()
            override fun raiseInvader(spec: RaiseInvaderSpec): Int {
                received = spec
                return 3
            }
        }
        val args = Json.parseToJsonElement("""[-2,-1.2,15000,-0.5]""")
            .let { it as kotlinx.serialization.json.JsonArray }
        val factory = RaiseInvaderAction.register(EventActionFactory())

        factory.create(RawAction(RaiseInvaderAction.NAME, args)).run(context)

        assertEquals(RaiseInvaderSpec(-2.0, -1.2, 15_000.0, -0.5), received)
    }

    @Test
    fun `factory rejects non numeric arguments`() {
        val factory = RaiseInvaderAction.register(EventActionFactory())
        assertFailsWith<IllegalArgumentException> {
            factory.create(
                RawAction(
                    RaiseInvaderAction.NAME,
                    listOf(Json.parseToJsonElement(""""bad"""")),
                ),
            )
        }
    }
}
