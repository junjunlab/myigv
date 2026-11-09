package org.broad.igv.bedpe;

import org.broad.igv.logging.*;
import org.broad.igv.Globals;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.track.TrackProperties;
import org.broad.igv.ui.color.ColorUtilities;
import org.broad.igv.util.ParsingUtils;
import org.broad.igv.util.ResourceLocator;

import java.awt.*;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * Parser for BEDPE (paired-end BED / interaction) files.
 *
 * <p>Files exported from Excel/WPS ("Unicode Text") or written by PowerShell
 * {@code Out-File} are UTF-16LE with a BOM.  Reading them as UTF-8 interleaves
 * NUL characters with every character and makes the numeric columns
 * unparseable.  This parser therefore probes the raw byte stream for a BOM
 * (or, without a BOM, for a UTF-16 NUL-byte pattern) before building the
 * reader.  The detection is deliberately local to BEDPE so that the many
 * other text formats handled by {@link ParsingUtils} are unaffected.</p>
 *
 * Created by jrobinso on 6/29/18.
 */
public class BedPEParser {

    private static Logger log = LogManager.getLogger(BedPEParser.class);

    /** Number of leading bytes examined to guess the encoding. */
    private static final int PROBE_READ_SIZE = 8192;
    /** Mark limit of the rewindable stream; must be >= PROBE_READ_SIZE. */
    private static final int PROBE_BUFFER_SIZE = 16384;
    /** Minimum fraction of NUL bytes needed to classify a BOM-less file as UTF-16. */
    private static final double UTF16_NUL_RATIO_THRESHOLD = 0.20;
    /** Cap on the number of skipped-line warnings, to avoid flooding the log. */
    private static final int MAX_LOGGED_SKIPPED_LINES = 5;

    /** Default column headers from the BedPE spec; a header line can override them. */
    private static final String[] DEFAULT_COLUMNS = {
            "chrom1", "start1", "stop1", "chrom2", "start2", "stop2",
            "name", "score", "strand1", "strand2"
    };

    enum DatasetType {TENX, CLUSTER, UNKNOWN}

