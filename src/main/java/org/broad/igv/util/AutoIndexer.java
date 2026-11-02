/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2007-2015 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.util;

import htsjdk.tribble.FeatureCodec;
import htsjdk.tribble.index.Index;
import htsjdk.tribble.index.IndexFactory;
import org.broad.igv.Globals;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.tribble.CodecFactory;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.tools.IgvTools;
import org.broad.igv.tools.sort.Sorter;
import org.broad.igv.tools.sort.SorterFactory;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Automatically prepares local feature files (GTF / GFF / GFF3 / GVF) for use by IGV:
 * <p>
 * <ul>
 * <li>if the file is not sorted by (chromosome, start) an IGV-sorted copy
 * {@code <name>.sorted<ext>} is written next to it;</li>
 * <li>a tribble index ({@code .idx}) is created if one does not exist.</li>
 * </ul>
 * <p>
 * This replaces the "create an index?" / "file must be sorted" dialogs, so importing an
 * annotation file needs no manual igvtools step.  Disable with {@code -Dmyigv.autoIndex=false}
 * (or the {@code MYIGV_AUTO_INDEX=false} environment variable).
 * <p>
 * Remote files and compressed files (which would require bgzip+tabix) are left untouched.
 */
public class AutoIndexer {

    private static Logger log = LogManager.getLogger(AutoIndexer.class);

    public static final String SORTED_SUFFIX = ".sorted";

    /**
     * @return true unless explicitly disabled by system property / environment variable.
     */
    public static boolean isEnabled() {
        String value = System.getProperty("myigv.autoIndex");
        if (value == null) {
            value = System.getenv("MYIGV_AUTO_INDEX");
        }
        if (value == null) return true;
        String v = value.trim().toLowerCase();
        return !(v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"));
    }

    /**
     * Sort (if needed) and index a local annotation file.
     *
     * @param path   resource path
     * @param genome current genome, used to pick the feature codec (may be null)
     * @return the path that should actually be loaded -- either {@code path} itself, or the
     * sorted copy when the source file was out of order
     */
    public static String ensureSortedAndIndexed(String path, Genome genome) {
        if (!isEnabled() || path == null) return path;
        try {
            return doEnsure(path, genome);
        } catch (Throwable t) {
            // Never let housekeeping break the actual data load
            log.error("Could not sort/index " + path + ": " + t.getMessage(), t);
            return path;
        }
    }

    private static String doEnsure(String path, Genome genome) throws Exception {

        if (!isLocalAnnotationFile(path)) return path;

        File file = new File(path);
        if (!file.isFile()) return path;

        // Already indexed -- nothing to do
        if (hasIndex(path)) return path;

        String target = path;
        File targetFile = file;

        File sortedFile = sortedCopyOf(file);
        boolean sortedCopyUsable = sortedFile.isFile()
                && sortedFile.length() > 0
                && sortedFile.lastModified() >= file.lastModified();

        if (sortedCopyUsable) {
            if (hasIndex(sortedFile.getPath())) {
                log.info("Using previously sorted annotation file " + sortedFile.getAbsolutePath());
                return sortedFile.getPath();
            }
            target = sortedFile.getPath();
            targetFile = sortedFile;
        } else if (!isSorted(file)) {
            status("Sorting " + file.getName() + " ...");
            log.info("Annotation file is not sorted, sorting to " + sortedFile.getAbsolutePath());
            deleteQuietly(sortedFile);
            Sorter sorter = SorterFactory.getSorter(file, sortedFile);
            if (sorter == null) {
                log.warn("No sorter available for " + file.getName() + ", loading without an index");
                return path;
            }
            sorter.run();
            if (!sortedFile.isFile() || sortedFile.length() == 0) {
                log.warn("Sorting produced no output for " + file.getName() + ", loading without an index");
                return path;
            }
            target = sortedFile.getPath();
            targetFile = sortedFile;
        }

        if (!createIndex(targetFile, target, genome)) {
            return target;
        }
        return target;
    }

    /**
     * Build a tribble (.idx) index for a sorted annotation file.
     *
     * @return true when an index file was written
     */
    public static boolean createIndex(File file, String path, Genome genome) {
        String idxPath = path + ".idx";
        File idxFile = new File(idxPath);
        if (hasIndex(path)) return true;

        status("Indexing " + file.getName() + " ...");
        FeatureCodec codec = null;
        try {
            codec = CodecFactory.getCodec(new ResourceLocator(path), genome);
            if (codec == null) {
                log.warn("No codec for " + path + ", skipping index creation");
                return false;
            }
            Index index = IndexFactory.createLinearIndex(file, codec, IgvTools.LINEAR_BIN_SIZE);
            if (index == null) {
                log.warn("Index creation returned no index for " + path);
                return false;
            }
            IgvTools.writeTribbleIndex(index, idxPath);
            boolean ok = idxFile.isFile() && idxFile.length() > 0;
            if (ok) {
                log.info("Created index " + idxPath);
            } else {
                deleteQuietly(idxFile);
            }
            return ok;
        } catch (Exception e) {
            log.error("Could not create index for " + path + ": " + e.getMessage(), e);
            deleteQuietly(idxFile);   // never leave a corrupt index behind
            return false;
        }
    }

    /**
     * Check that records are ordered by start position within each chromosome and that each
     * chromosome appears in a single contiguous block (what htsjdk's linear index requires).
     */
    static boolean isSorted(File file) throws IOException {
        Set<String> seenChromosomes = new HashSet<>();
        String currentChr = null;
        int lastStart = -1;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 256 * 1024)) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                char first = line.charAt(0);
                if (first == '#') continue;
                if (line.startsWith("track") || line.startsWith("browser")) continue;

