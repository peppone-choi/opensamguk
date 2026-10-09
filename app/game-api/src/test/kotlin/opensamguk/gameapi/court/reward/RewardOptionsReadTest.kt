package opensamguk.gameapi.court.reward

import java.util.Optional
import kotlin.test.*
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralOwnershipReadSource
import opensamguk.gameapi.owner.GeneralOwnershipSnapshot
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.world.StrategicRouteProjection
import org.mockito.Mockito.*
import org.springframework.aop.framework.ProxyFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager

class RewardReadFixture {
    val ownership = mock(GeneralOwnershipReadSource::class.java)
    val generals = mock(GeneralReadRepository::class.java)
    val worlds = mock(WorldStateReadRawRepository::class.java)
    val retainers = mock(RetainerReadRepository::class.java)
    val cities = mock(CityReadRepository::class.java)
    val nations = mock(NationReadRepository::class.java)
    val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    val reader = RewardOptionsReader(ownership, generals, worlds, retainers, cities, nations, artifacts, GameApiProcessWorld(7))
    val actor = person(10).apply { userId = "42" }
    val people = listOf(actor, person(20), person(30), person(40))
    val cards = listOf(card(4, 10, 20), card(5, 20, 30), card(6, 99, 40), card(7, 10, null))
    val cityRows = listOf(city(1, 500), city(2, 700), city(99, 1_000_000))
    val world = WorldStateReadEntity(id = 7, currentYear = 200, currentMonth = 1, currentPhase = 2,
        config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "mapName" to "han-world-v3"))
    val bundle = mock(ResolvedWorldArtifacts::class.java)

    init {
        `when`(ownership.findPlayableByUserId("42")).thenReturn(GeneralOwnershipSnapshot(10, 7, "42", 0))
        `when`(generals.findById(10)).thenReturn(Optional.of(actor))
        `when`(generals.findAll()).thenReturn(people)
        `when`(worlds.findById(7)).thenReturn(Optional.of(world))
        `when`(retainers.findAll()).thenReturn(cards)
        `when`(cities.findAll()).thenReturn(cityRows)
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, worldId = 7, capitalCityId = 2)))
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.administrativeCountyIds).thenReturn(setOf(1, 2))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, cityRows, bundle))
    }

    fun person(id: Int) = GeneralReadEntity(id = id, worldId = 7, name = "G$id", cityId = 1, nationId = 1)
    fun card(id: Int, master: Int, general: Int?) = GeneralRetainerReadEntity(worldId = 7, id = id,
        masterGeneralId = master, generalId = general, loyalty = 0)
    fun city(id: Int, money: Long) = CityReadEntity(id = id, worldId = 7, nationId = 1, supplyState = 1,
        meta = mapOf(CountyWarehouse.META_KEY to CountyWarehouse(id, 3, Resources(money = money)).toMetaValue()))
}

class RewardOptionsReadTest {
    @Test fun `only direct person card IDs select previews and noncounties never enter funding`() {
        val f = RewardReadFixture()
        val query = RewardOptionsQuery(f.reader)
        val result = query.options(10, 42, 4, "100")
        assertEquals("READY", result.status)
        assertEquals(listOf(4), result.cards!!.map { it.retainerId })
        assertEquals("1200", result.cards.single().funding.usableMoney)
        assertEquals(2, result.preview!!.debitPlan!!.single().cityId)
        for (id in listOf(5, 6, 7, 20, 999)) {
            val hidden = query.options(10, 42, id, "100").preview!!
            assertEquals("CARD_UNAVAILABLE", hidden.verdict)
            assertNull(hidden.debitPlan)
            assertNull(hidden.usableMoney)
        }
    }

    @Test fun `possession must match the playable process body and detailed owner`() {
        for (body in listOf(null, GeneralOwnershipSnapshot(11, 7, "42", 0),
            GeneralOwnershipSnapshot(10, 8, "42", 0), GeneralOwnershipSnapshot(10, 7, "43", 0),
            GeneralOwnershipSnapshot(10, 7, "42", 2))) {
            val f = RewardReadFixture()
            `when`(f.ownership.findPlayableByUserId("42")).thenReturn(body)
            assertFailsWith<RewardOptionsForbidden> { RewardOptionsQuery(f.reader).options(10, 42) }
            verifyNoInteractions(f.worlds, f.retainers, f.artifacts)
        }
        val f = RewardReadFixture()
        f.actor.userId = "43"
        assertFailsWith<RewardOptionsForbidden> { RewardOptionsQuery(f.reader).options(10, 42) }
        verifyNoInteractions(f.worlds, f.retainers, f.artifacts)
    }

    @Test fun `cross world roster rows close the whole projection before artifact resolution`() {
        for (kind in listOf("person", "card", "city")) {
            val f = RewardReadFixture()
            when (kind) {
                "person" -> `when`(f.generals.findAll()).thenReturn(f.people + f.person(555).apply { worldId = 8 })
                "card" -> `when`(f.retainers.findAll()).thenReturn(f.cards + f.card(555, 10, 20).apply { worldId = 8 })
                "city" -> `when`(f.cities.findAll()).thenReturn(f.cityRows + f.city(555, 99).apply { worldId = 8 })
            }
            val result = RewardOptionsQuery(f.reader).options(10, 42, 4, "100")
            assertEquals("ROSTER_INVALID", result.reason)
            assertNull(result.cards)
            assertNull(result.preview)
            verify(f.artifacts, never()).resolve()
        }
    }

