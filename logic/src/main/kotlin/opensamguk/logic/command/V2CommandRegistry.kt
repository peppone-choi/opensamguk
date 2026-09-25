package opensamguk.logic.v2.command

import java.math.BigDecimal
import java.time.Duration
import opensamguk.common.wire.CommandLifecycleResult

object V2CommandRegistry {
    val garrisonRecruitSchema = V2CommandSchema(
        canonicalId = "city.garrison.recruit",
        legacyAliases = setOf("v2GarrisonRecruit"),
        layer = V2CommandLayer.STRATEGIC,
        sourceRing = V2CommandSourceRing.NONE,
        subjectType = V2CommandSubjectType.CITY,
        target = V2CommandTarget.CITY,
        actor = V2CommandActor.GENERAL,
        authority = V2CommandAuthority.OWNED_CITY,
        authorityPolicyId = V2AuthorityPolicyId.SUBJECT_OWNER,
        authorityContextVersion = 1,
        payloadVersion = 1,
        adapter = "v2-city-garrison-recruit",
        parityStatus = V2CommandParityStatus.ADAPTED,
        argsType = V2GarrisonRecruitArgs::class,
        resultType = CommandLifecycleResult::class,
        idempotency = V2IdempotencyPolicy.NOT_SUPPORTED,
        expiry = Duration.ofHours(1),
        replayEvent = "CityGarrisonRecruited",
        routeRevision = V2RouteRevisionPolicy.NOT_APPLICABLE,
        parse = ::parseRecruit,
    )

    val cityTransportSchema = V2CommandSchema(
        canonicalId = "city.resources.transport",
        legacyAliases = setOf("v2CityTransport"),
        layer = V2CommandLayer.STRATEGIC,
        sourceRing = V2CommandSourceRing.NONE,
        subjectType = V2CommandSubjectType.CITY,
        target = V2CommandTarget.CITY_ROUTE,
        actor = V2CommandActor.GENERAL,
        authority = V2CommandAuthority.OWNED_CITY,
        authorityPolicyId = V2AuthorityPolicyId.SUBJECT_OWNER,
        authorityContextVersion = 1,
        payloadVersion = 1,
        adapter = "v2-city-transport",
        parityStatus = V2CommandParityStatus.ADAPTED,
        argsType = V2CityTransportArgs::class,
        resultType = CommandLifecycleResult::class,
        idempotency = V2IdempotencyPolicy.NOT_SUPPORTED,
        expiry = Duration.ofHours(1),
        replayEvent = "CityResourcesTransported",
        routeRevision = V2RouteRevisionPolicy.PASSTHROUGH,
        parse = ::parseTransport,
    )

    val schemas: List<V2CommandSchema> = listOf(garrisonRecruitSchema, cityTransportSchema)

    private val byId: Map<String, V2CommandSchema> = buildMap {
        schemas.forEach { schema ->
            check(put(schema.canonicalId, schema) == null) { "duplicate v2 canonical command: ${schema.canonicalId}" }
            schema.legacyAliases.forEach { alias ->
                check(put(alias, schema) == null) { "duplicate v2 command alias: $alias" }
            }
        }
    }

    fun resolve(id: String): V2CommandSchema? = byId[id]

    fun precheck(id: String, rawArgs: Map<String, Any?>): V2CommandAvailability {
        val schema = resolve(id) ?: return V2CommandAvailability.Unknown()
        return when (val parsed = schema.parse(rawArgs)) {
            is V2CommandAvailability.Available -> parsed.copy(schema = schema)
            is V2CommandAvailability.NeedsInput -> parsed
            is V2CommandAvailability.Blocked -> parsed
            is V2CommandAvailability.Unknown -> error("registered v2 parser returned UNKNOWN: ${schema.canonicalId}")
        }
    }

