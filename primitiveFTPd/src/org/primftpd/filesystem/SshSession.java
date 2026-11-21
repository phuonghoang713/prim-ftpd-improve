package org.primftpd.filesystem;

/**
 * What the file system classes need to know about a ssh session.
 * Keeps them independent of the ssh library.
 */
public interface SshSession {

    String getUsername();

    /**
     * @return Address of client, without port.
     */
    String getClientIp();
}
