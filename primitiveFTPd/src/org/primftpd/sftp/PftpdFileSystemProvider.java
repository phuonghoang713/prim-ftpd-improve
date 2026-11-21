package org.primftpd.sftp;

import org.primftpd.filesystem.SshFile;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the operations of <code>java.nio.file</code> to {@link SshFile}, see {@link PftpdFileSystem}.
 */
public class PftpdFileSystemProvider extends FileSystemProvider {

    public static final String SCHEME = "pftpd";

    private PftpdFileSystem fileSystem;

    void setFileSystem(PftpdFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    private PftpdPath toPftpdPath(Path path) {
        if (!(path instanceof PftpdPath)) {
            throw new ProviderMismatchException("not a path of this file system: " + path);
        }
        return (PftpdPath) path;
    }

    private SshFile existing(PftpdPath path) throws NoSuchFileException {
        SshFile file = fileSystem.resolve(path);
        if (file == null || !file.doesExist()) {
            throw new NoSuchFileException(path.toString());
        }
        return file;
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException("file systems are created by PftpdFileSystemFactory");
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystem;
    }

    @Override
    public Path getPath(URI uri) {
        return fileSystem.getPath(uri.getPath());
    }

    @Override
    public SeekableByteChannel newByteChannel(
            Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        PftpdPath pftpdPath = toPftpdPath(path);
        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean write = append || options.contains(StandardOpenOption.WRITE);
        boolean read = options.contains(StandardOpenOption.READ) || !write;
        boolean create = options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW);

        // file object is kept by the channel and may be modified, do not take it from cache
        SshFile file = fileSystem.resolveFresh(pftpdPath);
        if (file == null) {
            throw new NoSuchFileException(path.toString());
        }
        if (file.doesExist()) {
            if (options.contains(StandardOpenOption.CREATE_NEW)) {
                throw new FileAlreadyExistsException(path.toString());
            }
            if (file.isDirectory()) {
                throw new AccessDeniedException(path.toString(), null, "is a directory");
            }
            if (read && !file.isReadable()) {
                throw new AccessDeniedException(path.toString(), null, "can not read");
            }
            if (write && !file.isWritable()) {
                throw new AccessDeniedException(path.toString(), null, "can not write");
            }
            if (write && options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
                file.truncate();
            }
        } else {
            if (!write || !create) {
                throw new NoSuchFileException(path.toString());
            }
            if (!file.isWritable()) {
                throw new AccessDeniedException(path.toString(), null, "can not create");
            }
            if (!file.create()) {
                throw new NoSuchFileException(path.toString());
            }
        }
        fileSystem.invalidate();
        return new PftpdFileChannel(
                fileSystem, PftpdFileSystem.toPathString(pftpdPath), file, read, write, append);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path dir, final DirectoryStream.Filter<? super Path> filter) throws IOException {
        final PftpdPath dirPath = toPftpdPath(dir);
        SshFile dirFile = existing(dirPath);
        if (!dirFile.isDirectory()) {
            throw new NotDirectoryException(dir.toString());
        }
        if (!dirFile.isReadable()) {
            throw new AccessDeniedException(dir.toString());
        }
        List<SshFile> children = dirFile.listSshFiles();
        final List<Path> paths = new ArrayList<>(children != null ? children.size() : 0);
        if (children != null) {
            for (SshFile child : children) {
                String name = child.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                // names of files which have been resolved by path may be complete paths
                int slash = name.lastIndexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                    if (name.isEmpty()) {
                        continue;
                    }
                }
                PftpdPath childPath = dirPath.resolve(name);
                if (filter != null && !filter.accept(childPath)) {
                    continue;
                }
                // listing has all information about the child, no need to resolve it again
                childPath.setListedFile(child);
                paths.add(childPath);
            }
        }
        return new DirectoryStream<Path>() {
            @Override
            public Iterator<Path> iterator() {
                return paths.iterator();
            }
            @Override
            public void close() {
            }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        PftpdPath dirPath = toPftpdPath(dir);
        SshFile file = fileSystem.resolveFresh(dirPath);
        if (file.doesExist()) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!file.isWritable()) {
            throw new AccessDeniedException(dir.toString());
        }
        boolean created = file.mkdir();
        fileSystem.invalidate();
        if (!created) {
            throw new IOException("Error creating dir " + dir);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        PftpdPath pftpdPath = toPftpdPath(path);
        SshFile file = fileSystem.resolveFresh(pftpdPath);
        if (!file.doesExist()) {
            throw new NoSuchFileException(path.toString());
        }
        if (file.isDirectory()) {
            List<SshFile> children = file.listSshFiles();
            if (children != null && !children.isEmpty()) {
                throw new DirectoryNotEmptyException(path.toString());
            }
        }
        boolean deleted = file.delete();
        fileSystem.invalidate();
        if (!deleted) {
            throw new AccessDeniedException(path.toString(), null, "could not delete");
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        throw new UnsupportedOperationException("copy is not supported");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        PftpdPath sourcePath = toPftpdPath(source);
        PftpdPath targetPath = toPftpdPath(target);
        SshFile sourceFile = fileSystem.resolveFresh(sourcePath);
        SshFile targetFile = fileSystem.resolveFresh(targetPath);
        if (!sourceFile.doesExist()) {
            throw new NoSuchFileException(source.toString());
        }
        boolean replace = Arrays.asList(options).contains(StandardCopyOption.REPLACE_EXISTING);
        if (targetFile.doesExist() && !replace) {
            throw new FileAlreadyExistsException(target.toString());
        }
        boolean moved = sourceFile.move(targetFile);
        fileSystem.invalidate();
        if (!moved) {
            throw new IOException("Failed to rename " + source + " to " + target);
        }
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return PftpdFileSystem.toPathString(toPftpdPath(path))
                .equals(PftpdFileSystem.toPathString(toPftpdPath(path2)));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new IOException("file stores are not supported");
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        SshFile file = existing(toPftpdPath(path));
        for (AccessMode mode : modes) {
            switch (mode) {
                case READ:
                    if (!file.isReadable()) {
                        throw new AccessDeniedException(path.toString(), null, "can not read");
                    }
                    break;
                case WRITE:
                    if (!file.isWritable()) {
                        throw new AccessDeniedException(path.toString(), null, "can not write");
                    }
                    break;
                case EXECUTE:
                    if (!file.isExecutable()) {
                        throw new AccessDeniedException(path.toString(), null, "can not execute");
                    }
                    break;
            }
        }
    }

    void setLastModified(PftpdPath path, long millis) throws IOException {
        PftpdFileChannel channel = fileSystem.findWriteChannel(PftpdFileSystem.toPathString(path));
        if (channel != null) {
            channel.lastModifiedHasBeenSet(millis);
        }
        SshFile file = fileSystem.resolveFresh(path);
        if (!file.doesExist()) {
            throw new NoSuchFileException(path.toString());
        }
        // result is ignored as before: not all kinds of storage are able to set the time
        file.setLastModified(millis);
        fileSystem.invalidate();
    }

    private final class AttributeView implements PosixFileAttributeView {
        private final PftpdPath path;

        AttributeView(PftpdPath path) {
            this.path = path;
        }

        @Override
        public String name() {
            return "posix";
        }

        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            return new PftpdFileAttributes(existing(path));
        }

        @Override
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime)
                throws IOException {
            if (lastModifiedTime != null) {
                setLastModified(path, lastModifiedTime.toMillis());
            }
        }

