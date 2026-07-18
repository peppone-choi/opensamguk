package opensamguk.gateway.profile

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.slf4j.LoggerFactory

internal class ProfileIconLogCapture(type: Class<*>) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(type) as Logger
    private val appender = ListAppender<ILoggingEvent>().also {
        it.start()
        logger.addAppender(it)
    }

    fun singleEvent(): ILoggingEvent = appender.list.single()

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}

internal fun assertSanitizedProfileIconFailure(
    event: ILoggingEvent,
    expectedCategory: String,
    vararg sensitiveValues: String,
) {
    assertNull(event.throwableProxy, "failure logs must not serialize a Throwable or stack trace")
    assertEquals(2, event.argumentArray.size, "only operation id and exception category may be logged")
    assertTrue(
        event.argumentArray[0].toString() == "unknown" ||
            Regex("[0-9a-f]{32}").matches(event.argumentArray[0].toString()),
    )
    assertEquals(expectedCategory, event.argumentArray[1])
    val rendered = buildString {
        append(event.formattedMessage)
        event.argumentArray.forEach { append(it) }
    }
    sensitiveValues.forEach { sensitive ->
        assertFalse(rendered.contains(sensitive), "sensitive log value leaked: $sensitive")
    }
}
