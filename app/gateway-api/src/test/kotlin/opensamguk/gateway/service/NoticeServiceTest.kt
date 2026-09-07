package opensamguk.gateway.service

import opensamguk.gateway.dto.NoticeUpsertRequest
import opensamguk.infra.entity.GatewayNoticeEntity
import opensamguk.infra.read.GatewayNoticeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class NoticeServiceTest {
    @Mock lateinit var repository: GatewayNoticeRepository

    @Test
    fun `create trims title and body and stamps the actor`() {
        val captor = ArgumentCaptor.forClass(GatewayNoticeEntity::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { it.arguments[0] }

        val service = NoticeService(repository)
        val response = service.create(NoticeUpsertRequest(title = "  공개 알파 안내 ", body = " 월드는 초기화될 수 있습니다.\n계정은 보존됩니다. ", pinned = true), actorAccountId = 7)

        val saved = captor.value
        assertEquals("공개 알파 안내", saved.title)
        assertEquals("월드는 초기화될 수 있습니다.\n계정은 보존됩니다.", saved.body)
        assertTrue(saved.pinned)
        assertEquals(7L, saved.createdByAccountId)
        assertEquals("공개 알파 안내", response.title)
        assertFalse(response.deleted)
    }

    @Test
    fun `delete is a soft delete and is idempotent`() {
        val entity = GatewayNoticeEntity(id = 3, title = "t", body = "b")
        `when`(repository.findById(3L)).thenReturn(Optional.of(entity))
        `when`(repository.save(entity)).thenReturn(entity)

        val service = NoticeService(repository)
        val first = service.delete(3)
        val firstStamp = entity.deletedAt
        val second = service.delete(3)

        assertTrue(first.deleted)
        assertTrue(second.deleted)
        assertNotNull(firstStamp)
        assertEquals(firstStamp, entity.deletedAt)
        verify(repository, org.mockito.Mockito.times(2)).findById(3L)
    }

    @Test
    fun `update and pin fail closed when the notice does not exist`() {
        `when`(repository.findById(9L)).thenReturn(Optional.empty())
        val service = NoticeService(repository)
        assertThrows(NoticeNotFoundException::class.java) { service.update(9, NoticeUpsertRequest("t", "b")) }
        assertThrows(NoticeNotFoundException::class.java) { service.setPinned(9, true) }
    }

    @Test
    fun `public feed clamps the limit into the allowed window`() {
        // Kotlin 비-null 파라미터에는 Mockito any()(null)를 넘길 수 없다 — PageRequest 는 equals 가 있어 값으로 스텁한다.
        val row = listOf(GatewayNoticeEntity(id = 1, title = "고정", body = "b", pinned = true, publishedAt = Instant.EPOCH))
        `when`(repository.findFeed(org.springframework.data.domain.PageRequest.of(0, 1))).thenReturn(row)
        `when`(repository.findFeed(org.springframework.data.domain.PageRequest.of(0, NoticeService.MAX_LIMIT))).thenReturn(row)
        val service = NoticeService(repository)
        assertEquals(1, service.publicFeed(0).size)
        assertEquals(1, service.publicFeed(10_000).size)
    }
}