    public static Dataset parse(ResourceLocator locator, Genome genome) throws IOException {

        int colorColumn = -1;
        int thicknessColumn = -1;
        DatasetType type = DatasetType.UNKNOWN;

        String[] columns = DEFAULT_COLUMNS;
        String[] columnNames = normalizeColumnNames(columns);
        boolean col7isNumeric = true;   // Until proven otherwise

        Map<String, Color> colorCache = new HashMap<>();
        List<BedPEFeature> features = new ArrayList<>();
        BufferedReader br = null;

        try {
            br = openBedPEReader(locator);
            String nextLine;
            boolean firstLine = true;
            int[] skippedLineCount = {0};
            while ((nextLine = br.readLine()) != null) {

                // Defensive cleanup: a BOM character or stray NULs must never
                // reach the tokenizer / numeric parsers.
                nextLine = cleanLine(nextLine);
                if (isBlankLine(nextLine)) {
                    continue;
                }

                if (nextLine.startsWith("#columns")) {
                    // An IGV hack, not sure anyone is using this
                    try {
                        String[] t1 = ParsingUtils.WHITESPACE_PATTERN.split(nextLine);
                        if (t1.length == 2) {
                            String[] t2 = ParsingUtils.SEMI_COLON_PATTERN.split(t1[1]);
                            for (String keyValue : t2) {
                                String[] t = keyValue.split("=");
                                if (t[0].equals("color")) {
                                    colorColumn = Integer.parseInt(t[1]) - 1;
                                } else if (t[0].equals("thickness")) {
                                    thicknessColumn = Integer.parseInt(t[1]) - 1;
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        log.error("Error parsing #column line.", e);
                    }
                } else if (nextLine.trim().equals("#chrom1\tstart1\tstop1\tchrom2\tstart2\tstop2\tname\tqual\tstrand1\tstrand2\tfilters\tinfo")) {
                    type = DatasetType.TENX;
                }

                if (nextLine.startsWith("#") || nextLine.startsWith("chr1\tx1\tx2")) {

                    // -1 keeps trailing empty columns, which are common in
                    // spreadsheet exports (e.g. "name\t\t\t\t").
                    String[] tokens = Globals.tabPattern.split(nextLine, -1);
                    if (tokens.length >= 6) {
                        columns = tokens;
                        columnNames = normalizeColumnNames(columns);
                        for (int i = 6; i < columns.length; i++) {
                            if (columns[i].equalsIgnoreCase("color")) {
                                colorColumn = i;
                            } else if (columns[i].equalsIgnoreCase("thickness")) {
                                thicknessColumn = i;
                            }
                        }
                    }
                } else if (nextLine.startsWith("track") || nextLine.startsWith("##track")) {
                    TrackProperties trackProperties = new TrackProperties();
                    ParsingUtils.parseTrackLine(nextLine, trackProperties);
                } else if (firstLine && nextLine.startsWith("chromosome1\tx1\tx2") || nextLine.startsWith("chr1\tx1\tx2")) {
                    columns = Globals.tabPattern.split(nextLine, -1);
                    columnNames = normalizeColumnNames(columns);
                    for (int i = 6; i < columns.length; i++) {
                        if (columns[i].equalsIgnoreCase("color")) {
                            colorColumn = i;
                        }
                    }
                } else {
                    String[] tokens = Globals.tabPattern.split(nextLine, -1);

                    if (tokens.length < 6) {
                        warnSkippedLine("fewer than 6 columns", nextLine, skippedLineCount);
                        continue;
                    }

                    String chr1 = genome == null ? tokens[0] : genome.getCanonicalChrName(tokens[0]);
                    String chr2 = genome == null ? tokens[3] : genome.getCanonicalChrName(tokens[3]);
                    if (chr1 == null || chr1.trim().isEmpty() || chr2 == null || chr2.trim().isEmpty()) {
                        warnSkippedLine("missing chromosome", nextLine, skippedLineCount);
                        continue;
                    }

                    // A single malformed row must not abort the whole track.
                    int start1;
                    int end1;
                    int start2;
                    int end2;
                    try {
                        start1 = Integer.parseInt(tokens[1].trim());
                        end1 = Integer.parseInt(tokens[2].trim());
                        start2 = Integer.parseInt(tokens[4].trim());
                        end2 = Integer.parseInt(tokens[5].trim());
                    } catch (NumberFormatException e) {
                        warnSkippedLine("invalid coordinates", nextLine, skippedLineCount);
                        continue;
                    }

                    BedPEFeature feature = new BedPEFeature(chr1, start1, end1, chr2, start2, end2);

                    if (tokens.length > 6) {
                        feature.name = tokens[6];
                        col7isNumeric = col7isNumeric && isNumeric(tokens[6]);
                    } else {
                        col7isNumeric = false;
                    }

                    // Missing / empty score column simply means "no score" (0).
                    if (tokens.length > 7) {
                        feature.scoreString = tokens[7];
                        feature.score = parseDoubleOrZero(tokens[7]);
                    } else {
                        feature.score = 0;
                    }

                    if (tokens.length > 8) {
                        Map<String, String> attributes = new LinkedHashMap<>();

                        for (int i = 8; i < tokens.length; i++) {

                            String t = tokens[i];
                            String c = columnKey(columnNames, i);

                            if (c.equals("info") && t.contains("=")) {
                                String[] kvPairs = Globals.semicolonPattern.split(t);
                                for (String kvPair : kvPairs) {
                                    String[] kv = Globals.equalPattern.split(kvPair, 2);
                                    if (kv.length > 1) {
                                        attributes.put(kv[0], kv[1]);
                                    }
                                }
                            } else if (!t.trim().isEmpty()) {
                                // Empty trailing columns add nothing to the tooltip.
                                attributes.put(c, t);
                            }
                        }
                        feature.attributes = attributes;
                        feature.type = attributes.get("TYPE");
                    }

                    if (colorColumn > 0 && tokens.length > colorColumn) {
                        String colorString = tokens[colorColumn].trim();
                        if (!colorString.isEmpty() && !colorString.equals(".")) {
                            Color c = colorCache.get(colorString);
                            if (c == null) {
                                try {
                                    c = ColorUtilities.stringToColor(colorString);
                                } catch (RuntimeException e) {
                                    log.warn("Skipping invalid color '" + colorString + "' in BEDPE line: " + nextLine, e);
                                }
                                if (c != null) {
                                    colorCache.put(colorString, c);
                                }
                            }
                            feature.color = c;
                        }
                    }

                    if (thicknessColumn > 0 && tokens.length > thicknessColumn) {
                        String thicknessString = tokens[thicknessColumn].trim();
                        if (!thicknessString.isEmpty()) {
                            try {
                                feature.thickness = Integer.parseInt(thicknessString);
                            } catch (NumberFormatException e) {
                                // Keep the default thickness (1) for a bad/missing value.
                                warnSkippedColumn("thickness", thicknessString, nextLine);
                            }
                        }
                    }

                    // Keep every column so the track can offer the user a choice of which
                    // one drives the Proportional Arcs height (not just the score column).
                    // Empty header names become stable col8/col9/... keys.
                    feature.columnValues = new LinkedHashMap<>();
                    for (int i = 0; i < tokens.length; i++) {
                        feature.columnValues.put(columnKey(columnNames, i), tokens[i]);
                    }

                    features.add(feature);
                }
                firstLine = false;
            }


            // A hack to detect "interaction" bedpe files, which are not spec compliant.  Interaction score is column 7
            if (col7isNumeric) {
                for (BedPEFeature f : features) {
                    try {
                        f.score = Double.parseDouble(f.name.trim());
                        f.scoreString = f.name;
                        f.name = null;
                    } catch (RuntimeException e) {
                        log.warn("Skipping non-numeric interaction score '" + f.name + "'", e);
                        f.score = 0;
                    }
                }
                if (type == DatasetType.UNKNOWN) {
                    type = DatasetType.CLUSTER;   // A guess
                }
            }

            return new Dataset(type, features);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.warn("Error closing BEDPE reader", e);
                }
            }
        }
    }

    /**
     * Open a reader for a BEDPE resource, auto-detecting UTF-8 / UTF-16LE / UTF-16BE.
     * Detection uses the BOM first and a NUL-byte heuristic as a fallback for
     * BOM-less UTF-16 exports.  The underlying stream is rewindable so no data
     * is lost, and gzip/URL handling stays the same as before.
     *
     * @param locator resource to read
     * @return buffered reader decoding with the detected charset
     * @throws IOException if the resource cannot be opened
     */
    private static BufferedReader openBedPEReader(ResourceLocator locator) throws IOException {

        InputStream rawStream = ParsingUtils.openInputStreamGZ(new ResourceLocator(locator.getPath()));
        BufferedInputStream probeStream = new BufferedInputStream(rawStream, PROBE_BUFFER_SIZE);
        probeStream.mark(PROBE_BUFFER_SIZE);

        byte[] probe = new byte[PROBE_READ_SIZE];
        int bytesRead = 0;
        while (bytesRead < probe.length) {
            int count = probeStream.read(probe, bytesRead, probe.length - bytesRead);
            if (count < 0) {
                break;
            }
            bytesRead += count;
        }
        probeStream.reset();

        Charset charset = detectCharset(probe, bytesRead);
        int bomLength = bomLength(probe, bytesRead);
        if (bomLength > 0) {
            skipFully(probeStream, bomLength);
        }
        log.info("Reading BEDPE file " + locator.getPath() + " with charset " + charset.name());
        return new BufferedReader(new InputStreamReader(probeStream, charset));
    }

    /**
     * Detect the charset from the leading bytes of the file.
     *
     * @param bytes  leading bytes
     * @param length number of valid bytes in {@code bytes}
     * @return the charset to use, never {@code null}
     */
    private static Charset detectCharset(byte[] bytes, int length) {
        if (length >= 3 && u(bytes[0]) == 0xEF && u(bytes[1]) == 0xBB && u(bytes[2]) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (length >= 2 && u(bytes[0]) == 0xFF && u(bytes[1]) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (length >= 2 && u(bytes[0]) == 0xFE && u(bytes[1]) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        // No BOM: ASCII-ish UTF-16 text has a NUL in every other byte.  The
        // dominant parity tells us the byte order.  Plain UTF-8 has none.
        if (length >= 4) {
            int nulEven = 0;
            int nulOdd = 0;
            for (int i = 0; i < length; i++) {
                if (bytes[i] == 0) {
                    if ((i & 1) == 0) {
                        nulEven++;
                    } else {
                        nulOdd++;
                    }
                }
            }
            int nulCount = nulEven + nulOdd;
            if (nulCount > 0 && (double) nulCount / length >= UTF16_NUL_RATIO_THRESHOLD) {
                return nulOdd >= nulEven ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * @return number of leading BOM bytes to skip (0 if there is no BOM)
     */
    private static int bomLength(byte[] bytes, int length) {
        if (length >= 3 && u(bytes[0]) == 0xEF && u(bytes[1]) == 0xBB && u(bytes[2]) == 0xBF) {
            return 3;
        }
        if (length >= 2 &&
                ((u(bytes[0]) == 0xFF && u(bytes[1]) == 0xFE) ||
                 (u(bytes[0]) == 0xFE && u(bytes[1]) == 0xFF))) {
            return 2;
        }
        return 0;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    /** Skip exactly {@code count} bytes (or fewer if the stream ends). */
    private static void skipFully(InputStream stream, int count) throws IOException {
        int remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                if (stream.read() < 0) {
                    break;
                }
                skipped = 1;
            }
            remaining -= (int) skipped;
        }
    }

    /** Remove a leading BOM character and any stray NUL characters. */
    private static String cleanLine(String line) {
        if (line == null) {
            return "";
        }
        String cleaned = line;
        if (!cleaned.isEmpty() && cleaned.charAt(0) == '\uFEFF') {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.indexOf('\u0000') >= 0) {
            cleaned = cleaned.replace("\u0000", "");
        }
        return cleaned;
    }

    private static boolean isBlankLine(String line) {
        return line == null || line.trim().isEmpty();
    }

    /** Parse a numeric column, treating blank/invalid values as zero. */
    private static double parseDoubleOrZero(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Build stable, unique column keys.  Empty header names (frequent in
     * spreadsheet exports) become {@code col8}, {@code col9}, ... instead of
     * the empty string, which would collide with every other empty header.
     */
    private static String[] normalizeColumnNames(String[] rawNames) {
        String[] names = new String[rawNames.length];
        Set<String> used = new HashSet<>();
        for (int i = 0; i < rawNames.length; i++) {
            String base = rawNames[i] == null ? "" : rawNames[i].trim();
            if (base.isEmpty()) {
                base = "col" + (i + 1);
            }
            String name = base;
            int suffix = 2;
            while (!used.add(name)) {
                name = base + "_" + suffix++;
            }
            names[i] = name;
        }
        return names;
    }

    /** Column key for index {@code i}, falling back to colN beyond the header. */
    private static String columnKey(String[] columnNames, int i) {
        return columnNames != null && i < columnNames.length ? columnNames[i] : "col" + (i + 1);
    }

    /** Log at most {@link #MAX_LOGGED_SKIPPED_LINES} skipped lines. */
    private static void warnSkippedLine(String reason, String line, int[] skippedLineCount) {
        if (skippedLineCount[0] < MAX_LOGGED_SKIPPED_LINES) {
            skippedLineCount[0]++;
            log.warn("Skipping BEDPE line (" + reason + "): " + line);
            if (skippedLineCount[0] == MAX_LOGGED_SKIPPED_LINES) {
                log.warn("Further skipped BEDPE lines will not be logged");
            }
        }
    }

    /** Log a bad optional numeric column without discarding the feature. */
    private static void warnSkippedColumn(String column, String value, String line) {
        log.warn("Ignoring invalid " + column + " value '" + value + "' in BEDPE line: " + line);
    }

    public static boolean isNumeric(String strNum) {
        return strNum != null && strNum.trim().matches("-?\\d+(\\.\\d+)?");
    }

    public static class Dataset {

        public DatasetType type;
        public List<BedPEFeature> features;

        public Dataset(DatasetType type, List<BedPEFeature> features) {
            this.type = type;
            this.features = features;
        }
    }

}
