package org.primftpd.sftp;

import org.primftpd.filesystem.SshFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * Positional access to a {@link SshFile}, which only offers streams starting at an offset.
 * <div>
 * As long as a client reads or writes sequentially (all usual clients do) a single stream is used.
 * If the position changes the stream is closed and a new one is opened at the new position.
 * </div>
 */
public class PftpdFileChannel implements SeekableByteChannel {

    private static volatile long lastTransferTimestamp = 0;

    /**
     * @return Timestamp of last moment file data has been read or written by any client, 0 if there was no transfer yet.
     */
    public static long getLastTransferTimestamp() {
        return lastTransferTimestamp;
    }

    private final PftpdFileSystem fileSystem;
    private final String pathString;
    private final SshFile file;
    private final boolean readable;
    private final boolean writable;
    private final boolean append;

    private long position;
    private InputStream input;
    private long inputPos;
    private long inputLength;
    private OutputStream output;
    private long outputPos;
    private boolean wroteData;
    private Long lastModifiedToApplyOnClose;
    private boolean open = true;

    PftpdFileChannel(
            PftpdFileSystem fileSystem,
            String pathString,
            SshFile file,
            boolean readable,
            boolean writable,
            boolean append) {
        this.fileSystem = fileSystem;
        this.pathString = pathString;
        this.file = file;
        this.readable = readable;
        this.writable = writable;
        this.append = append;
        if (writable) {
            fileSystem.registerWriteChannel(pathString, this);
        }
    }

    private void checkOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
        checkOpen();
        if (!readable) {
            throw new NonReadableChannelException();
        }
        int requested = dst.remaining();
        if (requested == 0) {
            return 0;
        }
        // clients send read requests beyond end of file, do not open a stream for each of them
        if (input != null && position >= inputLength) {
            return -1;
        }
        if (input != null && position != inputPos) {
            closeQuietly(input);
            input = null;
        }
        if (input == null) {
            input = file.createInputStream(position);
            inputLength = file.getSize();
            inputPos = position;
        }
        if (position >= inputLength) {
            return -1;
        }
        // fill the buffer as far as possible, short reads cause a lot of additional round trips
        // and make clients ask for the rest at positions which are out of sequence
        int total = 0;
        if (dst.hasArray()) {
            byte[] array = dst.array();
            int offset = dst.arrayOffset() + dst.position();
            while (total < requested) {
                int n = input.read(array, offset + total, requested - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            dst.position(dst.position() + total);
        } else {
            byte[] tmp = new byte[Math.min(requested, 64 * 1024)];
            while (total < requested) {
                int n = input.read(tmp, 0, Math.min(tmp.length, requested - total));
                if (n < 0) {
                    break;
                }
                dst.put(tmp, 0, n);
                total += n;
            }
        }
        lastTransferTimestamp = System.currentTimeMillis();
        if (total == 0) {
            return -1;
        }
        inputPos += total;
        position += total;
        return total;
    }

    @Override
    public synchronized int write(ByteBuffer src) throws IOException {
        checkOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        int length = src.remaining();
        if (append) {
            position = output != null ? outputPos : file.getSize();
        }
        if (output != null && position != outputPos) {
            // errors must not be swallowed, data of the stream is written when it is closed
            OutputStream old = output;
            output = null;
            old.close();
        }
        if (output == null) {
            output = file.createOutputStream(position);
            outputPos = position;
        }
        if (src.hasArray()) {
            output.write(src.array(), src.arrayOffset() + src.position(), length);
            src.position(src.position() + length);
        } else {
            byte[] tmp = new byte[length];
            src.get(tmp);
            output.write(tmp, 0, length);
        }
        wroteData = true;
        outputPos += length;
        position += length;
        lastTransferTimestamp = System.currentTimeMillis();
        return length;
    }

    @Override
    public synchronized long position() throws IOException {
        checkOpen();
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        checkOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("negative position");
        }
        // streams are (re-)opened lazily by read() / write()
        this.position = newPosition;
        return this;
    }

    @Override
    public synchronized long size() throws IOException {
        checkOpen();
        long size = file.getSize();
        // data may not have reached the file yet
        return output != null ? Math.max(size, outputPos) : size;
    }

    @Override
    public synchronized SeekableByteChannel truncate(long size) throws IOException {
        checkOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        if (size == 0) {
            if (output != null) {
                OutputStream old = output;
                output = null;
                old.close();
            }
            file.truncate();
            position = 0;
            fileSystem.invalidate();
        } else if (size < size()) {
            throw new IOException("truncating to a size other than 0 is not supported");
        }
        return this;
    }

    /**
     * Writes buffered data to the file.
     */
    synchronized void flush() throws IOException {
        if (output != null) {
            output.flush();
        }
    }

    /**
     * Clients like OpenSSH (put -p) set the modification time before they close the file.
     * Data which is still buffered would be written afterwards and set the time to 'now'.
     * So buffered data is written first and the time is set once more when the file has been closed.
     */
    synchronized void lastModifiedHasBeenSet(long time) throws IOException {
        if (open && writable) {
            flush();
            lastModifiedToApplyOnClose = time;
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        // errors of closing the output stream must not be swallowed: buffered data is written by close(),
        // if that fails (e.g. no space left) the client must not be told that the upload was fine
        IOException error = null;
        OutputStream out = output;
        output = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                error = e;
            }
        }
        closeQuietly(input);
        input = null;
        try {
            file.handleClose();
        } catch (IOException e) {
            if (error == null) {
                error = e;
            }
        }
        if (writable) {
            fileSystem.unregisterWriteChannel(pathString, this);
            if (error == null && lastModifiedToApplyOnClose != null && wroteData) {
                try {
                    file.setLastModified(lastModifiedToApplyOnClose);
                } catch (RuntimeException e) {
                    // has been applied successfully before, never mind
                }
            }
            fileSystem.invalidate();
        }
        if (error != null) {
            throw error;
        }
    }

    private static void closeQuietly(InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (IOException e) {
                // nothing is lost when reading
            }
        }
    }
}
