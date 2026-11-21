package org.primftpd.sftp;

import org.apache.sshd.common.file.util.BaseFileSystem;
import org.primftpd.filesystem.SshFile;
import org.primftpd.filesystem.SshFileSystemView;

import java.io.IOException;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <code>java.nio.file</code> file system on top of the storage types of this app.
 * <div>
 * Current versions of Apache SSHD access files through <code>java.nio.file</code>. The storage types
 * of this app (plain, root, SAF, read-only SAF, virtual, quick share) are implemented as {@link SshFile}
 * and {@link SshFileSystemView}. This file system translates between the two, one instance per session.
 * </div>
 */
public class PftpdFileSystem extends BaseFileSystem<PftpdPath> {

    static final Set<String> SUPPORTED_VIEWS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("basic", "posix")));

    // a single request of a client makes the server ask several times for the same file
    // (exists? attributes? ...), resolving a path is expensive for SAF and root storage
    private static final long LOOKUP_CACHE_MILLIS = 1000;
    private static final int LOOKUP_CACHE_MAX_ENTRIES = 64;

    private static final class CachedFile {
        final SshFile file;
        final long timestamp;
        CachedFile(SshFile file, long timestamp) {
            this.file = file;
            this.timestamp = timestamp;
        }
    }

    private final SshFileSystemView view;
    private final String homeDir;
    private final Map<String, CachedFile> lookupCache = new ConcurrentHashMap<>();
    private final Map<String, PftpdFileChannel> openWriteChannels = new ConcurrentHashMap<>();
    private volatile boolean open = true;

    public PftpdFileSystem(SshFileSystemView view, String homeDir) {
        super(new PftpdFileSystemProvider());
        this.view = view;
        this.homeDir = homeDir != null && homeDir.startsWith("/") ? homeDir : "/";
        ((PftpdFileSystemProvider) provider()).setFileSystem(this);
    }

    @Override
    protected PftpdPath create(String root, List<String> names) {
        return new PftpdPath(this, root, names);
    }

    @Override
    public PftpdPath getDefaultDir() {
        return getPath(homeDir);
    }

    @Override
    protected boolean hostFsHasWindowsSeparator() {
        return false;
    }

    @Override
    public void close() throws IOException {
        open = false;
        lookupCache.clear();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return SUPPORTED_VIEWS;
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return new UserPrincipalLookupService() {
            @Override
            public UserPrincipal lookupPrincipalByName(String name) {
                return new PftpdFileAttributes.Principal(name);
            }
            @Override
            public GroupPrincipal lookupPrincipalByGroupName(String group) {
                return new PftpdFileAttributes.Principal(group);
            }
        };
    }

    static String toPathString(PftpdPath path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * @return File object for given path, may have been resolved shortly before.
     */
    SshFile resolve(PftpdPath path) {
        SshFile listed = path.takeListedFile();
        String pathString = toPathString(path);
        long now = System.currentTimeMillis();
        if (listed != null) {
            lookupCache.put(pathString, new CachedFile(listed, now));
            return listed;
        }
        CachedFile cached = lookupCache.get(pathString);
        if (cached != null && now - cached.timestamp < LOOKUP_CACHE_MILLIS && now >= cached.timestamp) {
            return cached.file;
        }
        SshFile file = view.getFile(pathString);
        if (lookupCache.size() >= LOOKUP_CACHE_MAX_ENTRIES) {
            lookupCache.clear();
        }
        lookupCache.put(pathString, new CachedFile(file, now));
        return file;
    }

    /**
     * @return New file object for given path, for operations which change the file or keep the object.
     */
    SshFile resolveFresh(PftpdPath path) {
        path.takeListedFile();
        invalidate();
        return view.getFile(toPathString(path));
    }

    /**
     * To be called whenever something has been changed.
     */
    void invalidate() {
        lookupCache.clear();
    }

    void registerWriteChannel(String pathString, PftpdFileChannel channel) {
        openWriteChannels.put(pathString, channel);
    }

    void unregisterWriteChannel(String pathString, PftpdFileChannel channel) {
        openWriteChannels.remove(pathString, channel);
    }

    PftpdFileChannel findWriteChannel(String pathString) {
        return openWriteChannels.get(pathString);
    }
}
