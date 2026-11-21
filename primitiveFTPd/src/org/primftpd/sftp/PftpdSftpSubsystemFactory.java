package org.primftpd.sftp;

import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.server.DirectoryHandle;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemConfigurator;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;

/**
 * Sftp subsystem of Apache SSHD, adapted to {@link PftpdFileSystem}.
 */
public class PftpdSftpSubsystemFactory extends SftpSubsystemFactory {

    /**
     * File access of the library expects channels of real files, {@link PftpdFileSystem} has its own.
     */
    static final SftpFileSystemAccessor ACCESSOR = new SftpFileSystemAccessor() {
        @Override
        public SeekableByteChannel openFile(
                SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file,
                String handle, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
                throws IOException {
            return Files.newByteChannel(file, options);
        }

        @Override
        public void syncFileData(
                SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file, String handle, Channel channel)
                throws IOException {
            if (channel instanceof PftpdFileChannel) {
                ((PftpdFileChannel) channel).flush();
            }
        }

        @Override
        public DirectoryStream<Path> openDirectory(
                SftpSubsystemProxy subsystem, DirectoryHandle dirHandle, Path dir, String handle,
                LinkOption... linkOptions)
                throws IOException {
            return Files.newDirectoryStream(dir);
        }

        @Override
        public String toString() {
            return "PftpdSftpFileSystemAccessor";
        }
    };

    public PftpdSftpSubsystemFactory() {
        setFileSystemAccessor(ACCESSOR);
    }

    @Override
    public Command createSubsystem(ChannelSession channel) throws IOException {
        SftpSubsystem subsystem = new PftpdSftpSubsystem(channel, this);
        GenericUtils.forEach(getRegisteredListeners(), subsystem::addSftpEventListener);
        return subsystem;
    }

    /**
     * Adds the extended command "check-file" as it has been offered by this app before the ssh library
     * was replaced: hash of a complete file, addressed by handle, hash is sent without length prefix.
     * It is used by prim-sync (https://github.com/lmagyar/prim-sync/) through paramiko:
     * <code>remote_file.check('sha256', 0, 0, 0)</code>.
     * The commands of the library ("check-file-handle", "check-file-name") differ in name and reply.
     */
    static class PftpdSftpSubsystem extends SftpSubsystem {

        private static final int HASH_BUFFER_SIZE = 64 * 1024;

        PftpdSftpSubsystem(ChannelSession channel, SftpSubsystemConfigurator configurator) {
            super(channel, configurator);
        }

        @Override
        protected void executeExtendedCommand(Buffer buffer, int id, String extension) throws IOException {
            if (SftpConstants.EXT_CHECK_FILE.equals(extension)) {
                doCheckFile(buffer, id);
            } else {
                super.executeExtendedCommand(buffer, id, extension);
            }
        }

        protected void doCheckFile(Buffer buffer, int id) throws IOException {
            String handle = buffer.getString();
            String hashAlgorithms = buffer.getString();
            long offset = buffer.getLong();
            long length = buffer.getLong();
            int blockSize = buffer.getInt();
            String hashAlgorithm = hashAlgorithms.split(",")[0];
            byte[] hash;
            try {
                if (offset != 0 || length != 0 || blockSize != 0) {
                    throw new UnsupportedOperationException("Only offset=0, length=0, blocksize=0 is supported");
                }
                Handle h = handles.get(handle);
                FileHandle fileHandle = validateHandle(handle, h, FileHandle.class);
                MessageDigest digest = MessageDigest.getInstance(hashAlgorithm);
                ByteBuffer data = ByteBuffer.allocate(HASH_BUFFER_SIZE);
                try (SeekableByteChannel channel = Files.newByteChannel(
                        fileHandle.getFile(), Collections.singleton(StandardOpenOption.READ))) {
                    while (true) {
                        data.clear();
                        int read = channel.read(data);
                        if (read <= 0) {
                            break;
                        }
                        digest.update(data.array(), 0, read);
                    }
                }
                hash = digest.digest();
            } catch (Exception e) {
                sendStatus(prepareReply(buffer), id, e, SftpConstants.SSH_FXP_EXTENDED, extensionArgs(handle, hashAlgorithms));
                return;
            }
            buffer = prepareReply(buffer);
            buffer.putByte((byte) SftpConstants.SSH_FXP_EXTENDED_REPLY);
            buffer.putInt(id);
            buffer.putString(SftpConstants.EXT_CHECK_FILE);
            buffer.putString(hashAlgorithm);
            buffer.putRawBytes(hash);
            send(buffer);
        }

        private static Object[] extensionArgs(String handle, String hashAlgorithms) {
            return new Object[] {SftpConstants.EXT_CHECK_FILE, hashAlgorithms};
        }
    }
}
