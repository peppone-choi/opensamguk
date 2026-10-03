package opensamguk.infra.battle.realtime

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.security.MessageDigest
import javax.sql.DataSource
import opensamguk.common.world.WorldId
import opensamguk.logic.battle.realtime.TacticalRules
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Dedicated battle_* writer. It deliberately does not depend on the campaign flush path. */
class JdbcBattleSessionStore(jdbc: NamedParameterJdbcTemplate, dataSource: DataSource) : BattleSessionStore {
    private val db = jdbc
    private val tx = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun create(ticket: FrozenBattleTicket): Boolean = tx.execute {
        val params = key(ticket.worldId, ticket.battleId)
            .addValue("payload", ticket.payloadJson)
            .addValue("payload_sha", ticket.payloadSha256)
            .addValue("rule_sha", ticket.ruleSha256)
            .addValue("catalog_sha", ticket.catalogSha256)
            .addValue("terrain_sha", ticket.terrainSha256)
            .addValue("seed", ticket.seed)
            .addValue("lock_generation", ticket.lockGeneration)
            .addValue("lock_set_revision", ticket.lockSetRevision)
            .addValue("join_deadline", Timestamp.from(ticket.joinDeadlineAt))
            .addValue("deadline", Timestamp.from(ticket.deadlineAt))
        val inserted = db.update("""
            INSERT INTO battle_ticket (world_id, battle_id, payload_text, payload_sha256, rule_sha256,
                catalog_sha256, terrain_sha256, seed, lock_generation, lock_set_revision, join_deadline_at, deadline_at)
            VALUES (:world_id, :battle_id, :payload, :payload_sha, :rule_sha, :catalog_sha,
                :terrain_sha, :seed, :lock_generation, :lock_set_revision, :join_deadline, :deadline)
            ON CONFLICT (world_id, battle_id) DO NOTHING
        """.trimIndent(), params)
        if (inserted == 0) {
            val existing = requireNotNull(ticket(ticket.worldId, ticket.battleId))
            check(existing.payloadSha256 == ticket.payloadSha256 &&
                existing.ruleSha256 == ticket.ruleSha256 &&
                existing.catalogSha256 == ticket.catalogSha256 &&
                existing.terrainSha256 == ticket.terrainSha256 &&
                existing.seed == ticket.seed &&
                existing.lockGeneration == ticket.lockGeneration &&
                existing.lockSetRevision == ticket.lockSetRevision &&
                existing.joinDeadlineAt.toEpochMilli() == ticket.joinDeadlineAt.toEpochMilli() &&
                existing.deadlineAt.toEpochMilli() == ticket.deadlineAt.toEpochMilli() &&
                existing.participants == ticket.participants) { "battle handoff identity conflict" }
            return@execute false
        }
        db.update("""
            INSERT INTO battle_session (world_id, battle_id, phase, session_epoch, current_tick,
                latest_event_seq, latest_snapshot_seq, join_deadline_at, deadline_at)
            VALUES (:world_id, :battle_id, 'READY', 0, 0, 0, 0, :join_deadline, :deadline)
        """.trimIndent(), params)
        ticket.participants.forEach { participant ->
            db.update("""
                INSERT INTO battle_participant (world_id, battle_id, participant_id, account_id,
                    general_id, side, authority_revision)
                VALUES (:world_id, :battle_id, :participant_id, :account_id, :general_id,
                    :side, :authority_revision)
            """.trimIndent(), key(ticket.worldId, ticket.battleId)
                .addValue("participant_id", participant.participantId)
                .addValue("account_id", participant.accountId)
                .addValue("general_id", participant.generalId)
                .addValue("side", participant.side)
                .addValue("authority_revision", participant.authorityRevision))
        }
        true
    } ?: error("battle ticket transaction returned no result")

