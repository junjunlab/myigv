package org.broad.igv.util.stream;

import htsjdk.samtools.seekablestream.SeekableStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * Adapts IGV's {@link SeekableStream} (used for HTTP, FTP and now SFTP) to the
 * {@link SeekableByteChannel} that jhdf needs to open an HDF5 file. This is what lets
 * HiCExplorer {@code .h5} and cooler {@code .cool}/{@code .mcool} matrices be read straight
 * off a remote server instead of being downloaded first.
 */
public class SeekableStreamChannel implements SeekableByteChannel {

    private final SeekableStream stream;
    private final long size;
    private boolean open = true;
    private final byte[] buffer = new byte[1 << 16];

    public SeekableStreamChannel(SeekableStream stream, String path) throws IOException {
        this.stream = stream;
        this.size = stream.length();
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        int wanted = dst.remaining();
        if (wanted == 0) {
            return 0;
        }
        int n = stream.read(buffer, 0, Math.min(wanted, buffer.length));
        if (n <= 0) {
            return -1;
        }
        dst.put(buffer, 0, n);
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new UnsupportedOperationException("SeekableStreamChannel is read-only");
    }

    @Override
    public long position() {
        try {
            return stream.position();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
        try {
            stream.seek(newPosition);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new UnsupportedOperationException("SeekableStreamChannel is read-only");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
        stream.close();
    }
}
