package opensamguk.gameapi.creation

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.dto.GeneralCreationChoiceDto
import opensamguk.gameapi.dto.GeneralCreationErrorResponseDto
import opensamguk.gameapi.dto.GeneralCreationRequestDto
import opensamguk.gameapi.dto.GeneralCreationStatsDto
import opensamguk.gameapi.member.MemberProfileClient
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.ActiveWorldArtifactSnapshot
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.web.GeneralCreationController
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.world.StrategicRouteBinding
import opensamguk.logic.world.StrategicRouteProjection
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneralCreationStatsAdmissionTest {
    @Test fun verificationWindowBlocksCreationBeforeReceiptAndInboxWrites() {
        val receipts = mock(CreationReceiptRepository::class.java, Answer { invocation ->
            if (invocation.method.name == "insertIfAbsent") true else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        val inbox = mock(CommandInboxRepository::class.java, Answer { invocation ->
            if (invocation.method.name == "insertAccepted") CommandInboxRepository.InsertResult.Inserted
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        val redis = mock(StringRedisTemplate::class.java)
        val worlds = mock(WorldStateReadRepository::class.java)
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val members = mock(MemberProfileClient::class.java)
        val state = WorldStateReadEntity(id = 1, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "block_general_create" to 1))
        `when`(worlds.findProcessWorld()).thenReturn(state)
        `when`(generals.countByNpcStateLessThan(2)).thenReturn(0L)
        `when`(cities.existsById(10)).thenReturn(true)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(projection.administrativeCountyIds).thenReturn(setOf(10))
        `when`(projection.bindingsByCityId).thenReturn(mapOf(
            10 to StrategicRouteBinding(10, "node-10", "place-10", "province-10", true)))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(state, emptyList(), bundle))
        val service = GeneralCreationService(receipts, inbox, redis, TestTransactions, worlds, generals,
            cities, artifacts, mock(SpatialStateReadRepository::class.java), members,
            GameApiProcessWorld(1), "fixture")
        val controller = GeneralCreationController(service,
            mock(GeneralCreationResultService::class.java), mock(GeneralCreationCatalog::class.java))
        val request = GeneralCreationRequestDto(1, "92d9244b-6eb5-4f89-971d-d1b1247e0ff6",
            GeneralCreationChoiceDto("CUSTOM", "검증 중 차단", 10, GeneralCreationStatsDto(60, 60, 60, 60, 60),
                "WANGDO", "DISCIPLINE", role = "RETAINER"))

        val response = controller.create(7L, request)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("CREATION_POLICY_UNAVAILABLE",
            assertIs<GeneralCreationErrorResponseDto>(response.body).error.code)
        assertTrue(mockingDetails(receipts).invocations.none { it.method.name == "insertIfAbsent" })
        verifyNoInteractions(inbox, redis, members)
    }

    @Test fun invalidStatsReturn422BeforeReceiptAndInboxWrites() {
        val receipts = mock(CreationReceiptRepository::class.java)
        val inbox = mock(CommandInboxRepository::class.java)
        val redis = mock(StringRedisTemplate::class.java)
        val worlds = mock(WorldStateReadRepository::class.java)
        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val members = mock(MemberProfileClient::class.java)
        val state = WorldStateReadEntity(id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))
        `when`(worlds.findProcessWorld()).thenReturn(state)
        `when`(generals.countByNpcStateLessThan(2)).thenReturn(0L)
        `when`(cities.existsById(10)).thenReturn(true)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(projection.administrativeCountyIds).thenReturn(setOf(10))
        `when`(projection.bindingsByCityId).thenReturn(mapOf(
            10 to StrategicRouteBinding(10, "node-10", "place-10", "province-10", true)))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(state, emptyList(), bundle))

        val service = GeneralCreationService(receipts, inbox, redis, TestTransactions, worlds, generals,
            cities, artifacts, mock(SpatialStateReadRepository::class.java), members,
            GameApiProcessWorld(1), "fixture")
        val controller = GeneralCreationController(service,
            mock(GeneralCreationResultService::class.java), mock(GeneralCreationCatalog::class.java))
        val base = GeneralCreationStatsDto(60, 60, 60, 60, 60)
        val invalid = listOf(
            GeneralCreationStatsDto(19, 85, 65, 65, 66),
            GeneralCreationStatsDto(86, 20, 64, 65, 65),
            base.copy(charm = 59),
            base.copy(charm = 61),
        )
        for ((index, stats) in invalid.withIndex()) {
            val request = GeneralCreationRequestDto(1, "92d9244b-6eb5-4f89-971d-d1b1247e0ff$index",
                GeneralCreationChoiceDto("CUSTOM", "검증 장수", 10, stats, "WANGDO", "DISCIPLINE",
                    role = "RETAINER"))
            val response = controller.create(7L, request)
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode, "stats=$stats")
            assertEquals("INVALID_STATS", assertIs<GeneralCreationErrorResponseDto>(response.body).error.code,
                "stats=$stats")
        }
        verify(artifacts, times(invalid.size)).resolve()
        verify(cities, times(invalid.size)).existsById(10)
        assertTrue(mockingDetails(receipts).invocations.none { it.method.name == "insertIfAbsent" })
        verifyNoInteractions(inbox, redis, members)
    }

    private object TestTransactions : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T? =
            action.doInTransaction(SimpleTransactionStatus())

        override fun executeWithoutResult(action: Consumer<TransactionStatus>) {
            action.accept(SimpleTransactionStatus())
        }
    }
}
