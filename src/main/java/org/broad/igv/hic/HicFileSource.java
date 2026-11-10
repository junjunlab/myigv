package org.broad.igv.hic;

import htsjdk.samtools.seekablestream.SeekableStream;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for Juicer ".hic" contact matrices (Aiden Lab format, version 7+).
 * <p>
 * Hi-C files of this kind are routinely hundreds of megabytes to several gigabytes, so
 * the matrix is never loaded as a whole: blocks are inflated on demand and a small LRU
 * cache keeps the blocks that the current view touches.
 * <p>
 * Layout summary:
 * <pre>
 *   "HIC\0" | version:int32 | masterIndex:int64 | genome:cstr
 *   nAttributes:int32, [key:cstr, value:cstr]*
 *   nChromosomes:int32, [name:cstr, length:int32]*
 *   nBpResolutions:int32, int32*
 *   ... at masterIndex: nBytes, nEntries, [key:cstr, pos:int64, size:int32]*
 * </pre>
 * Each chromosome pair then stores, per resolution, a block table whose entries are
 * {@code blockId, filePos, size}; every block is a zlib stream of sparse contacts.
 */
public class HicFileSource implements HiCSource {

    private static final Logger log = LogManager.getLogger(HicFileSource.class);

    /** Rough per-entry overhead of the HashMap holding one block's contacts. */
    private static final int BYTES_PER_RECORD = 48;
    private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;

    /** One warning per file is enough; getValue is called once per pixel. */
    private boolean blockReadFailureLogged;

    /**
     * Upper bound on how many bins may span the window. Beyond this the matrix needs
     * hundreds of thousands of cells for one screen, which costs seconds: at 1 kb bins a
     * 1 Mb window took 4.1 s to inflate, versus 0.26 s at 5 kb.
     */

    /**
     * Finest bin size the automatic mode will pick. Below 5 kb a single .hic block holds so
     * many contacts that inflating it costs seconds (measured: 4.1 s for a 200 kb window at
     * 1 kb, 0.26 s at 5 kb), so finer units stay an explicit choice in the menu.
     */

    private final SeekableStream stream;

    /**
     * Sequential read buffer. The parser issues many single-byte / 4-byte reads; over a network
     * stream each would otherwise be its own (cached) fetch, so we batch them. The SFTP stream
     * already caches 1 MB blocks, so this just avoids per-byte overhead.
     */
    private final byte[] readBuf = new byte[1 << 16];
    private int bufValid;
    private int bufPos;
    private long filePos;

    private final int version;
    private final long masterIndexPos;
    private final String genome;

    private final List<String> chromosomes = new ArrayList<>();
    private final Map<String, Integer> chromIndex = new HashMap<>();
    private final int[] chromLengths;
    private final int[] chromOffsets;
    private final int[] chromBinCounts;
    private int totalBins;

    private final List<Integer> bpResolutions = new ArrayList<>();
    private int binSize;

    /** Master index (chromosome pair -> file position), parsed once. */
    private Map<String, Long> masterIndex;

    /** chromosome pair key -> block table, loaded lazily */
    private final Map<Long, ChromPairBlocks> pairBlocks = new HashMap<>();

    /**
     * Decompressed blocks, keyed by chromosome pair + block id. Eviction is by total
     * estimated size rather than a fixed count: a whole-chromosome view touches ~200
     * blocks, and thrashing a small cache meant re-inflating them on every repaint.
     */
    private final LinkedHashMap<Long, HicBlock> blockCache =
            new LinkedHashMap<>(64, 0.75f, true);

    private long cachedBytes;

