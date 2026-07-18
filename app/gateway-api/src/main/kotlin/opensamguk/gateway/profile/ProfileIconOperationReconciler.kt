package opensamguk.gateway.profile

import opensamguk.infra.read.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ProfileIconOperationReconciler(
    private val userRepository: UserRepository,
    private val storage: LocalProfileIconStorage,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun reconcilePendingOperations() {
        val operations = try {
            storage.pendingOperations()
        } catch (e: Exception) {
            logger.error(
                "profile-icon.journal_read_failed operation_id={} exception={}",
                UNKNOWN_OPERATION_ID,
                e.javaClass.simpleName,
            )
            return
        }
        operations.forEach { operation ->
            try {
                reconcile(operation)
            } catch (e: Exception) {
                logger.error(
                    "profile-icon.reconciliation_failed operation_id={} exception={}",
                    operation.operationId,
                    e.javaClass.simpleName,
                )
            }
        }
        try {
            storage.cleanupOrphanOperationArtifacts()
        } catch (e: Exception) {
            logger.error(
                "profile-icon.orphan_cleanup_failed operation_id={} exception={}",
                UNKNOWN_OPERATION_ID,
                e.javaClass.simpleName,
            )
        }
    }

    fun reconcile(operation: ProfileIconOperation) {
        if (storage.operationStageExists(operation)) {
            storage.completeOperation(operation)
            return
        }
        when (operation.kind) {
            ProfileIconOperationKind.UPLOAD -> reconcileUpload(operation)
            ProfileIconOperationKind.RELEASE -> reconcileRelease(operation)
        }
        storage.completeOperation(operation)
    }

    private fun reconcileUpload(operation: ProfileIconOperation) {
        val newFileName = requireNotNull(operation.newFileName)
        if (isOwned(newFileName)) {
            if (!storage.managedFileExists(newFileName)) {
                throw ProfileIconStorageException()
            }
            operation.oldFileName
                ?.takeUnless(::isOwned)
                ?.let(storage::deleteManagedFile)
        } else {
            storage.deleteManagedFile(newFileName)
        }
    }

    private fun reconcileRelease(operation: ProfileIconOperation) {
        val oldFileName = requireNotNull(operation.oldFileName)
        if (!isOwned(oldFileName)) {
            storage.deleteManagedFile(oldFileName)
        }
    }

    private fun isOwned(fileName: String): Boolean =
        userRepository.existsByPictureAndProfileIconManagedTrue(fileName)

    private companion object {
        const val UNKNOWN_OPERATION_ID = "unknown"
    }
}
