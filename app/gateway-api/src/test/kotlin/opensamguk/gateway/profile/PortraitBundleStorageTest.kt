package opensamguk.gateway.profile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.io.IOException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import opensamguk.infra.read.UserRepository

class PortraitBundleStorageTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `one bundle journal recovers commit then release deletes every variant and original`() {
        val storage = LocalProfileIconStorage(tempDir, rootStreamFactory = secureTestRootStreamFactory())
        val prepared = storage.prepareUpload(PortraitBundle.create(PortraitBundleTest.stripedSource(), PortraitBundleTest.crops()), null)
        assertTrue(prepared.stored.fileName.endsWith(".portrait"))
        val repository = mock(UserRepository::class.java)
        `when`(repository.existsByPictureAndProfileIconManagedTrue(prepared.stored.fileName)).thenReturn(true)
        ProfileIconOperationReconciler(repository, storage).reconcilePendingOperations()
        assertTrue(storage.pendingOperations().isEmpty())
        assertArrayEquals(PortraitBundleTest.stripedSource(), storage.readBundle(prepared.stored.fileName) { PortraitBundle.entry(it, "source") })
        storage.prepareRelease(prepared.stored.fileName)
        `when`(repository.existsByPictureAndProfileIconManagedTrue(prepared.stored.fileName)).thenReturn(false)
        ProfileIconOperationReconciler(repository, storage).reconcilePendingOperations()
        assertFalse(Files.exists(tempDir.resolve(prepared.stored.fileName)))
        assertEquals(listOf(".ops"), Files.list(tempDir).use { it.map { path -> path.fileName.toString() }.toList() })
    }

    @Test
    fun `crash before database ownership removes new bundle while keeping old file`() {
        Files.write(tempDir.resolve("deadbeef.jpg"), byteArrayOf(1))
        val storage = LocalProfileIconStorage(tempDir, rootStreamFactory = secureTestRootStreamFactory())
        val prepared = storage.prepareUpload(PortraitBundle.create(PortraitBundleTest.stripedSource(), PortraitBundleTest.crops()), "deadbeef.jpg")
        ProfileIconOperationReconciler(mock(UserRepository::class.java), storage).reconcilePendingOperations()
        assertFalse(Files.exists(tempDir.resolve(prepared.stored.fileName)))
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(tempDir.resolve("deadbeef.jpg")))
    }

    @Test
    fun `partial bundle write cleans whole archive and journal without source sidecars`() {
        val storage = LocalProfileIconStorage(tempDir, channelWriter = ProfileIconChannelWriter { channel, bytes -> channel.write(ByteBuffer.wrap(bytes, 0, 12)); throw IOException("partial") }, rootStreamFactory = secureTestRootStreamFactory())
        assertThrows<ProfileIconStorageException> { storage.prepareUpload(PortraitBundle.create(PortraitBundleTest.stripedSource(), PortraitBundleTest.crops()), null) }
        assertTrue(storage.pendingOperations().isEmpty())
        assertEquals(listOf(".ops"), Files.list(tempDir).use { it.map { path -> path.fileName.toString() }.toList() })
    }

    @Test
    fun `secure read refuses symlink archive`() {
        val outside = Files.createTempFile("portrait-outside", ".portrait")
        try {
            Files.createSymbolicLink(tempDir.resolve("deadbeef.portrait"), outside)
            val storage = LocalProfileIconStorage(tempDir, rootStreamFactory = secureTestRootStreamFactory())
            assertThrows<ProfileIconStorageException> { storage.readBundle("deadbeef.portrait") { it.readBytes() } }
        } finally { Files.deleteIfExists(outside) }
    }
}
