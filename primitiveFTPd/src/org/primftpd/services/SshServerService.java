package org.primftpd.services;

import android.net.Uri;
import android.os.Looper;
import android.widget.Toast;

import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.usermanager.AnonymousAuthentication;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.UserMetadata;
import org.apache.ftpserver.util.IoUtils;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.helpers.AbstractSession;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.primftpd.R;
import org.primftpd.crypto.HostKeyAlgorithm;
import org.primftpd.events.ClientActionEvent;
import org.primftpd.filesystem.FsSshFileSystemView;
import org.primftpd.filesystem.QuickShareSshFileSystemView;
import org.primftpd.filesystem.RoSafSshFileSystemView;
import org.primftpd.filesystem.RootSshFileSystemView;
import org.primftpd.filesystem.SafSshFileSystemView;
import org.primftpd.filesystem.SshFileSystemView;
import org.primftpd.filesystem.SshSession;
import org.primftpd.filesystem.VirtualFileSystemView;
import org.primftpd.filesystem.VirtualSshFileSystemView;
import org.primftpd.pojo.KeyParser;
import org.primftpd.sftp.PftpdFileSystemFactory;
import org.primftpd.sftp.PftpdSftpSubsystemFactory;
import org.primftpd.util.Defaults;
import org.primftpd.util.RemoteIpChecker;
import org.primftpd.util.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import eu.chainfire.libsuperuser.Shell;

/**
 * Implements a SSH server. Intended to be used for sftp.
 */
public class SshServerService extends AbstractServerService
{
	private SshServer sshServer;

	@Override
	protected ServerServiceHandler createServiceHandler(
		Looper serviceLooper,
		AbstractServerService service)
	{
		return new ServerServiceHandler(serviceLooper, service, getServiceName());
	}

	@Override
	protected Object getServer()
	{
		return sshServer;
	}

	@Override
	protected int getPort()
	{
		return prefsBean.getSecurePort();
	}

	@Override
	protected String getServiceName()
	{
		return "sftp-ssh";
	}

	@Override
	protected ClientActionEvent.Protocol getProtocol() {
		return ClientActionEvent.Protocol.SFTP;
	}

	@Override
	protected void stopServer()
	{
		if (sshServer == null) {
			logger.info("ssh server already null");
			return;
		}
		try {
			List<AbstractSession> activeSessions = sshServer.getActiveSessions();
			if (activeSessions != null) {
				for (AbstractSession session : activeSessions) {
					try {
						session.close(true);
					} catch (Exception e) {
						logger.error("could not end active session", e);
					}
				}
			}
		} catch (Exception e) {
			logger.info("could not get active sessions on server stop. {}, {}",
				e.getClass().getCanonicalName(), e.getMessage());
		}
		try {
			sshServer.stop(true);
		} catch (Exception e) {
			logger.info("exception on server.stop().", e);
		}
		try {
			sshServer.close(true);
		} catch (Exception e) {
			logger.info("exception on server.close().", e);
		}
		sshServer = null;
	}

