package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedSourceUnavailable
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class D101SeedCandidateDataSourceTest {
    @Test
    fun `raw password is preserved and rejected material never appears in output`() {
        val secret = " secret-한글 "
        val oldOut = System.out
        val oldErr = System.err
        val capturedOut = ByteArrayOutputStream()
        val capturedErr = ByteArrayOutputStream()
        synchronized(System::class.java) {
            try {
                System.setOut(PrintStream(capturedOut, true, Charsets.UTF_8))
                System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
                assertEquals(secret, D101SeedCandidateDataSource.decodePassword(secret.toByteArray()))
                for (invalid in listOf("$secret\n", "$secret\r", "$secret\u0000")) {
                    val failure = assertFailsWith<SelectedSourceUnavailable> {
                        D101SeedCandidateDataSource.decodePassword(invalid.toByteArray())
                    }
                    assertFalse(failure.toString().contains(secret))
                }
                assertFailsWith<SelectedSourceUnavailable> {
                    D101SeedCandidateDataSource.decodePassword(byteArrayOf(0xC3.toByte(), 0x28))
                }
            } finally {
                System.setOut(oldOut)
                System.setErr(oldErr)
            }
        }
        assertFalse(capturedOut.toString(Charsets.UTF_8).contains(secret))
        assertFalse(capturedErr.toString(Charsets.UTF_8).contains(secret))
    }
}
