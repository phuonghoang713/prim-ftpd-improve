package org.primftpd.sftp;

import org.apache.sshd.common.file.util.BasePath;
import org.primftpd.filesystem.SshFile;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Path of {@link PftpdFileSystem}.
 */
public class PftpdPath extends BasePath<PftpdPath, PftpdFileSystem> {

    // file object which has been created when the parent directory was listed,
    // saves resolving every entry of a listing again
    private volatile SshFile listedFile;

    public PftpdPath(PftpdFileSystem fileSystem, String root, List<String> names) {
        super(fileSystem, root, names);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        PftpdPath absolute = toAbsolutePath().normalize();
        getFileSystem().provider().checkAccess(absolute);
        return absolute;
    }

    SshFile takeListedFile() {
        SshFile file = listedFile;
        listedFile = null;
        return file;
    }

    void setListedFile(SshFile file) {
        this.listedFile = file;
    }
}
