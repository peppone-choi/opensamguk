package opensamguk.logic.input

import kotlin.test.*

class DeployInputTest {
    @Test fun `personal deployment binds actor and canonicalizes card order`() {
        val parsed = assertNotNull(DeployInputs.parse(7, """{"bugokIds":[8,2],"destinationProvinceId":"p-9"}"""))
        assertEquals(DeploymentRequest(7, null, listOf(2,8)), parsed.deploymentRequest())
        assertEquals("p-9", parsed.destination.id)
        assertEquals("""{"bugokIds":[2,8],"destinationProvinceId":"p-9"}""", DeployInputs.canonicalJson(parsed))
        assertEquals(parsed, DeployInputs.parse(7, DeployInputs.canonicalJson(parsed)))
    }
    @Test fun `duplicate escaped keys identity overrides and malformed arrays are rejected`() {
        val invalid = listOf(
            """{"bugokIds":[1],"bugokIds":[2],"destinationProvinceId":"p"}""",
            """{"bugokIds":[1],"bugok\u0049ds":[2],"destinationProvinceId":"p"}""",
            """{"bugokIds":[1],"destinationProvinceId":"p","actorId":8}""",
            """{"bugokIds":[1],"destinationProvinceId":"p","commanderRetainerId":8}""",
            """{"bugokIds":[1],"destinationProvinceId":"p","orderId":"x"}""",
            """{"bugokIds":[1],"destinationProvinceId":3}""",
            """{"bugokIds":[1],"destinationProvinceId":" "}""",
        ) + listOf("[]", "[1,1]", "[0]", "[-1]", "[1.0]", "[1e2]", "[2147483648]", "[\"1\"]", "[{}]", "[[1]]", "[true]", "[null]", "[1,]", "[01]")
            .map { """{"bugokIds":$it,"destinationProvinceId":"p"}""" }
        invalid.forEach { assertNull(DeployInputs.parse(7, it), it) }
        assertNull(DeployInputs.parse(0, "{}"))
        assertNull(DeployInputs.parse(7, null))
        assertNull(DeployInputs.parse(7, """{"bugokIds":[1],"destinationProvinceId":"p"} garbage"""))
    }
    @Test fun `existing flat contracts do not acquire array arguments`() {
        assertFailsWith<IllegalArgumentException> { FlatArguments("""{"targetId":[1]}""").read() }
        assertNull(EnlistmentInput.parse(7, """{"mode":"NATION","targetId":[1]}"""))
    }
}
