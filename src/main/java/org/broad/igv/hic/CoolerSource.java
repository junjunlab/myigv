package org.broad.igv.hic;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A cooler-format HDF5 matrix read on demand, one row block at a time.
 * <p>
 * Cooler stores pixels sorted by {@code (bin1_id, bin2_id)} and provides
 * {@code indexes/bin1_offset}, a CSR-style row pointer of length {@code nBins + 1}. That makes
 * it possible to fetch exactly the pixels for a range of rows with a single HDF5 hyperslab
 * read ({@code Dataset.getData(offset, shape)}), instead of materialising the whole pixel
 * table. A real 10 kb human mcool holds ~76 million pixels per resolution (~1.2 GB as Java
 * arrays); loading it eagerly is not an option, and over SFTP it would also mean reading
 * hundreds of megabytes before the first frame appears.
 * <p>
 * The renderer asks {@link #getValue(int, int)} once per screen pixel, mostly with increasing
 * row indices, so rows are grouped into {@link #ROWS_PER_BLOCK} blocks and a small LRU keeps
 * the blocks touched by the current view. One repaint therefore costs a handful of slice
 * reads regardless of how large the file is.
 * <p>
 * Bin metadata (chromosome names, starts/ends, ranges) is delegated to {@link HiCData}; only
 * the contact lookup is replaced.
 */
public final class CoolerSource implements HiCSource {

    private static final Logger log = LogManager.getLogger(CoolerSource.class);

    /** Rows per hyperslab read. 512 rows is ~130k pixels at 10 kb, a few MB at most. */
    private static final int ROWS_PER_BLOCK = 512;

    /** Keep 8 blocks (~4k rows) so panning back and forth does not re-read. */
    private static final int MAX_CACHED_BLOCKS = 8;

    /** Guard against a single hyperslab exceeding int array limits / huge allocations. */
    private static final int MAX_SLICE = 4_000_000;

    private final String prefix;
    private final String path;

    private final Dataset dsBin2;
    private final Dataset dsCount;
    private final long[] rowOffsets;

    private final HiCData bins;
    private final int binSize;

    private final Map<Integer, RowBlock> cache =
            new LinkedHashMap<Integer, RowBlock>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, RowBlock> eldest) {
                    return size() > MAX_CACHED_BLOCKS;
                }
            };

    /**
     * @param prefix HDF5 group prefix, e.g. {@code ""}, {@code "resolutions/10000/"} or
     *               {@code "4/"} for a flat mcool
     * @param binSize bin width in bp, derived from the group's bins by the caller
     */
    CoolerSource(HdfFile hdf, String prefix, String path, int binSize) throws IOException {
        this.prefix = prefix;
        this.path = path;
        this.binSize = binSize;

        Dataset dsChrom = HiCFileReader.dataset(hdf, prefix + "bins/chrom");
        Dataset dsStart = HiCFileReader.dataset(hdf, prefix + "bins/start");
        Dataset dsEnd = HiCFileReader.dataset(hdf, prefix + "bins/end");
        Dataset dsOffset = HiCFileReader.dataset(hdf, prefix + "indexes/bin1_offset");
        this.dsBin2 = HiCFileReader.dataset(hdf, prefix + "pixels/bin2_id");
        this.dsCount = HiCFileReader.dataset(hdf, prefix + "pixels/count");

        if (dsChrom == null || dsStart == null || dsEnd == null || dsOffset == null
                || dsBin2 == null || dsCount == null) {
            throw new IOException("Incomplete cooler group " + prefix + " in " + path);
        }

        String[] chrom = HiCFileReader.toStringArray(dsChrom.getData());
        int[] starts = HiCFileReader.toIntArray(dsStart.getData());
        int[] ends = HiCFileReader.toIntArray(dsEnd.getData());
        this.rowOffsets = HiCFileReader.toLongArray(dsOffset.getData());

        if (chrom.length != starts.length || chrom.length != ends.length) {
            throw new IOException("Inconsistent cooler bins in " + path + " [" + prefix + "]");
        }
        if (rowOffsets.length != chrom.length + 1) {
            throw new IOException("Bad bin1_offset length in " + path + " [" + prefix + "]: "
                    + rowOffsets.length + " for " + chrom.length + " bins");
        }
        for (int i = 0; i < chrom.length; i++) {
            chrom[i] = chrom[i] == null ? "" : chrom[i].trim();
        }

        // Bin metadata only: the CSR arrays stay empty because getValue is overridden below.
        this.bins = new HiCData(chrom, starts, ends,
                new int[chrom.length + 1], new int[0], new double[0]);

        log.info("Cooler " + path + " [" + (prefix.isEmpty() ? "/" : prefix) + "] bins=" + chrom.length
                + " pixels=" + rowOffsets[chrom.length] + " binSize=" + binSize);
    }

    // ------------------------------------------------------------------
    // Bin metadata: delegate to HiCData
    // ------------------------------------------------------------------

    @Override
    public List<String> getChromosomes() {
        return bins.getChromosomes();
    }

    @Override
    public int getChromosomeLength(String chr) {
        return bins.getChromosomeLength(chr);
    }

    @Override
    public int getBinSize() {
        return binSize;
    }

    @Override
    public int getBinCount() {
        return bins.getBinCount();
    }

    @Override
    public int[] getChromBinRange(String chr) {
        return bins.getChromBinRange(chr);
    }

    @Override
    public int binAt(String chr, double bp) {
        return bins.binAt(chr, bp);
    }

    @Override
    public int[] getBinsOverlapping(String chr, int start, int end) {
        return bins.getBinsOverlapping(chr, start, end);
    }

    // ------------------------------------------------------------------
    // Contact lookup
    // ------------------------------------------------------------------

    @Override
    public synchronized double getValue(int row, int col) {
        if (row < 0 || row >= bins.getBinCount()) {
            return 0;
        }
        final int blockIndex = row / ROWS_PER_BLOCK;
        try {
            RowBlock block = cache.get(blockIndex);
            if (block == null) {
                block = loadBlock(blockIndex);
                cache.put(blockIndex, block);
            }
            return block.get(row, col);
        } catch (IOException | RuntimeException e) {
            // getValue runs once per pixel; report once instead of flooding the log.
            if (!readFailureLogged) {
                readFailureLogged = true;
                log.error("Failed to read cooler pixels in " + path + " [" + prefix + "]", e);
            }
            return 0;
        }
    }

    private boolean readFailureLogged;

    private RowBlock loadBlock(int blockIndex) throws IOException {
        final int first = blockIndex * ROWS_PER_BLOCK;
        final int last = Math.min(first + ROWS_PER_BLOCK, bins.getBinCount());
        final long from = rowOffsets[first];
        final long to = rowOffsets[last];

        int[] rowStarts = new int[last - first + 1];
        for (int i = first; i <= last; i++) {
            rowStarts[i - first] = (int) (rowOffsets[i] - from);
        }

        final long total = to - from;
        if (total <= 0) {
            return new RowBlock(first, last, rowStarts, new int[0], new double[0], true);
        }
        if (total > Integer.MAX_VALUE) {
            throw new IOException("Cooler row block too large: " + total + " pixels");
        }

        int[] cols = new int[(int) total];
        double[] values = new double[(int) total];
        long pos = from;
        int written = 0;
        while (pos < to) {
            int chunk = (int) Math.min(MAX_SLICE, to - pos);
            int[] c = HiCFileReader.toIntArray(
                    dsBin2.getData(new long[]{pos}, new int[]{chunk}));
            double[] v = HiCFileReader.toDoubleArray(
                    dsCount.getData(new long[]{pos}, new int[]{chunk}));
            System.arraycopy(c, 0, cols, written, chunk);
            System.arraycopy(v, 0, values, written, chunk);
            written += chunk;
            pos += chunk;
        }

        // Cooler guarantees pixels sorted by (bin1_id, bin2_id); verify so a non-conforming
        // file degrades to a linear scan rather than silently returning wrong values.
        boolean sorted = true;
        for (int i = 1; i < cols.length; i++) {
            if (cols[i] < cols[i - 1]) {
                sorted = false;
                break;
            }
        }
        return new RowBlock(first, last, rowStarts, cols, values, sorted);
    }

    /** The HDF5 file is owned by HdfSource (shared across resolutions); only drop cached rows. */
    @Override
    public void close() {
        cache.clear();
    }

    /** Pixels for a contiguous range of rows, addressed by offsets into the flat arrays. */
    private static final class RowBlock {
        private final int first;
        private final int last;
        private final int[] rowStarts;
        private final int[] cols;
        private final double[] values;
        private final boolean sorted;

        RowBlock(int first, int last, int[] rowStarts, int[] cols, double[] values, boolean sorted) {
            this.first = first;
            this.last = last;
            this.rowStarts = rowStarts;
            this.cols = cols;
            this.values = values;
            this.sorted = sorted;
        }

        double get(int row, int col) {
            if (row < first || row >= last) {
                return 0;
            }
            int s = rowStarts[row - first];
            int e = rowStarts[row - first + 1];
            if (!sorted) {
                for (int i = s; i < e; i++) {
                    if (cols[i] == col) {
                        return values[i];
                    }
                }
                return 0;
            }
            int lo = s;
            int hi = e - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = cols[mid];
                if (c < col) {
                    lo = mid + 1;
                } else if (c > col) {
                    hi = mid - 1;
                } else {
                    return values[mid];
                }
            }
            return 0;
        }
    }
}
