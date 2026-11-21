package org.primftpd.filesystem;

/**
 * File system as seen by one sftp / scp session, see {@link SshFile}.
 */
public interface SshFileSystemView {

    /**
     * @param file Path, relative paths are resolved against the home directory of the view.
     */
    SshFile getFile(String file);

    SshFile getFile(SshFile baseDir, String file);

    SshFileSystemView getNormalizedView();
}
