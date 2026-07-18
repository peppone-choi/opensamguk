package opensamguk.gateway.profile

import opensamguk.gateway.dto.ProfileIconRequest
import opensamguk.gateway.dto.UserResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class ProfileIconChangedTodayException : RuntimeException("프로필 아이콘은 하루에 한 번만 변경할 수 있습니다.")

class ProfileIconPersistenceException(cause: Throwable? = null) :
    RuntimeException("프로필 아이콘 변경을 완료할 수 없습니다.", cause)

class ProfileIconPayloadTooLargeException : RuntimeException("프로필 아이콘은 50KB 이하여야 합니다.")

@Service
class ProfileIconService(
    private val userRepository: UserRepository,
    private val decoder: ProfileIconDecoder,
    private val storage: LocalProfileIconStorage,
    private val catalog: SharedProfileIconCatalog,
    private val reconciler: ProfileIconOperationReconciler,
    private val clock: Clock,
    private val zoneId: ZoneId,
    // OPENSAM-94 — canonical 전콘 변경이 commit된 뒤 각 게임 서버로 typed sync를 fan-out한다.
    private val syncPublisher: opensamguk.gateway.service.ProfileIconSyncPublisher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun upload(userDetails: CustomUserDetails, source: ByteArray): UserResponse {
        val decoded = decoder.decode(source)
        val user = findLocked(userDetails)
        val changedAt = clock.instant()
        assertChangeAllowed(user, changedAt)

        requireTransactionSynchronization()
        val prepared = storage.prepareUpload(decoded, ownedCurrentFile(user))
        return persist(
            user = user,
            picture = prepared.stored.fileName,
            imgsvr = true,
            profileIconManaged = true,
            changedAt = changedAt,
            operation = prepared.operation,
        )
    }

    @Transactional
    fun selectShared(userDetails: CustomUserDetails, request: ProfileIconRequest): UserResponse {
        if (request.imgsvr != 0) {
            throw InvalidProfileIconException()
        }
        val entry = catalog.resolveSharedIcon(request.picture?.trim())
            ?: throw InvalidProfileIconException()
        val user = findLocked(userDetails)
        val changedAt = clock.instant()
        assertChangeAllowed(user, changedAt)
        requireTransactionSynchronization()
        val operation = ownedCurrentFile(user)?.let(storage::prepareRelease)
        return persist(
            user = user,
            picture = entry.canonicalFilename,
            imgsvr = false,
            profileIconManaged = false,
            changedAt = changedAt,
            operation = operation,
        )
    }

    @Transactional
    fun delete(userDetails: CustomUserDetails) {
        val user = findLocked(userDetails)
        val changedAt = clock.instant()
        assertChangeAllowed(user, changedAt)
        if (!user.imgsvr || !storage.isManagedFileName(user.picture)) {
            throw InvalidProfileIconException("삭제할 업로드 프로필 아이콘이 없습니다.")
        }
        requireTransactionSynchronization()
        val operation = ownedCurrentFile(user)?.let(storage::prepareRelease)
        persist(
            user = user,
            picture = DEFAULT_ICON,
            imgsvr = false,
            profileIconManaged = false,
            changedAt = changedAt,
            operation = operation,
        )
    }

    private fun findLocked(userDetails: CustomUserDetails): UserEntity =
        userRepository.findByUsernameForUpdate(userDetails.username)
            .orElseThrow { InvalidProfileIconException("사용자를 찾을 수 없습니다.") }

    private fun assertChangeAllowed(user: UserEntity, changedAt: java.time.Instant) {
        val previousDate = user.profileIconChangedAt?.atZone(zoneId)?.toLocalDate()
        if (previousDate == changedAt.atZone(zoneId).toLocalDate()) {
            throw ProfileIconChangedTodayException()
        }
    }

    private fun ownedCurrentFile(user: UserEntity): String? = user.picture
        ?.takeIf { user.imgsvr && user.profileIconManaged && storage.isManagedFileName(it) }

    private fun requireTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw ProfileIconPersistenceException()
        }
    }

    private fun persist(
        user: UserEntity,
        picture: String,
        imgsvr: Boolean,
        profileIconManaged: Boolean,
        changedAt: java.time.Instant,
        operation: ProfileIconOperation?,
    ): UserResponse {
        val snapshot = UserSnapshot(
            user.picture,
            user.imgsvr,
            user.profileIconManaged,
            user.profileIconChangedAt,
            user.updatedAt,
        )
        try {
            if (operation != null) {
                TransactionSynchronizationManager.registerSynchronization(
                    OperationCompletion(operation),
                )
            }
            user.picture = picture
            user.imgsvr = imgsvr
            user.profileIconManaged = profileIconManaged
            user.profileIconChangedAt = changedAt
            user.updatedAt = LocalDateTime.ofInstant(changedAt, zoneId)
            userRepository.saveAndFlush(user)
            // OPENSAM-94 — commit 성공 시에만 게임 서버로 sync fan-out(rollback → sync 0회, criterion 1).
            // grade는 계정 authoritative 원값을 그대로 싣고, 게임 서버가 eligibility를 재평가한다.
            TransactionSynchronizationManager.registerSynchronization(
                ProfileIconSyncCompletion(
                    userId = user.id,
                    picture = picture,
                    imgsvr = if (imgsvr) 1 else 0,
                    grade = user.grade ?: 0,
                ),
            )
            return user.toResponse()
        } catch (e: Exception) {
            user.picture = snapshot.picture
            user.imgsvr = snapshot.imgsvr
            user.profileIconManaged = snapshot.profileIconManaged
            user.profileIconChangedAt = snapshot.changedAt
            user.updatedAt = snapshot.updatedAt
            throw ProfileIconPersistenceException(e)
        }
    }

    private fun UserEntity.toResponse(): UserResponse = UserResponse(
        id = id,
        username = username,
        email = email,
        nickname = nickname,
        role = role,
        picture = picture,
        imageServer = if (imgsvr) 1 else 0,
    )

    private data class UserSnapshot(
        val picture: String?,
        val imgsvr: Boolean,
        val profileIconManaged: Boolean,
        val changedAt: java.time.Instant?,
        val updatedAt: LocalDateTime,
    )

    private inner class OperationCompletion(
        private val operation: ProfileIconOperation,
    ) : TransactionSynchronization {
        override fun afterCompletion(status: Int) {
            try {
                reconciler.reconcile(operation)
            } catch (e: Exception) {
                logger.error(
                    "profile-icon.transaction_cleanup_failed operation_id={} exception={}",
                    operation.operationId,
                    e.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * OPENSAM-94 — commit 후에만 실행되는 typed sync fan-out. [afterCommit]은 rollback 시 호출되지
     * 않으므로 실패 트랜잭션은 sync를 발행하지 않는다(criterion 1). 발행은 best-effort — 예외를 삼켜
     * 이미 commit된 계정 mutation에 영향을 주지 않는다(PII 없이 관측).
     */
    private inner class ProfileIconSyncCompletion(
        private val userId: Long,
        private val picture: String,
        private val imgsvr: Int,
        private val grade: Int,
    ) : TransactionSynchronization {
        override fun afterCommit() {
            try {
                syncPublisher.publish(userId = userId, picture = picture, imgsvr = imgsvr, grade = grade)
            } catch (e: Exception) {
                logger.warn("profile-icon.sync publish failed exception={}", e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val DEFAULT_ICON = "default.jpg"
    }
}