	@Override
	protected boolean launchServer(final Shell.Interactive shell)
	{
		sshServer = SshServer.setUpDefaultServer();

		// The library offers current algorithms by default (e.g. chacha20-poly1305, aes-gcm, curve25519,
		// strict key exchange). Just do not offer what is left for very old clients.
		sshServer.setCipherFactories(withoutWeakAlgorithms(sshServer.getCipherFactories()));
		sshServer.setMacFactories(withoutWeakAlgorithms(sshServer.getMacFactories()));
		sshServer.setKeyExchangeFactories(withoutWeakAlgorithms(sshServer.getKeyExchangeFactories()));
		sshServer.setPort(prefsBean.getSecurePort());
		String bindIp = getBindIp();
		if (bindIp != null) {
			sshServer.setHost(bindIp);
		}

		// do not depend on lookup of services, use I/O of the library itself
		sshServer.setIoServiceFactoryFactory(new Nio2ServiceFactoryFactory());

		// Refuse connections of clients which are not allowed by ip pattern.
		// Note: must be done when the session is created. The event 'connection accepted' of the library
		// comes earlier, but a connection which is refused there is not closed, the client would hang.
		final PftpdService pftpdService = this;
		sshServer.setSessionFactory(new SessionFactory(sshServer) {
			@Override
			protected ServerSessionImpl doCreateSession(IoSession ioSession) throws Exception {
				SocketAddress remoteAddress = ioSession.getRemoteAddress();
				if (!RemoteIpChecker.ipAllowed(remoteAddress, pftpdService, logger)) {
					throw new IOException("client is not allowed to connect: " + remoteAddress);
				}
				return super.doCreateSession(ioSession);
			}
		});

		// enable scp and sftp
		sshServer.setCommandFactory(new ScpCommandFactory());
		sshServer.setSubsystemFactories(Collections.singletonList(new PftpdSftpSubsystemFactory()));

		// an (authenticated) client must not be able to open handles without limit
		SftpModuleProperties.MAX_OPEN_HANDLES_PER_SESSION.set(sshServer, 1000);

		// PasswordAuthenticator based on android preferences
		if (StringUtils.isNotEmpty(prefsBean.getPassword())
				|| prefsBean.isAnonymousLogin())
		{
			final AndroidPrefsUserManager userManager = new AndroidPrefsUserManager(prefsBean);
			sshServer.setPasswordAuthenticator((username, password, session) -> {
			// address of client is needed to limit failed logins
			UserMetadata userMetadata = new UserMetadata();
			try {
				SocketAddress remoteAddress = session.getClientAddress();
				if (remoteAddress instanceof InetSocketAddress) {
					userMetadata.setInetAddress(((InetSocketAddress) remoteAddress).getAddress());
				}
			} catch (Exception e) {
				logger.debug("could not get address of client", e);
			}
			Authentication authentication = prefsBean.isAnonymousLogin()
				? new AnonymousAuthentication()
				: new UsernamePasswordAuthentication(username, password, userMetadata);
			logger.debug("auth type '{}' for user: {}", authentication.getClass().getName(), username);
			try {
				userManager.authenticate(authentication);
			} catch (AuthenticationFailedException e) {
				logger.debug("AuthenticationFailed", e);
				return false;
			}
			return true;
			});
		} else {
			sshServer.setPasswordAuthenticator(null);
		}

		if (prefsBean.isPubKeyAuth()) {
			String[] keyPaths = new String[] {
					Defaults.pubKeyAuthKeyPath(getApplicationContext()),
					Defaults.PUB_KEY_AUTH_KEY_PATH_OLD,
					Defaults.PUB_KEY_AUTH_KEY_PATH_OLDER,
			};
			final List<PublicKey> pubKeys = new ArrayList<>();
			for (String keyPath : keyPaths) {
				pubKeys.addAll(readKeyAuthKeys(keyPath, true));
			}
			logger.info("loaded {} keys for public key auth", pubKeys.size());
			if (!pubKeys.isEmpty()) {
				sshServer.setPublickeyAuthenticator(new PubKeyAuthenticator(pubKeys));
			} else {
				showToast(getText(R.string.couldNotReadKeyAuthKey), Toast.LENGTH_SHORT);
			}
		}

		// android filesystem view
		sshServer.setFileSystemFactory(new PftpdFileSystemFactory(new PftpdFileSystemFactory.ViewFactory() {
			@Override
			public SshFileSystemView createView(SshSession session) {
				return createFileSystemView(shell, session);
			}

			@Override
			public String getHomeDir() {
				if (quickShareBean != null) {
					return "/";
				}
				String startDir = prefsBean.getStartDir().getAbsolutePath();
				switch (prefsBean.getStorageType()) {
					case PLAIN:
					case ROOT:
						return startDir;
					case VIRTUAL:
						return "/" + VirtualFileSystemView.PREFIX_FS + startDir;
					default:
						return "/";
				}
			}
		}));

		// idle timeout, 0 means none
		long idleSeconds = prefsBean.getIdleTimeout() != null ? prefsBean.getIdleTimeout() : 0;
		CoreModuleProperties.IDLE_TIMEOUT.set(sshServer, Duration.ofSeconds(Math.max(0, idleSeconds)));

		try {
			// XXX preference to enable shell? seems to need root to access /dev/tty

			// read keys here, cannot open private files on server callback
			final List<KeyPair> keys = loadKeys();

			// keys may not be present when started via widget
			if (!keys.isEmpty()) {
				sshServer.setKeyPairProvider(KeyPairProvider.wrap(keys));
				sshServer.start();
				return true;
			}
		} catch (Throwable e) {
			sshServer = null;
			handleServerStartError(e);
		}
		return false;
	}

