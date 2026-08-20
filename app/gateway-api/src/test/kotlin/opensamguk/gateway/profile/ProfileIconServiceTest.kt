package opensamguk.gateway.profile

import opensamguk.gateway.dto.ProfileIconRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ProfileIconServiceTest {
    @Mock
    lateinit var userRepository: UserRepository

    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-07-17T01:00:00Z")
    private val zone = ZoneId.of("Asia/Seoul")

    @BeforeEach
    fun startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `database rollback reconciliation removes new file and never deletes prior managed file before completion`() {
        val oldName = "0123abcd.png"
        val oldBytes = TestImageFixtures.image("png", 64)
        Files.write(tempDir.resolve(oldName), oldBytes)
        val user = user(picture = oldName, imgsvr = true)
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenThrow(IllegalStateException("db down"))

        val service = service()
        assertThrows(ProfileIconPersistenceException::class.java) {
            service.upload(details(user), TestImageFixtures.image("png", 80))
        }

        assertTrue(Files.exists(tempDir.resolve(oldName)))
        complete(TransactionSynchronization.STATUS_ROLLED_BACK)

        val remaining = Files.list(tempDir).use { it.toList() }
        assertEquals(listOf(tempDir.resolve(".ops"), tempDir.resolve(oldName)).sorted(), remaining.sorted())
        assertArrayEquals(oldBytes, Files.readAllBytes(tempDir.resolve(oldName)))
    }

    @Test
    fun `transaction completion rollback removes new file and leaves prior managed file untouched`() {
        val oldName = "0123abcd.png"
        val oldBytes = TestImageFixtures.image("png", 64)
        Files.write(tempDir.resolve(oldName), oldBytes)
        val user = user(picture = oldName, imgsvr = true)
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        service().upload(details(user), TestImageFixtures.image("png", 80))
        complete(TransactionSynchronization.STATUS_ROLLED_BACK)

        val remaining = Files.list(tempDir).use { it.toList() }
        assertEquals(listOf(tempDir.resolve(".ops"), tempDir.resolve(oldName)).sorted(), remaining.sorted())
        assertArrayEquals(oldBytes, Files.readAllBytes(tempDir.resolve(oldName)))
    }

    @Test
    fun `storage failure leaves database mutation untouched`() {
        val user = user()
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        val storage = LocalProfileIconStorage(
            tempDir,
            ProfileIconNameGenerator { "../escape" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )

        assertThrows(ProfileIconStorageException::class.java) {
            service(storage = storage).upload(details(user), TestImageFixtures.image("png", 80))
        }

        assertEquals("default.jpg", user.picture)
        assertFalse(user.imgsvr)
        verify(userRepository, never()).saveAndFlush(any(UserEntity::class.java))
    }

    @Test
    fun `transaction cleanup log contains no throwable path user or filename`() {
        val configuredRoot = tempDir.resolve("private-root")
        Files.createDirectory(configuredRoot)
        val originalRoot = tempDir.resolve("private-root-original")
        val storage = LocalProfileIconStorage(
            configuredRoot,
            ProfileIconNameGenerator { "deadbeef" },
            rootStreamFactory = secureTestRootStreamFactory(),
        )
        val privateUser = "private-user-42"
        val user = user(username = privateUser)
        `when`(userRepository.findByUsernameForUpdate(privateUser)).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }
        service(storage = storage).upload(details(user), TestImageFixtures.image("png", 80))
        val privateFileName = requireNotNull(user.picture)
        Files.move(configuredRoot, originalRoot)
        Files.createDirectory(configuredRoot)

        ProfileIconLogCapture(ProfileIconService::class.java).use { capture ->
            complete(TransactionSynchronization.STATUS_COMMITTED)

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
    fun `same Seoul day rejects delete after successful upload`() {
        val user = user()
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }
        val service = service()

        service.upload(details(user), TestImageFixtures.image("png", 80))
        `when`(userRepository.existsByPictureAndProfileIconManagedTrue(requireNotNull(user.picture))).thenReturn(true)
        complete(TransactionSynchronization.STATUS_COMMITTED)
        TransactionSynchronizationManager.initSynchronization()

        assertThrows(ProfileIconChangedTodayException::class.java) { service.delete(details(user)) }
    }

    @Test
    fun `new Seoul calendar day allows a change across the UTC boundary`() {
        val user = user().apply {
            profileIconChangedAt = Instant.parse("2026-07-17T14:59:00Z")
        }
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }
        val nextSeoulDay = Clock.fixed(Instant.parse("2026-07-17T15:01:00Z"), zone)

        val response = service(clock = nextSeoulDay).upload(details(user), TestImageFixtures.image("png"))

        assertEquals(1, response.imageServer)
    }

    @Test
    fun `delete never touches default shared or arbitrary file`() {
        val sharedFile = tempDir.resolve("wiki-liu-bei.png")
        Files.write(sharedFile, byteArrayOf(1, 2, 3))
        val user = user(picture = "wiki-liu-bei.png", imgsvr = false)
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))

        assertThrows(InvalidProfileIconException::class.java) { service().delete(details(user)) }

        assertTrue(Files.exists(sharedFile))
        verify(userRepository, never()).saveAndFlush(any(UserEntity::class.java))
    }

    @Test
    fun `non-owner duplicate filename delete resets only its database state and preserves physical file`() {
        val duplicateName = "0123abcd.png"
        val bytes = TestImageFixtures.image("png", 64)
        Files.write(tempDir.resolve(duplicateName), bytes)
        val nonOwner = user(picture = duplicateName, imgsvr = true, profileIconManaged = false)
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(nonOwner))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }

        service().delete(details(nonOwner))

        assertEquals(ProfileIconService.DEFAULT_ICON, nonOwner.picture)
        assertFalse(nonOwner.imgsvr)
        assertFalse(nonOwner.profileIconManaged)
        assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve(duplicateName)))
        // OPENSAM-94: 계정 picture 리셋은 sync-fanout 동기화를 등록하지만 물리 파일은 건드리지 않는다.
        // 모든 동기화를 완료해도 비소유 공유 파일이 보존됨을 단언한다(reconciler 파일-op 미등록).
        complete(TransactionSynchronization.STATUS_COMMITTED)
        assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve(duplicateName)))
    }

    @Test
    fun `json selection accepts catalog id and rejects arbitrary paths and upload names`() {
        val user = user()
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.getArgument(0) }
        val service = service(catalog = SharedProfileIconCatalog.fromClasspath("profile-icons/shared-manifest.json"))

        val selected = service.selectShared(details(user), ProfileIconRequest("1001", 0))
        assertEquals("1001.jpg", selected.picture)
        assertEquals(0, selected.imageServer)
        complete(TransactionSynchronization.STATUS_COMMITTED)

        val other = user(username = "other")
        TransactionSynchronizationManager.initSynchronization()
        assertThrows(InvalidProfileIconException::class.java) {
            service.selectShared(details(other), ProfileIconRequest("../escape.png", 0))
        }
        assertThrows(InvalidProfileIconException::class.java) {
            service.selectShared(details(other), ProfileIconRequest("0123abcd.png", 0))
        }
    }

    private fun service(
        storage: LocalProfileIconStorage = LocalProfileIconStorage(
            tempDir,
            rootStreamFactory = secureTestRootStreamFactory(),
        ),
        catalog: SharedProfileIconCatalog = SharedProfileIconCatalog.fromClasspath("profile-icons/shared-manifest.json"),
        clock: Clock = Clock.fixed(now, zone),
    ): ProfileIconService {
        val reconciler = ProfileIconOperationReconciler(userRepository, storage)
        // 빈 ServerRegistry 기반 publisher — all()이 비어 publish()가 no-op(afterCommit도 이 테스트의
        // 수동 initSynchronization에서는 commit 이벤트가 없어 호출되지 않는다).
        val emptyRegistry = org.mockito.Mockito.mock(opensamguk.gateway.service.ServerRegistry::class.java)
        lenient().`when`(emptyRegistry.all()).thenReturn(emptyList())
        val syncPublisher = opensamguk.gateway.service.ProfileIconSyncPublisher(
            emptyRegistry,
            "",
        )
        return ProfileIconService(
            userRepository = userRepository,
            decoder = ProfileIconDecoder(51_200),
            storage = storage,
            catalog = catalog,
            reconciler = reconciler,
            clock = clock,
            zoneId = zone,
            syncPublisher = syncPublisher,
        )
    }

    private fun user(
        username: String = "tester",
        picture: String = "default.jpg",
        imgsvr: Boolean = false,
        profileIconManaged: Boolean = imgsvr,
    ) = UserEntity(
        id = if (username == "tester") 7 else 8,
        username = username,
        password = "encoded",
        picture = picture,
        imgsvr = imgsvr,
        profileIconManaged = profileIconManaged,
    )

    private fun details(user: UserEntity) = CustomUserDetails(user)

    private fun complete(status: Int) {
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(status) }
        TransactionSynchronizationManager.clearSynchronization()
    }
}
