package opensamguk.gateway.profile

import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView

internal fun secureTestRootStreamFactory(
    replaceOnMove: Boolean = false,
) = ProfileIconRootStreamFactory { root ->
    PathBackedSecureDirectoryStream(root, replaceOnMove)
}

private class PathBackedSecureDirectoryStream(
    private val directory: Path,
    private val replaceOnMove: Boolean,
) : SecureDirectoryStream<Path> {
    private val delegate = Files.newDirectoryStream(directory)

    override fun iterator(): MutableIterator<Path> = delegate.iterator()

    override fun close() = delegate.close()

    override fun newDirectoryStream(
        path: Path,
        vararg options: LinkOption,
    ): SecureDirectoryStream<Path> = PathBackedSecureDirectoryStream(directory.resolve(path), replaceOnMove)

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel = Files.newByteChannel(directory.resolve(path), options, *attrs)

    override fun deleteFile(path: Path) = Files.delete(directory.resolve(path))

    override fun deleteDirectory(path: Path) = Files.delete(directory.resolve(path))

    override fun move(
        srcpath: Path,
        targetdir: SecureDirectoryStream<Path>,
        targetpath: Path,
    ) {
        val target = requireNotNull(targetdir as? PathBackedSecureDirectoryStream)
        val options = if (replaceOnMove) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        Files.move(directory.resolve(srcpath), target.directory.resolve(targetpath), *options)
    }

    override fun <V : FileAttributeView> getFileAttributeView(type: Class<V>): V =
        requireNotNull(Files.getFileAttributeView(directory, type))

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V = requireNotNull(Files.getFileAttributeView(directory.resolve(path), type, *options))
}
