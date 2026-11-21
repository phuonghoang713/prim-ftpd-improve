package org.primftpd.sftp;

import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.primftpd.filesystem.SshFileSystemView;
import org.primftpd.filesystem.SshSession;
import org.primftpd.util.IpAddressProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * Creates a {@link PftpdFileSystem} for every ssh session.
 */
public class PftpdFileSystemFactory implements FileSystemFactory {

    /**
     * Creates the view of the storage type which is configured.
     */
    public interface ViewFactory {
        SshFileSystemView createView(SshSession session);

        /**
         * @return Directory relative paths refer to, in the name space of the view.
         */
        String getHomeDir();
    }

    private final ViewFactory viewFactory;

    public PftpdFileSystemFactory(ViewFactory viewFactory) {
        this.viewFactory = viewFactory;
    }

    @Override
    public Path getUserHomeDir(SessionContext session) throws IOException {
        // only used by the scp shell of the library, which is not offered
        return null;
    }

    @Override
    public FileSystem createFileSystem(SessionContext session) throws IOException {
        SshFileSystemView view = viewFactory.createView(toSshSession(session));
        if (view == null) {
            throw new IOException("no file system for configured storage type");
        }
        return new PftpdFileSystem(view, viewFactory.getHomeDir());
    }

    static SshSession toSshSession(final SessionContext session) {
        final String username = session != null ? session.getUsername() : null;
        String ip = null;
        try {
            SocketAddress remoteAddress = session != null ? session.getRemoteAddress() : null;
            if (remoteAddress instanceof InetSocketAddress && ((InetSocketAddress) remoteAddress).getAddress() != null) {
                ip = ((InetSocketAddress) remoteAddress).getAddress().getHostAddress();
            } else if (remoteAddress != null) {
                ip = IpAddressProvider.extractIp(String.valueOf(remoteAddress));
            }
        } catch (Exception e) {
            // e.g. session already closed
        }
        final String clientIp = ip;
        return new SshSession() {
            @Override
            public String getUsername() {
                return username;
            }
            @Override
            public String getClientIp() {
                return clientIp;
            }
        };
    }
}