    public HicFileSource(SeekableStream stream, String path) throws IOException {
        this.stream = stream;

        // Anything that fails while parsing the header must not leak the stream handle.
        try {

        seek(0);
        byte[] magic = new byte[4];
        readFully(magic);
        String m = new String(magic, 0, 3, "US-ASCII");
        if (!"HIC".equals(m)) {
            throw new IOException("Not a Juicer .hic file (bad magic): " + path);
        }

        version = readInt();
        masterIndexPos = readLong();
        genome = readCString();

        int nAttributes = readInt();
        for (int i = 0; i < nAttributes; i++) {
            readCString();
            readCString();
        }

        int nChromosomes = readInt();
        if (nChromosomes <= 0 || nChromosomes > 10000) {
            throw new IOException("Invalid chromosome count in .hic file: " + nChromosomes);
        }
        chromLengths = new int[nChromosomes];
        chromOffsets = new int[nChromosomes];
        chromBinCounts = new int[nChromosomes];

        for (int i = 0; i < nChromosomes; i++) {
            String name = readCString();
            int length = readInt();
            chromosomes.add(name);
            chromIndex.put(name, i);
            chromLengths[i] = length;
        }

        int nBpResolutions = readInt();
        for (int i = 0; i < nBpResolutions; i++) {
            bpResolutions.add(readInt());
        }

        // Prefer a resolution that gives a readable matrix: 10kb if available, else the
        // closest to 25kb, else the finest one present.
        binSize = chooseBinSize();
        totalBins = recomputeBinLayout();

        log.info("Hi-C .hic v" + version + " genome=" + genome + " chroms=" + nChromosomes
                + " bpRes=" + bpResolutions + " using binSize=" + binSize + " bins=" + totalBins);

        } catch (IOException | RuntimeException e) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // the original failure is what matters
            }
            throw e;
        }
    }

    /**
     * Recompute per-chromosome bin offsets for the current bin size.
     *
     * @return total bin count
     */
    private int recomputeBinLayout() {
        int offset = 0;
        for (int i = 0; i < chromLengths.length; i++) {
            chromOffsets[i] = offset;
            chromBinCounts[i] = (chromLengths[i] + binSize - 1) / binSize;
            offset += chromBinCounts[i];
        }
        return offset;
    }

    @Override
    public List<Integer> getResolutions() {
        return Collections.unmodifiableList(bpResolutions);
    }

    @Override
    public synchronized void setBinSize(int newBinSize) {
        if (newBinSize == binSize || !bpResolutions.contains(newBinSize)) {
            return;
        }
        binSize = newBinSize;
        totalBins = recomputeBinLayout();
        // Block tables and decompressed blocks belong to the previous unit.
        pairBlocks.clear();
        blockCache.clear();
        cachedBytes = 0;
        log.info("Hi-C resolution -> " + binSize + " bp, " + totalBins + " bins");
    }

    @Override
    public synchronized void autoSelectBinSize(double bpPerPixel) {
        autoSelectBinSize(bpPerPixel, 0);
    }

    /**
     * @param bpPerPixel  genomic bases per screen pixel
     * @param windowWidth drawable width in pixels, 0 when unknown
     */
    public synchronized void autoSelectBinSize(double bpPerPixel, int windowWidth) {
        if (bpResolutions.size() < 2 || !(bpPerPixel > 0)) {
            return;
        }

        // Two constraints: keep at least ~2 pixels per bin (finer bins would be skipped and
        // produce moire stripes), and keep the window to at most MAX_BINS_ACROSS bins so a
        // single repaint never has to inflate a huge number of cells.
        double target = 2 * bpPerPixel;
        if (windowWidth > 0) {
            target = Math.max(target, bpPerPixel * windowWidth / MAX_BINS_ACROSS);
        }

        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int r : bpResolutions) {
            if (r < MIN_AUTO_BIN) {
                continue;
            }
            double d = Math.abs(Math.log(r / target));
            if (d < bestDistance) {
                bestDistance = d;
                best = r;
            }
        }
        if (best < 0) {
            best = binSize;
        }
        setBinSize(best);
    }

    private int chooseBinSize() {
        if (bpResolutions.isEmpty()) {
            return 1_000_000;
        }
        if (bpResolutions.contains(10_000)) {
            return 10_000;
        }
        int best = bpResolutions.get(0);
        for (int r : bpResolutions) {
            if (Math.abs(r - 25_000) < Math.abs(best - 25_000)) {
                best = r;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // HiCSource
    // ------------------------------------------------------------------

    @Override
    public List<String> getChromosomes() {
        return Collections.unmodifiableList(chromosomes);
    }

    @Override
    public int getChromosomeLength(String chr) {
        int idx = indexOf(chr);
        return idx < 0 ? 0 : chromLengths[idx];
    }

    @Override
    public synchronized int getBinSize() {
        return binSize;
    }

    @Override
    public int getBinCount() {
        return totalBins;
    }

    @Override
    public int[] getChromBinRange(String chr) {
        int idx = indexOf(chr);
        if (idx < 0) {
            return null;
        }
        return new int[]{chromOffsets[idx], chromOffsets[idx] + chromBinCounts[idx]};
    }

    @Override
    public synchronized int binAt(String chr, double bp) {
        int idx = indexOf(chr);
        if (idx < 0 || bp < 0 || bp >= chromLengths[idx]) {
            return -1;
        }
        return chromOffsets[idx] + (int) (bp / binSize);
    }

    @Override
    public synchronized int[] getBinsOverlapping(String chr, int start, int end) {
        int[] range = getChromBinRange(chr);
        if (range == null) {
            return new int[0];
        }
        int first = binAt(chr, Math.max(0, start));
        int last = binAt(chr, Math.max(0, end - 1));
        if (first < 0) {
            first = range[0];
        }
        if (last < 0) {
            last = range[1] - 1;
        }
        if (last < first) {
            return new int[0];
        }
        int[] bins = new int[last - first + 1];
        for (int i = 0; i < bins.length; i++) {
            bins[i] = first + i;
        }
        return bins;
    }

    @Override
    public synchronized double getValue(int binX, int binY) {
        int c1 = chromosomeOf(binX);
        int c2 = chromosomeOf(binY);
        if (c1 < 0 || c2 < 0 || c1 != c2) {
            return 0;
        }

        int localX = binX - chromOffsets[c1];
        int localY = binY - chromOffsets[c2];
        if (localX > localY) {
            int t = localX;
            localX = localY;
            localY = t;
        }

        try {
            ChromPairBlocks cp = blocksFor(c1, c2);
            if (cp == null || cp.blockBinCount <= 0) {
                return 0;
            }

            // blockBinCount is the block edge in bins; blockColCount is the number of
            // blocks per row. Block ids in the table are row-major
            // (row * blockColCount + col), NOT triangular - verified against a real file
            // where id=19 maps to (row 1, col 0) for a 19-column layout.
            int blockRow = localY / cp.blockBinCount;
            int blockCol = localX / cp.blockBinCount;
            int blockId = blockRow * cp.blockColCount + blockCol;

            HicBlock block = getBlock(blockId, cp);
            return block == null ? 0 : block.get(localX, localY);

        } catch (IOException e) {
            // getValue runs once per pixel: reporting every failure floods the log with
            // millions of identical lines and hides the real one.
            if (!blockReadFailureLogged) {
                blockReadFailureLogged = true;
                log.error("Failed to read .hic block; further failures are not reported", e);
            }
            return 0;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        blockCache.clear();
        cachedBytes = 0;
        pairBlocks.clear();
        stream.close();
    }

    /**
     * Diagnostic helper: prints how a chromosome-pair block table is laid out.
     * Kept for troubleshooting unusual .hic files.
     */
    public void describePair(String chr) throws IOException {
        int idx = indexOf(chr);
        System.out.println("[hic] chrom " + chr + " -> index " + idx);
        Map<String, Long> mi = readMasterIndex();
        System.out.println("[hic] master entries: " + mi.size()
                + " sample=" + new ArrayList<>(mi.keySet()).subList(0, Math.min(8, mi.size())));
        Long pos = mi.get(idx + "_" + idx);
        System.out.println("[hic] pair pos = " + pos);
        if (pos == null) {
            return;
        }
        ChromPairBlocks cp = readBlockIndex(pos);
        if (cp == null) {
            System.out.println("[hic] no BP block table at chosen binSize");
            return;
        }
        System.out.println("[hic] blockBinCount=" + cp.blockBinCount
                + " blockColCount=" + cp.blockColCount + " nBlocks=" + cp.size());

        for (int id : cp.firstIds(8)) {
            long[] e = cp.get(id);
            HicBlock b = readBlock(e[0], (int) e[1]);
            int row = b.binYOffset / Math.max(1, cp.blockBinCount);
            int col = b.binXOffset / Math.max(1, cp.blockBinCount);
            System.out.println("[hic] id=" + id + " records=" + b.size()
                    + " binXOffset=" + b.binXOffset + " binYOffset=" + b.binYOffset
                    + "  => (row=" + row + ", col=" + col + ")"
                    + " triId=" + (row * (row + 1) / 2 + col)
                    + " rowMajorId=" + (row * cp.blockColCount + col));
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private int indexOf(String chr) {
        if (chr == null) {
            return -1;
        }
        Integer idx = chromIndex.get(chr);
        if (idx != null) {
            return idx;
        }
        String alias = chr.startsWith("chr") ? chr.substring(3) : "chr" + chr;
        idx = chromIndex.get(alias);
        return idx == null ? -1 : idx;
    }

    private synchronized int chromosomeOf(int globalBin) {
        int lo = 0;
        int hi = chromOffsets.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (globalBin < chromOffsets[mid]) {
                hi = mid - 1;
            } else if (globalBin >= chromOffsets[mid] + chromBinCounts[mid]) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private ChromPairBlocks blocksFor(int c1, int c2) throws IOException {
        long key = ((long) c1 << 32) | c2;
        ChromPairBlocks cached = pairBlocks.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, Long> pairIndex = readMasterIndex();
        Long pos = pairIndex.get(c1 + "_" + c2);
        if (pos == null) {
            pairBlocks.put(key, null);
            return null;
        }

        ChromPairBlocks cp = readBlockIndex(pos);
        pairBlocks.put(key, cp);
        return cp;
    }

    private Map<String, Long> readMasterIndex() throws IOException {
        if (masterIndex != null) {
            return masterIndex;
        }
        Map<String, Long> map = new HashMap<>();
        seek(masterIndexPos);
        readInt();                        // nBytes
        int nEntries = readInt();
        for (int i = 0; i < nEntries; i++) {
            String key = readCString();
            long pos = readLong();
            readInt();                    // size in bytes
            map.put(key, pos);
        }
        masterIndex = map;
        return map;
    }

    private ChromPairBlocks readBlockIndex(long filePos) throws IOException {
        seek(filePos);
        readInt();                        // c1 (redundant)
        readInt();                        // c2
        int nRes = readInt();

        for (int i = 0; i < nRes; i++) {
            String unit = readCString();
            skip(20);                     // 5 int32 of summary counts
            int binsize = readInt();
            int blockBinCount = readInt();
            int blockColCount = readInt();
            int nBlocks = readInt();

            if ("BP".equals(unit) && binsize == binSize) {
                ChromPairBlocks cp = new ChromPairBlocks(blockBinCount, blockColCount);
                for (int b = 0; b < nBlocks; b++) {
                    int id = readInt();
                    long pos = readLong();
                    int size = readInt();
                    cp.put(id, pos, size);
                }
                return cp;
            }
            skip((long) nBlocks * 16);    // blockId(4) + pos(8) + size(4)
        }
        return null;
    }

    private HicBlock getBlock(int blockId, ChromPairBlocks cp) throws IOException {
        long[] entry = cp.get(blockId);
        if (entry == null) {
            return null;
        }
        // Key the cache by file offset, not by (chromosome pair, block id). Block ids are only
        // unique within a pair, and packing them next to the pair key in a single long kept
        // just 16 bits of the id, so blocks 65536 apart shared one cache entry and returned
        // each other's contact values. Offsets are unique per block across the whole file.
        final long cacheKey = entry[0];
        HicBlock cached = blockCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        HicBlock block = readBlock(entry[0], (int) entry[1]);
        cacheBlock(cacheKey, block);
        return block;
    }

    private void cacheBlock(long key, HicBlock block) {
        blockCache.put(key, block);
        cachedBytes += block.estimatedBytes();
        if (cachedBytes <= MAX_CACHE_BYTES) {
            return;
        }
        java.util.Iterator<Map.Entry<Long, HicBlock>> it = blockCache.entrySet().iterator();
        while (cachedBytes > MAX_CACHE_BYTES && blockCache.size() > 1 && it.hasNext()) {
            Map.Entry<Long, HicBlock> eldest = it.next();
            if (eldest.getKey() == key) {
                continue;
            }
            cachedBytes -= eldest.getValue().estimatedBytes();
            it.remove();
        }
    }

    private HicBlock readBlock(long position, int size) throws IOException {
        byte[] compressed = new byte[size];
        seek(position);
        readFully(compressed);

        byte[] raw = inflate(compressed);
        int nRecords = readInt(raw, 0);

        HicBlock block = new HicBlock(Math.max(16, nRecords));

        if (version < 7) {
            int offset = 4;
            for (int i = 0; i < nRecords; i++) {
                int x = readInt(raw, offset);
                int y = readInt(raw, offset + 4);
                float c = readFloat(raw, offset + 8);
                block.put(x, y, c);
                offset += 12;
            }
            return block;
        }

        int binXOffset = readInt(raw, 4);
        int binYOffset = readInt(raw, 8);
        block.binXOffset = binXOffset;
        block.binYOffset = binYOffset;
        int useShort = raw[12] & 0xFF;
        int type = raw[13] & 0xFF;

        if (type == 1) {
            int rowCount = readShort(raw, 14);
            int temp = 16;
            for (int i = 0; i < rowCount; i++) {
                int y = readShort(raw, temp);
                temp += 2;
                y += binYOffset;
                int colCount = readShort(raw, temp);
                temp += 2;
                for (int j = 0; j < colCount; j++) {
                    int x = readShort(raw, temp);
                    temp += 2;
                    x += binXOffset;
                    double c;
                    if (useShort == 0) {
                        c = readShort(raw, temp) & 0xFFFF;
                        temp += 2;
                    } else {
                        c = readFloat(raw, temp);
                        temp += 4;
                    }
                    if (c != 0) {
                        block.put(x, y, c);
                    }
                }
            }
        } else if (type == 2) {
            int nPts = readInt(raw, 14);
            int w = readShort(raw, 18);
            int temp = 20;
            for (int i = 0; i < nPts; i++) {
                int row = i / w;
                int col = i % w;
                if (useShort == 0) {
                    int c = readShort(raw, temp);
                    temp += 2;
                    if (c != -32768 && c != 0) {
                        block.put(binXOffset + col, binYOffset + row, c);
                    }
                } else {
                    float c = readFloat(raw, temp);
                    temp += 4;
                    if (!Float.isNaN(c) && !Float.isInfinite(c) && c != 0) {
                        block.put(binXOffset + col, binYOffset + row, c);
                    }
                }
            }
        }

        return block;
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] buffer = new byte[Math.max(4096, compressed.length * 4)];
        int len = 0;
        try {
            while (!inflater.finished()) {
                if (len == buffer.length) {
                    byte[] grown = new byte[buffer.length * 2];
                    System.arraycopy(buffer, 0, grown, 0, len);
                    buffer = grown;
                }
                int n = inflater.inflate(buffer, len, buffer.length - len);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                len += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt .hic block: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        byte[] out = new byte[len];
        System.arraycopy(buffer, 0, out, 0, len);
        return out;
    }

    // --- primitive readers -------------------------------------------------

    /** Move the read cursor; the next read resumes from {@code p}. */
    private void seek(long p) throws IOException {
        stream.seek(p);
        filePos = p;
        bufPos = bufValid = 0;
    }

    /** Read a single byte (buffered); returns -1 at end of file. */
    private int read() throws IOException {
        if (bufPos >= bufValid) {
            bufValid = stream.read(readBuf, 0, readBuf.length);
            if (bufValid <= 0) {
                return -1;
            }
            bufPos = 0;
            filePos += bufValid;
        }
        return readBuf[bufPos++] & 0xFF;
    }

    /** Read exactly {@code dst.length} bytes, refilling the buffer / stream as needed. */
    private void readFully(byte[] dst) throws IOException {
        int off = 0;
        int need = dst.length;
        while (need > 0) {
            if (bufPos < bufValid) {
                int c = Math.min(need, bufValid - bufPos);
                System.arraycopy(readBuf, bufPos, dst, off, c);
                bufPos += c;
                off += c;
                need -= c;
            } else {
                int n = stream.read(dst, off, need);
                if (n < 0) {
                    throw new IOException("Unexpected end of .hic file");
                }
                off += n;
                need -= n;
                filePos += n;
                bufPos = bufValid = 0;
            }
        }
    }

    private int readInt() throws IOException {
        int b0 = read();
        int b1 = read();
        int b2 = read();
        int b3 = read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new IOException("Unexpected end of .hic file");
        }
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private long readLong() throws IOException {
        long lo = readInt() & 0xFFFFFFFFL;
        long hi = readInt() & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    private void skip(long n) throws IOException {
        while (n > 0) {
            if (bufPos < bufValid) {
                long c = Math.min(n, bufValid - bufPos);
                bufPos += c;
                filePos += c;
                n -= c;
            } else {
                seek(filePos + n);
                return;
            }
        }
    }

    private String readCString() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = read();
            if (b < 0 || b == 0) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readShort(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    private static float readFloat(byte[] b, int off) {
        return Float.intBitsToFloat(readInt(b, off));
    }

    // --- data holders ------------------------------------------------------

    private static final class ChromPairBlocks {
        final int blockBinCount;
        final int blockColCount;
        private final Map<Integer, long[]> entries = new HashMap<>();

        ChromPairBlocks(int blockBinCount, int blockColCount) {
            this.blockBinCount = blockBinCount;
            this.blockColCount = blockColCount;
        }

        void put(int blockId, long position, int size) {
            entries.put(blockId, new long[]{position, size});
        }

        long[] get(int blockId) {
            return entries.get(blockId);
        }

        int size() {
            return entries.size();
        }

        java.util.List<Integer> firstIds(int n) {
            java.util.List<Integer> ids = new ArrayList<>(entries.keySet());
            java.util.Collections.sort(ids);
            return ids.subList(0, Math.min(n, ids.size()));
        }

        long[] firstEntry() {
            if (entries.isEmpty()) return null;
            Map.Entry<Integer, long[]> first = entries.entrySet().iterator().next();
            long[] v = first.getValue();
            return new long[]{v[0], v[1], first.getKey()};
        }
    }

    private static final class HicBlock {
        private final Map<Long, Float> values;
        int binXOffset = -1;
        int binYOffset = -1;

        HicBlock(int capacity) {
            values = new HashMap<>(Math.max(16, capacity));
        }

        void put(int x, int y, double v) {
            values.put(key(x, y), (float) v);
        }

        int size() {
            return values.size();
        }

        long estimatedBytes() {
            return (long) values.size() * BYTES_PER_RECORD;
        }

        String sampleKeys() {
            List<String> out = new ArrayList<>();
            for (Long k : values.keySet()) {
                out.add("(" + (k >>> 32) + "," + (k & 0xFFFFFFFFL) + ")");
                if (out.size() >= 5) break;
            }
            return out.toString();
        }

        double get(int x, int y) {
            Float v = values.get(key(x, y));
            return v == null ? 0 : v;
        }

        private static long key(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }
    }
}
