package org.broad.igv.hic;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Group;
import io.jhdf.api.Node;
import io.jhdf.filter.FilterManager;
import org.broad.igv.logging.LogManager;
import org.broad.igv.util.GzipUtils;
import org.broad.igv.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Hi-C contact matrices from HDF5 files.
 * <p>
 * Two layouts are supported, which covers what pyGenomeTracks accepts:
 * <ul>
 *   <li><b>HiCExplorer .h5</b> — {@code /intervals/*} plus a CSR matrix under {@code /matrix}</li>
 *   <li><b>cooler .cool</b> — {@code /bins} plus a COO pixel list under {@code /pixels}</li>
 * </ul>
 * HiCExplorer writes its datasets through PyTables with blosc compression, which needs the
 * pure-Java {@link BloscFilter} registered below.
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

        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("Hi-C file not found: " + path);
        }

        // Gzip-wrapped matrices cannot be read in place; decompress once to a sibling file.
        if (path.toLowerCase().endsWith(".gz")) {
            String decompressed = GzipUtils.decompressToSibling(path);
            if (decompressed == null || decompressed.equals(path)) {
                throw new IOException("Could not decompress Hi-C file: " + path);
            }
            log.info("Decompressed Hi-C file to " + decompressed);
            return load(decompressed);
        }

        if (path.toLowerCase().endsWith(".hic")) {
            log.info("Loading Juicer .hic matrix " + path);
            return new HicFileSource(path);
        }

        log.info("Loading Hi-C matrix " + path + " (blosc filter registered: " + BLOSC_REGISTERED + ")");

        try (HdfFile hdf = new HdfFile(file)) {

            if (dataset(hdf, "matrix/indptr") != null) {
                return loadHiCExplorer(hdf, path);
            }
            Node resolutions = node(hdf, "resolutions");
            if (resolutions instanceof Group) {
                return loadMcool((Group) resolutions, hdf, path);
            }
            if (dataset(hdf, "pixels/bin1_id") != null || dataset(hdf, "pixels/count") != null) {
                return loadCooler(hdf, path, "");
            }
            throw new IOException("Unsupported Hi-C file layout: " + path);
        }
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

    private static HiCData loadMcool(Group resolutions, HdfFile hdf, String path) throws IOException {

        List<String> names = new ArrayList<>(resolutions.getChildren().keySet());
        names.removeIf(n -> !n.matches("\\d+"));

        if (names.isEmpty()) {
            throw new IOException("mcool file has no resolution groups: " + path);
        }

        names.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));

        // Prefer ~10kb: fine enough to resolve TADs, small enough to keep memory sane.
        String chosen = names.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (String n : names) {
            int diff = Math.abs(Integer.parseInt(n) - 10_000);
            if (diff < bestDiff) {
                bestDiff = diff;
                chosen = n;
            }
        }

        log.info("mcool resolutions " + names + " -> using " + chosen);
        return loadCooler(hdf, path, "resolutions/" + chosen + "/");
    }

    private static Node node(HdfFile hdf, String path) {
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

    private static Dataset dataset(HdfFile hdf, String path) {
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

    private static String[] toStringArray(Object data) throws IOException {
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

    private static int[] toIntArray(Object data) throws IOException {
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

    private static long[] toLongArray(Object data) throws IOException {
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

    private static double[] toDoubleArray(Object data) throws IOException {
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
}