    override fun ticket(worldId: WorldId, battleId: String): FrozenBattleTicket? {
        val row = db.query("""
            SELECT payload_text, payload_sha256, rule_sha256, catalog_sha256,
                   terrain_sha256, seed, lock_generation, lock_set_revision, join_deadline_at, deadline_at
              FROM battle_ticket WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), key(worldId, battleId)) { rs, _ ->
            TicketColumns(rs.getString("payload_text"), rs.getString("payload_sha256"),
                rs.getString("rule_sha256"), rs.getString("catalog_sha256"),
                rs.getString("terrain_sha256"), rs.getLong("seed"),
                rs.getLong("lock_generation"), rs.getLong("lock_set_revision"),
                rs.getTimestamp("join_deadline_at").toInstant(), rs.getTimestamp("deadline_at").toInstant())
        }.firstOrNull() ?: return null
        return readTicket(worldId, battleId, row)
    }

    private data class TicketColumns(val payload: String, val payloadSha: String, val ruleSha: String,
        val catalogSha: String, val terrainSha: String, val seed: Long, val lockGeneration: Long,
        val lockSetRevision: Long, val joinDeadlineAt: Instant, val deadlineAt: Instant)

    private fun readTicket(worldId: WorldId, battleId: String, row: TicketColumns): FrozenBattleTicket {
        val participants = db.query("""
            SELECT participant_id, account_id, general_id, side, authority_revision
              FROM battle_participant WHERE world_id = :world_id AND battle_id = :battle_id
             ORDER BY participant_id
        """.trimIndent(), key(worldId, battleId)) { rs, _ ->
            FrozenBattleParticipant(rs.getInt("participant_id"), rs.getInt("account_id"),
                rs.getInt("general_id"), rs.getString("side"), rs.getLong("authority_revision"))
        }
        return FrozenBattleTicket(worldId, battleId, row.payload, row.payloadSha, row.ruleSha,
            row.catalogSha, row.terrainSha, row.seed, row.lockGeneration, row.lockSetRevision,
            row.joinDeadlineAt, row.deadlineAt,
            participants)
    }

    override fun head(worldId: WorldId, battleId: String): BattleSessionHead? = db.query("""
        SELECT phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
               lease_owner, lease_until, join_deadline_at, deadline_at
          FROM battle_session WHERE world_id = :world_id AND battle_id = :battle_id
    """.trimIndent(), key(worldId, battleId)) { rs, _ -> readHead(worldId, battleId, rs) }.firstOrNull()

    override fun claimEpoch(worldId: WorldId, battleId: String, owner: String,
                            leaseMillis: Long): BattleSessionHead? {
        require(owner.isNotBlank() && leaseMillis in 1..60_000)
        return db.query("""
            UPDATE battle_session
               SET session_epoch = session_epoch + 1,
                   phase = CASE WHEN phase = 'READY' THEN 'JOINING' ELSE phase END,
                   lease_owner = :owner,
                   lease_until = clock_timestamp() + (:lease_millis * interval '1 millisecond')
             WHERE world_id = :world_id AND battle_id = :battle_id
               AND phase IN ('READY', 'JOINING', 'RUNNING', 'RESOLVING')
               AND (lease_until IS NULL OR lease_until < clock_timestamp())
               AND (phase IN ('RUNNING', 'RESOLVING') OR deadline_at > clock_timestamp())
            RETURNING phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
                      lease_owner, lease_until, join_deadline_at, deadline_at
        """.trimIndent(), key(worldId, battleId).addValue("owner", owner)
            .addValue("lease_millis", leaseMillis)) { rs, _ -> readHead(worldId, battleId, rs) }.firstOrNull()
    }

    override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                            sessionEpoch: Long, leaseMillis: Long): Boolean {
        require(owner.isNotBlank() && sessionEpoch > 0 && leaseMillis in 1..60_000)
        return db.update("""
            UPDATE battle_session
               SET lease_until = clock_timestamp() + (:lease_millis * interval '1 millisecond')
             WHERE world_id = :world_id AND battle_id = :battle_id AND session_epoch = :session_epoch
               AND lease_owner = :owner AND lease_until > clock_timestamp()
               AND phase IN ('JOINING', 'RUNNING', 'RESOLVING')
        """.trimIndent(), key(worldId, battleId).addValue("owner", owner)
            .addValue("session_epoch", sessionEpoch).addValue("lease_millis", leaseMillis)) == 1
    }

    override fun startRun(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long): Boolean = tx.execute {
        require(owner.isNotBlank() && sessionEpoch > 0)
        val params = key(worldId, battleId).addValue("owner", owner).addValue("session_epoch", sessionEpoch)
        val head = db.query("""
            SELECT phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
                   lease_owner, lease_until, join_deadline_at, deadline_at
              FROM battle_session WHERE world_id = :world_id AND battle_id = :battle_id FOR UPDATE
        """.trimIndent(), params) { rs, _ -> readHead(worldId, battleId, rs) }.firstOrNull()
            ?: return@execute false
        if (head.phase == BattleSessionPhase.RUNNING && head.sessionEpoch == sessionEpoch &&
            head.leaseOwner == owner && head.leaseUntil?.isAfter(dbNow()) == true) return@execute true
        if (head.phase != BattleSessionPhase.JOINING || head.sessionEpoch != sessionEpoch ||
            head.leaseOwner != owner || head.leaseUntil == null ||
            !head.leaseUntil.isAfter(dbNow()) || head.joinDeadlineAt.isAfter(dbNow())) return@execute false
        val payload = """{"schemaVersion":1,"kind":"SESSION_STARTED"}"""
        val seq = head.latestEventSeq + 1
        db.update("""
            INSERT INTO battle_event (world_id, battle_id, event_seq, session_epoch, accepted_tick,
                effective_tick, event_type, transition_id, payload_text, payload_sha256)
            VALUES (:world_id, :battle_id, :event_seq, :session_epoch, :tick,
                :tick, 'SESSION_STARTED', :transition_id, :payload, :payload_sha)
        """.trimIndent(), params.addValue("event_seq", seq).addValue("tick", head.currentTick)
            .addValue("transition_id", "start:$sessionEpoch").addValue("payload", payload)
            .addValue("payload_sha", sha256(payload)))
        check(db.update("""
            UPDATE battle_session SET phase = 'RUNNING', latest_event_seq = :event_seq
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params) == 1)
        true
    } ?: false

    override fun admit(command: BattleCommandRecord): CommandAdmission = tx.execute {
        require(sha256(command.intentJson) == command.intentSha256)
        val params = key(command.worldId, command.battleId)
            .addValue("participant_id", command.participantId)
            .addValue("client_command_id", command.clientCommandId)
            .addValue("intent_sha", command.intentSha256)
            .addValue("intent", command.intentJson)
        val existing = readReceipt(params)
        if (existing != null) return@execute if (existing.first == command.requestSha256)
            CommandAdmission.Receipt(existing.second.copy(replayed = true)) else CommandAdmission.IdempotencyConflict
        val head = db.query("""
            SELECT phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
                   lease_owner, lease_until, join_deadline_at, deadline_at
              FROM battle_session WHERE world_id = :world_id AND battle_id = :battle_id FOR UPDATE
        """.trimIndent(), params) { rs, _ -> readHead(command.worldId, command.battleId, rs) }.firstOrNull()
            ?: error("battle not found")
        // Another admission may have committed while this transaction waited for the session row.
        val afterLock = readReceipt(params)
        if (afterLock != null) return@execute if (afterLock.first == command.requestSha256)
            CommandAdmission.Receipt(afterLock.second.copy(replayed = true)) else CommandAdmission.IdempotencyConflict
        val authority = db.query("""
            SELECT side, authority_revision FROM battle_participant
             WHERE world_id = :world_id AND battle_id = :battle_id AND participant_id = :participant_id
        """.trimIndent(), params) { rs, _ -> rs.getString("side") to rs.getLong("authority_revision") }.firstOrNull()
        // No participant row means the signed identity has ceased to exist; do not emit a
        // non-durable ACK that could later be mistaken for an idempotent receipt.
        if (authority == null) throw SecurityException("battle participant unavailable")
        val reason = when {
            authority.first != command.side -> "UNAUTHORIZED"
            authority.second != command.expectedAuthorityRevision -> "STALE_AUTHORITY"
            head.sessionEpoch != command.expectedEpoch -> "STALE_EPOCH"
            head.phase != BattleSessionPhase.RUNNING || head.leaseUntil == null ||
                !head.leaseUntil.isAfter(dbNow()) || !head.deadlineAt.isAfter(dbNow()) -> "SESSION_CLOSED"
            head.currentTick >= TacticalRules.CANON.battleTicks -> "SESSION_CLOSED"
            command.issuedTick > head.currentTick ||
                head.currentTick - command.issuedTick > TacticalRules.CANON.commandIssuedTickMaxLag -> "STALE_TICK"
            command.mappedAtTick != null &&
                (head.currentTick != command.mappedAtTick ||
                    head.latestEventSeq != command.mappedAtEventSeq) -> "STALE_TICK"
            command.preflightReasonCode != null -> command.preflightReasonCode
            else -> null
        }
        val eventSeq = if (reason == null) head.latestEventSeq + 1 else null
        val effectiveTick = if (reason == null) head.currentTick + 1 else null
        if (eventSeq != null) {
            db.update("""
                INSERT INTO battle_event (world_id, battle_id, event_seq, session_epoch, accepted_tick,
                    effective_tick, event_type, participant_id, payload_text, payload_sha256)
                VALUES (:world_id, :battle_id, :event_seq, :session_epoch, :tick,
                    :effective_tick, 'COMMAND_ACCEPTED', :participant_id, :intent, :intent_sha)
            """.trimIndent(), params.addValue("event_seq", eventSeq)
                .addValue("session_epoch", head.sessionEpoch).addValue("tick", head.currentTick)
                .addValue("effective_tick", effectiveTick))
            db.update("""
                UPDATE battle_session SET latest_event_seq = :event_seq
                 WHERE world_id = :world_id AND battle_id = :battle_id
            """.trimIndent(), params)
        }
        val receipt = BattleCommandReceipt(command.clientCommandId,
            if (reason == null) BattleCommandVerdict.ACCEPTED else BattleCommandVerdict.REJECTED,
            reason, head.currentTick, effectiveTick, eventSeq, authority.second)
        db.update("""
            INSERT INTO battle_command_receipt (world_id, battle_id, participant_id,
                client_command_id, intent_sha256, verdict, reason_code, server_tick,
                effective_tick, event_seq, authority_revision)
            VALUES (:world_id, :battle_id, :participant_id, :client_command_id, :intent_sha,
                :verdict, :reason, :server_tick, :effective_tick, :event_seq, :authority_revision)
        """.trimIndent(), params.addValue("intent_sha", command.requestSha256)
            .addValue("verdict", receipt.verdict.name)
            .addValue("reason", receipt.reasonCode).addValue("server_tick", receipt.serverTick)
            .addValue("effective_tick", receipt.effectiveTick)
            .addValue("event_seq", receipt.eventSeq)
            .addValue("authority_revision", receipt.authorityRevision))
        CommandAdmission.Receipt(receipt)
    } ?: error("battle command transaction returned no result")

    override fun appendTransition(transition: BattleTransition): Long? = tx.execute {
        require(sha256(transition.payloadJson) == transition.payloadSha256)
        require(transition.effectiveTick <= TacticalRules.CANON.battleTicks)
        val params = key(transition.worldId, transition.battleId)
            .addValue("transition_id", transition.transitionId)
        fun existing(): Triple<Long, String, String>? = db.query("""
            SELECT event_seq, event_type, payload_sha256 FROM battle_event
             WHERE world_id = :world_id AND battle_id = :battle_id AND transition_id = :transition_id
        """.trimIndent(), params) { rs, _ ->
            Triple(rs.getLong("event_seq"), rs.getString("event_type"), rs.getString("payload_sha256"))
        }.firstOrNull()
        existing()?.let {
            check(it.second == transition.type && it.third == transition.payloadSha256) { "battle transition identity conflict" }
            return@execute it.first
        }
        val head = db.query("""
            SELECT phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
                   lease_owner, lease_until, join_deadline_at, deadline_at
              FROM battle_session WHERE world_id = :world_id AND battle_id = :battle_id FOR UPDATE
        """.trimIndent(), params) { rs, _ -> readHead(transition.worldId, transition.battleId, rs) }.firstOrNull()
            ?: return@execute null
        existing()?.let {
            check(it.second == transition.type && it.third == transition.payloadSha256) { "battle transition identity conflict" }
            return@execute it.first
        }
        if (head.sessionEpoch != transition.sessionEpoch || head.leaseOwner != transition.leaseOwner ||
            head.leaseUntil == null || !head.leaseUntil.isAfter(dbNow()) ||
            !head.deadlineAt.isAfter(dbNow()) ||
            head.phase !in setOf(BattleSessionPhase.JOINING, BattleSessionPhase.RUNNING) ||
            head.currentTick != transition.tick ||
            transition.effectiveTick > head.currentTick + 1) return@execute null
        val seq = head.latestEventSeq + 1
        db.update("""
            INSERT INTO battle_event (world_id, battle_id, event_seq, session_epoch, accepted_tick,
                effective_tick, event_type, participant_id, transition_id, payload_text, payload_sha256)
            VALUES (:world_id, :battle_id, :event_seq, :session_epoch, :tick,
                :effective_tick, :event_type, :participant_id, :transition_id, :payload, :payload_sha)
        """.trimIndent(), params.addValue("event_seq", seq)
            .addValue("session_epoch", transition.sessionEpoch).addValue("tick", transition.tick)
            .addValue("effective_tick", transition.effectiveTick).addValue("event_type", transition.type)
            .addValue("participant_id", transition.participantId)
            .addValue("payload", transition.payloadJson).addValue("payload_sha", transition.payloadSha256))
        db.update("""
            UPDATE battle_session SET latest_event_seq = :event_seq
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        seq
    }

    override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                             sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long): Boolean {
        require(owner.isNotBlank() && sessionEpoch > 0)
        require(expectedTick in 0 until TacticalRules.CANON.battleTicks && expectedEventSeq >= 0)
        return db.update("""
            UPDATE battle_session
               SET current_tick = :next_tick
             WHERE world_id = :world_id AND battle_id = :battle_id
               AND phase = 'RUNNING' AND session_epoch = :session_epoch
               AND lease_owner = :owner AND lease_until > clock_timestamp()
               AND deadline_at > clock_timestamp()
               AND current_tick = :expected_tick AND latest_event_seq = :expected_event_seq
        """.trimIndent(), key(worldId, battleId).addValue("owner", owner)
            .addValue("session_epoch", sessionEpoch).addValue("expected_tick", expectedTick)
            .addValue("next_tick", expectedTick + 1)
            .addValue("expected_event_seq", expectedEventSeq)) == 1
    }

    override fun checkpoint(checkpoint: BattleCheckpoint): Boolean = tx.execute {
        val params = key(checkpoint.worldId, checkpoint.battleId)
        val head = db.query("""
            SELECT phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
                   lease_owner, lease_until, join_deadline_at, deadline_at
              FROM battle_session WHERE world_id = :world_id AND battle_id = :battle_id FOR UPDATE
        """.trimIndent(), params) { rs, _ -> readHead(checkpoint.worldId, checkpoint.battleId, rs) }.firstOrNull()
            ?: return@execute false
        if (head.sessionEpoch != checkpoint.sessionEpoch || head.phase != BattleSessionPhase.RUNNING ||
            head.leaseOwner != checkpoint.leaseOwner || head.leaseUntil == null ||
            !head.leaseUntil.isAfter(dbNow()) ||
            checkpoint.tick != head.currentTick || checkpoint.eventSeq > head.latestEventSeq) return@execute false
        val previousEventSeq = db.query("""
            SELECT event_seq FROM battle_snapshot
             WHERE world_id = :world_id AND battle_id = :battle_id
             ORDER BY snapshot_seq DESC LIMIT 1
        """.trimIndent(), params) { rs, _ -> rs.getLong("event_seq") }.firstOrNull() ?: 0L
        if (checkpoint.eventSeq < previousEventSeq) return@execute false
        val seq = head.latestSnapshotSeq + 1
        db.update("""
            INSERT INTO battle_snapshot (world_id, battle_id, snapshot_seq, session_epoch,
                lease_owner, tick, event_seq, state_hash, compressed_state)
            VALUES (:world_id, :battle_id, :snapshot_seq, :session_epoch,
                :lease_owner, :tick, :event_seq, :state_hash, :compressed_state)
        """.trimIndent(), params.addValue("snapshot_seq", seq)
            .addValue("session_epoch", checkpoint.sessionEpoch).addValue("tick", checkpoint.tick)
            .addValue("lease_owner", checkpoint.leaseOwner)
            .addValue("event_seq", checkpoint.eventSeq).addValue("state_hash", checkpoint.stateHash)
            .addValue("compressed_state", checkpoint.compressedState))
        db.update("""
            UPDATE battle_session SET current_tick = :tick, latest_snapshot_seq = :snapshot_seq
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params)
        true
    } ?: false

    override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long): List<BattleEventRecord> {
        require(eventSeq >= 0)
        return db.query("""
            SELECT event_seq, session_epoch, accepted_tick, effective_tick, event_type,
                   payload_text, payload_sha256
              FROM battle_event
             WHERE world_id = :world_id AND battle_id = :battle_id AND event_seq > :event_seq
             ORDER BY event_seq
        """.trimIndent(), key(worldId, battleId).addValue("event_seq", eventSeq)) { rs, _ ->
            BattleEventRecord(rs.getLong("event_seq"), rs.getLong("session_epoch"),
                rs.getInt("accepted_tick"), rs.getInt("effective_tick"), rs.getString("event_type"),
                rs.getString("payload_text"), rs.getString("payload_sha256"))
        }
    }

    override fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint? = db.query("""
        SELECT session_epoch, lease_owner, tick, event_seq, state_hash, compressed_state
          FROM battle_snapshot WHERE world_id = :world_id AND battle_id = :battle_id
         ORDER BY snapshot_seq DESC LIMIT 1
    """.trimIndent(), key(worldId, battleId)) { rs, _ ->
        BattleCheckpoint(worldId, battleId, rs.getLong("session_epoch"), rs.getString("lease_owner"), rs.getInt("tick"),
            rs.getLong("event_seq"), rs.getString("state_hash"), rs.getBytes("compressed_state"))
    }.firstOrNull()

    override fun publishResult(result: BattleResultRecord): Boolean = tx.execute {
        require(sha256(result.resultJson) == result.resultSha256)
        val params = key(result.worldId, result.battleId).addValue("revision", result.resultRevision)
        val existing = readResultIdentity(params)
        if (existing != null) {
            check(existing.matches(result)) { "battle result identity conflict" }
            return@execute false
        }
        val head = db.query("""
            SELECT phase, session_epoch, current_tick, latest_event_seq, latest_snapshot_seq,
                   lease_owner, lease_until, join_deadline_at, deadline_at
              FROM battle_session WHERE world_id = :world_id AND battle_id = :battle_id FOR UPDATE
        """.trimIndent(), params) { rs, _ -> readHead(result.worldId, result.battleId, rs) }.firstOrNull()
            ?: return@execute false
        val afterLock = readResultIdentity(params)
        if (afterLock != null) {
            check(afterLock.matches(result)) { "battle result identity conflict" }
            return@execute false
        }
        val ticket = ticket(result.worldId, result.battleId) ?: return@execute false
        if (head.sessionEpoch != result.sessionEpoch || head.leaseOwner != result.leaseOwner ||
            head.leaseUntil == null || !head.leaseUntil.isAfter(dbNow()) ||
            head.phase !in setOf(BattleSessionPhase.RUNNING, BattleSessionPhase.RESOLVING) ||
            ticket.lockGeneration != result.lockGeneration || ticket.lockSetRevision != result.lockSetRevision)
            return@execute false
        val seq = head.latestEventSeq + 1
        val values = params.addValue("session_epoch", result.sessionEpoch)
            .addValue("lease_owner", result.leaseOwner)
            .addValue("event_seq", seq).addValue("tick", head.currentTick)
            .addValue("result", result.resultJson)
            .addValue("result_sha", result.resultSha256)
            .addValue("replay_hash", result.replayHash)
            .addValue("lock_generation", result.lockGeneration)
            .addValue("lock_set_revision", result.lockSetRevision)
        db.update("""
            INSERT INTO battle_event (world_id, battle_id, event_seq, session_epoch, accepted_tick,
                effective_tick, event_type, payload_text, payload_sha256)
            VALUES (:world_id, :battle_id, :event_seq, :session_epoch, :tick,
                :tick, 'BATTLE_RESOLVED', :result, :result_sha)
        """.trimIndent(), values)
        db.update("""
            INSERT INTO battle_result_outbox (world_id, battle_id, result_revision, session_epoch,
                lease_owner, result_text, result_sha256, replay_hash, lock_generation, lock_set_revision, status)
            VALUES (:world_id, :battle_id, :revision, :session_epoch, :lease_owner, :result, :result_sha,
                :replay_hash, :lock_generation, :lock_set_revision, 'PENDING')
        """.trimIndent(), values)
        db.update("""
            UPDATE battle_session SET phase = 'RESULT_PENDING', latest_event_seq = :event_seq,
                resolved_at = clock_timestamp()
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), values)
        true
    } ?: false

    override fun pendingResults(worldId: WorldId, limit: Int): List<BattleResultRecord> {
        require(limit in 1..1000)
        return db.query("""
            SELECT battle_id, result_revision, session_epoch, lease_owner, result_text,
                   result_sha256, replay_hash, lock_generation, lock_set_revision
              FROM battle_result_outbox
             WHERE world_id = :world_id AND status = 'PENDING'
             ORDER BY created_at, battle_id, result_revision LIMIT :limit
        """.trimIndent(), MapSqlParameterSource().addValue("world_id", worldId.value).addValue("limit", limit)) { rs, _ ->
            BattleResultRecord(worldId, rs.getString("battle_id"), rs.getLong("session_epoch"),
                rs.getString("lease_owner"), rs.getInt("result_revision"), rs.getString("result_text"),
                rs.getString("result_sha256"),
                rs.getString("replay_hash"), rs.getLong("lock_generation"), rs.getLong("lock_set_revision"))
        }
    }

    override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int): Boolean = tx.execute {
        val params = key(worldId, battleId).addValue("revision", resultRevision)
        val changed = db.update("""
            UPDATE battle_result_outbox SET status = 'APPLIED', applied_at = clock_timestamp()
             WHERE world_id = :world_id AND battle_id = :battle_id AND result_revision = :revision
               AND status = 'PENDING'
        """.trimIndent(), params)
        if (changed == 0) return@execute false
        check(db.update("""
            UPDATE battle_session SET phase = 'APPLIED'
             WHERE world_id = :world_id AND battle_id = :battle_id AND phase = 'RESULT_PENDING'
        """.trimIndent(), params) == 1) { "result applied without pending session" }
        true
    } ?: false

    override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int,
                             reason: String): Boolean = tx.execute {
        require(reason.isNotBlank() && reason.length <= 512)
        val params = key(worldId, battleId).addValue("revision", resultRevision).addValue("reason", reason)
        val changed = db.update("""
            UPDATE battle_result_outbox
               SET status = 'RESULT_BLOCKED', blocked_at = clock_timestamp(), block_reason = :reason
             WHERE world_id = :world_id AND battle_id = :battle_id AND result_revision = :revision
               AND status = 'PENDING'
        """.trimIndent(), params)
        if (changed == 0) return@execute false
        check(db.update("""
            UPDATE battle_session SET phase = 'RESULT_BLOCKED'
             WHERE world_id = :world_id AND battle_id = :battle_id AND phase = 'RESULT_PENDING'
        """.trimIndent(), params) == 1) { "result blocked without pending session" }
        true
    } ?: false

    private fun readReceipt(params: MapSqlParameterSource): Pair<String, BattleCommandReceipt>? = db.query("""
        SELECT intent_sha256, client_command_id, verdict, reason_code, server_tick,
               effective_tick, event_seq, authority_revision
          FROM battle_command_receipt WHERE world_id = :world_id AND battle_id = :battle_id
           AND participant_id = :participant_id AND client_command_id = :client_command_id
    """.trimIndent(), params) { rs, _ ->
        rs.getString("intent_sha256") to BattleCommandReceipt(rs.getString("client_command_id"),
            BattleCommandVerdict.valueOf(rs.getString("verdict")), rs.getString("reason_code"),
            rs.getInt("server_tick"), rs.getInt("effective_tick").takeUnless { rs.wasNull() },
            rs.getLong("event_seq").takeUnless { rs.wasNull() }, rs.getLong("authority_revision"))
    }.firstOrNull()

    private data class ResultIdentity(val sha: String, val replayHash: String, val sessionEpoch: Long,
        val leaseOwner: String, val lockGeneration: Long, val lockSetRevision: Long) {
        fun matches(result: BattleResultRecord): Boolean = sha == result.resultSha256 &&
            replayHash == result.replayHash && sessionEpoch == result.sessionEpoch &&
            leaseOwner == result.leaseOwner && lockGeneration == result.lockGeneration &&
            lockSetRevision == result.lockSetRevision
    }

    private fun readResultIdentity(params: MapSqlParameterSource): ResultIdentity? = db.query("""
        SELECT result_sha256, replay_hash, session_epoch, lease_owner, lock_generation, lock_set_revision
          FROM battle_result_outbox
         WHERE world_id = :world_id AND battle_id = :battle_id AND result_revision = :revision
    """.trimIndent(), params) { rs, _ ->
        ResultIdentity(rs.getString("result_sha256"), rs.getString("replay_hash"),
            rs.getLong("session_epoch"), rs.getString("lease_owner"),
            rs.getLong("lock_generation"), rs.getLong("lock_set_revision"))
    }.firstOrNull()

    private fun readHead(worldId: WorldId, battleId: String, rs: ResultSet): BattleSessionHead =
        BattleSessionHead(worldId, battleId, BattleSessionPhase.valueOf(rs.getString("phase")),
            rs.getLong("session_epoch"), rs.getInt("current_tick"), rs.getLong("latest_event_seq"),
            rs.getLong("latest_snapshot_seq"), rs.getString("lease_owner"),
            rs.getTimestamp("lease_until")?.toInstant(), rs.getTimestamp("join_deadline_at").toInstant(),
            rs.getTimestamp("deadline_at").toInstant())

    private fun dbNow(): Instant = db.jdbcOperations.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)!!
        .toInstant()

    private fun key(worldId: WorldId, battleId: String): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("world_id", worldId.value).addValue("battle_id", battleId)

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

}
