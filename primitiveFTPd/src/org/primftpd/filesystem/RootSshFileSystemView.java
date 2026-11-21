package org.primftpd.filesystem;

import org.primftpd.services.PftpdService;
import org.primftpd.pojo.LsOutputBean;

import java.io.File;

import eu.chainfire.libsuperuser.Shell;

public class RootSshFileSystemView extends RootFileSystemView<RootSshFile, SshFile> implements SshFileSystemView {

    private final File homeDir;
    private final SshSession session;

    public RootSshFileSystemView(PftpdService pftpdService, Shell.Interactive shell, File homeDir, SshSession session) {
        super(pftpdService, shell);
        this.homeDir = homeDir;
        this.session = session;
    }

    @Override
    protected RootSshFile createFile(String absPath, LsOutputBean bean) {
        return new RootSshFile(this, absPath, bean, session);
    }

    @Override
    protected String absolute(String file) {
        return Utils.absoluteOrHome(file, homeDir.getAbsolutePath());
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
