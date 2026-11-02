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

package org.broad.igv.feature;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.util.ParsingUtils;
import org.broad.igv.util.ResourceLocator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * An in-memory index of gene names parsed from a user supplied GTF / GFF file.
 * <p>
 * IGV's search box normally resolves gene names through {@link FeatureDB} (the genome's own
 * annotation) or a remote web service.  That means genes that exist only in an imported
 * transcript file cannot be found.  This class scans a GTF/GFF file once, keeps the gene
 * records (name -> locus), and answers exact / prefix (autocomplete) queries against them.
 * <p>
 * The scan runs on a background thread ({@link #buildAsync()}) because a full GTF can contain
 * millions of lines.  Queries return empty results until the index is ready.
 */
public class GeneSearchIndex {

    private static Logger log = LogManager.getLogger(GeneSearchIndex.class);

    /**
     * Attributes that may hold a gene symbol, in priority order.  Covers both GTF
     * ({@code gene_name "X";}) and GFF3 ({@code Name=X}) conventions.
     */
    private static final String[] SYMBOL_KEYS = {
            "gene_name", "Name", "gene", "name", "locus", "Locus", "alias", "Alias", "gene_symbol"
    };

    /**
     * Attributes that may hold a gene identifier, indexed as aliases so that Ensembl / Ensembl-like
     * accessions can also be searched.
     */
    private static final String[] ID_KEYS = {"gene_id", "ID", "gene", "gene_id", "Dbxref"};

    private static final String[] TRANSCRIPT_TYPES = {
            "transcript", "mrna", "ncrna", "lnc_rna", "nMRNA", "rrna", "trna", "snrna", "snorna", "mirna", "primary_transcript"
    };

    private static final int MAX_ALIASES_PER_GENE = 12;
    private static final int MAX_ALIAS_LENGTH = 60;

    private final String path;
    private final String displayName;

    private volatile TreeMap<String, IGVNamedFeature> primaryMap;
    private volatile Map<String, List<IGVNamedFeature>> aliasMap;
    private volatile boolean ready = false;
    private volatile boolean failed = false;
    private volatile int featureCount = 0;

    private final Object buildLock = new Object();
    private Thread builder;

    public GeneSearchIndex(String path, String displayName) {
        this.path = path;
        this.displayName = displayName == null ? shortName(path) : displayName;
    }

    public String getPath() {
        return path;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isFailed() {
        return failed;
    }

    public int getFeatureCount() {
        return featureCount;
    }

    /**
     * Kick off indexing on a background thread.  Safe to call repeatedly.
     */
    public void buildAsync() {
        if (ready || failed) return;
        synchronized (buildLock) {
            if (builder != null && builder.isAlive()) return;
            builder = new Thread(() -> {
                try {
                    build();
                } catch (Throwable t) {
                    failed = true;
                    log.error("Error indexing gene names from " + path, t);
                }
            }, "myigv-gene-index");
            builder.setDaemon(true);
            builder.start();
        }
    }

    /**
     * Build the index, blocking until it is ready.  Intended for background threads
     * (search commands are executed off the event dispatch thread).
     */
    public void build() {
        if (ready || failed) return;
        synchronized (buildLock) {
            if (ready || failed) return;

            long t0 = System.currentTimeMillis();
            Map<String, Entry> entries = new HashMap<>(100000);

            try {
                // Preferred: records whose type is gene-like (gene, pseudogene, ncRNA_gene, ...)
                readRecords(false, entries);
                if (entries.isEmpty()) {
                    // Fallback: files without gene records (e.g. transcript-only GTF exports)
                    readRecords(true, entries);
                }
            } catch (Exception e) {
                failed = true;
                log.error("Error indexing gene names from " + path, e);
                return;
            }

            TreeMap<String, IGVNamedFeature> primaries = new TreeMap<>();
            Map<String, List<IGVNamedFeature>> aliases = new HashMap<>(entries.size() * 2);

            for (Entry entry : entries.values()) {
                IGVNamedFeature feature = entry.feature;
                primaries.putIfAbsent(feature.getName().toUpperCase(), feature);
                for (String alias : entry.aliases) {
                    List<IGVNamedFeature> list = aliases.computeIfAbsent(alias.toUpperCase(), k -> new ArrayList<>(1));
                    if (list.size() < 20 && !list.contains(feature)) {
                        list.add(feature);
                    }
                }
            }

            this.primaryMap = primaries;
            this.aliasMap = aliases;
            this.featureCount = primaries.size();
            this.ready = true;

            log.info(String.format("Indexed %,d gene names from %s in %,d ms",
                    primaries.size(), displayName, System.currentTimeMillis() - t0));
        }
    }

    /**
     * Exact match on a gene symbol or one of its aliases (case insensitive).
     */
    public List<IGVNamedFeature> exact(String name) {
        if (name == null) return Collections.emptyList();
        String key = name.trim().toUpperCase();
        if (key.isEmpty()) return Collections.emptyList();

        Map<String, List<IGVNamedFeature>> aliases = aliasMap;
        if (aliases != null) {
            List<IGVNamedFeature> hits = aliases.get(key);
            if (hits != null && !hits.isEmpty()) return hits;
        }
        TreeMap<String, IGVNamedFeature> primaries = primaryMap;
        if (primaries != null) {
            IGVNamedFeature f = primaries.get(key);
            if (f != null) return Collections.singletonList(f);
        }
        return Collections.emptyList();
    }

    /**
     * Prefix (autocomplete) match on primary gene names, case insensitive.
     */
    public List<IGVNamedFeature> prefix(String name, int limit) {
        if (name == null) return Collections.emptyList();
        String key = name.trim().toUpperCase();
        if (key.isEmpty()) return Collections.emptyList();

        TreeMap<String, IGVNamedFeature> primaries = primaryMap;
        if (primaries == null) return Collections.emptyList();

        List<IGVNamedFeature> result = new ArrayList<>(Math.min(limit, 64));
        try {
            NavigableMap<String, IGVNamedFeature> sub =
                    primaries.subMap(key, true, key + Character.MAX_VALUE, false);
            for (IGVNamedFeature f : sub.values()) {
                result.add(f);
                if (result.size() >= limit) break;
            }
        } catch (IllegalArgumentException e) {
            // Bad range, nothing to do
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * @param fallbackMode when true, transcript-like records are also accepted and their
     *                     coordinates are merged into a single feature per gene.
     */
    private void readRecords(boolean fallbackMode, Map<String, Entry> entries) throws IOException {

        Genome genome = currentGenome();

        try (InputStream is = ParsingUtils.openInputStreamGZ(new ResourceLocator(path));
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 256 * 1024)) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '#') {
                    if (line.startsWith("##FASTA")) break;
                    continue;
                }

                // Cheaply locate column 3 (feature type) without splitting the whole line
                int t1 = line.indexOf('\t');
                if (t1 < 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 < 0) continue;
                int t3 = line.indexOf('\t', t2 + 1);
                if (t3 < 0) continue;
                String type = line.substring(t2 + 1, t3);

                if (!isGeneLike(type) && !(fallbackMode && isTranscriptLike(type))) {
                    continue;
                }

                String[] cols = line.split("\t", 9);
                if (cols.length < 9) continue;

                int start;
                int end;
                try {
                    start = Integer.parseInt(cols[3].trim()) - 1;   // GFF is 1-based
                    end = Integer.parseInt(cols[4].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (start < 0) start = 0;
                if (end < start) continue;

                Map<String, String> attributes = parseAttributes(cols[8]);
                String symbol = firstValue(attributes, SYMBOL_KEYS);
                String id = firstValue(attributes, ID_KEYS);
                String primary = symbol != null ? symbol : id;
                if (primary == null || primary.isEmpty()) continue;

                String key = primary.toUpperCase();
                Entry entry = entries.get(key);
                if (entry == null) {
                    String chr = canonicalChrName(cols[0].trim(), genome);
                    BasicFeature feature = new BasicFeature(chr, start, end);
                    feature.setName(primary);
                    feature.setType("gene");
                    entry = new Entry(feature);
                    entries.put(key, entry);
                } else if (entry.feature.getChr().equals(canonicalChrName(cols[0].trim(), genome))) {
                    // Same gene symbol on the same chromosome -- union the exons/transcripts
                    entry.feature.setStart(Math.min(entry.feature.getStart(), start));
                    entry.feature.setEnd(Math.max(entry.feature.getEnd(), end));
                }

                // Index the symbol plus every identifier attribute as an alias, so a gene can be
                // found by symbol, Ensembl id, GFF3 "gene:XXX" id, etc.
                entry.addAlias(symbol);
                entry.addAlias(id);
                for (String k : SYMBOL_KEYS) {
                    entry.addAlias(attributes.get(k));
                }
                for (String k : ID_KEYS) {
                    entry.addAlias(attributes.get(k));
                }
            }
        }
    }

    private static boolean isGeneLike(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        if (t.equals("gene")) return true;
        if (t.equals("pseudogene")) return true;
        return t.endsWith("_gene") || t.equals("ncrna_gene");
    }

    private static boolean isTranscriptLike(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        for (String tt : TRANSCRIPT_TYPES) {
            if (t.equals(tt)) return true;
        }
        return t.endsWith("_transcript");
    }

    /**
     * Parse the 9th GFF/GTF column.  Handles both {@code key "value";} (GTF / GFF2) and
     * {@code key=value;} (GFF3) syntax.
     */
    static Map<String, String> parseAttributes(String attributeString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (attributeString == null) return map;

        int length = attributeString.length();
        int i = 0;
        while (i < length) {
            int semi = attributeString.indexOf(';', i);
            String kv = (semi < 0 ? attributeString.substring(i) : attributeString.substring(i, semi)).trim();
            i = semi < 0 ? length : semi + 1;
            if (kv.isEmpty()) continue;

            int eq = kv.indexOf('=');
            String key;
            String value;
            if (eq > 0) {
                key = kv.substring(0, eq).trim();
                value = kv.substring(eq + 1).trim();
            } else {
                int sp = kv.indexOf(' ');
                if (sp <= 0) continue;
                key = kv.substring(0, sp).trim();
                value = kv.substring(sp + 1).trim();
            }
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            if (!key.isEmpty() && !value.isEmpty()) {
                map.putIfAbsent(key, value);
            }
        }
        return map;
    }

    private static String firstValue(Map<String, String> attributes, String[] keys) {
        for (String key : keys) {
            String value = attributes.get(key);
            if (value != null) {
                value = value.trim();
                if (!value.isEmpty() && !value.equals(".")) return value;
            }
        }
        return null;
    }

    private static String canonicalChrName(String chr, Genome genome) {
        if (genome == null || chr == null) return chr;
        try {
            String canonical = genome.getCanonicalChrName(chr);
            return canonical == null ? chr : canonical;
        } catch (Throwable t) {
            return chr;
        }
    }

    private static Genome currentGenome() {
        try {
            return GenomeManager.getInstance().getCurrentGenome();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shortName(String path) {
        if (path == null) return "";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * Feature plus the set of names it can be found by.
     */
    private static class Entry {
        final BasicFeature feature;
        final Set<String> aliases = new LinkedHashSet<>();

        Entry(BasicFeature feature) {
            this.feature = feature;
            addAlias(feature.getName());
        }

        void addAlias(String alias) {
            if (alias == null) return;
            String value = alias.trim();
            if (value.isEmpty() || value.equals(".")) return;
            if (value.length() > MAX_ALIAS_LENGTH) return;
            if (value.indexOf(' ') >= 0 || value.indexOf(',') >= 0) return;
            if (aliases.size() >= MAX_ALIASES_PER_GENE) return;
            aliases.add(value);
        }
    }
}
