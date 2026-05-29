package opensamguk.common

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun `module name is common`() {
        assertEquals("common", BuildInfo.MODULE)
    }
}
