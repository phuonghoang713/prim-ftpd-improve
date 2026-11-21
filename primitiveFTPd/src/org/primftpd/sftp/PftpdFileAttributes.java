package org.primftpd.sftp;

import org.primftpd.filesystem.SshFile;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Attributes of a {@link SshFile}, read once.
 */
public class PftpdFileAttributes implements PosixFileAttributes {

    static final class Principal implements UserPrincipal, GroupPrincipal {
        private final String name;
        Principal(String name) {
            this.name = name != null ? name : "";
        }
        @Override
        public String getName() {
            return name;
        }
        @Override
        public String toString() {
            return name;
        }
        @Override
        public boolean equals(Object obj) {
            return obj instanceof Principal && ((Principal) obj).name.equals(name);
        }
        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    private final boolean directory;
    private final boolean regularFile;
    private final boolean symbolicLink;
    private final long size;
    private final FileTime lastModified;
    private final Principal owner;
    private final Principal group;
    private final Set<PosixFilePermission> permissions;

    PftpdFileAttributes(SshFile file) throws IOException {
        Map<SshFile.Attribute, Object> attrs = file.getAttributes(false);
        this.directory = Boolean.TRUE.equals(attrs.get(SshFile.Attribute.IsDirectory));
        this.regularFile = Boolean.TRUE.equals(attrs.get(SshFile.Attribute.IsRegularFile));
        this.symbolicLink = Boolean.TRUE.equals(attrs.get(SshFile.Attribute.IsSymbolicLink));
        Object sizeObj = attrs.get(SshFile.Attribute.Size);
        this.size = sizeObj instanceof Number ? ((Number) sizeObj).longValue() : 0;
        Object timeObj = attrs.get(SshFile.Attribute.LastModifiedTime);
        this.lastModified = FileTime.fromMillis(timeObj instanceof Number ? ((Number) timeObj).longValue() : 0);
        Object ownerObj = attrs.get(SshFile.Attribute.Owner);
        this.owner = new Principal(ownerObj != null ? ownerObj.toString() : "owner");
        Object groupObj = attrs.get(SshFile.Attribute.Group);
        this.group = new Principal(groupObj != null ? groupObj.toString() : "group");
        this.permissions = toPosix(attrs.get(SshFile.Attribute.Permissions));
    }

    static Set<PosixFilePermission> toPosix(Object permissions) {
        Set<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);
        if (permissions instanceof Collection) {
            for (Object perm : (Collection<?>) permissions) {
                if (!(perm instanceof SshFile.Permission)) {
                    continue;
                }
                switch ((SshFile.Permission) perm) {
                    case UserRead: result.add(PosixFilePermission.OWNER_READ); break;
                    case UserWrite: result.add(PosixFilePermission.OWNER_WRITE); break;
                    case UserExecute: result.add(PosixFilePermission.OWNER_EXECUTE); break;
                    case GroupRead: result.add(PosixFilePermission.GROUP_READ); break;
                    case GroupWrite: result.add(PosixFilePermission.GROUP_WRITE); break;
                    case GroupExecute: result.add(PosixFilePermission.GROUP_EXECUTE); break;
                    case OthersRead: result.add(PosixFilePermission.OTHERS_READ); break;
                    case OthersWrite: result.add(PosixFilePermission.OTHERS_WRITE); break;
                    case OthersExecute: result.add(PosixFilePermission.OTHERS_EXECUTE); break;
                }
            }
        }
        return result;
    }

    /**
     * @param posix Whether attributes of view 'posix' are to be included.
     * @return Attributes by name as defined by <code>java.nio.file.Files.readAttributes()</code>.
     */
    Map<String, Object> toMap(boolean posix) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("lastModifiedTime", lastModifiedTime());
        map.put("lastAccessTime", lastAccessTime());
        map.put("creationTime", creationTime());
        map.put("size", size());
        map.put("isRegularFile", isRegularFile());
        map.put("isDirectory", isDirectory());
        map.put("isSymbolicLink", isSymbolicLink());
        map.put("isOther", isOther());
        if (posix) {
            map.put("owner", owner());
            map.put("group", group());
            map.put("permissions", permissions());
        }
        return map;
    }

    @Override
    public UserPrincipal owner() {
        return owner;
    }

    @Override
    public GroupPrincipal group() {
        return group;
    }

    @Override
    public Set<PosixFilePermission> permissions() {
        return EnumSet.copyOf(permissions.isEmpty() ? EnumSet.noneOf(PosixFilePermission.class) : permissions);
    }

    @Override
    public FileTime lastModifiedTime() {
        return lastModified;
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModified;
    }

    @Override
    public FileTime creationTime() {
        return lastModified;
    }

    @Override
    public boolean isRegularFile() {
        return regularFile;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean isSymbolicLink() {
        return symbolicLink;
    }

    @Override
    public boolean isOther() {
        return !regularFile && !directory && !symbolicLink;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Object fileKey() {
        return null;
    }
}
