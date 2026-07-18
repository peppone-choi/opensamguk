package opensamguk.gateway.profile

import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.nio.file.Path

@ExtendWith(MockitoExtension::class)
class ProfileIconOperationReconcilerTest {
    @Mock
    lateinit var userRepository: UserRepository

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `startup reconciliation keeps committed new file and removes unowned old file`() {
        val oldFileName = "0123abcd.png"
        Files.write(tempDir.resolve(oldFileName), byteArrayOf(9))
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "cafebabe" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )
        val prepared = storage.prepareUpload(decoded(), oldFileName)
        `when`(userRepository.existsByPictureAndProfileIconManagedTrue(prepared.stored.fileName)).thenReturn(true)
        `when`(userRepository.existsByPictureAndProfileIconManagedTrue(oldFileName)).thenReturn(false)

        ProfileIconOperationReconciler(userRepository, storage).reconcilePendingOperations()

        assertTrue(Files.exists(tempDir.resolve(prepared.stored.fileName)))
        assertFalse(Files.exists(tempDir.resolve(oldFileName)))
        assertTrue(storage.pendingOperations().isEmpty())
    }

    @Test
    fun `startup reconciliation removes rolled-back new file and preserves old file`() {
        val oldFileName = "0123abcd.png"
        val oldBytes = byteArrayOf(7, 8, 9)
        Files.write(tempDir.resolve(oldFileName), oldBytes)
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "cafebabe" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )
        val prepared = storage.prepareUpload(decoded(), oldFileName)
        `when`(userRepository.existsByPictureAndProfileIconManagedTrue(prepared.stored.fileName)).thenReturn(false)

        ProfileIconOperationReconciler(userRepository, storage).reconcilePendingOperations()

        assertFalse(Files.exists(tempDir.resolve(prepared.stored.fileName)))
        assertArrayEquals(oldBytes, Files.readAllBytes(tempDir.resolve(oldFileName)))
        assertTrue(storage.pendingOperations().isEmpty())
    }

    @Test
    fun `cleanup failure is observable and retains durable marker for retry`() {
        val outside = tempDir.resolve("outside-target")
        Files.write(outside, byteArrayOf(5))
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "cafebabe" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )
        val prepared = storage.prepareUpload(decoded(), oldFileName = null)
        val finalPath = tempDir.resolve(prepared.stored.fileName)
        Files.delete(finalPath)
        Files.createSymbolicLink(finalPath, outside)
        `when`(userRepository.existsByPictureAndProfileIconManagedTrue(prepared.stored.fileName)).thenReturn(false)

        assertThrows(ProfileIconStorageException::class.java) {
            ProfileIconOperationReconciler(userRepository, storage).reconcile(prepared.operation)
        }

        assertArrayEquals(byteArrayOf(5), Files.readAllBytes(outside))
        assertTrue(Files.isSymbolicLink(finalPath))
        assertTrue(storage.pendingOperations().contains(prepared.operation))
    }

    @Test
    fun `reconciliation log contains no throwable path user or filename`() {
        val privateUser = "private-user-42"
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "cafebabe" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )
        val prepared = storage.prepareUpload(decoded(), oldFileName = null)
        `when`(userRepository.existsByPictureAndProfileIconManagedTrue(prepared.stored.fileName)).thenThrow(
            IllegalStateException(
                "$privateUser ${tempDir.toAbsolutePath()} ${prepared.stored.fileName}",
            ),
        )

        ProfileIconLogCapture(ProfileIconOperationReconciler::class.java).use { capture ->
            ProfileIconOperationReconciler(userRepository, storage).reconcilePendingOperations()

            assertSanitizedProfileIconFailure(
                capture.singleEvent(),
                "IllegalStateException",
                tempDir.toAbsolutePath().toString(),
                privateUser,
                prepared.stored.fileName,
            )
        }
    }

    private fun decoded() = DecodedProfileIcon(
        bytes = byteArrayOf(1, 2, 3),
        extension = "png",
        mediaType = "image/png",
        width = 80,
        height = 80,
    )
}
