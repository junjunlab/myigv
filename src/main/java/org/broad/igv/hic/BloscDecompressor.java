package org.broad.igv.hic;

import java.io.IOException;
import java.util.Arrays;

/**
 * Pure-Java decoder for Blosc1 chunks, ported from c-blosc 1.21.6 (blosc/blosc.c).
 * <p>
 * This is what PyTables/HiCExplorer use when a matrix is stored with
 * {@code tables.Filters(complevel=5, complib='blosc')}, so HDF5 readers that have no
 * native blosc support (such as io.jhdf) cannot decode these chunks without it.
 * <p>
 * On-disk layout of a blosc chunk:
 * <pre>
 *   [16 byte header][block start offsets: nblocks x int32][compressed blocks...]
 * </pre>
 * A block with byte-shuffle enabled is split into {@code typesize} equal streams; each
 * stream is prefixed by its compressed length. A stream whose length equals the
 * uncompressed stream size is stored verbatim.
 */
final class BloscDecompressor {

    static final int HEADER_LENGTH = 16;

    // Header flag bits (blosc.h)
    private static final int FLAG_SHUFFLE = 0x1;
    private static final int FLAG_MEMCPYED = 0x2;
    private static final int FLAG_BITSHUFFLE = 0x4;
    private static final int FLAG_DONT_SPLIT = 0x10;

    // blosc.c constants that control block splitting
    private static final int MIN_BUFFER_SIZE = 128;
    private static final int MAX_SPLITS = 16;

    /** Sanity bound for the uncompressed size read from the header (256 MB). */
    private static final int MAX_UNCOMPRESSED_BYTES = 256 * 1024 * 1024;

    private BloscDecompressor() {
        // Utility class
    }

    /**
     * @return the number of uncompressed bytes declared in the header, or -1 if this
     * does not look like a blosc chunk
     */
    static int uncompressedSize(byte[] src) {
        if (src == null || src.length < HEADER_LENGTH) {
            return -1;
        }
        // Version bytes are 1..2 for blosc1; anything else is a different filter.
        int version = src[0] & 0xFF;
        if (version < 1 || version > 2) {
            return -1;
        }
        return readIntLE(src, 4);
    }

    static byte[] decompress(byte[] src) throws IOException {

        if (src == null || src.length < HEADER_LENGTH) {
            throw new IOException("Blosc chunk too small: " + (src == null ? "null" : src.length));
        }

        final int flags = src[2] & 0xFF;
        final int typesize = src[3] & 0xFF;
        final int nbytes = readIntLE(src, 4);
        final int blocksize = readIntLE(src, 8);
        final int cbytes = Math.min(readIntLE(src, 12), src.length);

        if (nbytes <= 0 || blocksize <= 0 || typesize <= 0) {
            throw new IOException("Invalid blosc header: nbytes=" + nbytes
                    + " blocksize=" + blocksize + " typesize=" + typesize);
        }

        // Whole buffer stored verbatim
        if ((flags & FLAG_MEMCPYED) != 0) {
            if (HEADER_LENGTH + nbytes > src.length) {
                throw new IOException("Truncated blosc memcpy chunk");
            }
            return Arrays.copyOfRange(src, HEADER_LENGTH, HEADER_LENGTH + nbytes);
        }

        if ((flags & FLAG_BITSHUFFLE) != 0) {
            throw new IOException("Blosc bitshuffle is not supported");
        }

        final boolean doShuffle = (flags & FLAG_SHUFFLE) != 0 && typesize > 1;
        final boolean dontSplit = (flags & FLAG_DONT_SPLIT) != 0;

        // nbytes comes straight from the file: a corrupt or hostile header would otherwise
        // allocate up to 2 GB before anything else can fail. Hi-C chunks are at most a few
        // hundred kB, so 256 MB is already far beyond any legitimate block.
        if (nbytes <= 0 || nbytes > MAX_UNCOMPRESSED_BYTES || blocksize <= 0) {
            throw new IOException("Implausible blosc header: nbytes=" + nbytes
                    + " blocksize=" + blocksize);
        }

        // Use long arithmetic: nbytes + blocksize - 1 overflows int near Integer.MAX_VALUE,
        // which would make the truncation check below pass for a truncated file.
        final long nblocksL = ((long) nbytes + blocksize - 1) / blocksize;
        final int leftover = nbytes % blocksize;
        if (nblocksL > Integer.MAX_VALUE / 4) {
            throw new IOException("Implausible blosc block count: " + nblocksL);
        }
        final int nblocks = (int) nblocksL;

        if (HEADER_LENGTH + nblocks * 4L > src.length) {
            throw new IOException("Truncated blosc block index");
        }

        final byte[] dest = new byte[nbytes];
        final byte[] tmp = doShuffle ? new byte[blocksize] : null;

        for (int j = 0; j < nblocks; j++) {

            final boolean leftoverBlock = (j == nblocks - 1) && leftover > 0;
            final int bsize = leftoverBlock ? leftover : blocksize;

            int pos = readIntLE(src, HEADER_LENGTH + j * 4);
            if (pos < HEADER_LENGTH || pos > cbytes) {
                throw new IOException("Invalid blosc block offset " + pos);
            }

            int nsplits = (!dontSplit
                    && typesize <= MAX_SPLITS
                    && (bsize / typesize) >= MIN_BUFFER_SIZE
                    && !leftoverBlock) ? typesize : 1;
            final int neblock = bsize / nsplits;

            final byte[] target = doShuffle ? tmp : dest;
            int writePos = doShuffle ? 0 : j * blocksize;

            for (int s = 0; s < nsplits; s++) {
                if (pos < 0 || pos > cbytes - 4) {
                    throw new IOException("Truncated blosc stream header");
                }
                final int streamBytes = readIntLE(src, pos);
                pos += 4;

                if (streamBytes < 0 || streamBytes > cbytes - pos) {
                    throw new IOException("Invalid blosc stream length " + streamBytes);
                }

                if (streamBytes == neblock) {
                    // stored uncompressed
                    System.arraycopy(src, pos, target, writePos, neblock);
                } else {
                    int written = BlosclzDecompressor.decompress(src, pos, streamBytes,
                            target, writePos, neblock);
                    if (written != neblock) {
                        throw new IOException("blosclz decompression failed: expected "
                                + neblock + " bytes, got " + written);
                    }
                }

                pos += streamBytes;
                writePos += neblock;
            }

            if (doShuffle) {
                unshuffle(typesize, bsize, tmp, dest, j * blocksize);
            }
        }

        return dest;
    }

    /**
     * Reverse the byte-shuffle: the compressed stream stores all first bytes, then all
     * second bytes, ... while the consumer expects interleaved elements.
     */
    private static void unshuffle(int typesize, int size, byte[] src, byte[] dest, int destOffset) {
        final int neblock = size / typesize;
        for (int j = 0; j < typesize; j++) {
            int srcPos = j * neblock;
            for (int i = 0; i < neblock; i++) {
                dest[destOffset + i * typesize + j] = src[srcPos + i];
            }
        }
    }

    private static int readIntLE(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }
}
