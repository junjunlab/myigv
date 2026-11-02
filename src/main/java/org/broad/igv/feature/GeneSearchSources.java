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

import org.broad.igv.track.Track;
import org.broad.igv.ui.IGV;
import org.broad.igv.util.ResourceLocator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the annotation sources that the search box can look gene names up in.
 * <p>
 * The default source is the genome's own annotation (IGV's standard behaviour, via
 * {@link FeatureDB} and the UCSC web service).  Every GTF / GFF track loaded by the user
 * becomes an additional selectable source, backed by a {@link GeneSearchIndex} built from
 * that file's gene records.
 */
public abstract class GeneSearchSources {

    public static final String DEFAULT_LABEL = "Default genome annotation";

    private static final Map<String, GeneSearchIndex> INDEX_CACHE = new ConcurrentHashMap<>();

    private static volatile GeneSearchIndex selected = null;

    /**
     * True if the path looks like a GTF / GFF gene annotation file.
     */
    public static boolean isGeneAnnotationPath(String path) {
        if (path == null || path.isEmpty()) return false;
        String p = path.toLowerCase();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".gz")) p = p.substring(0, p.length() - 3);
        return p.endsWith(".gtf") || p.endsWith(".gff") || p.endsWith(".gff3") || p.endsWith(".gvf");
    }

    /**
     * Build the list of selectable sources: the default genome annotation followed by every
     * distinct GTF/GFF track currently loaded.
     */
    public static List<Source> listSources() {
        List<Source> sources = new ArrayList<>();
        sources.add(new Source(DEFAULT_LABEL, null));

        if (!IGV.hasInstance()) {
            return sources;
        }

        // Preserve track order, de-duplicate by path
        LinkedHashMap<String, String> byPath = new LinkedHashMap<>();
        try {
            for (Track track : IGV.getInstance().getAllTracks()) {
                if (track == null) continue;
                ResourceLocator locator = track.getResourceLocator();
                if (locator == null) continue;
                String path = locator.getPath();
                if (!isGeneAnnotationPath(path)) continue;
                String name = track.getName();
                byPath.putIfAbsent(path, (name == null || name.isEmpty()) ? shortName(path) : name);
            }
        } catch (Exception e) {
            // No tracks / not initialized -- default only
        }

        for (Map.Entry<String, String> entry : byPath.entrySet()) {
            sources.add(new Source(entry.getValue(), entry.getKey()));
        }
        return sources;
    }

    /**
     * The currently selected source, or null when searching the default genome annotation.
     */
    public static GeneSearchIndex getSelected() {
        return selected;
    }

    public static void setSelected(GeneSearchIndex index) {
        selected = index;
    }

    /**
     * Get (and lazily create) the index for a GTF/GFF path.
     */
    public static GeneSearchIndex getIndex(String path, String displayName) {
        if (path == null) return null;
        return INDEX_CACHE.computeIfAbsent(path,
                p -> new GeneSearchIndex(p, displayName == null ? shortName(p) : displayName));
    }

    public static GeneSearchIndex getIndex(String path) {
        return getIndex(path, null);
    }

    /**
     * Drop all cached indexes and the current selection.  Called on genome change, since the
     * indexed features were canonicalized against the previous genome's chromosome naming.
     */
    public static void clear() {
        selected = null;
        INDEX_CACHE.clear();
    }

    private static String shortName(String path) {
        if (path == null) return "";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * A selectable entry in the search-source dropdown.
     */
    public static class Source {

        private final String label;
        private final String path;

        public Source(String label, String path) {
            this.label = label;
            this.path = path;
        }

        public String getLabel() {
            return label;
        }

        public String getPath() {
            return path;
        }

        public boolean isDefault() {
            return path == null;
        }

        /**
         * The index backing this source, or null for the default source.
         */
        public GeneSearchIndex index() {
            return path == null ? null : getIndex(path, label);
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Source)) return false;
            Source other = (Source) o;
            return Objects.equals(path, other.path);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(path);
        }
    }
}
