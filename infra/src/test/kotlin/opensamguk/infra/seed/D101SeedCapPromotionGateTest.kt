package opensamguk.infra.seed

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.*

class D101SeedCapPromotionGateTest {
    private class Fixture(config:String="{\"maxgeneral\":50}",env:String="50",meta:String="{}",extraWorld:Boolean=false) {
        val source=mock(DataSource::class.java);val connection=mock(Connection::class.java)
        val world=mock(PreparedStatement::class.java);val gameEnv=mock(PreparedStatement::class.java)
        init {
            `when`(source.connection).thenReturn(connection)
            `when`(connection.prepareStatement("SELECT id, scenario_code, tick_seconds, config, meta FROM world_state")).thenReturn(world)
            `when`(connection.prepareStatement("SELECT value FROM game_kv WHERE world_id=1 AND \"table\"='game_env' AND namespace='game_env' AND key='maxgeneral'")).thenReturn(gameEnv)
            val rows=mock(ResultSet::class.java);`when`(world.executeQuery()).thenReturn(rows)
            `when`(rows.next()).thenReturn(true,extraWorld,false);`when`(rows.getInt("id")).thenReturn(1)
            `when`(rows.getString("scenario_code")).thenReturn("scenario_3190");`when`(rows.getInt("tick_seconds")).thenReturn(3600)
            `when`(rows.getString("config")).thenReturn(config);`when`(rows.getString("meta")).thenReturn(meta)
            val envRows=mock(ResultSet::class.java);`when`(gameEnv.executeQuery()).thenReturn(envRows)
            `when`(envRows.next()).thenReturn(true,false);`when`(envRows.getString(1)).thenReturn(env)
        }
    }
    @Test fun `both actual DB numeric stores read in one repeatable read transaction preserve missing generation UNKNOWN`() {
        val f=Fixture();val observed=D101SeedCapPromotionGate(f.source).observeNewWorldBeforePromotion()
        assertEquals(50,observed.configMaxGeneral);assertEquals(50,observed.gameEnvMaxGeneral);assertNull(observed.generation)
        val order=inOrder(f.connection,f.world,f.gameEnv)
        order.verify(f.connection).isReadOnly=true
        order.verify(f.connection).transactionIsolation=Connection.TRANSACTION_REPEATABLE_READ
        order.verify(f.connection).autoCommit=false
        order.verify(f.world).queryTimeout=2;order.verify(f.world).executeQuery()
        order.verify(f.gameEnv).queryTimeout=2;order.verify(f.gameEnv).executeQuery()
        order.verify(f.connection).rollback()
    }
    @Test fun `string float missing mismatch or extra world cannot authorize cap facts`() {
        for(f in listOf(Fixture("{\"maxgeneral\":\"50\"}"),Fixture(env="\"50\""),Fixture(env="50.0"),
            Fixture(config="{}"),Fixture(env="500"),Fixture(meta="{\"server_generation\":\"0\"}"),Fixture(extraWorld=true))) {
            assertFailsWith<SelectedSourceUnavailable> {D101SeedCapPromotionGate(f.source).observeNewWorldBeforePromotion()}
            verify(f.connection).rollback()
        }
    }
    @Test fun `uninstalled source or connection failure remains unavailable`() {
        assertFailsWith<SelectedSourceUnavailable> {D101SeedCapPromotionGate(null).observeNewWorldBeforePromotion()}
        val source=mock(DataSource::class.java);`when`(source.connection).thenThrow(SQLException("synthetic"))
        assertFailsWith<SelectedSourceUnavailable> {D101SeedCapPromotionGate(source).observeNewWorldBeforePromotion()}
    }
}
