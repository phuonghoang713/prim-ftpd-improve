package org.primftpd.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * A file as seen by the sftp / scp server.
 * <div>
 * This interface has been taken over from Apache SSHD 0.14 (<code>org.apache.sshd.common.file.SshFile</code>)
 * when the ssh library was replaced by a current version, which works on <code>java.nio.file</code>.
 * The implementations for the storage types of this app stay as they are,
 * {@link org.primftpd.sftp.PftpdFileSystemProvider} adapts them to <code>java.nio.file</code>.
 * </div>
 */
public interface SshFile {

    enum Attribute {
        Size,               // long
        Uid,                // int
        Owner,              // String
        Gid,                // int
        Group,              // String
        IsDirectory,        // boolean
        IsRegularFile,      // boolean
        IsSymbolicLink,     // boolean
        Permissions,        // EnumSet<Permission>
        CreationTime,       // long
        LastModifiedTime,   // long
        LastAccessTime,     // long
        NLink               // int
    }

    enum Permission {
        UserRead,
        UserWrite,
        UserExecute,
        GroupRead,
        GroupWrite,
        GroupExecute,
        OthersRead,
        OthersWrite,
        OthersExecute
    }

    String getAbsolutePath();

    String getName();

    Map<Attribute,Object> getAttributes(boolean followLinks) throws IOException;

    void setAttributes(Map<Attribute, Object> attributes) throws IOException;

    Object getAttribute(Attribute attribute, boolean followLinks) throws IOException;

    void setAttribute(Attribute attribute, Object value) throws IOException;

    String readSymbolicLink() throws IOException;

    void createSymbolicLink(SshFile destination) throws IOException;

    String getOwner();

    boolean isDirectory();

    boolean isFile();

    boolean doesExist();

    boolean isReadable();

    boolean isWritable();

    boolean isExecutable();

    boolean isRemovable();

    SshFile getParentFile();

    long getLastModified();

    boolean setLastModified(long time);

    long getSize();

    boolean mkdir();

    boolean delete();

    boolean create() throws IOException;

    void truncate() throws IOException;

    boolean move(SshFile destination);

    List<SshFile> listSshFiles();

    OutputStream createOutputStream(long offset) throws IOException;

    InputStream createInputStream(long offset) throws IOException;

    void handleClose() throws IOException;
}
