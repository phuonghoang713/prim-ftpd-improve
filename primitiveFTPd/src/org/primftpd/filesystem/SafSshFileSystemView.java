package org.primftpd.filesystem;

import android.net.Uri;

import org.primftpd.services.PftpdService;

import java.util.List;

import androidx.documentfile.provider.DocumentFile;

public class SafSshFileSystemView extends SafFileSystemView<SafSshFile, SshFile> implements SshFileSystemView {

    private final SshSession session;

    public SafSshFileSystemView(PftpdService pftpdService, Uri startUrl, SshSession session) {
        super(pftpdService, startUrl);
        this.session = session;
    }

    @Override
    protected SafSshFile createFile(
            String absPath,
            DocumentFile parentDocumentFile,
            DocumentFile documentFile) {
        return new SafSshFile(this, absPath, parentDocumentFile, documentFile, session);
    }

    @Override
    protected SafSshFile createFile(
            String absPath,
            DocumentFile parentDocumentFile,
            List<String> parentNonexistentDirs,
            String name) {
        return new SafSshFile(this, absPath, parentDocumentFile, parentNonexistentDirs, name, session);
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
