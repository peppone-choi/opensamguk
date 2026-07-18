package opensamguk.gateway.profile

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProfileIconRecoveryIT {
    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var storage: LocalProfileIconStorage

    @Autowired
    lateinit var reconciler: ProfileIconOperationReconciler

    @BeforeEach
    fun resetState() {
        userRepository.deleteAll()
        Files.list(storageRoot).use { paths ->
            paths.filter { it.fileName.toString() != ".ops" }.forEach(Files::delete)
        }
        Files.list(storageRoot.resolve(".ops")).use { paths -> paths.forEach(Files::delete) }
    }

    @Test
    fun `restart recovery observes committed database ownership before deleting old file`() {
        val oldFileName = "0123abcd.png"
        Files.write(storageRoot.resolve(oldFileName), byteArrayOf(9))
        val user = userRepository.saveAndFlush(
            UserEntity(
                username = "committed-recovery",
                password = "encoded",
                picture = oldFileName,
                imgsvr = true,
                profileIconManaged = true,
            ),
        )
        val prepared = storage.prepareUpload(decoded(), oldFileName)
        user.picture = prepared.stored.fileName
        user.profileIconManaged = true
        userRepository.saveAndFlush(user)

        reconciler.reconcilePendingOperations()

        assertTrue(Files.exists(storageRoot.resolve(prepared.stored.fileName)))
        assertFalse(Files.exists(storageRoot.resolve(oldFileName)))
        assertTrue(storage.pendingOperations().isEmpty())
    }

    @Test
    fun `restart recovery observes rolled-back database state before deleting new file`() {
        val oldFileName = "0123abcd.png"
        val oldBytes = byteArrayOf(7, 8, 9)
        Files.write(storageRoot.resolve(oldFileName), oldBytes)
        userRepository.saveAndFlush(
            UserEntity(
                username = "rolled-back-recovery",
                password = "encoded",
                picture = oldFileName,
                imgsvr = true,
                profileIconManaged = true,
            ),
        )
        val prepared = storage.prepareUpload(decoded(), oldFileName)

        reconciler.reconcilePendingOperations()

        assertFalse(Files.exists(storageRoot.resolve(prepared.stored.fileName)))
        assertArrayEquals(oldBytes, Files.readAllBytes(storageRoot.resolve(oldFileName)))
        assertTrue(storage.pendingOperations().isEmpty())
    }

    private fun decoded() = DecodedProfileIcon(
        bytes = byteArrayOf(1, 2, 3),
        extension = "png",
        mediaType = "image/png",
        width = 80,
        height = 80,
    )

    companion object {
        private val storageRoot: Path = Files.createTempDirectory("opensam91-recovery-")

        @JvmStatic
        @DynamicPropertySource
        fun profileIconProperties(registry: DynamicPropertyRegistry) {
            registry.add("profile-icon.storage-root") { storageRoot.toString() }
        }
    }
}
