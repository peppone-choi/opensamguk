package opensamguk.engine.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.AuctionBidRow
import opensamguk.infra.persistence.AuctionBidRowMapper
import opensamguk.infra.persistence.AuctionRow
import opensamguk.infra.persistence.AuctionRowMapper
import opensamguk.infra.persistence.GameKvRow
import opensamguk.infra.persistence.GameKvRowMapper
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.persistence.NgBettingRow
import opensamguk.infra.persistence.NgBettingRowMapper
import opensamguk.logic.auction.ObfuscatedNamePool
import opensamguk.logic.message.Message
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

/**
 * P6 T0.9 — the daemon rehydrate contract.
 *
 * At daemon start, load survivor state from the DB into memory:
 *  - `inheritance_*` KV namespaces from `game_kv` (T0.8 inheritance store).
 *  - Active `ng_auction` rows (unfinished) + their `ng_auction_bid` rows (T0.7).
 *  - Active `ng_betting` rows (if the table exists — V7 migration created it).
 *  - Active `message` rows where `valid_until` > now (T0.5), reconstructed polymorphically via
 *    [Message.buildFromArray].
 *  - `obfuscatedNamePool` from `game_kv` (no re-shuffle if present).
 *
 * The service returns a [RehydratedState] snapshot; the caller (daemon bootstrap) wires the
 * pieces into the [InMemoryTurnWorld] / handler / allocator state.
 *
 * SUPERSEDED — NOT WIRED, AND MUST NOT BE (OPENSAM-149 D1, 2026-07-26).
 * The daemon settled on a different survivor design and every item above is already covered:
 *  - auction / bid / betting / message pools — read on demand from the repositories injected into
 *    `config/DaemonLoopConfig.kt` `turnRunService`; `MessageRepository`'s
 *    `findByWorldIdAndMailboxAndValidUntilAfter` is the same `valid_until > now` predicate as
 *    [loadActiveMessages], and polymorphic reconstruction happens at the handler
 *    (`MessageHandler`), not at boot.
 *  - message / auction id allocators — seeded from the DB in the same bean
 *    (`messageRepository.findMaxId()`, max persisted auction id).
 *  - `inheritance_*` KV — loaded by `boot/WorldSnapshotLoader.kt` into the world meta.
 *  - `obfuscatedNamePool` — regenerated deterministically from the hidden seed at the auction
 *    handlers, so there is nothing to preserve.
 * Wiring this service would make it a SECOND source of truth for rows the repositories already
 * serve — the exact two-dirty-truths failure mode CLAUDE.md forbids. Kept as the reference
 * implementation of the P6 contract; `boot/RehydrateWiringTest.kt` guards the real seam instead.
 * Note before any revival: [loadObfuscatedNamePool] passes the raw `hiddenSeed` to
 * `ObfuscatedNamePool.buildPool`, while the live handlers pass a serialized seed — the two pools
 * would not match (D4).
 */
