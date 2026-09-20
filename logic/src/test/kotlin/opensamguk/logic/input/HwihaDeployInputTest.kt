package opensamguk.logic.input

import kotlin.test.*

class HwihaDeployInputTest {
    @Test fun `personal deployment binds actor and canonicalizes card order`() {
        val parsed = assertNotNull(HwihaDeployInput.parse(7, """{"bugokIds":[8,2],"destinationProvinceId":"p-9"}"""))
        assertEquals(DeploymentRequest(7, null, listOf(2,8)), parsed.deploymentRequest())
        assertEquals("p-9", parsed.destination.id)
        assertEquals("""{"bugokIds":[2,8],"destinationProvinceId":"p-9"}""", HwihaDeployInput.canonicalJson(parsed))
        assertEquals(parsed, HwihaDeployInput.parse(7, HwihaDeployInput.canonicalJson(parsed)))
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
        invalid.forEach { assertNull(HwihaDeployInput.parse(7, it), it) }
        assertNull(HwihaDeployInput.parse(0, "{}"))
        assertNull(HwihaDeployInput.parse(7, null))
        assertNull(HwihaDeployInput.parse(7, """{"bugokIds":[1],"destinationProvinceId":"p"} garbage"""))
    }
    @Test fun `existing flat contracts do not acquire array arguments`() {
        assertFailsWith<IllegalArgumentException> { HwihaFlatArguments("""{"targetId":[1]}""").read() }
        assertNull(HwihaEnlistmentInput.parse(7, """{"mode":"NATION","targetId":[1]}"""))
    }
}
