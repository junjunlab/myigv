package org.broad.igv.hic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory Hi-C contact matrix in CSR form, independent of the on-disk format.
 * <p>
 * Both HiCExplorer .h5 files and cooler .cool files are loaded into this structure,
 * so the renderer only deals with one representation.
 */
public class HiCData implements HiCSource {

    private final String[] binChrom;
    private final int[] binStart;
    private final int[] binEnd;

    private final int[] indptr;
    private final int[] indices;
    private final double[] values;

    /** chromosome name to {firstBin, lastBinExclusive} */
    private final Map<String, int[]> chromRanges;
    private final List<String> chromosomes;
    private final Map<String, Integer> chromLengths;

    public HiCData(String[] binChrom, int[] binStart, int[] binEnd,
                   int[] indptr, int[] indices, double[] values) {

        this.binChrom = binChrom;
        this.binStart = binStart;
        this.binEnd = binEnd;
        this.indptr = indptr;
        this.indices = indices;
        this.values = values;

        Map<String, int[]> ranges = new LinkedHashMap<>();
        Map<String, Integer> lengths = new LinkedHashMap<>();

        int i = 0;
        while (i < binChrom.length) {
            String chr = binChrom[i];
            int start = i;
            while (i < binChrom.length && binChrom[i].equals(chr)) {
                i++;
            }
            ranges.put(chr, new int[]{start, i});
            lengths.put(chr, binEnd[i - 1]);
        }

        this.chromRanges = Collections.unmodifiableMap(ranges);
        this.chromosomes = Collections.unmodifiableList(new ArrayList<>(ranges.keySet()));
        this.chromLengths = Collections.unmodifiableMap(lengths);
    }

    public List<String> getChromosomes() {
        return chromosomes;
    }

    public int getChromosomeLength(String chr) {
        Integer len = chromLengths.get(chr);
        if (len == null) {
            len = chromLengths.get(aliasOf(chr));
        }
        return len == null ? 0 : len;
    }

    public int getBinCount() {
        return binChrom.length;
    }

    /** @return {firstBin, lastBinExclusive}, or null if the chromosome is absent */
    public int[] getChromBinRange(String chr) {
        int[] range = chromRanges.get(chr);
        if (range == null) {
            range = chromRanges.get(aliasOf(chr));
        }
        return range;
    }

    /**
     * Matrix files often use bare names ("X") while IGV genomes use UCSC style ("chrX").
     * Try the other convention before giving up.
     */
    private String aliasOf(String chr) {
        if (chr == null) {
            return "";
        }
        if (chr.startsWith("chr") || chr.startsWith("Chr")) {
            return chr.substring(3);
        }
        return "chr" + chr;
    }

    public int getBinStart(int bin) {
        return binStart[bin];
    }

    public int getBinEnd(int bin) {
        return binEnd[bin];
    }

    public String getBinChromosome(int bin) {
        return binChrom[bin];
    }

    /**
     * Typical bin size, taken as the median of the first 100 bins. Hi-C bins are usually
     * equal width, but HiCExplorer files created from restriction fragments can vary.
     */
    public int getBinSize() {
        int n = Math.min(100, binEnd.length);
        if (n == 0) {
            return 1;   // empty matrix: callers divide by this
        }
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            sizes[i] = binEnd[i] - binStart[i];
        }
        java.util.Arrays.sort(sizes);
        return Math.max(1, sizes[n / 2]);
    }

    /** Sparse lookup of a single cell. */
    public double getValue(int row, int col) {
        if (row < 0 || row >= indptr.length - 1) {
            return 0;
        }
        int from = indptr[row];
        int to = indptr[row + 1];
        int lo = from;
        int hi = to - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = indices[mid];
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

    /**
     * Extract a dense sub-matrix for the given row and column bins.
     * <p>
     * Bins are aggregated into at most {@code maxSize} per axis by summing, which keeps the
     * cost bounded when the view spans a whole chromosome.
     */
    public double[][] getSubMatrix(int[] rows, int[] cols, int maxSize) {

        final int rowFactor = Math.max(1, (int) Math.ceil(rows.length / (double) maxSize));
        final int colFactor = Math.max(1, (int) Math.ceil(cols.length / (double) maxSize));

        final int outRows = (rows.length + rowFactor - 1) / rowFactor;
        final int outCols = (cols.length + colFactor - 1) / colFactor;

        double[][] out = new double[outRows][outCols];

        for (int r = 0; r < rows.length; r++) {
            int row = rows[r];
            int outRow = r / rowFactor;

            int from = indptr[row];
            int to = indptr[row + 1];

            for (int p = from; p < to; p++) {
                int col = indices[p];
                int colPos = java.util.Arrays.binarySearch(cols, col);
                if (colPos < 0) {
                    continue;
                }
                out[outRow][colPos / colFactor] += values[p];
            }
        }

        return out;
    }

    /** @return bins of a chromosome that overlap [start, end), as bin indices */
    public int[] getBinsOverlapping(String chr, int start, int end) {
        int[] range = getChromBinRange(chr);
        if (range == null) {
            return new int[0];
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = range[0]; i < range[1]; i++) {
            if (binEnd[i] > start && binStart[i] < end) {
                hits.add(i);
            }
        }
        int[] result = new int[hits.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = hits.get(i);
        }
        return result;
    }

    /** @return all bins of a chromosome as a contiguous index array */
    public int[] getChromBins(String chr) {
        int[] range = getChromBinRange(chr);
        if (range == null) {
            return new int[0];
        }
        int[] bins = new int[range[1] - range[0]];
        for (int i = 0; i < bins.length; i++) {
            bins[i] = range[0] + i;
        }
        return bins;
    }

    @Override
    public int binAt(String chr, double bp) {
        int[] range = getChromBinRange(chr);
        if (range == null) {
            return -1;
        }
        int lo = range[0];
        int hi = range[1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (bp < binStart[mid]) {
                hi = mid - 1;
            } else if (bp >= binEnd[mid]) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        // fully in memory, nothing to release
    }
}
