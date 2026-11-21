package org.primftpd.filesystem;

import org.primftpd.services.PftpdService;

import java.io.File;

public class QuickShareSshFileSystemView extends QuickShareFileSystemView<QuickShareSshFile, SshFile> implements SshFileSystemView {

    private final SshSession session;

    public QuickShareSshFileSystemView(PftpdService pftpdService, File tmpDir, SshSession session) {
        super(pftpdService, tmpDir);
        this.session = session;
    }

    protected QuickShareSshFile createFile() {
        return new QuickShareSshFile(this, session);
    }
    protected QuickShareSshFile createFile(File realFile) {
        return new QuickShareSshFile(this, realFile, session);
    }

    @Override
    public SshFile getFile(SshFile baseDir, String file) {
        logger.trace("getFile(baseDir: {}, file: {})", baseDir.getAbsolutePath(), file);
        // e.g. for scp
        return getFile(baseDir.getAbsolutePath() + "/" + file);
    }

    @Override
    public SshFileSystemView getNormalizedView() {
        logger.trace("getNormalizedView()");
        return this;
    }
}
