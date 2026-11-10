package org.broad.igv.util.stream;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import htsjdk.samtools.seekablestream.SeekableStream;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.util.ssh.SshSessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Random access over SFTP, used to read tracks straight off a user's server.
 * <p>
 * A naive implementation would issue one SFTP round trip per {@code read}, which is unusable
 * for BAM: htsjdk seeks constantly while painting. Blocks of {@link #BLOCK_SIZE} bytes are
 * therefore fetched whole and kept in a small LRU, so a repaint costs one round trip per
 * megabyte rather than one per few hundred bytes.
 * <p>
 * The connection itself is owned by {@link SshSessionManager}; this stream only opens an SFTP
 * channel on the already authenticated session. It never sees a password.
 */
public class IGVSeekableSftpStream extends SeekableStream {

    private static final Logger log = LogManager.getLogger(IGVSeekableSftpStream.class);

    /** One round trip per block; 1 MB keeps latency amortised without wasting bandwidth. */
    private static final int BLOCK_SIZE = 1 << 20;

    /** Per-stream cache ceiling. 32 blocks is 32 MB, enough for a screenful plus margins. */
    private static final int MAX_BLOCKS = 32;

    /**
     * How many following blocks to pull ahead of the one just read. Sequential access (HDF5
     * dataset reads, BAM/CRAM painting) then hides network latency: while the foreground thread
     * inflates / decodes the current block, the prefetcher fetches the next ones on a channel of
     * its own so it never waits on the reader.
     */
    private static final int PREFETCH_AHEAD = 2;

    /** Single daemon thread shared by every SFTP stream for background block fetches. */
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sftp-prefetch");
        t.setDaemon(true);
        return t;
    });

    /** Separate SFTP channel used only for prefetch reads; JSch channels are not thread-safe. */
    private ChannelSftp prefetchChannel;

    /** Block indices already queued for prefetch, so we never queue one twice. */
    private final Set<Long> prefetching = Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile boolean closed;

    private final String source;
    private final String host;
    private final int port;
    private final String user;
    private final String path;

    private final Map<Long, byte[]> blocks =
            new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                    return size() > MAX_BLOCKS;
                }
            };

    private ChannelSftp channel;
    private long length = -1;
    private long position;

    public IGVSeekableSftpStream(String sftpUrl) throws IOException {
        this.source = sftpUrl;
        final URI uri;
        try {
            uri = new URI(sftpUrl);
        } catch (Exception e) {
            throw new IOException("Invalid sftp URL: " + sftpUrl, e);
        }
        this.host = uri.getHost();
        this.port = uri.getPort() > 0 ? uri.getPort() : 22;
        String userInfo = uri.getUserInfo();
        this.user = (userInfo == null || userInfo.isEmpty())
                ? System.getProperty("user.name") : userInfo;
        String p = uri.getPath();
        if (p == null || p.isEmpty()) {
            throw new IOException("No file path in sftp URL: " + sftpUrl);
        }
        this.path = p;
        this.length = statLength();
        log.info("SFTP stream " + SshSessionManager.key(host, port, user) + ":" + path
                + " (" + length + " bytes)");
    }

    @Override
    public synchronized void seek(long position) {
        this.position = position;
    }

    @Override
    public synchronized long position() {
        return position;
    }

    @Override
    public synchronized long length() {
        return length;
    }

    @Override
    public synchronized boolean eof() {
        return position >= length;
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int len) throws IOException {
        if (offset < 0 || len < 0 || offset + len > buffer.length) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (position >= length) {
            return -1;
        }
        final int wanted = (int) Math.min(len, length - position);
        int done = 0;
        while (done < wanted) {
            final long at = position + done;
            final long blockIndex = at / BLOCK_SIZE;
            final int blockOffset = (int) (at % BLOCK_SIZE);
            byte[] block = block(blockIndex);
            if (block == null || blockOffset >= block.length) {
                break;
            }
            int copy = Math.min(wanted - done, block.length - blockOffset);
            System.arraycopy(block, blockOffset, buffer, offset + done, copy);
            done += copy;
        }
        position += done;
        return done == 0 ? -1 : done;
    }

    /** Fetches one block, or returns the cached copy, then queues the next blocks for prefetch. */
    private byte[] block(long blockIndex) throws IOException {
        synchronized (blocks) {
            byte[] cached = blocks.get(blockIndex);
            if (cached != null) {
                return cached;
            }
        }
        byte[] data = fetchBlock(blockIndex, channel());
        synchronized (blocks) {
            blocks.put(blockIndex, data);
        }
        schedulePrefetch(blockIndex + 1);
        schedulePrefetch(blockIndex + 2);
        return data;
    }

    private byte[] fetchBlock(long blockIndex, ChannelSftp ch) throws IOException {
        final long start = blockIndex * BLOCK_SIZE;
        try (InputStream in = ch.get(path, null, start)) {
            return in.readNBytes(BLOCK_SIZE);
        } catch (Exception e) {
            throw new IOException("SFTP read failed at " + start + " of " + path
                    + ": " + e.getMessage(), e);
        }
    }

    /** Fetch the next block(s) on the dedicated prefetch channel so the reader is never blocked. */
    private void schedulePrefetch(long blockIndex) {
        if (closed || blockIndex * BLOCK_SIZE >= length) {
            return;
        }
        synchronized (prefetching) {
            if (prefetching.contains(blockIndex)) {
                return;
            }
            prefetching.add(blockIndex);
        }
        PREFETCH.submit(() -> {
            try {
                byte[] data = fetchBlock(blockIndex, prefetchChannel());
                synchronized (blocks) {
                    if (!blocks.containsKey(blockIndex)) {
                        blocks.put(blockIndex, data);
                    }
                }
            } catch (Exception e) {
                log.debug("SFTP prefetch of block " + blockIndex + " failed: " + e.getMessage());
            } finally {
                synchronized (prefetching) {
                    prefetching.remove(blockIndex);
                }
            }
        });
    }

    private synchronized ChannelSftp prefetchChannel() throws IOException {
        if (prefetchChannel == null || !prefetchChannel.isConnected()) {
            Session session = SshSessionManager.get(host, port, user);
            if (session == null) {
                throw new IOException("Not connected to " + SshSessionManager.key(host, port, user));
            }
            try {
                prefetchChannel = (ChannelSftp) session.openChannel("sftp");
                prefetchChannel.connect(10_000);
            } catch (Exception e) {
                throw new IOException("Cannot open SFTP prefetch channel: " + e.getMessage(), e);
            }
        }
        return prefetchChannel;
    }

    private long statLength() throws IOException {
        try {
            SftpATTRS attrs = channel().lstat(path);
            return attrs.getSize();
        } catch (Exception e) {
            throw new IOException("Cannot stat " + path + " on " + host + ": "
                    + e.getMessage(), e);
        }
    }

    private ChannelSftp channel() throws IOException {
        if (channel == null || !channel.isConnected()) {
            Session session = SshSessionManager.get(host, port, user);
            if (session == null) {
                throw new IOException("Not connected to " + SshSessionManager.key(host, port, user)
                        + ". Connect first via File > Load from SSH Server...");
            }
            try {
                channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(10_000);
            } catch (Exception e) {
                throw new IOException("Cannot open SFTP channel: " + e.getMessage(), e);
            }
        }
        return channel;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        blocks.clear();
        prefetching.clear();
        if (prefetchChannel != null) {
            try {
                prefetchChannel.disconnect();
            } catch (Exception ignored) {
                // best effort
            }
            prefetchChannel = null;
        }
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
    }

    @Override
    public int read() throws IOException {
        throw new UnsupportedOperationException(
                "read() is not supported on IGVSeekableSftpStream. Must read in blocks.");
    }
}