    private fun parseRecruit(args: Map<String, Any?>): V2CommandAvailability {
        if (args.keys.any { it !in RECRUIT_ARGUMENTS }) return invalidArgs()
        val missing = required(args, "cityId", "amount")
        if (missing.isNotEmpty()) return V2CommandAvailability.NeedsInput(missing)
        val cityId = args.int("cityId") ?: return invalidArgs()
        val amount = args.int("amount") ?: return invalidArgs()
        if (cityId <= 0) return V2CommandAvailability.Blocked("CITY_ID_INVALID", "도시를 찾을 수 없습니다.")
        if (amount < 100) {
            return V2CommandAvailability.Blocked("RECRUIT_AMOUNT_TOO_SMALL", "최소 100명부터 보충할 수 있습니다.")
        }
        return V2CommandAvailability.Available(garrisonRecruitSchema, V2GarrisonRecruitArgs(cityId, amount))
    }

    private fun parseTransport(args: Map<String, Any?>): V2CommandAvailability {
        if (args.keys.any { it !in TRANSPORT_ARGUMENTS }) return invalidArgs()
        val missing = required(args, "fromCityId", "toCityId")
        if (missing.isNotEmpty()) return V2CommandAvailability.NeedsInput(missing)
        val fromCityId = args.int("fromCityId") ?: return invalidArgs()
        val toCityId = args.int("toCityId") ?: return invalidArgs()
        val gold = args.optionalLong("gold") ?: return invalidArgs()
        val rice = args.optionalLong("rice") ?: return invalidArgs()
        val garrison = args.optionalInt("garrison") ?: return invalidArgs()
        val routeRevision = (args.optionalNullableLong("routeRevision") ?: return invalidArgs()).value
        val topologyRevision = args["topologyRevision"] as? String
        val routePathHash = args["routePathHash"] as? String
        if (("topologyRevision" in args && topologyRevision.isNullOrBlank()) ||
            ("routePathHash" in args && routePathHash.isNullOrBlank())
        ) return invalidArgs()
        if (fromCityId <= 0 || toCityId <= 0) return V2CommandAvailability.Blocked("CITY_ID_INVALID", "도시를 찾을 수 없습니다.")
        if (gold < 0 || rice < 0 || garrison < 0) {
            return V2CommandAvailability.Blocked("TRANSPORT_AMOUNT_NEGATIVE", "수송량은 음수일 수 없습니다.")
        }
        if (gold == 0L && rice == 0L && garrison == 0) {
            return V2CommandAvailability.Blocked("TRANSPORT_AMOUNT_EMPTY", "수송할 자원을 지정해야 합니다.")
        }
        if (routeRevision != null && routeRevision < 0) return invalidArgs()
        return V2CommandAvailability.Available(
            cityTransportSchema,
            V2CityTransportArgs(fromCityId, toCityId, gold, rice, garrison, routeRevision, topologyRevision, routePathHash),
        )
    }

    private fun required(args: Map<String, Any?>, vararg names: String): List<String> =
        names.filter { it !in args || args[it] == null }

    private fun Map<String, Any?>.int(name: String): Int? {
        val value = this[name] as? Number ?: return null
        val long = value.toLong()
        if (long !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        if (value.toDouble() != long.toDouble()) return null
        return long.toInt()
    }

    private fun Map<String, Any?>.long(name: String): Long? {
        val value = this[name] as? Number ?: return null
        return when (value) {
            is Byte, is Short, is Int, is Long -> value.toLong()
            is Float, is Double -> runCatching {
                BigDecimal.valueOf(value.toDouble()).longValueExact()
            }.getOrNull()
            else -> null
        }
    }

    private fun Map<String, Any?>.optionalInt(name: String): Int? =
        if (name !in this) 0 else int(name)

    private fun Map<String, Any?>.optionalLong(name: String): Long? =
        if (name !in this) 0L else long(name)

    private fun Map<String, Any?>.optionalNullableLong(name: String): ParsedNullableLong? =
        if (name !in this) ParsedNullableLong(null) else long(name)?.let(::ParsedNullableLong)

    private data class ParsedNullableLong(val value: Long?)

    private fun invalidArgs(): V2CommandAvailability.Blocked =
        V2CommandAvailability.Blocked("INVALID_ARGUMENTS", "명령 인자 형식이 올바르지 않습니다.")

    private val RECRUIT_ARGUMENTS = setOf("cityId", "amount")
    private val TRANSPORT_ARGUMENTS = setOf(
        "fromCityId", "toCityId", "gold", "rice", "garrison", "routeRevision", "topologyRevision", "routePathHash",
    )
}
