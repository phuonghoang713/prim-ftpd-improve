package org.primftpd.filesystem;

import android.net.Uri;

import org.primftpd.services.PftpdService;

public class RoSafSshFileSystemView extends RoSafFileSystemView<RoSafSshFile, SshFile> implements SshFileSystemView {

    private final SshSession session;

    public RoSafSshFileSystemView(PftpdService pftpdService, Uri startUrl, SshSession session) {
        super(pftpdService, startUrl);
        this.session = session;
    }

    @Override
    protected RoSafSshFile createFile(String absPath) {
        return new RoSafSshFile(this, absPath, session);
    }

    @Override
    protected RoSafSshFile createFile(String absPath, String docId, boolean exists) {
        return new RoSafSshFile(this, absPath, docId, exists, session);
    }

    @Override
    protected String absolute(String file) {
        return Utils.absoluteOrHome(file, ROOT_PATH);
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
