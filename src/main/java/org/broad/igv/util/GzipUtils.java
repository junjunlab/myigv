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
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.util;

import htsjdk.samtools.util.BlockCompressedInputStream;
import org.broad.igv.Globals;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

/**
 * Helpers for reading gzip-compressed inputs "directly".  Plain gzip is not seekable, so it cannot
 * be indexed (.idx / .fai) in place.  For the formats that need random access (GTF/GFF annotations
 * and reference FASTA) we transparently decompress once to a sibling file and index that instead.
 * <p>
 * The decompressed file is reused on subsequent loads as long as it is at least as new as the
 * compressed source, so the cost is paid once.
 */
public class GzipUtils {

    private static Logger log = LogManager.getLogger(GzipUtils.class);

    private static final int BUFFER_SIZE = 1 << 20;   // 1 MB
    private static final String GZ_SUFFIX = ".gz";
    private static final String BGZ_SUFFIX = ".bgz";

    /**
     * @return true for names ending in .gz or .bgz (case insensitive)
     */
    public static boolean hasCompressionSuffix(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(GZ_SUFFIX) || lower.endsWith(BGZ_SUFFIX);
    }

    /**
     * Remove a trailing .gz / .bgz.  Other paths are returned unchanged.
     */
    public static String stripCompressionSuffix(String path) {
        if (path == null) return null;
        String lower = path.toLowerCase();
        if (lower.endsWith(GZ_SUFFIX)) return path.substring(0, path.length() - GZ_SUFFIX.length());
        if (lower.endsWith(BGZ_SUFFIX)) return path.substring(0, path.length() - BGZ_SUFFIX.length());
        return path;
    }

    /**
     * @return true if the file starts with the gzip magic bytes (1f 8b)
     */
    public static boolean isGzip(File file) {
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new FileInputStream(file)) {
            int b1 = in.read();
            int b2 = in.read();
            return b1 == 0x1f && b2 == 0x8b;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return true if the file is BGZF (blocked gzip), detected from the "BC" extra subfield
     */
    public static boolean isBgzf(File file) {
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[16];
            if (in.readNBytes(header, 0, header.length) < header.length) return false;
            if ((header[0] & 0xff) != 0x1f || (header[1] & 0xff) != 0x8b) return false;
            if (header[2] != 8 || (header[3] & 0x04) == 0) return false;   // deflate + FEXTRA
            int xlen = (header[10] & 0xff) | ((header[11] & 0xff) << 8);
            if (xlen < 6) return false;
            return header[12] == 'B' && header[13] == 'C';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Decompress a local .gz / .bgz file to a sibling file without the compression suffix, and
     * return the path of the uncompressed file.  Returns {@code path} unchanged when it is not a
     * local compressed file, when the content is not actually gzip, or when decompression fails.
     * <p>
     * An existing sibling that is at least as new as the source is reused.
     */
    public static String decompressToSibling(String path) {
        if (path == null || !hasCompressionSuffix(path)) return path;

        File source = new File(path);
        if (!source.isFile() || !isGzip(source)) return path;

        String targetPath = stripCompressionSuffix(path);
        File target = new File(targetPath);
        if (target.isFile() && target.length() > 0 && target.lastModified() >= source.lastModified()) {
            return targetPath;
        }

        File tmp = new File(targetPath + ".tmp");
        status("Decompressing " + source.getName() + " ...");
        log.info("Decompressing " + source.getAbsolutePath() + " -> " + target.getAbsolutePath());

        try (InputStream in = isBgzf(source)
                ? new BlockCompressedInputStream(new FileInputStream(source))
                : new GZIPInputStream(new FileInputStream(source), BUFFER_SIZE);
             OutputStream out = new FileOutputStream(tmp)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            log.error("Could not decompress " + path + ": " + e.getMessage(), e);
            deleteQuietly(tmp);
            return path;
        }

        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Could not move decompressed file into place: " + e.getMessage(), e);
            deleteQuietly(tmp);
            return path;
        }

        return targetPath;
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void status(String message) {
        try {
            if (IGV.hasInstance() && !Globals.isHeadless() && !SwingUtilities.isEventDispatchThread()) {
                IGV.getInstance().setStatusBarMessage3(message);
            }
        } catch (Throwable ignored) {
            // status bar is best effort only
        }
    }
}