        @Override
        public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
            // not supported by storage of this app, must not be an error:
            // clients set permissions together with times after an upload
            existing(path);
        }

        @Override
        public void setGroup(GroupPrincipal group) throws IOException {
            existing(path);
        }

        @Override
        public UserPrincipal getOwner() throws IOException {
            return readAttributes().owner();
        }

        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            existing(path);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type == BasicFileAttributeView.class
                || type == PosixFileAttributeView.class
                || type == FileOwnerAttributeView.class) {
            return (V) new AttributeView(toPftpdPath(path));
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type == BasicFileAttributes.class || type == PosixFileAttributes.class) {
            return (A) new PftpdFileAttributes(existing(toPftpdPath(path)));
        }
        throw new UnsupportedOperationException("attributes of type " + type.getName() + " are not supported");
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        String view = "basic";
        String names = attributes;
        int colon = attributes.indexOf(':');
        if (colon >= 0) {
            view = attributes.substring(0, colon);
            names = attributes.substring(colon + 1);
        }
        boolean posix = "posix".equals(view) || "unix".equals(view);
        if (!posix && !"basic".equals(view)) {
            throw new UnsupportedOperationException("view '" + view + "' is not supported");
        }
        Map<String, Object> all = new PftpdFileAttributes(existing(toPftpdPath(path))).toMap(posix);
        if ("*".equals(names)) {
            return all;
        }
        List<String> requested = Arrays.asList(names.split(","));
        all.keySet().retainAll(requested);
        return all;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        PftpdPath pftpdPath = toPftpdPath(path);
        String name = attribute;
        int colon = attribute.indexOf(':');
        if (colon >= 0) {
            name = attribute.substring(colon + 1);
        }
        if ("lastModifiedTime".equals(name) && value instanceof FileTime) {
            setLastModified(pftpdPath, ((FileTime) value).toMillis());
        } else {
            // other attributes can not be set, see AttributeView.setPermissions()
            existing(pftpdPath);
        }
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        SshFile file = existing(toPftpdPath(link));
        String target = file.readSymbolicLink();
        if (target == null || target.isEmpty()) {
            throw new NotLinkException(link.toString());
        }
        return fileSystem.getPath(target);
    }
}
