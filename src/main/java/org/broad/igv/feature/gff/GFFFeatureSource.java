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

package org.broad.igv.feature.gff;

import org.broad.igv.logging.*;
import org.broad.igv.feature.*;
import htsjdk.tribble.Feature;
import org.broad.igv.feature.tribble.GFFCodec;
import org.broad.igv.track.FeatureSource;

import java.io.IOException;
import java.util.*;

/**
 * User: jacob
 * Date: 2012-Jun-22
 */
public class GFFFeatureSource implements org.broad.igv.track.FeatureSource {

    private static Logger log = LogManager.getLogger(GFFFeatureSource.class);
    private  GFFCodec.Version gffVersion;

    private FeatureSource wrappedSource;

    public static boolean isGFF(String path) {
        String lowpath = path.toLowerCase();
        if (lowpath.endsWith(".gz")) {
            int idx = lowpath.length() - 3;
            lowpath = lowpath.substring(0, idx);
        }
        if (lowpath.endsWith(".txt")) {
            int idx = lowpath.length() - 4;
            lowpath = lowpath.substring(0, idx);
        }
        return lowpath.endsWith("gff3") || lowpath.endsWith("gvf") || lowpath.endsWith("gff") || lowpath.endsWith("gtf");
    }

    public static GFFCombiner getCombiner(GFFCodec.Version version) {
        return version == GFFCodec.Version.GFF3 ? new GFF3Combiner() : new GFF2Combiner();
    }

    public GFFFeatureSource(FeatureSource wrappedSource, GFFCodec.Version gffVersion) throws IOException {
        this.gffVersion = gffVersion;
        this.wrappedSource = wrappedSource;
    }

    @Override
    public Object getHeader() {
        return this.wrappedSource.getHeader();
    }

    @Override
    public void close() {
        wrappedSource.close();
    }

    @Override
    public Iterator<Feature> getFeatures(String chr, int start, int end) throws IOException {

        // Expand start/end to be sure we get adjacent CDS/Exon features
        int expandedStart = Math.max(0, start - 2000000);
        long longEnd = (long) end + 2000000;   // Protect against overflow
        int expandedEnd = (int) Math.min(Integer.MAX_VALUE, longEnd);

        Iterator<Feature> rawIter = wrappedSource.getFeatures(chr, expandedStart, expandedEnd);
        GFFCombiner combiner = (getCombiner(gffVersion)).addFeatures(rawIter);

        List<Feature> combined = combiner.combineFeatures();

        // GTF / GFF files carry a "gene" record for every gene in addition to its transcripts.
        // A gene record has no exon structure, so IGV paints it as a solid bar spanning the whole
        // gene, drawn on top of (and hiding) the transcript models it contains.  Drop those
        // redundant containers, unless the file has nothing else (gene-only annotations).
        boolean hasStructuredFeatures = false;
        for (Feature f : combined) {
            if (!isRedundantGeneContainer(f)) {
                hasStructuredFeatures = true;
                break;
            }
        }

        // Now trim features not requested
        List<Feature> requestedFeatures = new ArrayList<>();
        for(Feature f : combined) {
            if(f.getEnd() < start) continue;
            if(f.getStart() > end) break;
            if (hasStructuredFeatures && isRedundantGeneContainer(f)) continue;
            requestedFeatures.add(f);
        }

        return new WrappedIterator(requestedFeatures.iterator());
    }

    /**
     * True for "gene" / "pseudogene" container records that carry no sub structure.  These render
     * as an uninformative solid bar; the transcripts underneath carry the real information.
     */
    private static boolean isRedundantGeneContainer(Feature feature) {
        if (!(feature instanceof BasicFeature)) return false;
        BasicFeature bf = (BasicFeature) feature;
        if (bf.hasExons()) return false;
        String type = bf.getType();
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.equals("gene") || t.equals("pseudogene") || t.endsWith("_gene");
    }

    @Override
    public List<LocusScore> getCoverageScores(String chr, int start, int end, int zoom) {
        return wrappedSource.getCoverageScores(chr, start, end, zoom);
    }

    @Override
    public int getFeatureWindowSize() {
        return wrappedSource.getFeatureWindowSize();
    }

}