	private SshFileSystemView createFileSystemView(final Shell.Interactive shell, SshSession session) {
		if (quickShareBean != null) {
			logger.debug("launching server in quick share mode");
			return new QuickShareSshFileSystemView(
					SshServerService.this,
					quickShareBean.getTmpDir(),
					session);
		}
		switch (prefsBean.getStorageType()) {
			case PLAIN:
				return new FsSshFileSystemView(
						SshServerService.this,
						prefsBean.getStartDir(),
						session);
			case ROOT:
				return new RootSshFileSystemView(
						SshServerService.this,
						shell,
						prefsBean.getStartDir(),
						session);
			case SAF:
				return new SafSshFileSystemView(
						SshServerService.this,
						Uri.parse(prefsBean.getSafUrl()),
						session);
			case RO_SAF:
				return new RoSafSshFileSystemView(
						SshServerService.this,
						Uri.parse(prefsBean.getSafUrl()),
						session);
			case VIRTUAL:
				return new VirtualSshFileSystemView(
						SshServerService.this,
						new FsSshFileSystemView(
								SshServerService.this,
								prefsBean.getStartDir(),
								session),
						new RootSshFileSystemView(
								SshServerService.this,
								shell,
								prefsBean.getStartDir(),
								session),
						new SafSshFileSystemView(
								SshServerService.this,
								Uri.parse(prefsBean.getSafUrl()),
								session),
						new RoSafSshFileSystemView(
								SshServerService.this,
								Uri.parse(prefsBean.getSafUrl()),
								session),
						prefsBean.getStartDir(),
						session
				);
		}
		return null;
	}

	/**
	 * CBC mode of ssh allows recovery of plaintext, all usual clients support CTR, GCM or chacha20.
	 * Key exchanges and MACs based on SHA-1 or MD5 are left for clients older than about 2014.
	 */
	private static boolean isWeakAlgorithm(String name) {
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ENGLISH);
		return lower.endsWith("-cbc")
				|| lower.startsWith("arcfour")
				|| lower.startsWith("hmac-md5")
				|| lower.startsWith("hmac-sha1")
				|| (lower.startsWith("diffie-hellman") && lower.endsWith("-sha1"));
	}

	private <T extends NamedResource> List<T> withoutWeakAlgorithms(List<T> factories) {
		if (factories == null) {
			return null;
		}
		List<T> result = new ArrayList<>(factories.size());
		for (T factory : factories) {
			if (factory != null && isWeakAlgorithm(factory.getName())) {
				logger.debug("not offering weak algorithm: {}", factory.getName());
			} else {
				result.add(factory);
			}
		}
		// never end up without any algorithm
		return result.isEmpty() ? factories : result;
	}

	protected List<KeyPair> loadKeys() {
		List<KeyPair> keyPairList = new ArrayList<>(1);
		for (HostKeyAlgorithm hka : HostKeyAlgorithm.values()) {
			FileInputStream pubkeyFis = null;
			FileInputStream privkeyFis = null;
			try {
				pubkeyFis = openFileInput(hka.getFilenamePublicKey());
				PublicKey publicKey = hka.readPublicKey(pubkeyFis);

				privkeyFis = openFileInput(hka.getFilenamePrivateKey());
				PrivateKey privateKey = hka.readPrivateKey(privkeyFis);

				// return key pair
				keyPairList.add(new KeyPair(publicKey, privateKey));
			} catch (Exception e) {
				logger.debug("could not read key: " + e.getClass().getName() + " " + e.getMessage());
			} finally {
				if (pubkeyFis != null) {
					IoUtils.close(pubkeyFis);
				}
				if (privkeyFis != null) {
					IoUtils.close(privkeyFis);
				}
			}
		}
		return keyPairList;
	}

	protected List<PublicKey> readKeyAuthKeys(String path, boolean ignoreErrors)
	{
		List<PublicKey> keys = null;
		FileInputStream fis = null;
		try {
			logger.debug("trying authorized keys file {}", path);
			fis = new FileInputStream(path);
			List<String> parserErrors = new ArrayList<>();
			keys = KeyParser.parsePublicKeys(fis, parserErrors);

			for (String parserError : parserErrors) {
				logger.debug("{}", parserError);
			}

		} catch (Exception e) {
			logger.debug("could not read keys {}, {}", e.getClass().getSimpleName(), e.getMessage());
			if (!ignoreErrors) {
				logger.error("could not read key auth keys", e);
			}
		} finally {
			try {
				if (fis != null) {
					fis.close();
				}
			} catch (IOException e) {
				if (!ignoreErrors) {
					logger.error("could not close key auth keys file", e);
				}
			}
		}
		return keys != null ? keys : Collections.emptyList();
	}
}