                int t1 = line.indexOf('\t');
                if (t1 < 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 < 0) continue;
                int t3 = line.indexOf('\t', t2 + 1);
                if (t3 < 0) continue;
                int t4 = line.indexOf('\t', t3 + 1);
                if (t4 < 0) continue;

                int start;
                try {
                    start = Integer.parseInt(line.substring(t3 + 1, t4).trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                String chr = line.substring(0, t1);
                if (!chr.equals(currentChr)) {
                    if (!seenChromosomes.add(chr)) {
                        return false;   // chromosome reappears after another one
                    }
                    currentChr = chr;
                    lastStart = -1;
                }
                if (start < lastStart) {
                    return false;
                }
                lastStart = start;
            }
        }
        return true;
    }

    /**
     * {@code genes.gtf} -> {@code genes.sorted.gtf}
     */
    static File sortedCopyOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(file.getAbsoluteFile().getParentFile(), base + SORTED_SUFFIX + ext);
    }

    private static boolean hasIndex(String path) {
        File idx = new File(path + ".idx");
        return idx.isFile() && idx.length() > 0;
    }

    /**
     * True for a local, uncompressed GTF / GFF / GFF3 / GVF file.
     */
    static boolean isLocalAnnotationFile(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://")
                || lower.startsWith("s3://") || lower.startsWith("gs://") || lower.startsWith("data:")) {
            return false;
        }
        // Compressed inputs would need bgzip + tabix; leave those to the user
        if (lower.endsWith(".gz") || lower.endsWith(".bgz") || lower.endsWith(".bz2") || lower.endsWith(".zip")) {
            return false;
        }
        return lower.endsWith(".gtf") || lower.endsWith(".gff") || lower.endsWith(".gff3") || lower.endsWith(".gvf");
    }

    private static void status(String message) {
        try {
            if (IGV.hasInstance() && !Globals.isHeadless() && !SwingUtilities.isEventDispatchThread()) {
                IGV.getInstance().setStatusBarMessage3(message);
            }
        } catch (Exception e) {
            // Status bar is a nicety only
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file.exists()) file.delete();
        } catch (Exception e) {
            // ignore
        }
    }
}
