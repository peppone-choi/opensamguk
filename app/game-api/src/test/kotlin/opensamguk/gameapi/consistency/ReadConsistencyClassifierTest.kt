package opensamguk.gameapi.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadConsistencyClassifierTest {
    private val classifier = ReadConsistencyClassifier()

    @Test
    fun `classifies command result as read-your-writes`() {
        assertEquals(ReadConsistencyClass.READ_YOUR_WRITES, classifier.classify("/api/command/result/req-1"))
    }

    @Test
    fun `classifies ranking history world log and admin reads as eventual`() {
        assertEquals(ReadConsistencyClass.EVENTUAL, classifier.classify("/api/rankings/generals"))
        assertEquals(ReadConsistencyClass.EVENTUAL, classifier.classify("/api/history"))
        assertEquals(ReadConsistencyClass.EVENTUAL, classifier.classify("/api/world-log"))
        assertEquals(ReadConsistencyClass.EVENTUAL, classifier.classify("/api/admin/game-settings"))
    }

    @Test
    fun `classifies other api reads as authoritative by default`() {
        assertEquals(ReadConsistencyClass.AUTHORITATIVE, classifier.classify("/api/front-info"))
        assertEquals(ReadConsistencyClass.AUTHORITATIVE, classifier.classify("/api/my-page"))
    }
}
