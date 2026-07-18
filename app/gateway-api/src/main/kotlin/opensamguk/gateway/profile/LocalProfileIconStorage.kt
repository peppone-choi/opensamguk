package opensamguk.gateway.profile

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Locale
import org.slf4j.LoggerFactory

fun interface ProfileIconNameGenerator {
    fun nextName(): String
}

internal fun interface ProfileIconChannelWriter {
    fun write(channel: SeekableByteChannel, bytes: ByteArray)
}

fun interface ProfileIconRootStreamFactory {
    fun open(root: Path): DirectoryStream<Path>
}

class SecureRandomProfileIconNameGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : ProfileIconNameGenerator {
    override fun nextName(): String = ByteArray(4)
        .also(secureRandom::nextBytes)
        .joinToString(separator = "") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

data class StoredProfileIcon(val fileName: String)

enum class ProfileIconOperationKind {
    UPLOAD,
    RELEASE,
}

data class ProfileIconOperation(
    val operationId: String,
    val kind: ProfileIconOperationKind,
    val oldFileName: String?,
    val newFileName: String?,
)

data class PreparedProfileIcon(
    val stored: StoredProfileIcon,
    val operation: ProfileIconOperation,
)

class ProfileIconStorageException(cause: Throwable? = null) :
    RuntimeException("프로필 아이콘을 저장할 수 없습니다.", cause)

class LocalProfileIconStorage internal constructor(
    storageRoot: Path,
    private val nameGenerator: ProfileIconNameGenerator = SecureRandomProfileIconNameGenerator(),
    private val maxAttempts: Int = 32,
    private val maxStoredBytes: Int = 51_200,
    private val channelWriter: ProfileIconChannelWriter = ProfileIconChannelWriter { channel, bytes ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw ProfileIconStorageException()
            }
        }
    },
    private val rootStreamFactory: ProfileIconRootStreamFactory = ProfileIconRootStreamFactory { root ->
        Files.newDirectoryStream(root)
    },
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val root = canonicalStorageRoot(storageRoot)
    private val operationsDirectory = root.resolve(OPERATIONS_DIRECTORY)
    private val rootFileKey: Any
    private val operationsFileKey: Any
    private val rootAnchor: SecureDirectoryStream<Path>
    private val secureRandom = SecureRandom()
    private val ioLock = Any()
    private var closed = false

    init {
        var openedAnchor: DirectoryStream<Path>? = null
        try {
            validateNoSymbolicLinkAncestors(requireRoot = false)
            Files.createDirectories(root)
            validateNoSymbolicLinkAncestors(requireRoot = true)
            val attributes = rootAttributes()
            if (attributes.isSymbolicLink || !attributes.isDirectory || attributes.fileKey() == null) {
                throw ProfileIconStorageException()
            }
            rootFileKey = requireNotNull(attributes.fileKey())
            openedAnchor = rootStreamFactory.open(root)
            val secureAnchor = openedAnchor as? SecureDirectoryStream<Path>
                ?: throw ProfileIconStorageException()
            val anchoredKey = secureAnchor
                .getFileAttributeView(BasicFileAttributeView::class.java)
                .readAttributes()
                .fileKey()
            if (anchoredKey == null || anchoredKey != rootFileKey) {
                throw ProfileIconStorageException()
            }
            rootAnchor = secureAnchor
            if (!Files.exists(operationsDirectory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(operationsDirectory)
            }
            val operationAttributes = rootAnchor
                .getFileAttributeView(
                    Path.of(OPERATIONS_DIRECTORY),
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                .readAttributes()
            if (
                operationAttributes.isSymbolicLink ||
                !operationAttributes.isDirectory ||
                operationAttributes.fileKey() == null
            ) {
                throw ProfileIconStorageException()
            }
            operationsFileKey = requireNotNull(operationAttributes.fileKey())
        } catch (e: ProfileIconStorageException) {
            openedAnchor?.close()
            throw e
        } catch (e: Exception) {
            openedAnchor?.close()
            throw ProfileIconStorageException(e)
        }
    }

    override fun close() {
        synchronized(ioLock) {
            if (!closed) {
                closed = true
                rootAnchor.close()
            }
        }
    }

    fun prepareUpload(icon: DecodedProfileIcon, oldFileName: String?): PreparedProfileIcon {
        if (icon.bytes.isEmpty() || icon.bytes.size > maxStoredBytes || icon.extension !in EXTENSIONS) {
            throw ProfileIconStorageException()
        }
        if (oldFileName != null && !isManagedFileName(oldFileName)) {
            throw ProfileIconStorageException()
        }
        repeat(maxAttempts) {
            val stem = nameGenerator.nextName()
            if (!STEM.matches(stem)) {
                throw ProfileIconStorageException()
            }
            val fileName = "$stem.${icon.extension}"
            val operation = ProfileIconOperation(
                operationId = nextOperationId(),
                kind = ProfileIconOperationKind.UPLOAD,
                oldFileName = oldFileName,
                newFileName = fileName,
            )
            writeOperation(operation)
            var created = false
            try {
                writeManagedFile(operation, icon.bytes) { created = true }
                return PreparedProfileIcon(StoredProfileIcon(fileName), operation)
            } catch (e: FileAlreadyExistsException) {
                abandonUnpublishedOperation(operation)
            } catch (e: ProfileIconStorageException) {
                cleanupFailedUpload(operation, created)
                throw e
            } catch (e: Exception) {
                cleanupFailedUpload(operation, created)
                throw ProfileIconStorageException(e)
            }
        }
        throw ProfileIconStorageException()
    }

    fun prepareRelease(oldFileName: String): ProfileIconOperation {
        if (!isManagedFileName(oldFileName)) {
            throw ProfileIconStorageException()
        }
        return ProfileIconOperation(
            operationId = nextOperationId(),
            kind = ProfileIconOperationKind.RELEASE,
            oldFileName = oldFileName,
            newFileName = null,
        ).also(::writeOperation)
    }

    internal fun deleteManagedFile(fileName: String) {
        if (!isManagedFileName(fileName)) {
            throw ProfileIconStorageException()
        }
        try {
            withCheckedRoot {
                val attributes = managedAttributes(fileName)
                if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                    throw ProfileIconStorageException()
                }
                deleteManagedRelative(fileName)
            }
        } catch (e: NoSuchFileException) {
            return
        } catch (e: ProfileIconStorageException) {
            throw e
        } catch (e: Exception) {
            throw ProfileIconStorageException(e)
        }
    }

    internal fun managedFileExists(fileName: String): Boolean {
        if (!isManagedFileName(fileName)) {
            throw ProfileIconStorageException()
        }
        return try {
            withCheckedRoot {
                val attributes = managedAttributes(fileName)
                attributes.isRegularFile && !attributes.isSymbolicLink
            }
        } catch (_: NoSuchFileException) {
            false
        }
    }

    internal fun operationStageExists(operation: ProfileIconOperation): Boolean = withCheckedRoot {
        validateOperation(operation)
        withOperationsStream { operations ->
            operationArtifactExists(operations, stageFileName(operation))
        }
    }

    internal fun pendingOperations(): List<ProfileIconOperation> = withCheckedRoot {
        withOperationsStream { operations ->
            operations
                .map { it.fileName.toString() }
                .filter(OPERATION_MARKER::matches)
                .sorted()
                .map { markerName -> readOperation(operations, markerName) }
        }
    }

    internal fun completeOperation(operation: ProfileIconOperation) {
        validateOperation(operation)
        withCheckedRoot {
            withOperationsStream { operations ->
                deleteOperationMarker(operations, operation)
                deleteStageIfPresent(operations, operation)
                forceDirectory(operations)
            }
        }
    }

    internal fun cleanupOrphanOperationArtifacts() {
        withCheckedRoot {
            withOperationsStream { operations ->
                val fileNames = operations
                    .map { it.fileName.toString() }
                    .sorted()
                val markerIds = fileNames
                    .filter(OPERATION_MARKER::matches)
                    .map { it.removeSuffix(".json") }
                    .toSet()
                var deleted = false
                fileNames.forEach { fileName ->
                    val orphanStageId = STAGE_FILE.matchEntire(fileName)?.groupValues?.get(1)
                    val orphanTemporaryId = TEMPORARY_MARKER.matchEntire(fileName)?.groupValues?.get(1)
                    if ((orphanStageId != null && orphanStageId !in markerIds) || orphanTemporaryId != null) {
                        val attributes = operationAttributes(operations, fileName)
                        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > maxStoredBytes) {
                            throw ProfileIconStorageException()
                        }
                        operations.deleteFile(Path.of(fileName))
                        deleted = true
                    }
                }
                if (deleted) {
                    forceDirectory(operations)
                }
            }
        }
    }

    fun isManagedFileName(fileName: String?): Boolean = fileName != null && MANAGED_FILE.matches(fileName)

    private fun writeOperation(operation: ProfileIconOperation) {
        validateOperation(operation)
        val markerName = markerFileName(operation)
        val temporaryName = ".marker-${operation.operationId}.tmp"
        val bytes = OBJECT_MAPPER.writeValueAsBytes(
            linkedMapOf(
                "version" to 1,
                "operation_id" to operation.operationId,
                "kind" to operation.kind.name,
                "old_file" to operation.oldFileName,
                "new_file" to operation.newFileName,
            ),
        )
        try {
            withCheckedRoot {
                withOperationsStream { operations ->
                    newOperationChannel(
                        operations,
                        temporaryName,
                        setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    ).use { channel ->
                        writeFully(channel, bytes)
                        forceFile(channel)
                    }
                    operations.move(Path.of(temporaryName), operations, Path.of(markerName))
                    forceDirectory(operations)
                }
            }
        } catch (e: Exception) {
            try {
                withCheckedRoot {
                    withOperationsStream { operations ->
                        deleteOperationArtifactIfPresent(operations, temporaryName)
                        forceDirectory(operations)
                    }
                }
            } catch (_: Exception) {
                Unit
            }
            throw if (e is ProfileIconStorageException) e else ProfileIconStorageException(e)
        }
    }

    private fun writeManagedFile(
        operation: ProfileIconOperation,
        bytes: ByteArray,
        onCreated: () -> Unit,
    ) {
        val newFileName = requireNotNull(operation.newFileName)
        withCheckedRoot {
            rootAnchor.newByteChannel(
                Path.of(newFileName),
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                onCreated()
                val attributes = managedAttributes(newFileName)
                if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() == null) {
                    throw ProfileIconStorageException()
                }
                channelWriter.write(channel, bytes)
                if (channel.size() != bytes.size.toLong()) {
                    throw ProfileIconStorageException()
                }
                forceFile(channel)
            }
            forceDirectory(rootAnchor)
        }
    }

    private fun cleanupFailedUpload(operation: ProfileIconOperation, created: Boolean) {
        try {
            if (created) {
                operation.newFileName?.let(::deleteManagedFile)
                completeOperation(operation)
            } else {
                withCheckedRoot {
                    withOperationsStream { operations ->
                        deleteStageIfPresent(operations, operation)
                        deleteOperationMarker(operations, operation)
                        forceDirectory(operations)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(
                "profile-icon.cleanup_failed operation_id={} exception={}",
                operation.operationId,
                e.javaClass.simpleName,
            )
        }
    }

    private fun abandonUnpublishedOperation(operation: ProfileIconOperation) {
        withCheckedRoot {
            withOperationsStream { operations ->
                deleteOperationMarker(operations, operation)
                deleteStageIfPresent(operations, operation)
                forceDirectory(operations)
            }
        }
    }

    private fun readOperation(
        operations: SecureDirectoryStream<Path>,
        markerName: String,
    ): ProfileIconOperation {
        val attributes = operationAttributes(operations, markerName)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() !in 1..MAX_MARKER_BYTES) {
            throw ProfileIconStorageException()
        }
        val node = newOperationChannel(
            operations,
            markerName,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.allocate(attributes.size().toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) {
                    throw ProfileIconStorageException()
                }
            }
            OBJECT_MAPPER.readTree(buffer.array())
        }
        val fields = node.fieldNames().asSequence().toSet()
        if (fields != MARKER_FIELDS || node.path("version").asInt(-1) != 1) {
            throw ProfileIconStorageException()
        }
        val operation = ProfileIconOperation(
            operationId = node.path("operation_id").takeIf { it.isTextual }?.asText()
                ?: throw ProfileIconStorageException(),
            kind = node.path("kind").takeIf { it.isTextual }?.asText()?.let {
                runCatching { ProfileIconOperationKind.valueOf(it) }.getOrNull()
            } ?: throw ProfileIconStorageException(),
            oldFileName = node.path("old_file").takeIf { it.isTextual }?.asText(),
            newFileName = node.path("new_file").takeIf { it.isTextual }?.asText(),
        )
        validateOperation(operation)
        if (markerName != markerFileName(operation)) {
            throw ProfileIconStorageException()
        }
        return operation
    }

    private fun validateOperation(operation: ProfileIconOperation) {
        if (!OPERATION_ID.matches(operation.operationId)) {
            throw ProfileIconStorageException()
        }
        if (operation.oldFileName != null && !isManagedFileName(operation.oldFileName)) {
            throw ProfileIconStorageException()
        }
        if (operation.newFileName != null && !isManagedFileName(operation.newFileName)) {
            throw ProfileIconStorageException()
        }
        when (operation.kind) {
            ProfileIconOperationKind.UPLOAD -> {
                if (operation.newFileName == null || operation.newFileName == operation.oldFileName) {
                    throw ProfileIconStorageException()
                }
            }
            ProfileIconOperationKind.RELEASE -> {
                if (operation.oldFileName == null || operation.newFileName != null) {
                    throw ProfileIconStorageException()
                }
            }
        }
    }

    private fun deleteOperationMarker(
        operations: SecureDirectoryStream<Path>,
        operation: ProfileIconOperation,
    ) {
        try {
            operations.deleteFile(Path.of(markerFileName(operation)))
        } catch (_: NoSuchFileException) {
            Unit
        }
    }

    private fun deleteStageIfPresent(
        operations: SecureDirectoryStream<Path>,
        operation: ProfileIconOperation,
    ) {
        val stageName = stageFileName(operation)
        if (!operationArtifactExists(operations, stageName)) {
            return
        }
        val attributes = operationAttributes(operations, stageName)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > maxStoredBytes) {
            throw ProfileIconStorageException()
        }
        operations.deleteFile(Path.of(stageName))
    }

    private fun deleteOperationArtifactIfPresent(
        operations: SecureDirectoryStream<Path>,
        fileName: String,
    ) {
        try {
            operations.deleteFile(Path.of(fileName))
        } catch (_: NoSuchFileException) {
            Unit
        }
    }

    private fun <T> withOperationsStream(block: (SecureDirectoryStream<Path>) -> T): T {
        return rootAnchor.newDirectoryStream(Path.of(OPERATIONS_DIRECTORY), LinkOption.NOFOLLOW_LINKS).use { operations ->
            val attributes = operations
                .getFileAttributeView(BasicFileAttributeView::class.java)
                .readAttributes()
            if (
                attributes.isSymbolicLink ||
                !attributes.isDirectory ||
                attributes.fileKey() != operationsFileKey
            ) {
                throw ProfileIconStorageException()
            }
            block(operations)
        }
    }

    private fun newOperationChannel(
        operations: SecureDirectoryStream<Path>,
        fileName: String,
        options: Set<OpenOption>,
    ): SeekableByteChannel = operations.newByteChannel(Path.of(fileName), options)

    private fun operationAttributes(
        operations: SecureDirectoryStream<Path>,
        fileName: String,
    ): BasicFileAttributes = operations
        .getFileAttributeView(
            Path.of(fileName),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        .readAttributes()

    private fun operationArtifactExists(
        operations: SecureDirectoryStream<Path>,
        fileName: String,
    ): Boolean = try {
        operationAttributes(operations, fileName)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun managedAttributes(fileName: String): BasicFileAttributes = rootAnchor
        .getFileAttributeView(
            Path.of(fileName),
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        .readAttributes()

    private fun deleteManagedRelative(fileName: String) {
        rootAnchor.deleteFile(Path.of(fileName))
    }

    private fun writeFully(channel: SeekableByteChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw ProfileIconStorageException()
            }
        }
    }

    private fun forceFile(channel: SeekableByteChannel) {
        (channel as? FileChannel ?: throw ProfileIconStorageException()).force(true)
    }

    private fun forceDirectory(directory: SecureDirectoryStream<Path>) {
        directory.newByteChannel(
            Path.of("."),
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use(::forceFile)
    }

    private fun nextOperationId(): String = ByteArray(16)
        .also(secureRandom::nextBytes)
        .joinToString(separator = "") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun markerFileName(operation: ProfileIconOperation) = "${operation.operationId}.json"

    private fun stageFileName(operation: ProfileIconOperation) = ".stage-${operation.operationId}"

    private fun <T> withCheckedRoot(block: () -> T): T {
        return synchronized(ioLock) {
            assertRootIdentity()
            val result = block()
            assertRootIdentity()
            result
        }
    }

    private fun assertRootIdentity() {
        if (closed) {
            throw ProfileIconStorageException()
        }
        validateNoSymbolicLinkAncestors(requireRoot = true)
        val attributes = rootAttributes()
        if (attributes.isSymbolicLink || !attributes.isDirectory || attributes.fileKey() != rootFileKey) {
            throw ProfileIconStorageException()
        }
        val anchoredKey = rootAnchor
            .getFileAttributeView(BasicFileAttributeView::class.java)
            .readAttributes()
            .fileKey()
        if (anchoredKey == null || anchoredKey != rootFileKey) {
            throw ProfileIconStorageException()
        }
        val operationAttributes = rootAnchor
            .getFileAttributeView(
                Path.of(OPERATIONS_DIRECTORY),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            .readAttributes()
        if (
            operationAttributes.isSymbolicLink ||
            !operationAttributes.isDirectory ||
            operationAttributes.fileKey() != operationsFileKey
        ) {
            throw ProfileIconStorageException()
        }
    }

    private fun validateNoSymbolicLinkAncestors(requireRoot: Boolean) {
        var current = root.root ?: throw ProfileIconStorageException()
        for (name in root) {
            current = current.resolve(name)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (requireRoot) {
                    throw ProfileIconStorageException()
                }
                continue
            }
            val attributes = Files.readAttributes(
                current,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw ProfileIconStorageException()
            }
        }
    }

    private fun rootAttributes(): BasicFileAttributes = Files.readAttributes(
        root,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    companion object {
        private val STEM = Regex("[0-9a-f]{8}")
        private val MANAGED_FILE = Regex("[0-9a-f]{8}\\.(avif|webp|jpg|png|gif)")
        private val EXTENSIONS = setOf("avif", "webp", "jpg", "png", "gif")
        private val OPERATION_ID = Regex("[0-9a-f]{32}")
        private val OPERATION_MARKER = Regex("[0-9a-f]{32}\\.json")
        private val STAGE_FILE = Regex("\\.stage-([0-9a-f]{32})")
        private val TEMPORARY_MARKER = Regex("\\.marker-([0-9a-f]{32})\\.tmp")
        private val MARKER_FIELDS = setOf("version", "operation_id", "kind", "old_file", "new_file")
        private const val OPERATIONS_DIRECTORY = ".ops"
        private const val MAX_MARKER_BYTES = 4_096L
        private val OBJECT_MAPPER = ObjectMapper()

        private fun canonicalStorageRoot(storageRoot: Path): Path {
            val requested = storageRoot.toAbsolutePath().normalize()
            if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(requested)) {
                    throw ProfileIconStorageException()
                }
                return requested.toRealPath()
            }
            var existing = requested.parent ?: throw ProfileIconStorageException()
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.parent ?: throw ProfileIconStorageException()
            }
            if (Files.isSymbolicLink(existing)) {
                throw ProfileIconStorageException()
            }
            return existing.toRealPath().resolve(existing.relativize(requested)).normalize()
        }
    }
}
