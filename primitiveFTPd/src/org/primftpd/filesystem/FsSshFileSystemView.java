package org.primftpd.filesystem;

import org.primftpd.services.PftpdService;

import java.io.File;

public class FsSshFileSystemView extends FsFileSystemView<FsSshFile, SshFile> implements SshFileSystemView {

	private final File homeDir;
	private final SshSession session;

	public FsSshFileSystemView(PftpdService pftpdService, File homeDir, SshSession session) {
		super(pftpdService);
		this.homeDir = homeDir;
		this.session = session;
	}

	@Override
	protected FsSshFile createFile(File file) {
		return new FsSshFile(this, file, session);
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
