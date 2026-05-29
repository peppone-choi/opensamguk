package opensamguk.common.wire

import kotlinx.serialization.json.Json

/**
 * Single shared JSON codec for the turn-daemon / realtime wire contract.
 * `classDiscriminator = "type"` mirrors the TS discriminated unions; the
 * remaining flags reproduce the JS `JSON.stringify` envelope behaviour
 * (omit defaults, omit nulls, ignore unknown forward-compat keys).
 */
val WireJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
