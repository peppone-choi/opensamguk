package opensamguk.engine.boot

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class D101SeedOnlyVerifiedInstallerTest {
    @Test
    fun `seed only classpath publishes one concrete installer`() {
        val providers = ServiceLoader.load(D101SeedOnlyInstaller::class.java).toList()
        assertEquals(1, providers.size)
        assertEquals(D101SeedOnlyVerifiedInstaller::class.java.name, providers.single().javaClass.name)
    }
}
