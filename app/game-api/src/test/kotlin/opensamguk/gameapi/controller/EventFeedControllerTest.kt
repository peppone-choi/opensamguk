package opensamguk.gameapi.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.gameapi.dto.GameEventPage
import opensamguk.gameapi.read.EventFeedReader
import opensamguk.logic.record.EventSection
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.web.server.ResponseStatusException

class EventFeedControllerTest {
    @Test
    fun `city id must be a positive integer when supplied`() {
        val reader = mock(EventFeedReader::class.java)
        val controller = EventFeedController(reader)
        for (cityId in listOf("", "0", "-1", "abc", "2147483648")) {
            assertFailsWith<ResponseStatusException> {
                controller.privateFeed(7, "PERSONAL", null, cityId, 30)
            }
        }
        val expected = GameEventPage(emptyList(), null)
        `when`(reader.privateFeed(7, EventSection.PERSONAL, null, 30, 11)).thenReturn(expected)
        assertEquals(expected, controller.privateFeed(7, "PERSONAL", null, "11", 30))
    }
}
