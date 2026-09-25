package opensamguk.logic.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.common.wire.CommandLifecycleResult

class CommandSchemaCatalogTest {
    @Test
    fun `transport accepts additive topology pins and rejects malformed pins`() {
        val base = mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to 1)
        val available = assertIs<CommandAvailability.Available>(CommandSchemaCatalog.precheck("v2CityTransport", base + mapOf(
            "topologyRevision" to "han-v3:abc", "routePathHash" to "path:123",
        )))
        val args = assertIs<CityTransportArgs>(available.args)
        assertEquals("han-v3:abc", args.topologyRevision)
        assertEquals("path:123", args.routePathHash)
        listOf("", " ", 12, null).forEach { invalid ->
            listOf("topologyRevision", "routePathHash").forEach { key ->
                assertTrue(CommandSchemaCatalog.precheck("v2CityTransport", base + (key to invalid)) is CommandAvailability.Blocked)
            }
        }
    }

    @Test
    fun `registered commands expose the complete canonical schema`() {
        val schemas = CommandSchemaCatalog.schemas

        assertEquals(setOf("city.garrison.recruit", "city.resources.transport"), schemas.map { it.canonicalId }.toSet())
        assertEquals(setOf("v2GarrisonRecruit", "v2CityTransport"), schemas.flatMap { it.legacyAliases }.toSet())
        schemas.forEach { schema ->
            assertTrue(schema.canonicalId.isNotBlank())
            assertTrue(schema.legacyAliases.isNotEmpty())
            assertEquals(CommandLayer.STRATEGIC, schema.layer)
            assertEquals(CommandSourceRing.NONE, schema.sourceRing)
            assertEquals(CommandSubjectType.CITY, schema.subjectType)
            assertTrue(schema.target == CommandTarget.CITY || schema.target == CommandTarget.CITY_ROUTE)
            assertEquals(CommandActor.GENERAL, schema.actor)
            assertEquals(CommandAuthority.OWNED_CITY, schema.authority)
            assertEquals(AuthorityPolicyId.SUBJECT_OWNER, schema.authorityPolicyId)
            assertEquals(1, schema.authorityContextVersion)
            assertEquals(1, schema.payloadVersion)
            assertTrue(schema.adapter.isNotBlank())
            assertEquals(CommandParityStatus.ADAPTED, schema.parityStatus)
            assertEquals(IdempotencyPolicy.NOT_SUPPORTED, schema.idempotency)
            assertEquals(CommandLifecycleResult::class, schema.resultType)
            assertTrue(schema.expiry.seconds > 0)
            assertTrue(schema.replayEvent.isNotBlank())
        }
    }

    @Test
    fun `canonical id and frozen legacy alias resolve to the same typed schema`() {
        val canonical = CommandSchemaCatalog.resolve("city.garrison.recruit")
        val alias = CommandSchemaCatalog.resolve("v2GarrisonRecruit")

        assertEquals(canonical, alias)
        assertEquals(RouteRevisionPolicy.NOT_APPLICABLE, canonical?.routeRevision)
    }

    @Test
    fun `unknown canonical id fails closed`() {
        val result = CommandSchemaCatalog.precheck("personal.travel.teleport", emptyMap())

        val unknown = assertIs<CommandAvailability.Unknown>(result)
        assertEquals("UNKNOWN_COMMAND", unknown.code)
    }

    @Test
    fun `missing typed arguments require input`() {
        val result = CommandSchemaCatalog.precheck("city.garrison.recruit", mapOf("cityId" to 7))

        val needsInput = assertIs<CommandAvailability.NeedsInput>(result)
        assertEquals(listOf("amount"), needsInput.missing)
    }

    @Test
    fun `invalid typed arguments are blocked with the execution reason`() {
        val result = CommandSchemaCatalog.precheck(
            "city.resources.transport",
            mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to -1L, "rice" to 0L, "garrison" to 0),
        )

        val blocked = assertIs<CommandAvailability.Blocked>(result)
        assertEquals("TRANSPORT_AMOUNT_NEGATIVE", blocked.code)
        assertEquals("수송량은 음수일 수 없습니다.", blocked.reason)
    }

    @Test
    fun `valid arguments are parsed into a typed command`() {
        val result = CommandSchemaCatalog.precheck(
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

        val available = assertIs<CommandAvailability.Available>(result)
        assertEquals(CityTransportArgs(1, 2, 100, 200, 300, 9), available.args)
        assertEquals(RouteRevisionPolicy.PASSTHROUGH, available.schema.routeRevision)
    }

    @Test
    fun `integer arguments outside the wire range are blocked instead of truncated`() {
        val result = CommandSchemaCatalog.precheck(
            "city.garrison.recruit",
            mapOf("cityId" to Int.MAX_VALUE.toLong() + 1, "amount" to 100),
        )

        val blocked = assertIs<CommandAvailability.Blocked>(result)
        assertEquals("INVALID_ARGUMENTS", blocked.code)
    }

    @Test
    fun `malformed supplied optional numeric argument is not treated as its default`() {
        val result = CommandSchemaCatalog.precheck(
            "city.resources.transport",
            mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to "oops", "rice" to 1),
        )

        assertEquals("INVALID_ARGUMENTS", assertIs<CommandAvailability.Blocked>(result).code)
    }

    @Test
    fun `double at the positive long boundary is rejected instead of saturated`() {
        val result = CommandSchemaCatalog.precheck(
            "city.resources.transport",
            mapOf("fromCityId" to 1, "toCityId" to 2, "gold" to 9.223372036854776E18, "rice" to 1),
        )

        assertEquals("INVALID_ARGUMENTS", assertIs<CommandAvailability.Blocked>(result).code)
    }

    @Test
    fun `unknown arguments fail closed`() {
        val result = CommandSchemaCatalog.precheck(
            "city.garrison.recruit",
            mapOf("cityId" to 1, "amount" to 100, "surprise" to true),
        )

        assertEquals("INVALID_ARGUMENTS", assertIs<CommandAvailability.Blocked>(result).code)
    }
}
