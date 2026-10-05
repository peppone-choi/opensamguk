package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real Spring transaction lifecycle; the JDBC connection is mocked and no database is required. */
class ImperialCourtTransactionBoundaryTest {
    @Test
    fun `participating collaborator failures roll back before controller translates them to uncached 409`() {
        for (failure in listOf(IllegalArgumentException("invalid source"), IllegalStateException("missing source"),
                ResponseStatusException(HttpStatus.CONFLICT))) {
            val fixture = Fixture()
            val response = fixture.controller(failure).read(41L, "10")
            assertEquals(HttpStatus.CONFLICT, response.statusCode)
            assertEquals("no-store", response.headers.cacheControl)
            assertEquals(ImperialCourtDto(ImperialCourtStatus.STATE_UNAVAILABLE, emptyList()), response.body)
            fixture.assertRolledBack()
        }
    }

    @Test
    fun `swallowing a participating failure reproduces unexpected rollback with the same real manager`() {
        val fixture = Fixture()
        assertFailsWith<UnexpectedRollbackException> {
            TransactionTemplate(fixture.manager).execute {
                try {
                    TransactionTemplate(fixture.manager).execute<Unit> { throw IllegalArgumentException("invalid source") }
                } catch (_: IllegalArgumentException) {
                    // Regression control: this is the old reader's catch inside the outer transaction.
                }
            }
        }
        fixture.assertRolledBack()
    }

    @Test
    fun `unrelated source 503 propagates after rollback and is never rewritten to court conflict`() {
        val fixture = Fixture()
        val failure = ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        assertSame(failure, assertFailsWith<ResponseStatusException> { fixture.controller(failure).read(41L, "10") })
        fixture.assertRolledBack()
    }

    private class Fixture {
        private val connection = mock(Connection::class.java).also { `when`(it.autoCommit).thenReturn(true) }
        private val dataSource = mock(DataSource::class.java).also { `when`(it.connection).thenReturn(connection) }
        val manager = DataSourceTransactionManager(dataSource)
        private val interceptor = TransactionInterceptor(manager, AnnotationTransactionAttributeSource())

        fun controller(failure: RuntimeException): ImperialCourtController {
            val resolver = mock(GeneralResolver::class.java)
            val actor = GeneralReadEntity(id = 10, worldId = 1, userId = "41", nationId = 7)
            `when`(resolver.resolve(41)).thenReturn(GeneralResolver.ResolvedGeneral(actor, 0, 0, 7, 1))
            val worlds = mock(WorldStateReadRepository::class.java)
            `when`(worlds.findProcessWorld()).thenAnswer {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                // Match a transactional collaborator joining the reader/query transaction and marking it rollback-only.
                TransactionTemplate(manager).execute<WorldStateReadEntity> { throw failure }
            }
            val reader = transactional(ImperialCourtReader(worlds, mock(GeneralReadRepository::class.java),
                mock(NationReadRepository::class.java), mock(CityReadRepository::class.java),
                mock(ActiveWorldArtifactResolver::class.java)))
            return ImperialCourtController(transactional(ImperialCourtQuery(resolver, reader, GameApiProcessWorld(1))))
        }

        fun assertRolledBack() {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            verify(connection).rollback()
            verify(connection, never()).commit()
        }

        private fun <T : Any> transactional(target: T): T {
            val factory = ProxyFactory(target)
            factory.isProxyTargetClass = true
            factory.addAdvice(interceptor)
            @Suppress("UNCHECKED_CAST")
            return factory.proxy as T
        }
    }
}
