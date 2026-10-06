package opensamguk.infra.battle.realtime

import opensamguk.common.world.WorldId
import opensamguk.logic.battle.realtime.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcBattleOwnerOwnershipSourceTest {
    private val world = WorldId(1)
    private fun key(id: Int) = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, id.toString())
    private fun origin(): CampaignBattleOriginSnapshot {
        fun unit(id: Int, owner: Int, commander: Int, side: BattleSide) =
            CampaignBattleOriginUnit(key(id), side, owner, commander, 1, 30, 50, 50, 0, 20, null, 1)
        return CampaignBattleOriginSnapshot(1, "battle", "c".repeat(64), "d".repeat(64), 1, 1,
            "topology", "e".repeat(64), "province", "approach", "f".repeat(64), 1, 1,
            CampaignBattleOriginParticipant("attack", 7, 9, 1, listOf(1)),
            listOf(CampaignBattleOriginParticipant("defend", 8, 11, 2, listOf(2))),
            listOf(unit(1, 7, 9, BattleSide.ATTACKER), unit(2, 8, 11, BattleSide.DEFENDER)),
            listOf(CampaignBattleOriginOwner(7, 42, 0, true, BattleSide.ATTACKER, listOf(key(1)), 1,
                CampaignBattleAuthorityStatus.ACTIVE),
                CampaignBattleOriginOwner(8, null, 2, false, BattleSide.DEFENDER, listOf(key(2)), 1,
                    CampaignBattleAuthorityStatus.ACTIVE)))
    }
    private fun native() = listOf(BattleOwnerNativeGeneral(1, 7, "42", 0),
        BattleOwnerNativeGeneral(1, 8, null, 2))
    private fun current(origin: CampaignBattleOriginSnapshot = origin()) = CampaignBattleCurrentRead(origin,
        CampaignBattleOriginCodec.sha256(CampaignBattleOriginCodec.encode(origin)), origin.owners,
        mapOf(1 to 1L, 2 to 1L), 1, 1)

    private inner class Fixture(current: CampaignBattleCurrentRead? = current(), rows: List<BattleOwnerNativeGeneral> = native()) {
        val data = mock(DataSource::class.java)
        val connection = mock(Connection::class.java)
        val reader = mock(JdbcCampaignBattleOriginReader::class.java)
        val statement = mock(PreparedStatement::class.java)
        val result = mock(ResultSet::class.java)
        val ids = mock(java.sql.Array::class.java)
        val users = mock(java.sql.Array::class.java)
        var sql = ""
        init {
            `when`(data.connection).thenReturn(connection)
            `when`(reader.readCommitted(connection, world, "battle")).thenReturn(current)
            `when`(connection.createArrayOf(eq("integer"), any())).thenReturn(ids)
            `when`(connection.createArrayOf(eq("text"), any())).thenReturn(users)
            `when`(connection.prepareStatement(anyString())).thenAnswer { sql = it.getArgument(0); statement }
            `when`(statement.executeQuery()).thenReturn(result)
            var index = -1
            `when`(result.next()).thenAnswer { ++index < rows.size }
            `when`(result.getInt("id")).thenAnswer { rows[index].generalId }
            `when`(result.getInt("world_id")).thenAnswer { rows[index].worldId }
            `when`(result.getInt("npc_state")).thenAnswer { rows[index].npcState }
            `when`(result.getString("user_id")).thenAnswer { rows[index].userId }
        }
        fun source() = JdbcBattleOwnerOwnershipSource(world, data, reader)
    }
    private fun expected() = BattleOwnerAuthorityBinding.fromOrigin(origin(), native()).single()

    @Test fun `same read only repeatable read connection supplies origin and all native owners`() {
        val f = Fixture()
        assertNotNull(f.source().read(expected()))
        val order = inOrder(f.connection, f.reader, f.statement)
        order.verify(f.connection).isReadOnly = true
        order.verify(f.connection).transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        order.verify(f.connection).autoCommit = false
        order.verify(f.reader).readCommitted(f.connection, world, "battle")
        order.verify(f.connection).prepareStatement(anyString())
        order.verify(f.statement).executeQuery()
        order.verify(f.connection).rollback()
        order.verify(f.connection).close()
        verify(f.connection, never()).commit()
        verify(f.ids).free()
        verify(f.users).free()
        assertTrue(f.sql.startsWith("SELECT ") && !f.sql.contains("LIMIT") && !f.sql.contains("FOR UPDATE"))
    }

    @Test fun `missing installation and cross process world do not open a connection`() {
        assertNull(JdbcBattleOwnerOwnershipSource(world).read(expected()))
        val f = Fixture()
        assertNull(JdbcBattleOwnerOwnershipSource(WorldId(2), f.data, f.reader).read(expected()))
        verify(f.data, never()).connection
        assertNull(JdbcBattleOwnerOwnershipSource(world, f.data).read(expected()))
        verify(f.data, never()).connection
    }

    @Test fun `missing origin and SQL failure remain unavailable and release the owned snapshot`() {
        val absent = Fixture(current = null)
        assertNull(absent.source().read(expected()))
        verify(absent.connection).rollback()
        verify(absent.connection).close()
        val failed = Fixture()
        `when`(failed.statement.executeQuery()).thenThrow(SQLException("synthetic"))
        assertNull(failed.source().read(expected()))
        verify(failed.connection).rollback()
        verify(failed.connection).close()
    }

    @Test fun `account ambiguity outside battle missing owner duplicate or foreign row is denied`() {
        for (rows in listOf(native() + BattleOwnerNativeGeneral(1, 99, "42", 0), native().take(1),
            native() + native()[0], native().map { it.copy(worldId = 2) })) {
            val f = Fixture(rows = rows)
            assertNull(f.source().read(expected()))
            verify(f.connection).rollback()
        }
    }

    @Test fun `raw human account cannot be repaired normalized or degraded to NPC`() {
        for (user in listOf(null, "043", "42.0", "", "43")) {
            val rows = native().map { if (it.generalId == 7) it.copy(userId = user) else it }
            assertNull(Fixture(rows = rows).source().read(expected()))
        }
        assertNull(Fixture(rows = native().map { if (it.generalId == 7) it.copy(npcState = 2) else it })
            .source().read(expected()))
    }

    @Test fun `current authority ABA revoke account side and source revision drift stay denied`() {
        val base = current()
        val changedOwner = base.currentOwners[0]
        for (change in listOf(changedOwner.copy(authorityRevision = 3),
            changedOwner.copy(authorityStatus = CampaignBattleAuthorityStatus.REVOKED),
            changedOwner.copy(accountId = 43), changedOwner.copy(side = BattleSide.DEFENDER))) {
            assertNull(Fixture(base.copy(currentOwners = listOf(change, base.currentOwners[1])))
                .source().read(expected()))
        }
        for (revisions in listOf(mapOf(1 to 2L, 2 to 1L), mapOf(1 to 1L), mapOf(1 to 0L, 2 to 1L))) {
            assertNull(Fixture(base.copy(currentBugokRevisions = revisions)).source().read(expected()))
        }
    }

    @Test fun `origin SHA writer generation and world version cannot be supplied by fallbacks`() {
        val base = current()
        for (read in listOf(base.copy(sourceSha256 = "b".repeat(64)),
            base.copy(currentWriterEpoch = 2), base.copy(currentWorldVersion = 0))) {
            assertNull(Fixture(read).source().read(expected()))
        }
    }

    @Test fun `null required native scalar or rollback failure cannot escape as a verified binding`() {
        val malformed = Fixture()
        `when`(malformed.result.wasNull()).thenReturn(true)
        assertNull(malformed.source().read(expected()))
        val failed = Fixture()
        doThrow(SQLException("synthetic rollback failure")).`when`(failed.connection).rollback()
        assertNull(failed.source().read(expected()))
        verify(failed.connection).close()
    }
}
