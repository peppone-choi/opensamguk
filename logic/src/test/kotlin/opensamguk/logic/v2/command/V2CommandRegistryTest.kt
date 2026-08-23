package opensamguk.logic.v2.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.common.wire.CommandLifecycleResult

class V2CommandRegistryTest {
    @Test
    fun `registered commands expose the complete canonical schema`() {
        val schemas = V2CommandRegistry.schemas

        assertEquals(setOf("city.garrison.recruit", "city.resources.transport"), schemas.map { it.canonicalId }.toSet())
        assertEquals(setOf("v2GarrisonRecruit", "v2CityTransport"), schemas.flatMap { it.legacyAliases }.toSet())
        schemas.forEach { schema ->
            assertTrue(schema.canonicalId.isNotBlank())
            assertTrue(schema.legacyAliases.isNotEmpty())
            assertEquals(V2CommandLayer.STRATEGIC, schema.layer)
            assertEquals(V2CommandSourceRing.NONE, schema.sourceRing)
            assertEquals(V2CommandSubjectType.CITY, schema.subjectType)
            assertTrue(schema.target == V2CommandTarget.CITY || schema.target == V2CommandTarget.CITY_ROUTE)
            assertEquals(V2CommandActor.GENERAL, schema.actor)
            assertEquals(V2CommandAuthority.OWNED_CITY, schema.authority)
            assertEquals(V2AuthorityPolicyId.SUBJECT_OWNER, schema.authorityPolicyId)
            assertEquals(1, schema.authorityContextVersion)
            assertEquals(1, schema.payloadVersion)
            assertTrue(schema.adapter.isNotBlank())
            assertEquals(V2CommandParityStatus.ADAPTED, schema.parityStatus)
            assertEquals(V2IdempotencyPolicy.NOT_SUPPORTED, schema.idempotency)
            assertEquals(CommandLifecycleResult::class, schema.resultType)
            assertTrue(schema.expiry.seconds > 0)
            assertTrue(schema.replayEvent.isNotBlank())
        }
    }

    @Test
    fun `canonical id and frozen legacy alias resolve to the same typed schema`() {
        val canonical = V2CommandRegistry.resolve("city.garrison.recruit")
        val alias = V2CommandRegistry.resolve("v2GarrisonRecruit")

        assertEquals(canonical, alias)
        assertEquals(V2RouteRevisionPolicy.NOT_APPLICABLE, canonical?.routeRevision)
    }

    @Test
    fun `unknown canonical id fails closed`() {
        val result = V2CommandRegistry.precheck("personal.travel.teleport", emptyMap())

        val unknown = assertIs<V2CommandAvailability.Unknown>(result)
        assertEquals("UNKNOWN_COMMAND", unknown.code)
    }

    @Test
    fun `missing typed arguments require input`() {
        val result = V2CommandRegistry.precheck("city.garrison.recruit", mapOf("cityId" to 7))

        val needsInput = assertIs<V2CommandAvailability.NeedsInput>(result)
        assertEquals(listOf("amount"), needsInput.missing)
    }

    @Test
    fun `invalid typed arguments are blocked with the execution reason`() {
        val result = V2CommandRegistry.precheck(
            "city.resources.transport",
            mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to -1L, "rice" to 0L, "garrison" to 0),
        )

        val blocked = assertIs<V2CommandAvailability.Blocked>(result)
        assertEquals("TRANSPORT_AMOUNT_NEGATIVE", blocked.code)
        assertEquals("수송량은 음수일 수 없습니다.", blocked.reason)
    }

    @Test
    fun `valid arguments are parsed into a typed command`() {
        val result = V2CommandRegistry.precheck(
            "city.resources.transport",
            mapOf(
                "fromCityId" to 1,
                "toCityId" to 2,
                "gold" to 100L,
                "rice" to 200L,
                "garrison" to 300,
                "routeRevision" to 9L,
            ),
        )

        val available = assertIs<V2CommandAvailability.Available>(result)
        assertEquals(V2CityTransportArgs(1, 2, 100, 200, 300, 9), available.args)
        assertEquals(V2RouteRevisionPolicy.PASSTHROUGH, available.schema.routeRevision)
    }

    @Test
    fun `integer arguments outside the wire range are blocked instead of truncated`() {
        val result = V2CommandRegistry.precheck(
            "city.garrison.recruit",
            mapOf("cityId" to Int.MAX_VALUE.toLong() + 1, "amount" to 100),
        )

        val blocked = assertIs<V2CommandAvailability.Blocked>(result)
        assertEquals("INVALID_ARGUMENTS", blocked.code)
    }

    @Test
    fun `malformed supplied optional numeric argument is not treated as its default`() {
        val result = V2CommandRegistry.precheck(
            "city.resources.transport",
            mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to "oops", "rice" to 1),
        )

        assertEquals("INVALID_ARGUMENTS", assertIs<V2CommandAvailability.Blocked>(result).code)
    }

    @Test
    fun `double at the positive long boundary is rejected instead of saturated`() {
        val result = V2CommandRegistry.precheck(
            "city.resources.transport",
            mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to 9.223372036854776E18, "rice" to 1),
        )

        assertEquals("INVALID_ARGUMENTS", assertIs<V2CommandAvailability.Blocked>(result).code)
    }

    @Test
    fun `unknown arguments fail closed`() {
        val result = V2CommandRegistry.precheck(
            "city.garrison.recruit",
            mapOf("cityId" to 1, "amount" to 100, "surprise" to true),
        )

        assertEquals("INVALID_ARGUMENTS", assertIs<V2CommandAvailability.Blocked>(result).code)
    }
}
