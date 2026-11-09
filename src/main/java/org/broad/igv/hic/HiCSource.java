package org.broad.igv.hic;

import java.io.IOException;
import java.util.List;

/**
 * Read-only view of a Hi-C contact matrix, independent of the file format.
 * <p>
 * Two implementations exist: {@link HiCData} holds a small matrix fully in memory
 * (HiCExplorer .h5 / cooler .cool), while {@link HicFileSource} reads blocks on demand
 * from a Juicer .hic file, which is far too large to load in full.
 * <p>
 * Bins are addressed by a global index that runs across chromosomes in file order.
 */
public interface HiCSource extends AutoCloseable {

    /** Chromosome names in file order. */
    List<String> getChromosomes();

    /** Chromosome length in base pairs, or 0 if unknown. */
    int getChromosomeLength(String chr);

    /** Typical bin width in base pairs. */
    int getBinSize();

    /** @return number of bins in the whole matrix */
    int getBinCount();

    /** @return {firstBin, lastBinExclusive} for a chromosome, or null if absent */
    int[] getChromBinRange(String chr);

    /** @return the global bin index containing {@code bp}, or -1 when outside the chromosome */
    int binAt(String chr, double bp);

    /** @return global bin indices overlapping [start, end) on {@code chr} */
    int[] getBinsOverlapping(String chr, int start, int end);

    /**
     * @return bin sizes in bp available in the underlying file; empty when the matrix only
     *         has a single resolution (in-memory matrices)
     */
    default List<Integer> getResolutions() {
        return java.util.Collections.emptyList();
    }

    /** Switch to another bin size, when the source supports several. */
    default void setBinSize(int binSize) {
        // single-resolution sources ignore this
    }

    /**
     * Pick the bin size that best matches the current zoom level. Without this, zooming out
     * samples a handful of 10 kb bins per pixel and the plot turns into vertical stripes.
     *
     * @param bpPerPixel genomic bases covered by one screen pixel
     */
    default void autoSelectBinSize(double bpPerPixel) {
        // single-resolution sources ignore this
    }

    /**
     * @param windowWidth drawable width in pixels, used to bound the number of cells a
     *                    single repaint has to read; 0 when unknown
     */
    default void autoSelectBinSize(double bpPerPixel, int windowWidth) {
        autoSelectBinSize(bpPerPixel);
    }

    /** @return contact count for a global bin pair; 0 when absent */
    double getValue(int binX, int binY);

    @Override
    void close() throws IOException;
}
