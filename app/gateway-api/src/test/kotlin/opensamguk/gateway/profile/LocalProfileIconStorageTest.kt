package opensamguk.gateway.profile

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class LocalProfileIconStorageTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `stores with canonical server name and retries create-new collision`() {
        Files.write(tempDir.resolve("deadbeef.png"), byteArrayOf(9))
        val names = ArrayDeque(listOf("deadbeef", "cafebabe"))
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { names.removeFirst() },
            rootStreamFactory = secureTestRootStreamFactory(),
        )

        val prepared = storage.prepareUpload(decoded(byteArrayOf(1, 2, 3), "png"), oldFileName = null)

        assertEquals("cafebabe.png", prepared.stored.fileName)
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(tempDir.resolve("deadbeef.png")))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(tempDir.resolve("cafebabe.png")))
        assertEquals(listOf(prepared.operation), storage.pendingOperations())
    }

    @Test
    fun `secure publish collision preserves existing sentinel bytes`() {
        val sentinel = byteArrayOf(9, 8, 7)
        Files.write(tempDir.resolve("deadbeef.png"), sentinel)
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "deadbeef" },
            maxAttempts = 1,
            rootStreamFactory = secureTestRootStreamFactory(replaceOnMove = true),
        )

        assertThrows(ProfileIconStorageException::class.java) {
            storage.prepareUpload(decoded(byteArrayOf(1, 2, 3), "png"), oldFileName = null)
        }

        assertArrayEquals(sentinel, Files.readAllBytes(tempDir.resolve("deadbeef.png")))
    }

    @Test
    fun `unsupported secure directory stream fails before journal mutation`() {
        val configuredRoot = tempDir.resolve("unsupported-root")
        Files.createDirectory(configuredRoot)

        assertThrows(ProfileIconStorageException::class.java) {
            LocalProfileIconStorage(
                configuredRoot,
                rootStreamFactory = ProfileIconRootStreamFactory { root -> Files.newDirectoryStream(root) },
            )
        }

        assertFalse(Files.exists(configuredRoot.resolve(".ops"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `invalid generated name cannot escape configured root`() {
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "../escape" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )

        assertThrows(ProfileIconStorageException::class.java) {
            storage.prepareUpload(decoded(byteArrayOf(1), "png"), oldFileName = null)
        }
        assertFalse(Files.exists(tempDir.parent.resolve("escape.png")))
    }

    @Test
    fun `partial channel write failure removes only the newly created orphan`() {
        val failingWriter = ProfileIconChannelWriter { channel, bytes ->
            assertTrue(
                Files.list(tempDir.resolve(".ops")).use { paths ->
                    paths.anyMatch { it.fileName.toString().endsWith(".json") }
                },
            )
            channel.write(ByteBuffer.wrap(bytes, 0, 1))
            throw IOException("synthetic partial write")
        }
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "deadbeef" },
            channelWriter = failingWriter,
            rootStreamFactory = secureTestRootStreamFactory(),
        )

        assertThrows(ProfileIconStorageException::class.java) {
            storage.prepareUpload(decoded(byteArrayOf(1, 2, 3), "png"), oldFileName = null)
        }
        assertFalse(Files.exists(tempDir.resolve("deadbeef.png")))
        assertTrue(storage.pendingOperations().isEmpty())
    }

    @Test
    fun `failed upload cleanup log contains no throwable path user or filename`() {
        val configuredRoot = tempDir.resolve("private-root")
        Files.createDirectory(configuredRoot)
        val originalRoot = tempDir.resolve("private-root-original")
        val privateUser = "private-user-42"
        val privateFileName = "deadbeef.png"
        val writer = ProfileIconChannelWriter { channel, bytes ->
            channel.write(ByteBuffer.wrap(bytes, 0, 1))
            Files.move(configuredRoot, originalRoot)
            Files.createDirectory(configuredRoot)
            throw IOException("$privateUser ${configuredRoot.toAbsolutePath()} $privateFileName")
        }
        val storage = LocalProfileIconStorage(
            configuredRoot,
            ProfileIconNameGenerator { "deadbeef" },
            channelWriter = writer,
            rootStreamFactory = secureTestRootStreamFactory(),
        )

        ProfileIconLogCapture(LocalProfileIconStorage::class.java).use { capture ->
            assertThrows(ProfileIconStorageException::class.java) {
                storage.prepareUpload(decoded(byteArrayOf(1, 2, 3), "png"), oldFileName = null)
            }

            assertSanitizedProfileIconFailure(
                capture.singleEvent(),
                "ProfileIconStorageException",
                configuredRoot.toAbsolutePath().toString(),
                privateUser,
                privateFileName,
            )
        }
        storage.close()
    }

    @Test
    fun `concurrent operations directory swap fails closed without touching external sentinel`() {
        val configuredRoot = tempDir.resolve("icons")
        Files.createDirectory(configuredRoot)
        val externalDirectory = tempDir.resolve("external-journal")
        Files.createDirectory(externalDirectory)
        val sentinel = externalDirectory.resolve(".marker-${"a".repeat(32)}.tmp")
        val sentinelBytes = byteArrayOf(7, 6, 5)
        Files.write(sentinel, sentinelBytes)
        val originalOperations = configuredRoot.resolve(".ops-original")
        val writer = ProfileIconChannelWriter { channel, bytes ->
            Files.move(configuredRoot.resolve(".ops"), originalOperations)
            Files.createSymbolicLink(configuredRoot.resolve(".ops"), externalDirectory)
            channel.write(ByteBuffer.wrap(bytes))
        }
        val storage = LocalProfileIconStorage(
            configuredRoot,
            ProfileIconNameGenerator { "deadbeef" },
            channelWriter = writer,
            rootStreamFactory = secureTestRootStreamFactory(),
        )

        assertThrows(ProfileIconStorageException::class.java) {
            storage.prepareUpload(decoded(byteArrayOf(1, 2, 3), "png"), oldFileName = null)
        }

        assertArrayEquals(sentinelBytes, Files.readAllBytes(sentinel))
        assertEquals(listOf(sentinel.fileName), Files.list(externalDirectory).use { it.map(Path::getFileName).toList() })
        storage.close()
    }

    @Test
    fun `rejects a storage root below a symbolic-link ancestor without creating directories`() {
        val actualParent = tempDir.resolve("actual")
        Files.createDirectory(actualParent)
        val linkedParent = tempDir.resolve("linked")
        Files.createSymbolicLink(linkedParent, actualParent)

        assertThrows(ProfileIconStorageException::class.java) {
            LocalProfileIconStorage(linkedParent.resolve("icons"))
        }
        assertFalse(Files.exists(actualParent.resolve("icons")))
    }

    @Test
    fun `rejects a root replaced after construction and writes to neither directory`() {
        val configuredRoot = tempDir.resolve("icons")
        Files.createDirectory(configuredRoot)
        val storage = LocalProfileIconStorage(
            configuredRoot,
            ProfileIconNameGenerator { "deadbeef" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )
        val originalRoot = tempDir.resolve("icons-original")
        Files.move(configuredRoot, originalRoot)
        Files.createDirectory(configuredRoot)

        assertThrows(ProfileIconStorageException::class.java) {
            storage.prepareUpload(decoded(byteArrayOf(1, 2, 3), "png"), oldFileName = null)
        }
        assertFalse(Files.exists(configuredRoot.resolve("deadbeef.png")))
        assertFalse(Files.exists(originalRoot.resolve("deadbeef.png")))
        storage.close()
    }

    @Test
    fun `physical delete touches only managed uploaded names`() {
        val managed = tempDir.resolve("0123abcd.webp")
        val shared = tempDir.resolve("wiki-liubei.webp")
        Files.write(managed, byteArrayOf(4, 5, 6))
        Files.write(shared, byteArrayOf(7, 8, 9))
        val storage = LocalProfileIconStorage(tempDir, rootStreamFactory = secureTestRootStreamFactory())

        assertThrows(ProfileIconStorageException::class.java) { storage.deleteManagedFile("default.jpg") }
        assertThrows(ProfileIconStorageException::class.java) { storage.deleteManagedFile("wiki-liubei.webp") }
        assertTrue(Files.exists(shared))

        storage.deleteManagedFile("0123abcd.webp")
        assertFalse(Files.exists(managed))
    }

    @Test
    fun `startup cleanup removes only journal artifacts without a durable marker`() {
        val storage = LocalProfileIconStorage(tempDir, rootStreamFactory = secureTestRootStreamFactory())
        val orphanStage = tempDir.resolve(".ops/.stage-${"a".repeat(32)}")
        val orphanTemporaryMarker = tempDir.resolve(".ops/.marker-${"b".repeat(32)}.tmp")
        Files.write(orphanStage, byteArrayOf(1))
        Files.write(orphanTemporaryMarker, byteArrayOf(2))

        storage.cleanupOrphanOperationArtifacts()

        assertFalse(Files.exists(orphanStage))
        assertFalse(Files.exists(orphanTemporaryMarker))
    }

    @Test
    fun `managed-looking symlink is never read or followed`() {
        val outside = tempDir.parent.resolve("outside-profile-icon")
        Files.write(outside, byteArrayOf(7))
        Files.createSymbolicLink(tempDir.resolve("0123abcd.png"), outside)
        val storage = LocalProfileIconStorage(tempDir, rootStreamFactory = secureTestRootStreamFactory())

        assertThrows(ProfileIconStorageException::class.java) {
            storage.deleteManagedFile("0123abcd.png")
        }
        assertArrayEquals(byteArrayOf(7), Files.readAllBytes(outside))
        assertTrue(Files.isSymbolicLink(tempDir.resolve("0123abcd.png")))
    }

    private fun decoded(bytes: ByteArray, extension: String) = DecodedProfileIcon(
        bytes = bytes,
        extension = extension,
        mediaType = "image/$extension",
        width = 80,
        height = 80,
    )
}