class RehydrateService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val hiddenSeed: String,
    private val worldId: WorldId,
) {

    data class RehydratedState(
        /** The obfuscated name pool (null only on a catastrophic parse failure). */
        val obfuscatedNamePool: List<String>?,
        /** T0.8 survivor KV rows keyed by `inheritance_{ownerID}`. */
        val inheritanceKvRows: List<GameKvRow>,
        /** T0.7 unfinished auctions. */
        val activeAuctions: List<AuctionRow>,
        /** T0.7 bids for the active auctions. */
        val activeAuctionBids: List<AuctionBidRow>,
        /** Active betting rows (empty if `ng_betting` does not exist). */
        val activeBettings: List<NgBettingRow>,
        /** T0.5 active messages reconstructed via [Message.buildFromArray]. */
        val activeMessages: List<Message>,
    )

    /**
     * Load all survivor state from the DB.
     *
     * @param now the wall-clock instant used for the `message.valid_until` predicate
     *            (quarantined — research §11).
     */
    fun rehydrate(now: Instant = Instant.now()): RehydratedState {
        val pool = loadObfuscatedNamePool()
        val inheritanceRows = loadInheritanceKv()
        val auctions = loadActiveAuctions()
        val auctionIds = auctions.mapNotNull { it.id }
        val bids = if (auctionIds.isNotEmpty()) loadAuctionBids(auctionIds) else emptyList()
        val bettings = loadBettings()
        val messages = loadActiveMessages(now)
        return RehydratedState(pool, inheritanceRows, auctions, bids, bettings, messages)
    }

    // --- obfuscatedNamePool ------------------------------------------------------------------------

    private fun loadObfuscatedNamePool(): List<String>? {
        val rows = jdbc.queryForList(
            """SELECT value FROM game_kv WHERE "table" = 'game_env' AND namespace = 'game_env' AND key = 'obfuscatedNamePool'""",
            MapSqlParameterSource(),
        )
        return if (rows.isEmpty()) {
            val pool = ObfuscatedNamePool.buildPool(hiddenSeed)
            // Bootstrap write: direct JDBC UPSERT (there is no active ChangeRecorder at daemon startup).
            jdbc.update(
                """
                INSERT INTO game_kv (world_id, "table", namespace, key, value)
                VALUES (:worldId, 'game_env', 'game_env', 'obfuscatedNamePool', :value::jsonb)
                ON CONFLICT (world_id, "table", namespace, key)
                    WHERE "table" <> 'inheritance' AND world_id IS NOT NULL
                DO UPDATE SET value = EXCLUDED.value
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("worldId", worldId.value)
                    .addValue("value", MetaJson.encode(pool)),
            )
            pool
        } else {
            val json = rows.first()["value"] as? String ?: return null
            parseStringList(json)
        }
    }

    /** Parse a JSON array of strings (the PHP `json_encode` of the shuffled name pool). */
    private fun parseStringList(json: String): List<String>? {
        return try {
            Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            null
        }
    }

    // --- inheritance KV ----------------------------------------------------------------------------

    private fun loadInheritanceKv(): List<GameKvRow> {
        val rows = jdbc.queryForList(
            """SELECT "table", namespace, key, value::text AS value FROM game_kv WHERE namespace LIKE 'inheritance_%'""",
            MapSqlParameterSource(),
        )
        return rows.map { GameKvRowMapper.fromRow(it) }
    }

    // --- auctions ----------------------------------------------------------------------------------

    private fun loadActiveAuctions(): List<AuctionRow> {
        val rows = jdbc.queryForList(
            "SELECT * FROM ng_auction WHERE finished = false",
            MapSqlParameterSource(),
        )
        return rows.map { AuctionRowMapper.fromRow(it) }
    }

    private fun loadAuctionBids(auctionIds: List<Int>): List<AuctionBidRow> {
        if (auctionIds.isEmpty()) return emptyList()
        val rows = jdbc.queryForList(
            "SELECT * FROM ng_auction_bid WHERE auction_id IN (:ids)",
            MapSqlParameterSource().addValue("ids", auctionIds),
        )
        return rows.map { AuctionBidRowMapper.fromRow(it) }
    }

    // --- betting -----------------------------------------------------------------------------------

    private fun loadBettings(): List<NgBettingRow> {
        val tableExists = jdbc.queryForObject(
            """
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema = 'public' AND table_name = 'ng_betting'
            """.trimIndent(),
            MapSqlParameterSource(),
            Int::class.java,
        ) ?: 0
        if (tableExists == 0) return emptyList()

        return try {
            val rows = jdbc.queryForList("SELECT * FROM ng_betting", MapSqlParameterSource())
            rows.map { NgBettingRowMapper.fromRow(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- messages ----------------------------------------------------------------------------------

    private fun loadActiveMessages(now: Instant): List<Message> {
        val rows = jdbc.queryForList(
            "SELECT * FROM message WHERE valid_until > :now",
            MapSqlParameterSource().addValue("now", java.sql.Timestamp.from(now)),
        )
        return rows.mapNotNull { row ->
            val bodyJson = row["message"] as? String ?: return@mapNotNull null
            val body = try {
                MetaJson.decode(bodyJson)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            Message.buildFromArray(row, body)
        }
    }
}
