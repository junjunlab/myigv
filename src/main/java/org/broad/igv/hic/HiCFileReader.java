package org.broad.igv.hic;

import htsjdk.samtools.seekablestream.SeekableStream;
import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Group;
import io.jhdf.api.Node;
import io.jhdf.filter.FilterManager;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.util.FileUtils;
import org.broad.igv.util.stream.IGVSeekableStreamFactory;
import org.broad.igv.util.stream.SeekableStreamChannel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads Hi-C contact matrices from HDF5 files.
 * <p>
 * Layouts supported (all readable over SFTP through {@link SeekableStreamChannel}):
 * <ul>
 *   <li><b>HiCExplorer .h5</b> — {@code /intervals/*} plus a CSR matrix under {@code /matrix}</li>
 *   <li><b>cooler .cool</b> — {@code /bins} plus a COO pixel list under {@code /pixels}</li>
 *   <li><b>standard mcool</b> — numeric resolutions under {@code /resolutions/&lt;bp&gt;/}</li>
 *   <li><b>flat mcool</b> — numeric resolution groups directly at the root (pyGenomeTracks
 *       test fixtures use {@code /0/}, {@code /1/}, ... where the names are indices, not bp)</li>
 * </ul>
 * Cooler matrices are read by {@link CoolerSource} in row blocks using
 * {@code indexes/bin1_offset}, so a 10 kb human mcool (tens of millions of pixels) never has
 * to be materialised. HiCExplorer writes its datasets through PyTables with blosc
 * compression, which needs the pure-Java {@link BloscFilter} registered below.
 */
public class HiCFileReader {

    private static final Logger log = LogManager.getLogger(HiCFileReader.class);

    private static final boolean BLOSC_REGISTERED;

    static {
        boolean ok;
        try {
            FilterManager.addFilter(new BloscFilter());
            ok = true;
        } catch (Throwable t) {
            log.error("Could not register blosc filter", t);
            ok = false;
        }
        BLOSC_REGISTERED = ok;
    }

    private HiCFileReader() {
        // Utility class
    }

    public static boolean isHiCDataFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        // .cool.gz / .h5.gz etc. are gzip-wrapped HDF5 files
        if (lower.endsWith(".gz")) {
            lower = lower.substring(0, lower.length() - 3);
        }
        return lower.endsWith(".h5") || lower.endsWith(".cool")
                || lower.endsWith(".mcool") || lower.endsWith(".hic");
    }

    public static HiCSource load(String path) throws IOException {
        final String lower = path.toLowerCase();
        final boolean remote = FileUtils.isRemote(path);

        // Local existence check: a clear error beats a cryptic stream failure.
        if (!remote && !new File(path).isFile()) {
            throw new IOException("Hi-C file not found: " + path);
        }

        // Gzip-wrapped HDF5 (.h5.gz / .cool.gz): plain gzip is not seekable, so inflate once
        // into a temporary local file and open that with jhdf. Streaming the bytes (rather
        // than downloading to a sibling) keeps this working for remote files too.
        if (lower.endsWith(".gz")) {
            File tmp = File.createTempFile("myigv-hic-", ".h5");
            tmp.deleteOnExit();
            try (SeekableStream base = IGVSeekableStreamFactory.getInstance().getStreamFor(path);
                 InputStream in = new GZIPInputStream(new SeekableStreamInputStream(base), 1 << 16);
                 OutputStream out = new FileOutputStream(tmp)) {
                in.transferTo(out);
            } catch (IOException e) {
                throw new IOException("Could not decompress Hi-C file " + path + ": " + e.getMessage(), e);
            }
            log.info("Decompressed Hi-C matrix " + path + " -> " + tmp.getAbsolutePath());
            return load(tmp.getAbsolutePath());
        }

        // Juicer .hic: random-read directly over the (possibly remote) stream.
        if (lower.endsWith(".hic")) {
            log.info("Loading Juicer .hic matrix " + path);
            SeekableStream stream = IGVSeekableStreamFactory.getInstance().getStreamFor(path);
            return new HicFileSource(stream, path);
        }

        // HiCExplorer .h5 / cooler .cool / mcool: HDF5. Remote reads go through a
        // SeekableByteChannel backed by the SFTP stream; local reads use the file directly.
        log.info("Loading Hi-C matrix " + path + " (blosc filter registered: " + BLOSC_REGISTERED + ")");
        HdfFile hdf = remote
                ? new HdfFile(new SeekableStreamChannel(
                        IGVSeekableStreamFactory.getInstance().getStreamFor(path), path), URI.create(path))
                : new HdfFile(new File(path));
        return HdfSource.build(hdf, path);
    }

    // ------------------------------------------------------------------
    // HiCExplorer .h5
    // ------------------------------------------------------------------

    private static HiCData loadHiCExplorer(HdfFile hdf, String path) throws IOException {

        int[] indptr = toIntArray(dataset(hdf, "matrix/indptr").getData());
        int[] indices = toIntArray(dataset(hdf, "matrix/indices").getData());
        double[] values = toDoubleArray(dataset(hdf, "matrix/data").getData());

        long[] shape = toLongArray(dataset(hdf, "matrix/shape").getData());
        String[] chroms = toStringArray(dataset(hdf, "intervals/chr_list").getData());
        int[] starts = toIntArray(dataset(hdf, "intervals/start_list").getData());
        int[] ends = toIntArray(dataset(hdf, "intervals/end_list").getData());

        int nBins = chroms.length;
        if (starts.length != nBins || ends.length != nBins) {
            throw new IOException("Inconsistent intervals in " + path
                    + ": chrom=" + nBins + " start=" + starts.length + " end=" + ends.length);
        }
        if (shape.length >= 2 && shape[0] != nBins) {
            throw new IOException("Matrix size " + shape[0] + " does not match bin count " + nBins);
        }

        // Strip any whitespace padding from the fixed-width chromosome names.
        for (int i = 0; i < nBins; i++) {
            chroms[i] = chroms[i].trim();
        }

        log.info("HiCExplorer matrix: " + nBins + " bins, " + values.length + " non-zero cells");
        return new HiCData(chroms, starts, ends, indptr, indices, values);
    }

    // ------------------------------------------------------------------
    // cooler .cool
    // ------------------------------------------------------------------

    private static HiCData loadCooler(HdfFile hdf, String path, String prefix) throws IOException {

        String[] chroms = toStringArray(dataset(hdf, prefix + "bins/chrom").getData());
        int[] starts = toIntArray(dataset(hdf, prefix + "bins/start").getData());
        int[] ends = toIntArray(dataset(hdf, prefix + "bins/end").getData());

        int[] bin1 = toIntArray(dataset(hdf, prefix + "pixels/bin1_id").getData());
        int[] bin2 = toIntArray(dataset(hdf, prefix + "pixels/bin2_id").getData());
        double[] counts = toDoubleArray(dataset(hdf, prefix + "pixels/count").getData());

        int nBins = chroms.length;
        int nnz = bin1.length;
        if (bin2.length != nnz || counts.length != nnz) {
            throw new IOException("Inconsistent pixel arrays in " + path);
        }

        for (int i = 0; i < nBins; i++) {
            chroms[i] = chroms[i].trim();
        }

        // The bin ids come from the file and are used as array indices below: a corrupt or
        // truncated matrix would otherwise throw ArrayIndexOutOfBounds deep inside the CSR
        // build instead of reporting which file is broken.
        for (int i = 0; i < nnz; i++) {
            if (bin1[i] < 0 || bin1[i] >= nBins || bin2[i] < 0 || bin2[i] >= nBins) {
                throw new IOException("Pixel index out of range in " + path + ": (" + bin1[i]
                        + ", " + bin2[i] + "), nBins=" + nBins);
            }
        }

        // COO -> CSR
        int[] indptr = new int[nBins + 1];
        for (int i = 0; i < nnz; i++) {
            indptr[bin1[i] + 1]++;
        }
        for (int i = 1; i <= nBins; i++) {
            indptr[i] += indptr[i - 1];
        }

        int[] indices = new int[nnz];
        double[] values = new double[nnz];
        int[] next = indptr.clone();
        for (int i = 0; i < nnz; i++) {
            int row = bin1[i];
            int pos = next[row]++;
            indices[pos] = bin2[i];
            values[pos] = counts[i];
        }

        log.info("Cooler matrix: " + nBins + " bins, " + nnz + " pixels");
        return new HiCData(chroms, starts, ends, indptr, indices, values);
    }

    // ------------------------------------------------------------------
    // mcool: several resolutions in one file
    // ------------------------------------------------------------------

    /** A cooler resolution: its bin width in bp and the HDF5 group that holds it. */
    private static final class Resolution {
        final int binSize;
        final String prefix;

        Resolution(int binSize, String prefix) {
            this.binSize = binSize;
            this.prefix = prefix;
        }
    }

    /** @return true when {@code prefix} points at a cooler group with bins and pixels. */
    private static boolean isCoolerGroup(HdfFile hdf, String prefix) {
        return dataset(hdf, prefix + "pixels/bin1_id") != null
                && dataset(hdf, prefix + "pixels/count") != null
                && dataset(hdf, prefix + "bins/chrom") != null;
    }

    /**
     * Find cooler resolutions in all three layouts. Group names are deliberately NOT trusted
     * as bin sizes: a standard mcool names them in bp ({@code /resolutions/10000/}), while
     * flat mcool fixtures use indices ({@code /0/}, {@code /1/}, ...), so the width is always
     * derived from the group's own {@code bins/start} / {@code bins/end}.
     */
    private static List<Resolution> coolerResolutions(HdfFile hdf) {
        List<Resolution> found = new ArrayList<>();

        // (1) Standard mcool: /resolutions/<bp>/...
        Node resolutions = node(hdf, "resolutions");
        if (resolutions instanceof Group) {
            for (Map.Entry<String, Node> e : ((Group) resolutions).getChildren().entrySet()) {
                if (!(e.getValue() instanceof Group) || !e.getKey().matches("\\d+")) {
                    continue;
                }
                String prefix = "resolutions/" + e.getKey() + "/";
                if (isCoolerGroup(hdf, prefix)) {
                    found.add(new Resolution(deriveBinSize(hdf, prefix, e.getKey()), prefix));
                }
            }
        }

        // (2) Flat mcool: numeric groups directly at the root (/0/, /1/, ...).
        if (found.isEmpty()) {
            for (Map.Entry<String, Node> e : hdf.getChildren().entrySet()) {
                if (!(e.getValue() instanceof Group) || !e.getKey().matches("\\d+")) {
                    continue;
                }
                String prefix = e.getKey() + "/";
                if (isCoolerGroup(hdf, prefix)) {
                    found.add(new Resolution(deriveBinSize(hdf, prefix, null), prefix));
                }
            }
        }

        // (3) Single-resolution cooler at the root.
        if (found.isEmpty() && isCoolerGroup(hdf, "")) {
            found.add(new Resolution(deriveBinSize(hdf, "", null), ""));
        }
        return found;
    }

    /**
     * Median width of the first few bins. Only a tiny hyperslab is read, so listing the
     * resolutions of a 500 MB mcool stays cheap.
     */
    private static int deriveBinSize(HdfFile hdf, String prefix, String groupName) {
        Dataset dsStart = dataset(hdf, prefix + "bins/start");
        Dataset dsEnd = dataset(hdf, prefix + "bins/end");
        if (dsStart != null && dsEnd != null && dsStart.getDimensions()[0] > 0) {
            int n = (int) Math.min(64, dsStart.getDimensions()[0]);
            try {
                int[] starts = toIntArray(dsStart.getData(new long[]{0}, new int[]{n}));
                int[] ends = toIntArray(dsEnd.getData(new long[]{0}, new int[]{n}));
                int[] widths = new int[n];
                for (int i = 0; i < n; i++) {
                    widths[i] = ends[i] - starts[i];
                }
                Arrays.sort(widths);
                if (widths[n / 2] > 0) {
                    return widths[n / 2];
                }
            } catch (Exception e) {
                log.warn("Could not derive bin size for " + prefix + ": " + e.getMessage());
            }
        }
        // Fall back to the group name when it plausibly names a bp resolution.
        if (groupName != null && groupName.matches("\\d+")) {
            try {
                int v = Integer.parseInt(groupName);
                if (v >= 1_000) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // not a usable resolution name
            }
        }
        return 1;
    }

    /** @return the element of {@code sizes} closest to {@code target} */
    private static int closestTo(List<Integer> sizes, int target) {
        int best = sizes.get(0);
        for (int s : sizes) {
            if (Math.abs(s - target) < Math.abs(best - target)) {
                best = s;
            }
        }
        return best;
    }

    /**
     * Open one resolution lazily. Coolers without {@code indexes/bin1_offset} (rare, old
     * files) cannot be windowed, so they keep the original eager {@link #loadCooler} path.
     */
    private static HiCSource openResolution(HdfFile hdf, String path, Resolution r) throws IOException {
        if (dataset(hdf, r.prefix + "indexes/bin1_offset") == null) {
            return loadCooler(hdf, path, r.prefix);
        }
        return new CoolerSource(hdf, r.prefix, path, r.binSize);
    }

    /**
     * HDF5 matrix that keeps the file open. cooler archives with several resolutions
     * (.mcool) expose all of them, so the track menu can switch bin size exactly like it
     * does for a Juicer .hic; the other layouts simply report a single resolution.
     * <p>
     * Resolutions are loaded on demand and only the active one (plus the one before it) is
     * kept, otherwise walking through the menu would end up holding every matrix in memory.
     */
    static final class HdfSource implements HiCSource {

        private final HdfFile hdf;
        private final String path;
        private final List<Integer> resolutions;
        private final Map<Integer, Resolution> byBinSize;
        private HiCSource current;
        private HiCSource previous;
        private int previousBinSize = -1;

        private HdfSource(HdfFile hdf, String path, HiCSource current,
                          List<Integer> resolutions, Map<Integer, Resolution> byBinSize) {
            this.hdf = hdf;
            this.path = path;
            this.current = current;
            this.resolutions = resolutions;
            this.byBinSize = byBinSize;
        }

        static HdfSource build(HdfFile hdf, String path) throws IOException {
            try {
                if (dataset(hdf, "matrix/indptr") != null) {
                    return new HdfSource(hdf, path, loadHiCExplorer(hdf, path),
                            java.util.Collections.emptyList(), java.util.Collections.emptyMap());
                }

                List<Resolution> found = coolerResolutions(hdf);
                if (found.isEmpty()) {
                    throw new IOException("Unsupported Hi-C file layout: " + path);
                }

                // Keyed by the *derived* bin width, which is what getResolutions() exposes and
                // what setBinSize() receives. Group names may be indices, never bp.
                Map<Integer, Resolution> bySize = new LinkedHashMap<>();
                for (Resolution r : found) {
                    bySize.putIfAbsent(r.binSize, r);
                }
                List<Integer> sizes = new ArrayList<>(bySize.keySet());
                sizes.sort(Integer::compare);

                // Prefer ~10 kb to start with: fine enough to resolve TADs; the renderer
                // re-selects per zoom level afterwards. CoolerSource reads on demand, so
                // picking a fine resolution no longer loads the whole pixel table.
                int chosen = closestTo(sizes, 10_000);
                Resolution start = bySize.get(chosen);
                log.info("cooler resolutions " + sizes + " from " + found.size()
                        + " group(s) -> starting at " + chosen + " bp [" + start.prefix + "]");
                return new HdfSource(hdf, path, openResolution(hdf, path, start), sizes, bySize);
            } catch (IOException | RuntimeException e) {
                try {
                    hdf.close();
                } catch (Exception ignored) {
                    // the original failure is what matters
                }
                throw e;
            }
        }

        @Override
        public List<String> getChromosomes() {
            return current.getChromosomes();
        }

        @Override
        public int getChromosomeLength(String chr) {
            return current.getChromosomeLength(chr);
        }

        @Override
        public synchronized int getBinSize() {
            return current.getBinSize();
        }

        @Override
        public int getBinCount() {
            return current.getBinCount();
        }

        @Override
        public int[] getChromBinRange(String chr) {
            return current.getChromBinRange(chr);
        }

        @Override
        public synchronized int binAt(String chr, double bp) {
            return current.binAt(chr, bp);
        }

        @Override
        public synchronized int[] getBinsOverlapping(String chr, int start, int end) {
            return current.getBinsOverlapping(chr, start, end);
        }

        @Override
        public double getValue(int binX, int binY) {
            return current.getValue(binX, binY);
        }

        @Override
        public List<Integer> getResolutions() {
            // A single-resolution matrix has nothing to choose from; keep the menu hidden,
            // as it was for .cool / HiCExplorer files before mcool support was generalised.
            return resolutions.size() < 2
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(resolutions);
        }

        @Override
        public synchronized void setBinSize(int newBinSize) {
            if (byBinSize.isEmpty() || newBinSize == current.getBinSize()
                    || !byBinSize.containsKey(newBinSize)) {
                return;
            }
            HiCSource data;
            if (previous != null && previousBinSize == newBinSize) {
                data = previous;                 // zooming back out is instant
            } else {
                try {
                    data = openResolution(hdf, path, byBinSize.get(newBinSize));
                } catch (IOException e) {
                    log.error("Could not read resolution " + newBinSize + " bp from " + path, e);
                    return;
                }
            }
            previousBinSize = current.getBinSize();
            previous = current;
            current = data;
            log.info("Hi-C resolution -> " + newBinSize + " bp, " + data.getBinCount() + " bins");
        }

        @Override
        public synchronized void autoSelectBinSize(double bpPerPixel, int windowWidth) {
            if (resolutions.size() < 2 || !(bpPerPixel > 0)) {
                return;
            }
            // Same rule as the .hic reader: at least ~2 pixels per bin, and never more than
            // MAX_BINS_ACROSS bins for one repaint.
            double target = 2 * bpPerPixel;
            if (windowWidth > 0) {
                target = Math.max(target, bpPerPixel * windowWidth / MAX_BINS_ACROSS);
            }
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int r : resolutions) {
                if (r < MIN_AUTO_BIN) {
                    continue;
                }
                double d = Math.abs(Math.log(r / target));
                if (d < bestDistance) {
                    bestDistance = d;
                    best = r;
                }
            }
            setBinSize(best < 0 ? current.getBinSize() : best);
        }

        @Override
        public void close() throws IOException {
            if (previous != null) {
                previous.close();
            }
            if (current != null) {
                current.close();
            }
            previous = null;
            current = null;
            hdf.close();
        }
    }

    static Node node(HdfFile hdf, String path) {
        try {
            return hdf.getByPath("/" + path);
        } catch (Exception e) {
            try {
                return hdf.getByPath(path);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static Dataset dataset(HdfFile hdf, String path) {
        try {
            return hdf.getDatasetByPath("/" + path);
        } catch (Exception e) {
            try {
                return hdf.getDatasetByPath(path);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static String[] toStringArray(Object data) throws IOException {
        if (data instanceof String[]) {
            return (String[]) data;
        }
        if (data instanceof byte[]) {
            byte[] bytes = (byte[]) data;
            String[] out = new String[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                out[i] = new String(new byte[]{bytes[i]}, StandardCharsets.US_ASCII);
            }
            return out;
        }
        if (data instanceof Object[]) {
            Object[] arr = (Object[]) data;
            List<String> out = new ArrayList<>(arr.length);
            for (Object o : arr) {
                if (o instanceof byte[]) {
                    out.add(new String((byte[]) o, StandardCharsets.US_ASCII).trim());
                } else {
                    out.add(o == null ? "" : o.toString());
                }
            }
            return out.toArray(new String[0]);
        }
        throw new IOException("Unsupported string dataset type: "
                + (data == null ? "null" : data.getClass().getName()));
    }

    static int[] toIntArray(Object data) throws IOException {
        if (data instanceof int[]) {
            return (int[]) data;
        }
        if (data instanceof long[]) {
            long[] src = (long[]) data;
            int[] out = new int[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = (int) src[i];
            }
            return out;
        }
        if (data instanceof short[]) {
            short[] src = (short[]) data;
            int[] out = new int[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i];
            }
            return out;
        }
        if (data instanceof double[]) {
            double[] src = (double[]) data;
            int[] out = new int[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = (int) src[i];
            }
            return out;
        }
        throw new IOException("Unsupported integer dataset type: "
                + (data == null ? "null" : data.getClass().getName()));
    }

    static long[] toLongArray(Object data) throws IOException {
        if (data instanceof long[]) {
            return (long[]) data;
        }
        if (data instanceof int[]) {
            int[] src = (int[]) data;
            long[] out = new long[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i];
            }
            return out;
        }
        throw new IOException("Unsupported long dataset type: "
                + (data == null ? "null" : data.getClass().getName()));
    }

    static double[] toDoubleArray(Object data) throws IOException {
        if (data instanceof double[]) {
            return (double[]) data;
        }
        if (data instanceof float[]) {
            float[] src = (float[]) data;
            double[] out = new double[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i];
            }
            return out;
        }
        if (data instanceof int[]) {
            int[] src = (int[]) data;
            double[] out = new double[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i];
            }
            return out;
        }
        if (data instanceof long[]) {
            long[] src = (long[]) data;
            double[] out = new double[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i];
            }
            return out;
        }
        throw new IOException("Unsupported numeric dataset type: "
                + (data == null ? "null" : data.getClass().getName()));
    }

    /**
     * Exposes a start-to-end {@link SeekableStream} as an {@link InputStream} so a gzip-wrapped
     * matrix can be inflated by {@link GZIPInputStream}. HDF5 gzip inputs are only ever read
     * sequentially from offset 0, so no seeking is required.
     */
    private static final class SeekableStreamInputStream extends InputStream {

        private final SeekableStream stream;
        private final byte[] one = new byte[1];

        SeekableStreamInputStream(SeekableStream stream) {
            this.stream = stream;
        }

        @Override
        public int read() throws IOException {
            int n = stream.read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = stream.read(b, off, len);
            return n <= 0 ? -1 : n;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