    @Test fun `world calendar format profile and artifacts failures remain distinct`() {
        val f = RewardReadFixture()
        val query = RewardOptionsQuery(f.reader)
        `when`(f.worlds.findById(7)).thenReturn(Optional.empty())
        assertEquals("WORLD_UNAVAILABLE", query.options(10, 42).reason)
        `when`(f.worlds.findById(7)).thenReturn(Optional.of(WorldStateReadEntity(id = 0)))
        assertEquals("WORLD_UNAVAILABLE", query.options(10, 42).reason)
        `when`(f.worlds.findById(7)).thenReturn(Optional.of(f.world))
        f.world.config = mapOf("ruleProfile" to RuleProfile.entries.single { it != RuleProfile.HWIHA }.name)
        assertEquals("WRONG_RULE_PROFILE", query.options(10, 42).status)
        f.world.config = mapOf("worldFormat" to "unknown")
        assertEquals("UNSUPPORTED_WORLD_FORMAT", query.options(10, 42).status)
        f.world.config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")
        f.world.currentPhase = 0
        assertEquals("WORLD_DATE_INVALID", query.options(10, 42).reason)
        f.world.currentPhase = 2
        `when`(f.artifacts.resolve()).thenReturn(null)
        assertEquals("ARTIFACTS_UNAVAILABLE", query.options(10, 42).reason)
    }

    @Test fun `invalid date boundaries close the root with the agreed reason`() {
        for ((year, month, phase) in listOf(Triple(0, 1, 2), Triple(200, 0, 2), Triple(200, 13, 2),
            Triple(200, 1, 0), Triple(200, 1, 4))) {
            val f = RewardReadFixture()
            f.world.currentYear = year; f.world.currentMonth = month; f.world.currentPhase = phase
            val result = RewardOptionsQuery(f.reader).options(10, 42, 4, "100")
            assertEquals("UNAVAILABLE", result.status)
            assertEquals("WORLD_DATE_INVALID", result.reason)
            assertNull(result.snapshot)
            assertNull(result.queued)
            assertNull(result.preview)
            verifyNoInteractions(f.retainers, f.artifacts)
        }
    }

    @Test fun `duplicate roster IDs and invalid card IDs close the root without resolver reads`() {
        for (kind in listOf("person", "card", "city", "invalid-card")) {
            val f = RewardReadFixture()
            when (kind) {
                "person" -> `when`(f.generals.findAll()).thenReturn(f.people + f.person(20))
                "card" -> `when`(f.retainers.findAll()).thenReturn(f.cards + f.card(4, 10, 20))
                "city" -> `when`(f.cities.findAll()).thenReturn(f.cityRows + f.city(1, 500))
                else -> `when`(f.retainers.findAll()).thenReturn(f.cards + f.card(0, 10, 20))
            }
            val result = RewardOptionsQuery(f.reader).options(10, 42, 4, "100")
            assertEquals("ROSTER_INVALID", result.reason)
            assertNull(result.snapshot)
            assertNull(result.queued)
            assertNull(result.cards)
            assertNull(result.preview)
            verify(f.artifacts, never()).resolve()
        }
    }

    @Test fun `missing recipient remains an owned card and malformed queue is diagnostic only`() {
        val f = RewardReadFixture()
        `when`(f.generals.findAll()).thenReturn(f.people.filter { it.id != 20 })
        f.actor.meta = mapOf("queuedReward" to "broken")
        val result = RewardOptionsQuery(f.reader).options(10, 42, 4, "100")
        assertEquals("READY", result.status)
        assertEquals("UNAVAILABLE", result.queued!!.status)
        assertEquals(20, result.cards!!.single().recipientGeneralId)
        assertNull(result.cards.single().name)
        assertNull(result.cards.single().locationCityId)
        assertEquals("RECIPIENT_MISSING", result.cards.single().funding.unavailableReason)
        assertEquals("CARD_UNAVAILABLE", result.preview!!.verdict)
    }

    @Test fun `one readonly repeatable transaction covers ownership and all reads and resolver errors propagate`() {
        val f = RewardReadFixture()
        val tm = TrackingTransactions()
        val proxy = ProxyFactory(f.reader).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(tm, AnnotationTransactionAttributeSource()))
        }.proxy as RewardOptionsReader
        val query = RewardOptionsQuery(proxy)
        `when`(f.ownership.findPlayableByUserId("42")).thenAnswer {
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
            GeneralOwnershipSnapshot(10, 7, "42", 0)
        }
        assertEquals("READY", query.options(10, 42).status)
        assertEquals(1, tm.commits)
        assertEquals(1, tm.begins)
        val failure = DataAccessResourceFailureException("synthetic resolver failure")
        `when`(f.artifacts.resolve()).thenThrow(failure)
        assertSame(failure, assertFailsWith<DataAccessResourceFailureException> { query.options(10, 42) })
        assertEquals(1, tm.rollbacks)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @Test fun `storage failures roll back and propagate without invented unavailable remapping`() {
        val f = RewardReadFixture()
        val tm = TrackingTransactions()
        val proxy = ProxyFactory(f.reader).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(tm, AnnotationTransactionAttributeSource()))
        }.proxy as RewardOptionsReader
        val failure = DataAccessResourceFailureException("synthetic storage failure")
        `when`(f.worlds.findById(7)).thenThrow(failure)
        assertSame(failure, assertFailsWith<DataAccessResourceFailureException> {
            RewardOptionsQuery(proxy).options(10, 42)
        })
        assertEquals(1, tm.rollbacks)
        assertEquals(0, tm.commits)
    }

    private class TrackingTransactions : AbstractPlatformTransactionManager() {
        var begins = 0
        var commits = 0
        var rollbacks = 0
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            assertTrue(definition.isReadOnly)
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.isolationLevel)
            begins++
        }
        override fun doCommit(status: DefaultTransactionStatus) { commits++ }
        override fun doRollback(status: DefaultTransactionStatus) { rollbacks++ }
    }
}
