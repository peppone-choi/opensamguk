package opensamguk.logic

import opensamguk.common.BuildInfo as CommonBuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun `logic depends on common`() {
        assertEquals("logic", BuildInfo.MODULE)
        assertEquals("common", CommonBuildInfo.MODULE)
    }
}
