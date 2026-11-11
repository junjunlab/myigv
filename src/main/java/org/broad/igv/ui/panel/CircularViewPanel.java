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

package org.broad.igv.ui.panel;

import org.broad.igv.event.IGVEvent;
import org.broad.igv.event.IGVEventBus;
import org.broad.igv.event.IGVEventObserver;
import org.broad.igv.feature.BasicFeature;
import org.broad.igv.feature.Cytoband;
import org.broad.igv.feature.Exon;
import org.broad.igv.feature.FeatureDB;
import htsjdk.tribble.NamedFeature;
import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.Globals;
import org.broad.igv.ui.IGV;
import org.broad.igv.bedpe.BedPEFeature;
import org.broad.igv.bedpe.InteractionTrack;
import org.broad.igv.lists.GeneList;
import org.broad.igv.session.Session;
import org.broad.igv.util.ChromosomeColors;
import org.broad.igv.hic.HiCColorScale;
import org.broad.igv.hic.HiCRenderer;
import org.broad.igv.hic.HiCSource;
import org.broad.igv.hic.HiCTrack;
import org.broad.igv.hic.HiCTransform;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import htsjdk.tribble.Feature;
import org.broad.igv.track.DataTrack;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.RegionScoreType;
import org.broad.igv.track.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Circular / ring genome view, drawn in the classic Circos ideogram style, with configurable
 * ticks / labels (parameters mirror Circos "ticks" settings).
 *
 * <ul>
 *   <li>"All" (whole genome): every chromosome is a cytoband-striped ideogram arc, scaled by
 *       length, grouped clockwise around the ring with gaps.</li>
 *   <li>Single chromosome: a full 360&deg; ideogram ring.</li>
 *   <li>Ctrl + mouse-wheel over a chromosome sector ("All" mode) zooms that chromosome.</li>
 *   <li>No cytoband (karyotype) data -> solid colour blocks.</li>
 * </ul>
 *
 * <p>Cytoband colours use circlize's exact {@code cytoband.col} palette
 * (R/genomic_utils.R: gneg white, gpos25/50 = 200, gpos33 210, gpos66 160, gpos75 130,
 * gpos100 black, gvar 220, acen 217,47,39, stalk 100,127,164). Tick placement follows
 * Circos {@code draw_ticks}: {@code orientation=out} puts labels outside the band, and
 * {@code label_tangential} keeps them parallel to the circle.</p>
 */
public class CircularViewPanel extends JPanel implements IGVEventObserver {

    private static final int MARGIN = 56;   // room for labels outside the ring

    private static final Color BG = Color.WHITE;

    private static final org.broad.igv.logging.Logger log =
            org.broad.igv.logging.LogManager.getLogger(CircularViewPanel.class);

    /** User-configurable rendering parameters. */
    public static class CircularSettings {
        /** Master switch for the coordinate axis (baseline arc + major/minor ticks + labels). */
        public boolean showAxis = true;
        /** Number of equal subdivisions between two major ticks; 0 disables minor ticks. */
        public int     minorTicksPerMajor = 5;
        public boolean showTicks = true;
        public boolean showTickLabels = true;
        public boolean tickLabelsOutside = true;   // orientation "out" (coords above the band)
        public double  tickSpacingMb = 0;           // 0 = auto (~3 ticks/chromosome)
        public int     tickLabelSize = 7;
        /** Stroke width (px) of the coordinate AXIS: the baseline arc and the major ticks.
         *  Minor ticks and the baseline thickness are derived from it (x0.6 / x0.85). */
        public double  axisLineWidth = 1.0;
        public boolean showChromosomeLabels = true;
        public int     chromosomeLabelSize = 12;
        /** Radial thickness of the chromosome (ideogram) ring, in pixels. */
        public int     chromosomeHeightPx = 15;
        public boolean labelTangential = true;       // tangential (circos label_tangential) vs radial
        public double  bandInner = 0.78;
        public double  bandOuter = 0.90;
        public double  gapDeg = 1.5;
        /** When true, all data tracks share one Y range (group scale); default each track scales itself. */
        public boolean ySharedAcrossTracks = false;
        /** When true, fill every chromosome with chromosomeColor (ignore cytoband stripes). */
        public boolean solidColor = false;
        /** Solid chromosome fill colour (used when solidColor; also the default for species w/o karyotype). */
        public Color   chromosomeColor = new Color(93, 145, 190);
        /** Chromosomes to show in "All" mode; empty = show all. */
        public Set<String> shownChromosomes = new HashSet<>();

        // ---- Circos-style "links": bezier chords / ribbons joining two genomic intervals ----

        public boolean showLinks = true;
        /** Filled band (Circos {@code ribbon=yes}) instead of a stroked curve. */
        public boolean linkRibbon = true;
        /** Swap which end of the second interval the ribbon's edges connect to (Circos {@code twist}). */
        public boolean linkTwist = true;
        /** Radial position of the link ends, as a fraction of the ideogram inner radius. */
        public double  linkRadius = 0.95;
        /** Radial position of the bezier control point, as a fraction of the link end radius.
         *  Values &lt;= 0 draw a straight chord (Circos {@code bezier_radius=0}). */
        public double  linkBezierRadius = 0.25;
        /** 1 = control point purely radial (curves hug the circle), 0 = pulled to the Cartesian midpoint. */
        public double  linkBezierPurity = 0.75;
        /** Extra control points that make links meet the ideogram perpendicularly; 0 disables. */
        public double  linkCrest = 0.5;
        /** Minimum angular width (degrees) of a ribbon end. Real BEDPE anchors are only a few kb,
         *  which is sub-pixel on a whole-genome ring, so ribbons would be invisible without this. */
        public double  linkRibbonMinDeg = 1.2;
        /** Ribbons are filled areas, so they need more opacity than stroked links to read well.
         *  255 = fully opaque, which is the default (transparency 0). */
        public int     linkRibbonAlpha = 255;
        /** Outline the ribbon: keeps the thin middle of a tapered band visible (Circos stroke_*). */
        public boolean linkRibbonStroke = true;
        public float   linkRibbonStrokeThickness = 0.6f;
        public Color   linkColor = new Color(40, 90, 170);
        /** Alpha 0-255. 255 = fully opaque, which is the default (transparency 0). */
        public int     linkAlpha = 255;
        public float   linkThickness = 1.2f;
        /** Arrow heads on links: 0 = none (default), 1 = start, 2 = end, 3 = both ends. */
        public int     linkArrow = 0;
        /** Arrow length in px and base width as a multiple of the band width (circlize
         *  arr.length / arr.width). */
        public double  linkArrowLength = 9.0;
        public double  linkArrowWidth = 1.7;
        /** Cap on the number of links drawn per frame (Circos {@code record_limit}). */
        public int     linkRecordLimit = 5000;
        public boolean linkColorByChromosome = false;
        /** Anchor links inside the stacked ring bands instead of on top of them. */
        public boolean linkEndsInsideTracks = true;
        /** Flank (bp) added to each gene interval in gene-list mode so sectors aren't edge-to-edge. */
        public int     geneFlankBp = 2000;
        /** Radial thickness (px) of the genomic-heatmap band when a heatmap is loaded. */
        public int     heatmapHeightPx = 50;
        /** Height of the circlize "connection track" between the ideogram and the heatmap band.
         *  It is what the comb lines live in; 0 packs the heatmap right against the ideogram. */
        public int     heatmapConnectionPx = 22;
        /** true = heatmap ring sits OUTSIDE the ideogram (band + comb grow outward),
         *  false = inside, between the ideogram and the data tracks (circlize side=). */
        public boolean heatmapOutside = false;
        /** Heatmap ramp colours: low / mid (used only when the data straddles 0) / high. */
        public Color   heatmapLowColor = new Color(0, 150, 0);
        public Color   heatmapMidColor = new Color(0, 0, 0);
        public Color   heatmapHighColor = new Color(215, 40, 40);
        /** Label ring (circos.genomicLabels): text outside the ideogram, connected by a stub. */
        public int     labelFontSize = 9;
        public boolean labelBold = false;
        public Color   labelColor = new Color(30, 30, 30);
        public Color   labelLineColor = new Color(120, 120, 120);
        public boolean labelShowLines = true;
        /** Distance from the ideogram outer edge to the first label ring, in px. */
        public int     labelGapPx = 16;
        /** Extra radius for each overlap level (labels pushed outward to avoid each other). */
        public int     labelLevelGapPx = 13;
        /** Heatmap colour-bar legend, drawn OUTSIDE the ring (same corner set as the Hi-C legend). */
        public boolean heatmapLegend = true;
        public org.broad.igv.hic.HiCTrack.LegendPosition heatmapLegendPosition =
                org.broad.igv.hic.HiCTrack.LegendPosition.TOP_RIGHT;
        /** Draw thin connectors tying each heatmap cell to its chromosome position (circlize comb). */
        public boolean heatmapConnectors = true;
        /** Single-chromosome mode: fraction of the full circle the arc spans.
         *  1.0 = 360° (full ring, default), 0.5 = 180° (half circle), etc. */
        public double singleArcFraction = 1.0;
        /** Single-chromosome mode: where the arc's 0 position sits, in degrees clockwise
         *  from the top (0 = top, 90 = right, 180 = bottom, 270 = left). Default 0 (top). */
        public double singleArcStartDeg = 0.0;

        public CircularSettings copy() {
            CircularSettings s = new CircularSettings();
            s.showAxis = showAxis; s.minorTicksPerMajor = minorTicksPerMajor;
            s.showTicks = showTicks; s.showTickLabels = showTickLabels;
            s.tickLabelsOutside = tickLabelsOutside; s.tickSpacingMb = tickSpacingMb;
            s.axisLineWidth = axisLineWidth;
            s.tickLabelSize = tickLabelSize; s.showChromosomeLabels = showChromosomeLabels;
            s.chromosomeLabelSize = chromosomeLabelSize; s.chromosomeHeightPx = chromosomeHeightPx;
            s.labelTangential = labelTangential;
            s.bandInner = bandInner; s.bandOuter = bandOuter; s.gapDeg = gapDeg;
            s.solidColor = solidColor; s.chromosomeColor = chromosomeColor;
            s.ySharedAcrossTracks = ySharedAcrossTracks;
            s.shownChromosomes = new HashSet<>(shownChromosomes);
            s.showLinks = showLinks; s.linkRibbon = linkRibbon; s.linkTwist = linkTwist;
            s.linkRadius = linkRadius; s.linkBezierRadius = linkBezierRadius;
            s.linkBezierPurity = linkBezierPurity; s.linkCrest = linkCrest;
            s.linkRibbonMinDeg = linkRibbonMinDeg;
            s.linkRibbonAlpha = linkRibbonAlpha;
            s.linkRibbonStroke = linkRibbonStroke;
            s.linkRibbonStrokeThickness = linkRibbonStrokeThickness;
            s.linkColor = linkColor; s.linkAlpha = linkAlpha;
            s.linkThickness = linkThickness; s.linkRecordLimit = linkRecordLimit;
            s.linkArrow = linkArrow; s.linkArrowLength = linkArrowLength;
            s.linkArrowWidth = linkArrowWidth;
            s.linkColorByChromosome = linkColorByChromosome;
            s.linkEndsInsideTracks = linkEndsInsideTracks;
            s.geneFlankBp = geneFlankBp;
            s.heatmapHeightPx = heatmapHeightPx;
            s.heatmapConnectionPx = heatmapConnectionPx;
            s.heatmapOutside = heatmapOutside;
            s.heatmapLowColor = heatmapLowColor;
            s.heatmapMidColor = heatmapMidColor;
            s.heatmapHighColor = heatmapHighColor;
            s.labelFontSize = labelFontSize; s.labelBold = labelBold;
            s.labelColor = labelColor; s.labelLineColor = labelLineColor;
            s.labelShowLines = labelShowLines; s.labelGapPx = labelGapPx;
            s.labelLevelGapPx = labelLevelGapPx;
            s.heatmapLegend = heatmapLegend;
            s.heatmapLegendPosition = heatmapLegendPosition;
            s.singleArcFraction = singleArcFraction;
            s.singleArcStartDeg = singleArcStartDeg;
            return s;
        }

        long tickStepBp(long chromosomeLength) {
            if (tickSpacingMb > 0) return Math.max(1, Math.round(tickSpacingMb * 1_000_000.0));
            return niceStep(chromosomeLength, 3);
        }
    }

    private final CircularSettings settings = new CircularSettings();

    /** Per-chromosome zoom factors (Ctrl+wheel in "All" mode). */
    private final Map<String, Double> zoom = new HashMap<>();

    /** Per-chromosome visible bp window (Shift+wheel in "All" mode); absent = whole chromosome. */
    private final Map<String, double[]> rangeZoom = new HashMap<>();

    private BufferedImage ringImage;
    private String ringKey;
    private int lastGenomeId = 0;

    // ---- feature tracks drawn as concentric bands inside the ideogram ----
    private final List<Track> ringTracks = new ArrayList<>();
    private final Set<String> hiddenRingTracks = new HashSet<>();
    private final Map<String, TrackRingConfig> ringConfig = new HashMap<>();
    private final Map<String, List<Feature>> featureCache = new ConcurrentHashMap<>();
    /** Resolved gene features for the current gene-list mode, keyed by gene name. */
    private final Map<String, List<Feature>> geneFeatures = new HashMap<>();
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();
    private final Map<String, float[]> dataCache = new ConcurrentHashMap<>();   // bigWig/bedGraph bins (whole chr)
    private final Set<String> dataPending = ConcurrentHashMap.newKeySet();
    /** Rendered Hi-C matrix tiles, keyed by track + sector window + every render parameter, so
     *  the contact matrix is never re-sampled on an ordinary EDT repaint. */
    private final Map<String, BufferedImage> hicTileCache = new ConcurrentHashMap<>();
    /** Colour limits actually used for each rendered Hi-C tile. */
    private final Map<String, double[]> hicTileLimits = new ConcurrentHashMap<>();
    /** Limits per visible Hi-C track, consumed by the legends drawn beside the ring. */
    private final Map<String, double[]> hicLimitsByTrack = new ConcurrentHashMap<>();
    private static final int DATA_BINS = 300;                                    // bins per chromosome
    /** Fine, window-scoped bigWig bins recomputed on zoom (so a zoomed window is not a stretched
     *  coarse bin). Keyed by "track|chr|windowStart_windowEnd_binBp". */
    private final Map<String, float[]> adaptiveCache = new ConcurrentHashMap<>();
    private final Set<String> adaptivePending = ConcurrentHashMap.newKeySet();
    /** Target number of bins across the visible window when zoomed (resolution tracks the zoom). */
    private static final int ADAPTIVE_BINS = 600;
    private final Map<String, Integer> loadAttempts = new HashMap<>();
    private final Map<String, Long> lastAttemptMs = new HashMap<>();
    private final Map<String, Boolean> trackLoaded = new HashMap<>();
    private javax.swing.Timer retryTimer;
    private final ExecutorService featureLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "circular-feature-loader");
        t.setDaemon(true);
        return t;
    });

    /** Per-track ring band configuration: fixed radial height (px), collapsed/expanded, colour. */
    public static class TrackRingConfig {
        public int heightPx = 26;
        /** Hi-C matrix bands are taller than a 26 px data band so the 2D warp stays legible. */
        public int hicHeightPx = 60;
        public boolean expanded = false;
        /** When expanded, stack transcript rows from the outer (top) edge inward instead of the
         *  default inner (bottom) edge outward. */
        public boolean stackFromOuter = false;
        public Color color = new Color(60, 90, 170);
        /** Data-track rendering: "Bar" (default, IGV wig style), "Point", "Line", "Heatmap". */
        public String graphType = "Bar";
        /** Y-axis range: false = per-chromosome scale (default), true = global across all chromosomes. */
        public boolean globalYScale = false;
        /** Non-null = this track shares a Y range with tracks of the same group id (group scale). */
        public String yGroup = null;
        /** When true and yGroup is set, the group's Y range is computed PER SECTOR (per gene), so
         *  each gene is scaled independently across the samples in the group instead of one global
         *  range that pulls weak genes down. */
        public boolean yGroupPerGene = false;
    }

    /**
     * One Circos link: an interval on {@code chr1} joined to an interval on {@code chr2}.
     * Field order matches the Circos link file layout
     * ({@code chr1 start1 end1 chr2 start2 end2 [options]}), which is also the first six
     * columns of a BEDPE file.
     */
    public static class RingLink {
        public final String chr1, chr2;
        public final int start1, end1, start2, end2;
        public double score = 0;
        /** Draw order: larger z is painted on top (Circos {@code z}). */
        public double z = 0;
        /** Per-link colour; null means "use {@link CircularSettings#linkColor}". */
        public Color color;
        /** Per-link thickness; 0 means "use {@link CircularSettings#linkThickness}". */
        public int thickness = 0;
        /** Tooltip text, may be null. */
        public String label;

        public RingLink(String chr1, int start1, int end1, String chr2, int start2, int end2) {
            this.chr1 = chr1;
            this.start1 = start1;
            this.end1 = end1;
            this.chr2 = chr2;
            this.start2 = start2;
            this.end2 = end2;
        }
    }

    // Two sources so that refreshing the track list never discards user-loaded links.
    private final List<RingLink> trackLinks = new ArrayList<>();   // auto-collected from InteractionTracks

    /**
     * A data layer that lives ONLY in the circular view: it is loaded straight from a file into
     * the ring and never becomes an IGV track, so it appears neither in the horizontal view nor
     * in a saved session. The Data Tracks column lists one row per layer, and that row carries
     * the layer's own context menu (ribbon / heatmap parameters + Remove).
     */
    public static class CircularLayer {
        public enum Kind { LINKS, HEATMAP, LABELS }

        public final Kind kind;
        public final String name;      // file name, shown in the Data Tracks column
        public final String path;      // full path, for tooltips / reload
        /** LINKS: the chords parsed from this file. Empty for HEATMAP layers. */
        public final List<RingLink> links = new ArrayList<>();
        /** HEATMAP: the matrix (only one heatmap ring exists at a time). */
        public GenomicHeatmap heatmap;
        /** LABELS: name rows drawn outside the ideogram (circos.genomicLabels). */
        public final List<LabelRow> labels = new ArrayList<>();
        public boolean visible = true;

        public CircularLayer(Kind kind, String name, String path) {
            this.kind = kind;
            this.name = name;
            this.path = path;
        }

        /** Short "what is inside" tag for the column row. */
        public String summary() {
            if (kind == Kind.LABELS) return "labels " + labels.size();
            if (kind == Kind.HEATMAP) {
                return heatmap == null ? "heatmap"
                        : "heatmap " + heatmap.rows.size() + " x " + heatmap.samples;
            }
            return "links " + links.size();
        }
    }

    /** One row of a labels file: an interval plus the text to print next to it. */
    public static class LabelRow {
        public final String chr, text;
        public final int start, end;

        public LabelRow(String chr, int start, int end, String text) {
            this.chr = chr; this.start = start; this.end = end; this.text = text;
        }
    }

    /** Parse a BED/TSV whose 4th column holds the label (chr, start, end, name). */
    public static List<LabelRow> parseLabels(org.broad.igv.util.ResourceLocator locator) throws java.io.IOException {
        List<LabelRow> out = new ArrayList<>();
        java.io.File f = new java.io.File(locator.getPath());
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\t");
                if (t.length < 4) t = line.trim().split("\\s+");
                if (t.length < 4) continue;
                int st, en;
                try { st = Integer.parseInt(t[1].trim()); en = Integer.parseInt(t[2].trim()); }
                catch (NumberFormatException e) { continue; }
                String text = t[3].trim();
                if (text.isEmpty()) text = t[0] + ":" + st;
                out.add(new LabelRow(t[0], st, en, text));
            }
        }
        return out;
    }

    /** Add a labels file as its own layer (several label files stack up). */
    public CircularLayer addLabelsLayer(String name, String path, List<LabelRow> rows) {
        CircularLayer l = new CircularLayer(CircularLayer.Kind.LABELS, name, path);
        if (rows != null) l.labels.addAll(rows);
        layers.add(l);
        invalidateRing();
        log.info("Circular labels layer '" + name + "': " + l.labels.size() + " label(s)");
        return l;
    }

    /** True when at least one visible labels layer exists (drives the ring shrink). */
    public boolean hasVisibleLabels() {
        for (CircularLayer l : layers) {
            if (l.kind == CircularLayer.Kind.LABELS && l.visible && !l.labels.isEmpty()) return true;
        }
        return false;
    }

    public boolean hasLabelsLayer() {
        for (CircularLayer l : layers) if (l.kind == CircularLayer.Kind.LABELS) return true;
        return false;
    }

    private final List<CircularLayer> layers = new ArrayList<>();

    /** Where the heatmap band sits in the band stack: 0 = outermost (first row). */
    private int heatmapBandIndex = 0;

    /** Row order of the Data Tracks column decides the radial order, so the heatmap band is
     *  inserted at the position of its row among the track bands. */
    public void setHeatmapBandIndex(int i) { heatmapBandIndex = Math.max(0, i); invalidateRing(); }

    public int getHeatmapBandIndex() { return heatmapBandIndex; }

    /** Snapshot of the circular-only layers, in load order. */
    public List<CircularLayer> getLayers() { return new ArrayList<>(layers); }

    /** Add a links file as its own layer — several link files stack up. */
    public CircularLayer addLinksLayer(String name, String path, List<RingLink> links) {
        CircularLayer l = new CircularLayer(CircularLayer.Kind.LINKS, name, path);
        if (links != null) l.links.addAll(links);
        layers.add(l);
        invalidateRing();
        log.info("Circular links layer '" + name + "': " + l.links.size() + " link(s)");
        return l;
    }

    /** Set (or replace) the heatmap layer; only one heatmap ring exists at a time. */
    public CircularLayer setHeatmapLayer(String name, String path, GenomicHeatmap hm) {
        layers.removeIf(l -> l.kind == CircularLayer.Kind.HEATMAP);
        CircularLayer l = new CircularLayer(CircularLayer.Kind.HEATMAP, name, path);
        l.heatmap = hm;
        layers.add(l);
        setHeatmap(hm);
        log.info("Circular heatmap layer '" + name + "': " + l.summary());
        return l;
    }

    /** Show / hide one layer without unloading it. */
    public void setLayerVisible(CircularLayer l, boolean v) {
        if (l == null) return;
        l.visible = v;
        if (l.kind == CircularLayer.Kind.HEATMAP) {
            heatmap = v ? l.heatmap : null;
        }
        invalidateRing();
    }

    /** Unload a layer (and, for a heatmap, its ring). */
    public void removeLayer(CircularLayer l) {
        if (l == null) return;
        layers.remove(l);
        if (l.kind == CircularLayer.Kind.HEATMAP && heatmap == l.heatmap) heatmap = null;
        invalidateRing();
        log.info("Circular layer removed: " + l.name);
    }

    /** Replace the user-loaded links with a single unnamed layer (kept for compatibility). */
    public void setRingLinks(List<RingLink> links) {
        layers.removeIf(l -> l.kind == CircularLayer.Kind.LINKS);
        if (links != null && !links.isEmpty()) addLinksLayer("links", null, links);
        invalidateRing();
    }

    /** Replace the links auto-collected from loaded interaction tracks. */
    public void setTrackLinks(List<RingLink> links) {
        trackLinks.clear();
        if (links != null) trackLinks.addAll(links);
        invalidateRing();
    }

    public void clearRingLinks() {
        layers.removeIf(l -> l.kind == CircularLayer.Kind.LINKS);
        trackLinks.clear();
        invalidateRing();
    }

    /** Links contributed by the visible LINKS layers. */
    private List<RingLink> layerLinks() {
        List<RingLink> out = new ArrayList<>();
        for (CircularLayer l : layers) {
            if (l.kind != CircularLayer.Kind.LINKS || !l.visible) continue;
            out.addAll(l.links);
        }
        return out;
    }

    public int getRingLinkCount() {
        return layerLinks().size() + trackLinks.size();
    }

    private List<RingLink> allLinks() {
        List<RingLink> all = layerLinks();
        all.addAll(trackLinks);
        return all;
    }

    /**
     * Auto-collect links from every loaded interaction track (BEDPE). Each {@link BedPEFeature}
     * is one connection between two genomic intervals — exactly the Circos / circlize {@code links}
     * semantic. This is what makes a BEDPE / Circos-link file loaded through File &gt; Load from
     * File (which becomes an {@link InteractionTrack}) appear in the circular view automatically.
     * Called from {@link #setRingTracks}, so the link layer re-syncs whenever the track set changes.
     */
    public void refreshTrackLinks() {
        List<RingLink> links = new ArrayList<>();
        IGV igv = IGV.getInstance();
        if (igv != null) {
            for (Track t : igv.getAllTracks()) {
                if (t instanceof InteractionTrack) {
                    for (BedPEFeature f : ((InteractionTrack) t).getAllFeatures()) {
                        if (f.chr1 == null || f.chr2 == null) continue;
                        RingLink l = new RingLink(f.chr1, f.start1, f.end1, f.chr2, f.start2, f.end2);
                        l.score = f.getScore();
                        l.color = f.getColor();
                        l.thickness = f.getThickness();
                        l.label = f.getValueString();
                        links.add(l);
                    }
                }
            }
        }
        setTrackLinks(links);
    }

    // ------------------------------------------------------------------
    //  Genomic heatmap (rows x samples matrix, e.g. hg38_heatmap.bed)
    //  Rendered as a ring band: angular = genomic position, radial = the
    //  samples stacked as sub-bands, colour = diverging (blue .. white .. red).
    //  This is the inner heatmap of a circlize genomicHeatmap plot.
    // ------------------------------------------------------------------

    /** User-loaded genomic heatmap (from "Load heatmap…"). */
    private GenomicHeatmap heatmap = null;

    /** Replace the genomic heatmap. {@code null} clears it. */
    public void setHeatmap(GenomicHeatmap hm) {
        this.heatmap = hm;
        invalidateRing();
        log.info("Circular heatmap: " + (hm == null ? 0 : hm.rows.size()) + " row(s), "
                + (hm == null ? 0 : hm.samples) + " sample(s)");
    }

    public GenomicHeatmap getHeatmap() { return heatmap; }

    /** One heatmap row: a genomic interval carrying {@code samples} numeric values. */
    public static class HeatRow {
        public final String chr;
        public final long start, end;
        public double[] vals;
        public HeatRow(String chr, long start, long end, double[] vals) {
            this.chr = chr; this.start = start; this.end = end; this.vals = vals;
        }
    }

    /** A rows x samples matrix for the circular heatmap band. */
    public static class GenomicHeatmap {
        public final int samples;
        public final double min, max;
        public final List<HeatRow> rows;
        public GenomicHeatmap(int samples, double min, double max, List<HeatRow> rows) {
            this.samples = samples; this.min = min; this.max = max; this.rows = rows;
        }
    }

    /**
     * Parse a multi-value BED / TSV into a {@link GenomicHeatmap}. Each line is
     * {@code chr start end v1 v2 ... vN}. Lines starting with '#' or lacking numeric
     * coordinate columns are skipped. The diverging colour scale spans the global min..max.
     */
    public static GenomicHeatmap parseHeatmap(org.broad.igv.util.ResourceLocator locator) throws java.io.IOException {
        List<HeatRow> rows = new ArrayList<>();
        int samples = 0;
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        java.io.File f = new java.io.File(locator.getPath());
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tok = line.split("\t");
                if (tok.length < 4) tok = line.trim().split("\\s+");
                if (tok.length < 4) continue;
                long start, end;
                try { start = Long.parseLong(tok[1]); end = Long.parseLong(tok[2]); }
                catch (NumberFormatException e) { continue; }   // header / non-data line
                int nval = tok.length - 3;
                double[] vals = new double[nval];
                boolean ok = true;
                for (int i = 0; i < nval; i++) {
                    try { vals[i] = Double.parseDouble(tok[3 + i]); }
                    catch (NumberFormatException e) { ok = false; break; }
                }
                if (!ok || nval == 0) continue;
                if (nval > samples) samples = nval;
                for (double v : vals) { if (v < mn) mn = v; if (v > mx) mx = v; }
                rows.add(new HeatRow(tok[0], start, end, vals));
            }
        }
        if (rows.isEmpty()) return null;
        // pad short rows to `samples` with NaN so every row has the same width
        for (HeatRow r : rows) {
            if (r.vals.length < samples) {
                double[] p = new double[samples];
                java.util.Arrays.fill(p, Double.NaN);
                System.arraycopy(r.vals, 0, p, 0, r.vals.length);
                r.vals = p;
            }
        }
        if (mn == Double.POSITIVE_INFINITY) { mn = 0; mx = 1; }
        return new GenomicHeatmap(samples, mn, mx, rows);
    }

    /**
     * Heatmap colour ramp with configurable anchors.
     *
     * <p>Three colours (low / mid / high) are used when the value range straddles 0 — the mid
     * colour is then pinned at 0 and the scale is symmetric, so "no change" reads as the mid
     * colour. A one-sided range (all values > 0 or all < 0) has no meaningful midpoint, so it
     * gets a plain two-colour ramp from low to high. Colours come from
     * {@link CircularSettings#heatmapLowColor} and friends (Heatmap layer context menu).
     */
    static Color divergingColor(double v, double mn, double mx, CircularSettings s) {
        return divergingColor(v, mn, mx,
                s == null ? new Color(0, 150, 0) : s.heatmapLowColor,
                s == null ? new Color(0, 0, 0) : s.heatmapMidColor,
                s == null ? new Color(215, 40, 40) : s.heatmapHighColor);
    }

    /** Same ramp with explicit colours (kept for callers that have no settings at hand). */
    static Color divergingColor(double v, double mn, double mx,
                                Color low, Color mid, Color high) {
        if (Double.isNaN(v)) return new Color(200, 200, 200);
        if (low == null) low = new Color(0, 150, 0);
        if (mid == null) mid = new Color(0, 0, 0);
        if (high == null) high = new Color(215, 40, 40);

        boolean straddles = mn < 0 && mx > 0;
        if (!straddles) {
            // one-sided data: no midpoint -> two colours, min -> max
            double denom = (mx - mn) > 1e-9 ? (mx - mn) : 1.0;
            return mix(low, high, clamp01((v - mn) / denom));
        }
        double a = Math.max(-mn, mx);                  // symmetric about 0
        double t = clamp01((v + a) / (2 * a));         // 0 = low, 0.5 = 0, 1 = high
        return t <= 0.5 ? mix(low, mid, t / 0.5) : mix(mid, high, (t - 0.5) / 0.5);
    }

    /** Linear blend of two colours (c2 weight = t). */
    static Color mix(Color c1, Color c2, double t) {
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return new Color(
                clamp8((int) Math.round(c1.getRed() + (c2.getRed() - c1.getRed()) * t)),
                clamp8((int) Math.round(c1.getGreen() + (c2.getGreen() - c1.getGreen()) * t)),
                clamp8((int) Math.round(c1.getBlue() + (c2.getBlue() - c1.getBlue()) * t)));
    }

    /**
     * Draw the genomic-heatmap ring band: angular = genomic position, radial = stacked samples.
     *
     * <p>Cells are EQUAL-WIDTH slices of each chromosome, not proportional to the interval
     * width: that is what circlize's {@code posTransform.default} does before drawing
     * ({@code segment = seq(xlim[1], xlim[2], length.out = nrow+1)}), and it is the reason the
     * connector lines fan instead of standing straight. Rows stack outward, so numeric column 1
     * is the innermost ring and the last column touches the connection track (circlize
     * {@code stack = TRUE}: ytop = i + 0.5).
     */
    private void drawHeatmapRing(Graphics2D g2, int cx, int cy, double bandInner, double bandOuter,
                                List<ChrInfo> chrs, List<double[]> sec, GenomicHeatmap hm) {
        double bandH = bandOuter - bandInner;
        if (bandH < 2 || hm == null || hm.rows.isEmpty()) return;
        java.util.Map<String, List<HeatRow>> byChr = rowsByChromosome(hm);
        java.util.Map<String, Integer> idx = chromosomeIndex(chrs);
        int samples = hm.samples;
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(0.9f));       // white cell borders, like border = "white"
        for (java.util.Map.Entry<String, List<HeatRow>> e : byChr.entrySet()) {
            Integer ii = sectorFor(idx, e.getKey());
            if (ii == null) continue;
            int si = ii;
            double f0 = sec.get(si)[0], span = sec.get(si)[1] - f0;
            if (span <= 0) continue;
            List<HeatRow> list = e.getValue();
            int n = list.size();
            for (int i = 0; i < n; i++) {
                HeatRow row = list.get(i);
                // Equal-width slice i of n across this chromosome's arc (posTransform.default).
                double a0 = f0 + span * i / (double) n;
                double a1 = f0 + span * (i + 1) / (double) n;
                if (a1 <= a0) a1 = a0 + 1e-4;
                for (int j = 0; j < samples; j++) {
                    double v = j < row.vals.length ? row.vals[j] : Double.NaN;
                    if (Double.isNaN(v)) continue;
                    double rIn = bandInner + bandH * j / samples;
                    double rOut = bandInner + bandH * (j + 1) / samples;
                    Shape cell = annularPath(cx, cy, rIn, rOut, a0, a1);
                    g2.setColor(divergingColor(v, hm.min, hm.max, settings));
                    g2.fill(cell);
                    g2.setColor(Color.WHITE);
                    g2.draw(cell);
                }
            }
        }
        g2.setStroke(oldStroke);
    }

    /** Heatmap rows grouped per chromosome, keeping the file's row order (circlize row order). */
    private static java.util.Map<String, List<HeatRow>> rowsByChromosome(GenomicHeatmap hm) {
        java.util.Map<String, List<HeatRow>> byChr = new java.util.LinkedHashMap<>();
        for (HeatRow r : hm.rows) {
            byChr.computeIfAbsent(r.chr, k -> new ArrayList<>()).add(r);
        }
        return byChr;
    }

    /**
     * Sector lookup by chromosome name, tolerant of the "chr" prefix (and case) mismatch that
     * IGV data files constantly have: a BED written as {@code chr1} must still land on a sector
     * named {@code 1} and vice versa. Without this the whole heatmap silently draws nothing.
     */
    static java.util.Map<String, Integer> chromosomeIndex(List<ChrInfo> chrs) {
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < chrs.size(); i++) {
            String n = chrs.get(i).name;
            if (n == null) continue;
            idx.putIfAbsent(n, i);
            idx.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), i);
            String bare = n.regionMatches(true, 0, "chr", 0, 3) ? n.substring(3) : "chr" + n;
            idx.putIfAbsent(bare, i);
            idx.putIfAbsent(bare.toLowerCase(java.util.Locale.ROOT), i);
        }
        return idx;
    }

    /** Resolve a data-file chromosome name against the sector index built by chromosomeIndex(). */
    static Integer sectorFor(java.util.Map<String, Integer> idx, String name) {
        if (name == null) return null;
        Integer i = idx.get(name);
        if (i != null) return i;
        i = idx.get(name.toLowerCase(java.util.Locale.ROOT));
        if (i != null) return i;
        String bare = name.regionMatches(true, 0, "chr", 0, 3) ? name.substring(3) : "chr" + name;
        i = idx.get(bare);
        return i != null ? i : idx.get(bare.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Place and draw the heatmap band + its comb, either INSIDE the ideogram (between the
     * ideogram and the data-track bands) or OUTSIDE it, as circlize's {@code side=} argument
     * does. When outside, the whole ring is pulled in (see {@link #ideogramOuterRadius}) so the
     * connection track and the cells still fit inside the panel.
     */
    private double drawHeatmapBand(Graphics2D g2, int cx, int cy, int w, int h, double R, double rOut,
                                   double cursor, List<ChrInfo> chrs, List<double[]> sec,
                                   CircularSettings s, GenomicHeatmap hm, boolean stackInside) {
        double connH = connectionTrackHeight(s);
        double heatH = Math.max(8, s.heatmapHeightPx > 0 ? s.heatmapHeightPx : 50);
        if (s.heatmapOutside) {
            double bandInner = outsideHeatmapInner(rOut, s) + connH;
            double bandOuter = Math.min(bandInner + heatH, Math.min(w, h) / 2.0 - 6);
            if (bandOuter - bandInner < 6) return cursor;
            if (connH > 0) {
                drawHeatmapConnectors(g2, cx, cy, rOut, bandInner, true, chrs, sec, hm);
            }
            drawHeatmapRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, hm);
            return cursor;                              // outside: the inner stack is untouched
        }
        // INSIDE: the comb lives in its OWN track, exactly like circlize's connection track
        // (connection_height of its own, then the heatmap track). It used to stretch from the
        // ideogram inner edge all the way down to the band, so it ran straight through every
        // track band in between -- and shorting the height did nothing visible.
        double bandOuter = cursor - connH;
        double bandInner = bandOuter - heatH;
        if (bandInner < 24) return cursor;
        if (connH > 0) {
            drawHeatmapConnectors(g2, cx, cy, cursor, bandOuter, false, chrs, sec, hm);
        }
        drawHeatmapRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, hm);
        return bandInner - 5;
    }

    /**
     * Draw the circlize "comb" between the ideogram and the heatmap, following
     * {@code circos.genomicPosTransformLines(posTransform = posTransform.default,
     * horizontalLine = "top")} exactly:
     *
     * <ol>
     *   <li>a horizontal cap over the region's REAL genomic interval, hugging the ideogram;</li>
     *   <li>a radial stem from that interval's midpoint down to 2/3 of the connection track;</li>
     *   <li>a diagonal to 1/3 of the track at the CELL's midpoint (cells are equal-width slices,
     *       so the two midpoints differ — this is what makes the teeth fan);</li>
     *   <li>a radial stem from there onto the heatmap band's outer edge.</li>
     * </ol>
     * Uniform black, as in circlize: the value is carried by the cell colour, not the line.
     */
    private void drawHeatmapConnectors(Graphics2D g2, int cx, int cy, double rIdeoEdge,
                                       double bandNear, boolean outward,
                                       List<ChrInfo> chrs, List<double[]> sec, GenomicHeatmap hm) {
        if (hm == null || hm.rows.isEmpty()) return;
        double h = Math.abs(bandNear - rIdeoEdge);
        if (h < 2) return;
        // outward=true: heatmap outside the ideogram, so every station grows AWAY from the centre
        // (circlize direction="outside", horizontalLine="bottom"); false mirrors it inwards.
        double dir = outward ? 1.0 : -1.0;
        java.util.Map<String, List<HeatRow>> byChr = rowsByChromosome(hm);
        java.util.Map<String, Integer> idx = chromosomeIndex(chrs);
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(0.7f));
        g2.setColor(Color.BLACK);
        double rMidTrack = rIdeoEdge + dir * h / 3.0;   // first third of the connection track
        double rLowerTrack = rIdeoEdge + dir * 2 * h / 3.0;
        for (java.util.Map.Entry<String, List<HeatRow>> e : byChr.entrySet()) {
            Integer ii = sectorFor(idx, e.getKey());
            if (ii == null) continue;
            ChrInfo c = chrs.get(ii);
            double f0 = sec.get(ii)[0], span = sec.get(ii)[1] - f0;
            if (span <= 0) continue;
            double vStart = c.viewStart, vSpan = c.viewSpan();
            if (vSpan <= 0) vSpan = Math.max(1, c.length);
            List<HeatRow> list = e.getValue();
            int n = list.size();
            for (int i = 0; i < n; i++) {
                HeatRow row = list.get(i);
                // (a) real genomic position of the interval -> the cap and the upper stem
                double a0 = f0 + span * clamp01(((double) row.start - vStart) / vSpan);
                double a1 = f0 + span * clamp01(((double) row.end - vStart) / vSpan);
                if (a1 < a0) { double t = a0; a0 = a1; a1 = t; }
                double fracReal = (a0 + a1) / 2.0;
                // (b) equal-width cell slot -> the lower stem (posTransform.default)
                double fracCell = f0 + span * (i + 0.5) / n;

                // horizontal cap over the real interval, hugging the ideogram edge
                g2.fill(annularPath(cx, cy, rIdeoEdge - 0.45, rIdeoEdge + 0.45, a0, a1));

                Point2D pTop = polar(cx, cy, rIdeoEdge, fracReal);
                Point2D pMid = polar(cx, cy, rMidTrack, fracReal);
                Point2D pLow = polar(cx, cy, rLowerTrack, fracCell);
                Point2D pBot = polar(cx, cy, bandNear, fracCell);
                g2.draw(new Line2D.Double(pTop, pMid));
                g2.draw(new Line2D.Double(pMid, pLow));
                g2.draw(new Line2D.Double(pLow, pBot));
            }
        }
        g2.setStroke(old);
    }

    public CircularViewPanel() {
        setBackground(BG);
        setOpaque(true);
        IGVEventBus.getInstance().subscribe(FrameManager.ChangeEvent.class, this);
        IGVEventBus.getInstance().subscribe(org.broad.igv.event.RefreshEvent.class, this);
        addMouseWheelListener(this::handleWheel);

        // IGV loads feature tracks asynchronously; a fetch started too early returns empty.
        // If any shown chromosome still lacks a cached result, repaint so ensureFeaturesLoaded
        // retries it (with a cooldown), until data arrives or we give up.
        retryTimer = new javax.swing.Timer(1500, e -> {
            if (ringTracks.isEmpty()) return;
            Genome g = GenomeManager.getInstance().getCurrentGenome();
            if (g != null) ensureFeaturesLoaded(g);   // submissions only; data arrival triggers repaint
        });
        retryTimer.setRepeats(true);
        retryTimer.start();
    }

    /** Current settings (for the settings dialog / callers). */
    public CircularSettings getSettings() { return settings; }

    /** Clear all per-chromosome zoom factors. */
    /** Clear all per-chromosome zoom factors and visible windows. */
    public void resetZoom() {
        zoom.clear();
        rangeZoom.clear();
        invalidateHiCTiles();
        ringKey = null;
        repaint();
    }

    /** Force a redraw after settings changed (settings are read live during paint). */
    public void refresh() {
        invalidateHiCTiles();
        ringKey = null;
        repaint();
    }

    @Override
    public void receiveEvent(IGVEvent event) {
        if (event instanceof org.broad.igv.event.RefreshEvent) {
            // A refresh means IGV (re)loaded data. Purge cached EMPTY feature results so those
            // chromosomes are refetched — this is what fills in a track that was only partially
            // parsed when we first queried it (e.g. only chrX had data).
            List<String> empties = new ArrayList<>();
            for (Map.Entry<String, List<Feature>> e : featureCache.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) empties.add(e.getKey());
            }
            if (!empties.isEmpty()) {
                for (String k : empties) featureCache.remove(k);
                loadAttempts.clear();
                lastAttemptMs.clear();
                ringKey = null;
            }
        }
        repaint();
    }

    // ------------------------------------------------------------------
    //  Painting
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) {
            g2.setColor(Color.GRAY);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
            String t = "Circular view — no genome loaded";
            g2.drawString(t, (w - g2.getFontMetrics().stringWidth(t)) / 2, h / 2);
            return;
        }

        int gid = System.identityHashCode(genome);
        if (gid != lastGenomeId) {
            zoom.clear(); rangeZoom.clear(); featureCache.clear(); geneFeatures.clear(); pendingLoads.clear();
            loadAttempts.clear(); lastAttemptMs.clear(); trackLoaded.clear();
            dataCache.clear(); dataPending.clear();
            adaptiveCache.clear(); adaptivePending.clear();
            invalidateHiCTiles();
            lastGenomeId = gid; ringKey = null;
        }

        String chr = FrameManager.getDefaultFrame().getChrName();
        if (chr == null || chr.isEmpty()) chr = genome.getHomeChromosome();

        ensureFeaturesLoaded(genome);
        ensureDataLoaded(genome);

        // Render the offscreen ring at DEVICE resolution so it stays crisp on HiDPI
        // (Windows 125/150/200% scaling). The component is w x h logical pixels; the
        // graphics transform carries the device scale (sx, sy).
        AffineTransform tx = g2.getTransform();
        double sx = tx.getScaleX(), sy = tx.getScaleY();
        if (sx <= 0) sx = 1.0;
        if (sy <= 0) sy = 1.0;
        int bw = Math.max(1, (int) Math.ceil(w * sx));
        int bh = Math.max(1, (int) Math.ceil(h * sy));

        // Gene-list mode takes priority: one sector per gene from the session's current gene list.
        boolean geneMode = FrameManager.isGeneListMode();
        String geneSig = "";
        if (geneMode) {
            GeneList gl = IGV.getInstance().getSession().getCurrentGeneList();
            if (gl != null && gl.getLoci() != null) geneSig = "|G" + gl.getLoci().hashCode();
        }
        String key = gid + "|" + chr + "|" + bw + "x" + bh + geneSig;
        if (!key.equals(ringKey) || ringImage == null) {
            hicLimitsByTrack.clear();   // only legends for tracks drawn in THIS rebuild
            ringImage = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = ringImage.createGraphics();
            ig.scale(sx, sy);   // draw in logical coords, mapped to device pixels
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            ig.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            geneFeatures.clear();
            if (geneMode) {
                // One sector per gene; each sector draws its gene's structure (UTR/CDS) even with
                // no gene track loaded, plus any data tracks anchored to the gene's chromosome.
                List<ChrInfo> geneChrs = buildGenes(genome, zoom, rangeZoom, settings, geneFeatures);
                if (geneChrs.isEmpty()) {
                    drawMessage(ig, w, h, "Gene list empty or no genes recognised");
                } else {
                    lastChrs = geneChrs;
                    drawAll(ig, w, h, geneChrs, settings, hasVisibleLabels());
                    drawTrackRings(ig, w, h, geneChrs, settings);
                }
            } else if (Globals.CHR_ALL.equals(chr)) {
                List<ChrInfo> chrs = buildAll(genome, zoom, rangeZoom, settings);
                lastChrs = chrs;
                drawAll(ig, w, h, chrs, settings, hasVisibleLabels());
                drawTrackRings(ig, w, h, chrs, settings);
                // Links are an independent z-layer drawn last, like Circos, so they sit on top.
                drawLinks(ig, w, h, chrs, allLinks(), settings, linkAnchorRadius(w, h, settings));
            } else {
                ChrInfo one = buildOne(genome, chr, rangeZoom, settings);
                if (one == null) drawMessage(ig, w, h, "Chromosome not found: " + chr);
                else {
                    lastChrs = Collections.singletonList(one);
                    drawSingle(ig, w, h, one, settings, hasVisibleLabels());
                    drawTrackRingsSingle(ig, w, h, one, settings);
                    drawLinks(ig, w, h, Collections.singletonList(one), allLinks(), settings,
                            linkAnchorRadius(w, h, settings));
                }
            }
            if (!pendingLoads.isEmpty()) {
                ig.setColor(new Color(120, 120, 120));
                ig.setFont(new Font("SansSerif", Font.PLAIN, 11));
                ig.drawString("Loading features… (" + pendingLoads.size() + ")", 10, h - 10);
            }
            ig.dispose();
            layoutLabels(w, h, lastChrs, settings);   // label ring is laid out with the ring
            ringKey = key;
        }
        // blit the device-resolution image back at logical size; the panel transform
        // scales it to exactly bw x bh device pixels -> 1:1, sharp.
        g2.drawImage(ringImage, 0, 0, w, h, null);

        // Hi-C colour legends sit BESIDE the plot, never inside a ring band. Draw them on top
        // of the blitted ring image so they stay upright and crisp (also on cached repaints).
        List<HiCTrack> visibleHics = new ArrayList<>();
        for (Track t : ringTracks) if (t instanceof HiCTrack) visibleHics.add((HiCTrack) t);
        if (!visibleHics.isEmpty()) {
            drawHiCLegends(g2, w, h, visibleHics, hicLimitsByTrack, settings);
        }
        // The heatmap legend is independent of any Hi-C track being loaded.
        drawHeatmapLegend(g2, w, h, heatmap, settings, visibleHics);
        drawLabelLayers(g2, w, h, settings);
    }

    // ------------------------------------------------------------------
    //  Off-screen rendering (PNG / SVG / PDF export)
    // ------------------------------------------------------------------

    /**
     * Render the complete circular view into an arbitrary {@link Graphics2D} at {@code w x h}
     * logical units. This is the seam the exporters use: it runs exactly the same drawing code
     * as {@link #paintComponent} but never touches the on-screen {@code ringImage} cache, so
     * exporting at a size different from the window cannot disturb — or be disturbed by — the
     * display. Call on the EDT; data is whatever is already loaded.
     */
    public void renderTo(Graphics2D g2, int w, int h) {
        if (g2 == null || w <= 0 || h <= 0) return;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) {
            drawMessage(g2, w, h, "No genome loaded");
            return;
        }
        String chr = FrameManager.getDefaultFrame().getChrName();
        if (chr == null || chr.isEmpty()) chr = genome.getHomeChromosome();

        // Load whatever is still missing so a first-time export is not blank. These are
        // idempotent (guarded by pendingLoads / trackLoaded) and asynchronous, so an export
        // started right after opening the panel renders the already-available data.
        ensureFeaturesLoaded(genome);
        ensureDataLoaded(genome);

        // Legends are collected during drawing; start clean so only the tracks drawn now report.
        hicLimitsByTrack.clear();

        boolean geneMode = FrameManager.isGeneListMode();
        if (geneMode) {
            geneFeatures.clear();
            List<ChrInfo> geneChrs = buildGenes(genome, zoom, rangeZoom, settings, geneFeatures);
            if (geneChrs.isEmpty()) {
                drawMessage(g2, w, h, "Gene list empty or no genes recognised");
                return;
            }
            lastChrs = geneChrs;
            drawAll(g2, w, h, geneChrs, settings, hasVisibleLabels());
            drawTrackRings(g2, w, h, geneChrs, settings);
            drawLinks(g2, w, h, geneChrs, allLinks(), settings, linkAnchorRadius(w, h, settings));
        } else if (Globals.CHR_ALL.equals(chr)) {
            List<ChrInfo> chrs = buildAll(genome, zoom, rangeZoom, settings);
            lastChrs = chrs;
            drawAll(g2, w, h, chrs, settings, hasVisibleLabels());
            drawTrackRings(g2, w, h, chrs, settings);
            drawLinks(g2, w, h, chrs, allLinks(), settings, linkAnchorRadius(w, h, settings));
        } else {
            ChrInfo one = buildOne(genome, chr, rangeZoom, settings);
            if (one == null) {
                drawMessage(g2, w, h, "Chromosome not found: " + chr);
                return;
            }
            lastChrs = Collections.singletonList(one);
            drawSingle(g2, w, h, one, settings, hasVisibleLabels());
            drawTrackRingsSingle(g2, w, h, one, settings);
            drawLinks(g2, w, h, Collections.singletonList(one), allLinks(), settings,
                    linkAnchorRadius(w, h, settings));
        }

        List<HiCTrack> visibleHics = new ArrayList<>();
        for (Track t : ringTracks) if (t instanceof HiCTrack) visibleHics.add((HiCTrack) t);
        if (!visibleHics.isEmpty()) drawHiCLegends(g2, w, h, visibleHics, hicLimitsByTrack, settings);
        drawHeatmapLegend(g2, w, h, heatmap, settings, visibleHics);
        layoutLabels(w, h, lastChrs, settings);
        drawLabelLayers(g2, w, h, settings);
    }

    // ------------------------------------------------------------------
    //  Wheel zoom ("All" mode)
    //    Ctrl  -> widen / narrow the chromosome's arc (sector span)
    //    Shift -> widen / narrow the visible bp window under the cursor, arc unchanged
    // ------------------------------------------------------------------

    /** Smallest visible window, so Shift+wheel can get down to base-pair scale. */
    private static final double MIN_RANGE_BP = 200;

    private void handleWheel(MouseWheelEvent e) {
        boolean ctrl = e.isControlDown();
        boolean shift = e.isShiftDown();
        if (!ctrl && !shift) return;
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) return;

        String frameChr = FrameManager.getDefaultFrame().getChrName();
        boolean zoomIn = e.getWheelRotation() < 0;
        int cx = getWidth() / 2, cy = getHeight() / 2;
        double theta = Math.atan2(e.getX() - cx, -(e.getY() - cy));
        double frac = theta / (2 * Math.PI);
        if (frac < 0) frac += 1.0;

        if (FrameManager.isGeneListMode()) {
            // Gene-list mode: zoom the gene sector under the cursor. Ctrl widens/narrows the
            // sector's arc (like All mode); Shift narrows/widens the visible bp window, anchored
            // at the cursor (like single-chromosome mode). One gene = one sector.
            geneFeatures.clear();
            List<ChrInfo> gchrs = buildGenes(genome, zoom, rangeZoom, settings, geneFeatures);
            if (gchrs.isEmpty()) return;
            String target = chromosomeAt(e.getX(), e.getY(), gchrs);
            if (target == null) return;
            int idx = indexOf(gchrs, target);
            if (idx < 0) return;
            if (ctrl) {
                double z = zoom.getOrDefault(target, 1.0);
                z = Math.max(0.25, Math.min(25.0, z * (zoomIn ? 1.12 : 1.0 / 1.12)));
                zoom.put(target, z);
            } else {
                List<double[]> sec = computeSectors(gchrs, settings.gapDeg);
                double f0 = sec.get(idx)[0], f1 = sec.get(idx)[1];
                double t = f1 > f0 ? (frac - f0) / (f1 - f0) : 0.5;
                zoomRange(gchrs.get(idx), Math.max(0, Math.min(1, t)), zoomIn);
            }
            ringKey = null;
            repaint();
            return;
        }

        if (Globals.CHR_ALL.equals(frameChr)) {
            List<ChrInfo> chrs = buildAll(genome, zoom, rangeZoom, settings);
            String target = chromosomeAt(e.getX(), e.getY(), chrs);
            if (target == null) return;
            int idx = indexOf(chrs, target);
            if (idx < 0) return;

            if (ctrl) {
                // Ctrl: widen / narrow this chromosome's arc (sector span).
                double z = zoom.getOrDefault(target, 1.0);
                z = Math.max(0.25, Math.min(25.0, z * (zoomIn ? 1.12 : 1.0 / 1.12)));
                zoom.put(target, z);
            } else {
                // Shift: keep the arc, narrow / widen the bp window under the cursor.
                List<double[]> sec = computeSectors(chrs, settings.gapDeg);
                double f0 = sec.get(idx)[0], f1 = sec.get(idx)[1];
                double t = f1 > f0 ? (frac - f0) / (f1 - f0) : 0.5;
                zoomRange(chrs.get(idx), Math.max(0, Math.min(1, t)), zoomIn);
            }
        } else if (frameChr != null && !frameChr.isEmpty()) {
            // Single-chromosome mode: the chromosome already fills the ring, so the wheel zooms
            // the visible bp window and that interval is drawn as the full ring.
            ChrInfo one = buildOne(genome, frameChr, rangeZoom, settings);
            if (one == null) return;
            double[] sec = sectorsFor(Collections.singletonList(one), settings).get(0);
            double f0 = sec[0], f1 = sec[1];
            double ff = frac;
            if (ff < f0) ff += 1.0;
            double t = f1 > f0 ? (ff - f0) / (f1 - f0) : 0.5;
            zoomRange(one, Math.max(0, Math.min(1, t)), zoomIn);
        } else {
            return;
        }

        ringKey = null;
        repaint();
    }

    private static int indexOf(List<ChrInfo> chrs, String name) {
        for (int i = 0; i < chrs.size(); i++) {
            if (chrs.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    /** Narrow / widen the visible bp window of one chromosome, anchored at fraction t (0..1). */
    private void zoomRange(ChrInfo ci, double t, boolean zoomIn) {
        double span = ci.viewEnd - ci.viewStart;
        double anchor = ci.viewStart + t * span;
        double newSpan = Math.max(MIN_RANGE_BP, Math.min(ci.length, span * (zoomIn ? 1 / 1.6 : 1.6)));
        if (newSpan >= ci.length) {          // fully widened -> back to the whole chromosome
            rangeZoom.remove(ci.name);
            return;
        }
        double newStart = anchor - t * newSpan;
        if (newStart < 0) newStart = 0;
        if (newStart + newSpan > ci.length) newStart = ci.length - newSpan;
        rangeZoom.put(ci.name, new double[]{newStart, newStart + newSpan});
    }

    private String chromosomeAt(int x, int y, List<ChrInfo> chrs) {
        if (chrs.isEmpty()) return null;
        int cx = getWidth() / 2, cy = getHeight() / 2;
        double R = Math.min(getWidth(), getHeight()) / 2.0 - MARGIN;
        if (R <= 0) return null;
        double rOut = R * settings.bandOuter;
        double rIn = Math.max(R * 0.2, rOut - settings.chromosomeHeightPx);

        double dx = x - cx, dy = y - cy, r = Math.hypot(dx, dy);
        if (r < rIn * 0.6 || r > R + MARGIN) return null;

        double theta = Math.atan2(dx, -dy);
        double frac = theta / (2 * Math.PI);
        if (frac < 0) frac += 1.0;

        List<double[]> sec = sectorsFor(chrs, settings);
        for (int i = 0; i < chrs.size(); i++) {
            double f0 = sec.get(i)[0], f1 = sec.get(i)[1];
            double ff = frac;
            if (ff < f0) ff += 1.0;                   // sector may wrap past 12 o'clock
            if (ff >= f0 && ff < f1) return chrs.get(i).name;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Settings dialog  (opened from the in-panel toolbar)
    // ------------------------------------------------------------------

    public void showSettingsDialog(Component parent) {
        final CircularSettings s = settings;

        JCheckBox cbAxis = new JCheckBox("Show coordinate axis", s.showAxis);
        JCheckBox cbTicks = new JCheckBox("Show tick marks", s.showTicks);
        JCheckBox cbTickLabels = new JCheckBox("Show tick labels", s.showTickLabels);
        JCheckBox cbChrLabels = new JCheckBox("Show chromosome labels", s.showChromosomeLabels);
        JComboBox<String> cbPos = new JComboBox<>(new String[]{"Outside (above the band)", "Inside (below the band)"});
        cbPos.setSelectedIndex(s.tickLabelsOutside ? 0 : 1);
        JSpinner spSpace = new JSpinner(new SpinnerNumberModel(s.tickSpacingMb, 0.0, 5000.0, 10.0));
        JSpinner spMinor = new JSpinner(new SpinnerNumberModel(s.minorTicksPerMajor, 0, 20, 1));
        JSpinner spAxisW = new JSpinner(new SpinnerNumberModel(s.axisLineWidth, 0.25, 6.0, 0.25));
        JSpinner spTickSize = new JSpinner(new SpinnerNumberModel(s.tickLabelSize, 4, 24, 1));
        JSpinner spChrSize = new JSpinner(new SpinnerNumberModel(s.chromosomeLabelSize, 6, 30, 1));
        JSpinner spChrH = new JSpinner(new SpinnerNumberModel(s.chromosomeHeightPx, 8, 220, 2));
        JComboBox<String> cbOrient = new JComboBox<>(new String[]{"Tangential", "Radial"});
        cbOrient.setSelectedIndex(s.labelTangential ? 0 : 1);
        JSpinner spInner = new JSpinner(new SpinnerNumberModel(s.bandInner, 0.40, 0.85, 0.01));
        JSpinner spOuter = new JSpinner(new SpinnerNumberModel(s.bandOuter, 0.60, 0.99, 0.01));
        JSpinner spGap = new JSpinner(new SpinnerNumberModel(s.gapDeg, 0.0, 8.0, 0.1));

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(cbAxis);
        p.add(cbTicks);
        p.add(cbTickLabels);
        p.add(cbChrLabels);
        p.add(row("Tick label position:", cbPos));
        p.add(row("Tick spacing (Mb, 0 = auto):", spSpace));
        p.add(row("Minor ticks per major (0 = off):", spMinor));
        p.add(row("Axis line thickness (px):", spAxisW));
        p.add(row("Tick label size:", spTickSize));
        p.add(row("Chromosome label size:", spChrSize));
        p.add(row("Chromosome height (px):", spChrH));
        p.add(row("Chromosome number orientation:", cbOrient));
        p.add(row("Band inner radius (0–1):", spInner));
        p.add(row("Band outer radius (0–1):", spOuter));
        p.add(row("Gap between chromosomes (°):", spGap));

        int r = JOptionPane.showConfirmDialog(parent, p, "Circular View Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        s.showAxis = cbAxis.isSelected();
        s.showTicks = cbTicks.isSelected();
        s.showTickLabels = cbTickLabels.isSelected();
        s.showChromosomeLabels = cbChrLabels.isSelected();
        s.tickLabelsOutside = cbPos.getSelectedIndex() == 0;
        s.tickSpacingMb = ((Number) spSpace.getValue()).doubleValue();
        s.minorTicksPerMajor = ((Number) spMinor.getValue()).intValue();
        s.axisLineWidth = ((Number) spAxisW.getValue()).doubleValue();
        s.tickLabelSize = ((Number) spTickSize.getValue()).intValue();
        s.chromosomeLabelSize = ((Number) spChrSize.getValue()).intValue();
        s.chromosomeHeightPx = ((Number) spChrH.getValue()).intValue();
        s.labelTangential = cbOrient.getSelectedIndex() == 0;
        s.bandInner = ((Number) spInner.getValue()).doubleValue();
        s.bandOuter = ((Number) spOuter.getValue()).doubleValue();
        s.gapDeg = ((Number) spGap.getValue()).doubleValue();
        if (s.bandInner >= s.bandOuter) s.bandOuter = Math.min(0.99, s.bandInner + 0.05);

        ringKey = null;
        repaint();
    }

    private static JPanel row(String label, JComponent field) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(210, 20));
        r.add(l);
        r.add(field);
        return r;
    }

    // ------------------------------------------------------------------
    //  Genome -> ChrInfo list
    // ------------------------------------------------------------------

    /** Clear, saturated palette for species without cytoband (karyotype) data. */
    private static final Color[] PALETTE = {
            new Color(93, 145, 190), new Color(222, 126, 80), new Color(119, 167, 109),
            new Color(196, 120, 160), new Color(180, 160, 105), new Color(150, 140, 190),
            new Color(215, 140, 140), new Color(110, 160, 160), new Color(190, 170, 90),
            new Color(120, 130, 180), new Color(210, 110, 130), new Color(140, 170, 120),
            new Color(170, 140, 90), new Color(120, 150, 180), new Color(200, 160, 170),
            new Color(130, 160, 130)
    };

    /** Cytoband data -> circlize grayscale ideogram; no data -> distinct solid palette colour. */
    public static Color pickColor(String name, List<Cytoband> cyto) {
        if (cyto != null && !cyto.isEmpty()) return ChromosomeColors.getColor(name);
        int idx = Math.floorMod(name == null ? 0 : name.hashCode(), PALETTE.length);
        return PALETTE[idx];
    }

    static List<ChrInfo> buildAll(Genome genome, Map<String, Double> zoomMap, CircularSettings s) {
        return buildAll(genome, zoomMap, null, s);
    }

    static List<ChrInfo> buildAll(Genome genome, Map<String, Double> zoomMap,
                                  Map<String, double[]> rangeMap, CircularSettings s) {
        List<ChrInfo> list = new ArrayList<>();
        for (String name : genome.getLongChromosomeNames()) {
            org.broad.igv.feature.Chromosome c = genome.getChromosome(name);
            if (c == null) continue;
            long len = c.getLength();
            if (len <= 0) continue;
            if (s != null && s.shownChromosomes != null && !s.shownChromosomes.isEmpty()
                    && !s.shownChromosomes.contains(name)) continue;
            double z = zoomMap != null ? zoomMap.getOrDefault(name, 1.0) : 1.0;
            List<Cytoband> cyto = (s != null && s.solidColor) ? null : genome.getCytobands(name);
            Color color = (s != null && s.solidColor) ? s.chromosomeColor : pickColor(name, cyto);
            double vs = 0, ve = len;
            if (rangeMap != null) {
                double[] r = rangeMap.get(name);
                if (r != null && r[1] > r[0]) {
                    vs = Math.max(0, r[0]);
                    ve = Math.min(len, r[1]);
                }
            }
            list.add(new ChrInfo(name, len, len * z, cyto, color, vs, ve));
        }
        return list;
    }

    static ChrInfo buildOne(Genome genome, String chr, CircularSettings s) {
        return buildOne(genome, chr, null, s);
    }

    static ChrInfo buildOne(Genome genome, String chr, Map<String, double[]> rangeMap, CircularSettings s) {
        org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
        if (c == null) return null;
        long len = c.getLength();
        List<Cytoband> cyto = (s != null && s.solidColor) ? null : genome.getCytobands(chr);
        Color color = (s != null && s.solidColor) ? s.chromosomeColor : pickColor(chr, cyto);
        double vs = 0, ve = len;
        if (rangeMap != null) {
            double[] r = rangeMap.get(chr);
            if (r != null && r[1] > r[0]) {
                vs = Math.max(0, r[0]);
                ve = Math.min(len, r[1]);
            }
        }
        return new ChrInfo(chr, len, len, cyto, color, vs, ve);
    }

    /** Flank (bp) added to each gene interval so sectors are not edge-to-edge. */
    private static final int GENE_FLANK_BP = 2000;

    /**
     * Gene-list mode: build one sector per gene from the session's current gene list. Each sector
     * carries its gene name as the display label but resolves data/features from the chromosome the
     * gene lives on ({@link ChrInfo#realChr}). The sector's angular weight is the (flanked) gene
     * span, so genes keep their relative size; {@code c.length} stays the real chromosome length
     * so bigWig bins and feature coordinates map correctly.
     *
     * @param arcZoom per-gene arc-width zoom factors (Ctrl+wheel), keyed by gene name (may be null).
     * @param rangeMap per-gene visible-window overrides (Shift+wheel), keyed by gene name (may be null).
     * @param geneOut receives the resolved gene features (keyed by gene name) so the structure
     *               (UTR/CDS) is drawn even when no gene track is loaded.
     */
    static List<ChrInfo> buildGenes(Genome genome, Map<String, Double> arcZoom,
                                   Map<String, double[]> rangeMap,
                                   CircularSettings s, Map<String, List<Feature>> geneOut) {
        geneOut.clear();
        List<ChrInfo> list = new ArrayList<>();
        if (!FrameManager.isGeneListMode()) return list;
        GeneList gl = IGV.getInstance().getSession().getCurrentGeneList();
        if (gl == null) return list;
        List<String> loci = gl.getLoci();
        if (loci == null || loci.isEmpty()) return list;

        int flank = (s != null) ? Math.max(0, s.geneFlankBp) : GENE_FLANK_BP;
        for (String locus : loci) {
            if (locus == null || locus.trim().isEmpty()) continue;
            NamedFeature nf = resolveLocus(genome, locus.trim());
            if (nf == null) continue;
            String chr = nf.getChr();
            org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
            if (c == null) continue;
            long clen = c.getLength();
            long gs = nf.getStart(), ge = nf.getEnd();
            if (ge <= gs) continue;
            long vs = Math.max(0, gs - flank);
            long ve = Math.min(clen, ge + flank);
            double z = (arcZoom != null) ? arcZoom.getOrDefault(locus, 1.0) : 1.0;
            double weight = Math.max(1, (ve - vs) * z);
            // Draw the sector with the REAL chromosome's ideogram (cytoband stripes + colour) for
            // this interval, not a fresh palette colour, so each gene reflects where it lives.
            List<Cytoband> cyto = (s != null && s.solidColor) ? null : genome.getCytobands(chr);
            Color color = (s != null && s.solidColor) ? s.chromosomeColor : pickColor(chr, cyto);
            List<Cytoband> sectorCyto = null;
            if (cyto != null) {
                sectorCyto = new ArrayList<>();
                for (Cytoband cb : cyto) {
                    if (cb.getEnd() > vs && cb.getStart() < ve) sectorCyto.add(cb);
                }
            }

            if (nf instanceof Feature) {
                geneOut.put(locus, Collections.singletonList((Feature) nf));
            }

            double rvs = vs, rve = ve;
            if (rangeMap != null) {
                double[] r = rangeMap.get(locus);
                if (r != null && r[1] > r[0]) {
                    // Clamp the zoomed window to the (flanked) gene interval so zooming out in
                    // gene mode never escapes into chromosome-wide context.
                    rvs = Math.max(vs, Math.min(ve, Math.max(0, r[0])));
                    rve = Math.min(ve, Math.max(vs, Math.min(clen, r[1])));
                }
            }
            list.add(new ChrInfo(locus, clen, weight, sectorCyto, color, rvs, rve, chr));
        }
        return list;
    }

    /** Resolve a gene-list entry to a feature: by name via FeatureDB, else as a chr:start-end locus. */
    private static NamedFeature resolveLocus(Genome genome, String locus) {
        NamedFeature nf = FeatureDB.getFeature(locus.toUpperCase());
        if (nf != null) return nf;
        int colon = locus.indexOf(':');
        if (colon > 0) {
            String chr = locus.substring(0, colon);
            if (genome.getChromosome(chr) == null) return null;
            int dash = locus.indexOf('-', colon);
            try {
                long st = Long.parseLong(locus.substring(colon + 1, dash > 0 ? dash : locus.length()).replace(",", ""));
                long en = dash > 0 ? Long.parseLong(locus.substring(dash + 1).replace(",", "")) : st + 1;
                return new BasicFeature(chr, (int) st, (int) en);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Public static entry points (default settings; used by the render probe)
    // ------------------------------------------------------------------

    public static void drawAllChromosomes(Graphics2D g2, int w, int h, List<ChrInfo> chrs) {
        drawAll(g2, w, h, chrs, new CircularSettings());
    }

    public static void drawSingleChromosome(Graphics2D g2, int w, int h, ChrInfo chr) {
        drawSingle(g2, w, h, chr, new CircularSettings());
    }

    public static void drawSingleChromosome(Graphics2D g2, int w, int h, ChrInfo chr,
                                            CircularSettings s) {
        drawSingle(g2, w, h, chr, s);
    }

    public static void drawAllChromosomes(Graphics2D g2, int w, int h, List<ChrInfo> chrs, CircularSettings s) {
        drawAll(g2, w, h, chrs, s);
    }

    // ------------------------------------------------------------------
    //  Sector layout (shared by painting + hit-testing)
    // ------------------------------------------------------------------

    public static List<double[]> computeSectors(List<ChrInfo> chrs, double gapDeg) {
        List<double[]> out = new ArrayList<>();
        double totalW = 0;
        for (ChrInfo c : chrs) totalW += c.weight;
        int n = chrs.size();
        double spanTotal = 360.0 - gapDeg * n;
        double frac = 0.0;
        for (ChrInfo c : chrs) {
            double spanDeg = totalW > 0 ? spanTotal * (c.weight / totalW) : 0;
            double f0 = frac, f1 = frac + spanDeg / 360.0;
            out.add(new double[]{f0, f1, spanDeg});
            frac = f1 + gapDeg / 360.0;
        }
        return out;
    }

    /**
     * Sector list for one drawing pass. In single-chromosome mode ({@code chrs.size()==1}) the arc
     * can be shrunk with {@link CircularSettings#singleArcFraction} (1 = 360°, 0.5 = 180°); the arc
     * starts at the top (12 o'clock) and sweeps clockwise. All other modes use the normal
     * per-chromosome layout, so this stays a no-op before the option is touched (default 1.0).
     */
    static List<double[]> sectorsFor(List<ChrInfo> chrs, CircularSettings s) {
        if (chrs != null && chrs.size() == 1) {
            double f = s.singleArcFraction;
            if (!(f > 0)) f = 1.0;
            if (f > 1.0) f = 1.0;
            double start = s.singleArcStartDeg / 360.0;
            start = start - Math.floor(start);            // normalize into [0,1)
            List<double[]> out = new ArrayList<>();
            // f1 may exceed 1 (arc wraps past 12 o'clock); polar()/annularPath() handle that,
            // and angle->fraction lookups add 1.0 when the folded fraction is below f0.
            out.add(new double[]{start, start + f, f * 360.0});
            return out;
        }
        return computeSectors(chrs, s.gapDeg);
    }

    private static void drawAll(Graphics2D g2, int w, int h, List<ChrInfo> chrs, CircularSettings s) {
        drawAll(g2, w, h, chrs, s, false);
    }

    private static void drawAll(Graphics2D g2, int w, int h, List<ChrInfo> chrs,
                                CircularSettings s, boolean reserveLabels) {
        if (chrs == null || chrs.isEmpty()) { drawMessage(g2, w, h, "Circular view — no chromosomes"); return; }
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rOut = ideogramOuterRadius(w, h, s, reserveLabels);
        double rIn = Math.max(R * 0.2, rOut - s.chromosomeHeightPx);

        List<double[]> sec = computeSectors(chrs, s.gapDeg);
        double chrLabelR;
        if (s.heatmapOutside) {
            // The heatmap ring lives outside the ideogram, so push the chromosome numbers past it
            // instead of letting them sit under the cells.
            chrLabelR = outsideHeatmapInner(rOut, s) + connectionTrackHeight(s)
                    + Math.max(8, s.heatmapHeightPx > 0 ? s.heatmapHeightPx : 50) + 14;
        } else {
            chrLabelR = rOut + ((s.showAxis && s.showTickLabels && s.tickLabelsOutside) ? 32 : 16);
        }
        for (int i = 0; i < chrs.size(); i++) {
            ChrInfo c = chrs.get(i);
            double f0 = sec.get(i)[0], f1 = sec.get(i)[1], spanDeg = sec.get(i)[2];

            drawIdeogramBand(g2, cx, cy, rIn, rOut, f0, f1, c);
            drawBandTicks(g2, cx, cy, rIn, rOut, f0, f1, c, spanDeg, s);

            if (s.showChromosomeLabels) {
                double fMid = (f0 + f1) / 2.0;
                drawLabelText(g2, cx, cy, chrLabelR, fMid, shortName(c.name),
                        new Font("SansSerif", Font.BOLD, s.chromosomeLabelSize), Color.BLACK, s.labelTangential);
            }
        }

        // Centre of the ring is intentionally left empty (no genome display-name label), so
        // links and data rings are not obscured by text.
    }

    private static void drawSingle(Graphics2D g2, int w, int h, ChrInfo chr, CircularSettings s) {
        drawSingle(g2, w, h, chr, s, false);
    }

    private static void drawSingle(Graphics2D g2, int w, int h, ChrInfo chr,
                                   CircularSettings s, boolean reserveLabels) {
        if (chr == null || chr.length <= 0) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rOut = ideogramOuterRadius(w, h, s, reserveLabels);
        double rIn = Math.max(R * 0.2, rOut - s.chromosomeHeightPx);

        double[] sec = sectorsFor(Collections.singletonList(chr), s).get(0);
        drawIdeogramBand(g2, cx, cy, rIn, rOut, sec[0], sec[1], chr);
        drawBandTicks(g2, cx, cy, rIn, rOut, sec[0], sec[1], chr, sec[2], s);

        // Centre text removed: keep the ring interior clear for links/tracks.
    }

    // ------------------------------------------------------------------
    //  Ideogram band
    // ------------------------------------------------------------------

    private static void drawIdeogramBand(Graphics2D g2, double cx, double cy,
                                         double rIn, double rOut,
                                         double fStart, double fEnd, ChrInfo c) {
        boolean hasCyto = c.cytobands != null && !c.cytobands.isEmpty();
        if (hasCyto) {
            g2.setColor(new Color(235, 235, 235));
            g2.fill(annularPath(cx, cy, rIn, rOut, fStart, fEnd));
            double span = fEnd - fStart;
            for (Cytoband cb : c.cytobands) {
                double f0 = fStart + span * c.viewFrac(cb.getStart());
                double f1 = fStart + span * c.viewFrac(cb.getEnd());
                if (f1 <= f0) continue;
                g2.setColor(cytoColor(cb));
                g2.fill(annularPath(cx, cy, rIn, rOut, f0, f1));
            }
            g2.setColor(new Color(150, 150, 150));
            g2.setStroke(new BasicStroke(0.5f));
            for (Cytoband cb : c.cytobands) {
                double f = fStart + span * c.viewFrac(cb.getEnd());
                Point2D a = polar(cx, cy, rIn, f), b = polar(cx, cy, rOut, f);
                g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            }
        } else {
            g2.setColor(c.color != null ? c.color : new Color(190, 190, 190));
            g2.fill(annularPath(cx, cy, rIn, rOut, fStart, fEnd));
        }
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(annularPath(cx, cy, rIn, rOut, fStart, fEnd));
    }

    /** Distance from the ideogram edge to the axis baseline arc. */
    private static final double AXIS_GAP = 2.0;
    private static final double MAJOR_TICK_LEN = 7.0;
    private static final double MINOR_TICK_LEN = 3.5;

    /**
     * Space the coordinate labels occupy INSIDE the ideogram band when "Inside (below band)" is
     * selected. Track bands stack inwards from the ideogram, so without this the first band lands
     * on top of the labels.
     */
    static double insideLabelClearance(CircularSettings s) {
        if (s == null || !s.showTickLabels || s.tickLabelsOutside) return 0;
        double size = s.tickLabelSize;
        // labelR = (rIn - AXIS_GAP) - (MAJOR_TICK_LEN + 3 + size*0.6), text centred there.
        double axisPad = Math.max(0.0, Math.max(0.25, Math.min(6.0, s.axisLineWidth)) - 1.0);
        return AXIS_GAP + MAJOR_TICK_LEN + 3 + size * 0.6 + size + 3 + axisPad;
    }

    /**
     * Outer radius of the FIRST track band: just inside the ideogram band, with the inside tick
     * label zone (if any) reserved so the axis never overlaps the innermost track.
     */
    static double bandStartRadius(double R, CircularSettings s) {
        return bandStartRadius(R, R * s.bandOuter, s);
    }

    /** Same, for callers that already resolved the (possibly shrunk) ideogram outer radius. */
    static double bandStartRadius(double R, double rOut, CircularSettings s) {
        double r = Math.max(R * 0.2, rOut - s.chromosomeHeightPx) - 5;
        double clear = insideLabelClearance(s);
        if (clear <= 0) return r;
        return Math.max(R * 0.15, r - clear);
    }

    /** Radial clearance the coordinate axis + its labels occupy OUTSIDE the ideogram band. */
    static double outsideAxisClearance(CircularSettings s) {
        if (s == null || !s.showAxis) return 2;
        double axisPad = Math.max(0.0, Math.max(0.25, Math.min(6.0, s.axisLineWidth)) - 1.0);
        double c = AXIS_GAP + MAJOR_TICK_LEN + 2 + axisPad;
        if (s.showTickLabels) c += 3 + s.tickLabelSize * 0.6 + s.tickLabelSize;
        return c + 4;
    }

    /** Inner edge of the connection track when the heatmap is drawn OUTSIDE the ideogram. */
    static double outsideHeatmapInner(double rOut, CircularSettings s) {
        return rOut + outsideAxisClearance(s);
    }

    /** Height of the connection track (0 when the comb is switched off). */
    static double connectionTrackHeight(CircularSettings s) {
        return (s.heatmapConnectors && s.heatmapConnectionPx > 0) ? s.heatmapConnectionPx : 0;
    }

    /**
     * Outer radius of the ideogram band. When the heatmap is placed OUTSIDE, the whole ring is
     * pulled in so the connection track + heatmap band still fit inside the panel.
     */
    static double ideogramOuterRadius(int w, int h, CircularSettings s) {
        return ideogramOuterRadius(w, h, s, false);
    }

    /** Same, reserving room outside the ring for a labels layer (circos.genomicLabels). */
    static double ideogramOuterRadius(int w, int h, CircularSettings s, boolean reserveLabels) {
        double half = Math.min(w, h) / 2.0;
        double R = half - MARGIN;
        double rOut = R * s.bandOuter;
        if (reserveLabels) rOut = Math.min(rOut, R * 0.76);   // room for the label ring outside
        if (!s.heatmapOutside) return rOut;
        double need = outsideAxisClearance(s) + connectionTrackHeight(s)
                + Math.max(8, s.heatmapHeightPx > 0 ? s.heatmapHeightPx : 50) + 18;
        return Math.min(rOut, Math.max(R * 0.35, half - 8 - need));
    }

    /**
     * Circos-style coordinate axis. The axis is a thin baseline arc drawn just OUTSIDE (or
     * inside, per {@code tickLabelsOutside}) the ideogram band; major and minor ticks grow away
     * from the band. Nothing is ever drawn across {@code rIn..rOut}, so the cytoband stripes
     * stay clean. Major ticks carry Mb/kb/bp labels.
     */
    private static void drawBandTicks(Graphics2D g2, double cx, double cy,
                                      double rIn, double rOut,
                                      double fStart, double fEnd, ChrInfo c, double spanDeg,
                                      CircularSettings s) {
        if (!s.showAxis) return;                          // master on/off switch (default on)
        if (!s.showTicks && !s.showTickLabels) return;
        if (spanDeg < 1.0) return;

        // Tick interval follows the visible bp window, so a Shift+wheel zoom gets finer ticks
        // (down to bp) while the arc width stays the same.
        double viewLen = c.viewSpan();
        long step;
        if (s.tickSpacingMb > 0) {
            step = Math.max(1, Math.round(s.tickSpacingMb * 1_000_000.0));
        } else {
            double midR = (rIn + rOut) / 2.0;
            double arcPx = arcLength(midR, spanDeg);
            int targetTicks = Math.max(2, (int) Math.round(arcPx / 70.0));
            step = Math.max(1, niceStep((long) viewLen, targetTicks));
        }
        double span = fEnd - fStart;
        Font tickFont = new Font("SansSerif", Font.PLAIN, s.tickLabelSize);
        double stepDeg = spanDeg * ((double) step / viewLen);   // angular gap between major ticks

        // Ticks grow away from the band: +1 when the axis sits outside, -1 when inside.
        double sign = s.tickLabelsOutside ? 1.0 : -1.0;
        double baseR = s.tickLabelsOutside ? (rOut + AXIS_GAP) : (rIn - AXIS_GAP);
        // A thick axis eats into the label ring, so push the text out by the extra width.
        double axisPad = Math.max(0.0, Math.max(0.25, Math.min(6.0, s.axisLineWidth)) - 1.0);
        double labelR = baseR + sign * (MAJOR_TICK_LEN + 3 + s.tickLabelSize * 0.6 + axisPad);

        if (s.showTicks) {
            // Baseline arc following the ideogram, never crossing into the band itself.
            // Axis thickness is user controlled (0.25..6 px). The baseline arc is a filled
            // annular strip, so its radial thickness tracks the same value (x0.85).
            double axisW = Math.max(0.25, Math.min(6.0, s.axisLineWidth));
            double baseT = Math.max(0.35, axisW * 0.85);
            g2.setColor(new Color(90, 90, 90));
            g2.fill(annularPath(cx, cy, Math.min(baseR, baseR + sign * baseT),
                    Math.max(baseR, baseR + sign * baseT), fStart, fEnd));
            g2.setColor(new Color(60, 60, 60));
            g2.setStroke(new BasicStroke((float) axisW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

            long first = (long) (Math.ceil(c.viewStart / (double) step) * step);
            for (long p = first; p <= (long) c.viewEnd; p += step) {
                if (p < c.viewStart) continue;
                double f = fStart + span * c.viewFrac(p);

                // Minor ticks subdivide the interval up to the next major tick.
                if (s.minorTicksPerMajor > 1) {
                    g2.setStroke(new BasicStroke((float) Math.max(0.2, axisW * 0.6),
                            BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    for (int k = 1; k < s.minorTicksPerMajor; k++) {
                        double mf = f + span * (k * (double) step / s.minorTicksPerMajor) / viewLen;
                        if (mf >= fEnd) break;
                        tickLine(g2, cx, cy, baseR, baseR + sign * MINOR_TICK_LEN, mf);
                    }
                    g2.setStroke(new BasicStroke((float) axisW,
                            BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                }
                // Major tick (longer) + label.
                tickLine(g2, cx, cy, baseR, baseR + sign * MAJOR_TICK_LEN, f);
            }
            g2.setStroke(new BasicStroke(1.0f));
        }

        if (s.showTickLabels) {
            // Coordinate labels are ALWAYS tangential — radial coordinate text crosses the
            // band and collides with neighbours. The "Radial" option only rotates the
            // chromosome numbers (see drawAll), which is the standard circular-genome look.
            long first = (long) (Math.ceil(c.viewStart / (double) step) * step);
            for (long p = first; p <= (long) c.viewEnd; p += step) {
                if (p < c.viewStart) continue;
                double f = fStart + span * c.viewFrac(p);
                String txt = tickLabel(p, step);            // Mb / kb / bp by interval
                int tw = g2.getFontMetrics(tickFont).stringWidth(txt);
                if (arcLength(labelR, stepDeg) < tw + 6) continue;   // adjacent labels would collide
                drawTangentialText(g2, cx, cy, labelR, f, txt, tickFont, new Color(70, 70, 70));
            }
        }
    }

    /** One radial tick stroke from r0 to r1 at fraction f. */
    private static void tickLine(Graphics2D g2, double cx, double cy, double r0, double r1, double f) {
        Point2D a = polar(cx, cy, r0, f), b = polar(cx, cy, r1, f);
        g2.drawLine((int) Math.round(a.getX()), (int) Math.round(a.getY()),
                (int) Math.round(b.getX()), (int) Math.round(b.getY()));
    }

    // ------------------------------------------------------------------
    //  Feature tracks as concentric bands inside the ideogram
    // ------------------------------------------------------------------

    /**
     * True when this track can be drawn as a ring band of its own: it must be visible and be one
     * of the band-capable types. A hidden track gets no band — which is what removes the
     * duplicate ring for a BED that a Hi-C track owns as a TAD/domain overlay (attaching an
     * overlay hides that track, because its shapes are drawn on the Hi-C band instead).
     */
    static boolean drawableAsBand(Track t) {
        if (t == null || !t.isVisible()) return false;
        return t instanceof FeatureTrack || t instanceof DataTrack || t instanceof HiCTrack;
    }

    /** Tracks to render as concentric feature bands (set by IGVContentPane from the Data Tracks list). */
    public void setRingTracks(List<Track> tracks) {
        ringTracks.clear();
        if (tracks != null) {
            for (Track t : tracks) {
                if (isRingTrackHidden(t)) continue;
                if (!drawableAsBand(t)) continue;
                ringTracks.add(t);  // order == top->bottom == outer->inner
            }
        }
        refreshTrackLinks();   // re-sync the link layer from any loaded interaction tracks
        log.info("Circular ring tracks: " + ringTracks.size());
        invalidateHiCTiles();
        ringKey = null;
        repaint();
    }

    /**
     * Remove a track from the ring without unloading it.
     *
     * @deprecated removing a track now DELETES it (see {@link #forgetRingTrack(Track)}); the
     *            hide-and-keep-loaded behaviour left the Data Tracks row reading "(hidden)".
     */
    @Deprecated
    public void removeRingTrack(String name) {
        hiddenRingTracks.add(name);
        ringTracks.removeIf(t -> name.equals(t.getName()));
        invalidateRing();
    }

    /** Allow all removed tracks back onto the ring (caller then calls refreshDataColumn). */
    public void clearHiddenRingTracks() {
        hiddenRingTracks.clear();
        hiddenTrackObjects.clear();
    }

    /** Tracks removed from the ring. Tracked by object identity as well as name, because a
     *  restored track can have an empty name, which made name-only hiding silently fail. */
    private final Set<Track> hiddenTrackObjects =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Hide a specific track object from the ring (robust even when its name is empty or null). */
    public void hideRingTrack(Track t) {
        if (t == null) return;
        hiddenTrackObjects.add(t);
        if (t.getName() != null) hiddenRingTracks.add(t.getName());
        ringTracks.remove(t);
        invalidateRing();
    }

    /** True when this track object was removed from the ring. */
    public boolean isRingTrackHidden(Track t) {
        if (t == null) return false;
        return hiddenTrackObjects.contains(t)
                || (t.getName() != null && hiddenRingTracks.contains(t.getName()));
    }

    /**
     * Drop every ring-side trace of a track that is about to be DELETED (not just hidden):
     * the band entry, any hide mark, its band config, and every cache entry keyed by its name
     * (features, bigWig bins, adaptive zoom bins, Hi-C tiles and their colour limits).
     *
     * <p>Call this immediately before {@code IGV.deleteTracks(...)}. Afterwards the "(hidden)"
     * marker can no longer match a re-imported track of the same name, and no stale matrix or
     * feature list can be served for the deleted object.
     */
    public void forgetRingTrack(Track t) {
        if (t == null) return;
        ringTracks.remove(t);
        hiddenTrackObjects.remove(t);
        final String name = t.getName();
        if (name != null) {
            hiddenRingTracks.remove(name);
            ringConfig.remove(name);
            final String p = name + "|";
            featureCache.keySet().removeIf(k -> k.startsWith(p));
            dataCache.keySet().removeIf(k -> k.startsWith(p));
            adaptiveCache.keySet().removeIf(k -> k.startsWith(p));
            hicTileCache.keySet().removeIf(k -> k.startsWith(p));
            hicTileLimits.keySet().removeIf(k -> k.startsWith(p));
            hicLimitsByTrack.remove(name);
            trackLoaded.remove(name);
            loadAttempts.keySet().removeIf(k -> k.startsWith(p));
            lastAttemptMs.keySet().removeIf(k -> k.startsWith(p));
        }
        invalidateHiCTiles();
        ringKey = null;
    }

    /** True when the user removed this track from the ring (it stays loaded, just is not drawn). */
    public boolean isRingTrackHidden(String name) {
        return name != null && hiddenRingTracks.contains(name);
    }

    /**
     * Radius of the free space left after stacking every ring band, so links can be drawn inside
     * the data tracks instead of on top of them. Returns &lt;= 0 to mean "no ring tracks, fall
     * back to the ideogram inner radius".
     */
    private double linkAnchorRadius(int w, int h, CircularSettings s) {
        if (!s.linkEndsInsideTracks || ringTracks.isEmpty()) return -1;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return -1;
        double cursor = bandStartRadius(R, s);   // leaves room for inside tick labels
        for (Track t : ringTracks) {
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandInner = cursor - ringHeightPx(t, cfg);
            if (bandInner < 24) break;
            cursor = bandInner - 5;
        }
        return cursor > 24 ? cursor : -1;
    }

    /** Per-track ring band config (created with defaults on first use). */
    public TrackRingConfig getRingConfig(String trackName) {
        return ringConfig.computeIfAbsent(trackName, k -> new TrackRingConfig());
    }

    /** Radial height of one ring band. Hi-C matrix bands use their own (taller) height so the
     *  2D warp has enough pixels; link anchors must use the same value to stay aligned. */
    private int ringHeightPx(Track t, TrackRingConfig cfg) {
        return t instanceof HiCTrack ? cfg.hicHeightPx : cfg.heightPx;
    }

    public void invalidateRing() { invalidateHiCTiles(); ringKey = null; repaint(); }

    /** Drop cached Hi-C tiles and sampled limits. Called whenever the ring is rebuilt so a
     *  parameter change is picked up on the next paint, without re-sampling the matrix on
     *  every repaint (paintComponent runs on the EDT). */
    private void invalidateHiCTiles() {
        hicTileCache.clear();
        hicTileLimits.clear();
        hicLimitsByTrack.clear();
    }

    /** Load features for all ring feature-tracks / chromosomes in the background (cached). */
    private void ensureFeaturesLoaded(Genome genome) {
        if (genome == null || ringTracks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Track t : ringTracks) {
            if (!(t instanceof FeatureTrack)) continue;
            FeatureTrack ft = (FeatureTrack) t;
            for (String chr : genome.getLongChromosomeNames()) {
                org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
                if (c == null) continue;
                if (!settings.shownChromosomes.isEmpty() && !settings.shownChromosomes.contains(chr)) continue;
                final String key = ft.getName() + "|" + chr;
                if (featureCache.containsKey(key) || pendingLoads.contains(key)) continue;
                Long last = lastAttemptMs.get(key);
                if (last != null && now - last < 1200) continue;      // cooldown between retries
                lastAttemptMs.put(key, now);
                int attempts = loadAttempts.merge(key, 1, Integer::sum);
                final boolean giveUp = attempts >= 120;
                final int len = c.getLength();
                pendingLoads.add(key);
                // Background thread (not EDT): the first getFeatures on a remote refGene source may
                // block while it downloads/parses the whole file — must not freeze the UI.
                featureLoader.submit(() -> {
                    // Try the genome's chromosome name, then the "chr"-stripped/added alias,
                    // so annotation tracks with a different naming convention still resolve.
                    List<Feature> list = tryFetch(ft, chr, len);
                    if (list.isEmpty()) {
                        String alt = chr.startsWith("chr") ? chr.substring(3) : "chr" + chr;
                        if (!alt.equals(chr)) {
                            List<Feature> l2 = tryFetch(ft, alt, len);
                            if (!l2.isEmpty()) list = l2;
                        }
                    }
                    if (!list.isEmpty()) {
                        featureCache.put(key, list);
                        loadAttempts.remove(key);
                        trackLoaded.put(ft.getName(), Boolean.TRUE);
                        log.info("circular features " + key + " = " + list.size());
                    } else if (giveUp) {
                        // Only give up after many attempts. A per-chromosome 0 does NOT mean the
                        // track is loaded -- non-indexed .txt.gz sources fill chromosomes gradually,
                        // so an early 0 is usually "not parsed yet". Keep retrying until giveUp,
                        // and RefreshEvent (data loaded) purges cached empties to refetch.
                        featureCache.put(key, Collections.emptyList());
                        log.info("circular features " + key + " = 0 (give up)");
                    }
                    // else: track data not ready yet -> leave uncached; the retry timer retries.
                    // else: leave uncached so the retry timer tries again (track may still be loading)
                    pendingLoads.remove(key);
                    SwingUtilities.invokeLater(this::invalidateRing);
                });
            }
        }
    }

    private static List<Feature> tryFetch(FeatureTrack ft, String chr, int len) {
        try {
            List<Feature> l = ft.getFeatures(chr, 0, len);
            return l != null ? l : Collections.emptyList();
        } catch (Throwable ex) {
            return Collections.emptyList();
        }
    }

    private void drawTrackRings(Graphics2D g2, int w, int h, List<ChrInfo> chrs, CircularSettings s) {
        if ((ringTracks.isEmpty() && heatmap == null) || chrs == null || chrs.isEmpty()) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rOut = ideogramOuterRadius(w, h, s);
        double cursor = bandStartRadius(R, rOut, s);   // leaves room for inside tick labels
        List<double[]> sec = computeSectors(chrs, s.gapDeg);
        float[] globalShared = s.ySharedAcrossTracks ? computeSharedYRange(chrs) : null;
        java.util.Map<String, float[]> groups = computeGroupRanges(chrs);
        java.util.Map<String, java.util.Map<String, float[]>> perGeneGroups = needPerGeneGroups(chrs);
        // Row order == radial order (top row = outermost). The heatmap layer is just another row,
        // so it is drawn at its own position in the stack instead of being pinned to one end.
        int heatSlot = Math.max(0, Math.min(heatmapBandIndex, ringTracks.size()));
        int bandNo = 0;
        for (Track t : ringTracks) {
            if (heatmap != null && bandNo == heatSlot) {
                cursor = drawHeatmapBand(g2, cx, cy, w, h, R, rOut, cursor,
                        chrs, sec, s, heatmap, true);
            }
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandOuter = cursor;
            double bandInner = bandOuter - ringHeightPx(t, cfg);
            if (bandInner < 24) break;
            drawOneRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, t, cfg,
                    sharedRangeFor(cfg, globalShared, groups, perGeneGroups));
            cursor = bandInner - 5;
            bandNo++;
        }
        if (heatmap != null && bandNo == heatSlot) {
            cursor = drawHeatmapBand(g2, cx, cy, w, h, R, rOut, cursor,
                    chrs, sec, s, heatmap, true);
        }
    }

    private void drawTrackRingsSingle(Graphics2D g2, int w, int h, ChrInfo chr, CircularSettings s) {
        if (ringTracks.isEmpty() && heatmap == null) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rOut = ideogramOuterRadius(w, h, s);
        double cursor = bandStartRadius(R, rOut, s);   // leaves room for inside tick labels
        List<ChrInfo> chrs = Collections.singletonList(chr);
        List<double[]> sec = sectorsFor(chrs, s);
        float[] globalShared = s.ySharedAcrossTracks ? computeSharedYRange(chrs) : null;
        java.util.Map<String, float[]> groups = computeGroupRanges(chrs);
        java.util.Map<String, java.util.Map<String, float[]>> perGeneGroups = needPerGeneGroups(chrs);
        int heatSlot = Math.max(0, Math.min(heatmapBandIndex, ringTracks.size()));
        int bandNo = 0;
        for (Track t : ringTracks) {
            if (heatmap != null && bandNo == heatSlot) {
                cursor = drawHeatmapBand(g2, cx, cy, w, h, R, rOut, cursor,
                        chrs, sec, s, heatmap, true);
            }
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandOuter = cursor, bandInner = bandOuter - ringHeightPx(t, cfg);
            if (bandInner < 24) break;
            drawOneRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, t, cfg,
                    sharedRangeFor(cfg, globalShared, groups, perGeneGroups));
            cursor = bandInner - 5;
            bandNo++;
        }
        if (heatmap != null && bandNo == heatSlot) {
            cursor = drawHeatmapBand(g2, cx, cy, w, h, R, rOut, cursor,
                    chrs, sec, s, heatmap, true);
        }
    }

    /** Per-gene group ranges, computed only when some track has {@code yGroupPerGene} on. */
    private java.util.Map<String, java.util.Map<String, float[]>> needPerGeneGroups(List<ChrInfo> chrs) {
        for (Track t : ringTracks) {
            TrackRingConfig c = getRingConfig(t.getName());
            if (c.yGroup != null && c.yGroupPerGene) return computeGroupRangesPerGene(chrs);
        }
        return null;
    }

    /** Build the per-track Y-range function from the global / group / per-gene-group ranges. */
    private java.util.function.Function<ChrInfo, float[]> sharedRangeFor(TrackRingConfig cfg,
            float[] globalShared, java.util.Map<String, float[]> groups,
            java.util.Map<String, java.util.Map<String, float[]>> perGeneGroups) {
        if (cfg.yGroup != null && cfg.yGroupPerGene) {
            final java.util.Map<String, float[]> pg = perGeneGroups != null ? perGeneGroups.get(cfg.yGroup) : null;
            return c -> (pg != null) ? pg.get(c.name) : null;
        } else if (cfg.yGroup != null) {
            final float[] gr = groups.get(cfg.yGroup);
            return c -> gr;
        } else {
            final float[] gs = globalShared;
            return c -> gs;
        }
    }

    /** Dispatch one track to the right ring renderer (BED/feature -> marks; bigWig/bedGraph -> heatmap). */
    private void drawOneRing(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                             List<ChrInfo> chrs, List<double[]> sec, Track t, TrackRingConfig cfg,
                             java.util.function.Function<ChrInfo, float[]> sharedRange) {
        if (t instanceof FeatureTrack) {
            drawFeatures(g2, cx, cy, bandInner, bandOuter, chrs, sec, (FeatureTrack) t, cfg);
        } else if (t instanceof DataTrack) {
            drawDataRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, (DataTrack) t, cfg, sharedRange);
        } else if (t instanceof HiCTrack) {
            drawHiCRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, (HiCTrack) t, cfg);
        }
    }

    // ------------------------------------------------------------------
    //  Hi-C tracks: reuse the LINEAR view's TRIANGLE / SQUARE renderer, bent into a ring
    // ------------------------------------------------------------------

    /**
     * Draw one Hi-C track as a set of annular sectors. Each sector is rendered with the LINEAR
     * view's own renderer (HiCRenderer.paintTriangle / paintSquare), using every parameter read
     * straight off the HiCTrack (mode, transform, palette, colour limits, depth, heightRatio,
     * flipped, forced resolution, custom anchors). The linear tile is then polar-warped onto the
     * band, preserving BOTH the x (genomic) and y (distance / second axis) dimensions.
     */
    void drawHiCRing(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                     List<ChrInfo> chrs, List<double[]> sec, HiCTrack hic, TrackRingConfig cfg) {
        if (hic == null || chrs == null || sec == null) return;
        final HiCSource data = hic.getData();
        if (data == null) return;

        // Snapshot the linear track's settings. HiCSource is shared with the linear view, so
        // choosing the bin size here has exactly the same effect as it does there.
        final HiCTrack.Mode mode = hic.getMode();
        final HiCTransform transform = hic.getTransform();
        final HiCColorScale.Palette palette = hic.getPalette();
        final double minV = hic.getMinValue(), maxV = hic.getMaxValue();
        final double depth = hic.getDepth(), ratio = hic.getHeightRatio();
        final boolean flipped = hic.isFlipped();
        final int forced = hic.getForcedResolution();
        final int[][] anchors = hic.getCustomAnchors();
        final String trackName = String.valueOf(hic.getName());

        for (int i = 0; i < chrs.size() && i < sec.size(); i++) {
            ChrInfo c = chrs.get(i);
            double[] secI = sec.get(i);
            final double f0 = secI[0], f1 = secI[1], spanDeg = secI[2];
            if (!(f1 > f0) || c.viewEnd <= c.viewStart) continue;

            double midR = (bandInner + bandOuter) / 2.0;
            // Match the tile to DEVICE pixels (the ring image is device-sized) and let it grow with
            // the ring: a full-chromosome ring is ~1600-2500 px of arc, and the old 1200 cap halved
            // the angular resolution, so cells looked coarse/blurry when zoomed in.
            double dsx = g2.getTransform().getScaleX(); if (!(dsx > 0)) dsx = 1.0;
            double dsy = g2.getTransform().getScaleY(); if (!(dsy > 0)) dsy = 1.0;
            // Never under-sample compared to the logical pixel geometry: vector export to a
            // smaller page has dsx < 1, but the matrix should still be rendered at full detail.
            double qx = Math.max(dsx, 1.0), qy = Math.max(dsy, 1.0);
            int tileW = clampInt((int) Math.round(arcLength(midR, spanDeg) * qx), 32, 4000);
            int tileH = clampInt((int) Math.round((bandOuter - bandInner) * qy), 8, 1600);
            if (tileW < 2 || tileH < 2) continue;

            // Key covers everything that changes the matrix, so a parameter edit rebuilds the
            // tile but an ordinary repaint (mouse-over, tooltip, ...) just reuses it. The vertical
            // flip is NOT part of the key: it is applied to the composite at draw time, so
            // toggling it must not force the matrix to be re-sampled from the .hic file.
            String key = trackName + "|" + c.chrKey() + "|" + (long) c.viewStart + "_" + (long) c.viewEnd
                    + "|" + mode + "|" + transform + "|" + palette + "|" + minV + "|" + maxV
                    + "|" + depth + "|" + ratio + "|" + forced
                    + "|" + (anchors == null ? 0 : anchors.length)
                    + "|" + tileW + "x" + tileH + "|" + f0 + "_" + f1;

            BufferedImage tile = hicTileCache.get(key);
            if (tile == null) {
                if (forced > 0) data.setBinSize(forced);
                else data.autoSelectBinSize((c.viewEnd - c.viewStart) / tileW, tileW);

                tile = new BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_RGB);
                double[] limits;
                if (mode == HiCTrack.Mode.TRIANGLE) {
                    limits = HiCRenderer.paintTriangle(tile, data, c.chrKey(), c.viewStart, c.viewEnd,
                            depth, ratio, minV, maxV, transform, palette, anchors);
                } else {
                    limits = HiCRenderer.paintSquare(tile, data, c.chrKey(), c.viewStart, c.viewEnd,
                            minV, maxV, transform, palette, anchors);
                }
                // The vertical flip is applied at draw time, AFTER the TAD/domain overlay has been
                // composited in the matrix's own coordinate space — see below.
                hicTileCache.put(key, tile);
                hicTileLimits.put(key, limits == null ? new double[]{0, 1, 1} : limits);
            }

            // Keep the limits the renderer actually used, for the legend drawn BESIDE the ring.
            double[] limits = hicTileLimits.get(key);
            if (limits != null) {
                final double[] cur = limits;
                hicLimitsByTrack.merge(trackName, cur, (a, b) -> new double[]{
                        Math.min(a[0], b[0]), Math.max(a[1], b[1]), b[2]});
            }

            // Composite TAD/domain overlays onto a COPY of the cached matrix tile (the cache stays
            // matrix-only so toggling overlays never stales it), THEN flip, then warp. Order
            // matters: the overlay painter works in the matrix's own linear coordinates, so the
            // whole composite must be mirrored together — flipping the cached matrix first (as
            // this used to) left the TAD outlines un-flipped, so they no longer followed the
            // triangle after "Flip vertically".
            BufferedImage drawTile = tile;
            if (hic.hasDomainOverlays()) {
                BufferedImage base = new BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_RGB);
                Graphics2D cg = base.createGraphics();
                cg.drawImage(tile, 0, 0, null);
                cg.dispose();
                hic.paintDomainOverlays(base, c.chrKey(), c.viewStart, c.viewEnd,
                        tileW, tileH, mode, ratio);
                drawTile = base;
            }
            if (flipped) {
                BufferedImage mirror = new BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_RGB);
                Graphics2D mg = mirror.createGraphics();
                mg.drawImage(drawTile, 0, tileH, tileW, -tileH, null);
                mg.dispose();
                drawTile = mirror;
            }
            warpHiCTile(g2, cx, cy, bandInner, bandOuter, f0, f1, drawTile);
        }
    }

    /**
     * Polar warp: map a linear tile (x = genomic fraction, y = distance / second axis) onto the
     * annular sector band. Pixels outside [bandInner,bandOuter] or [f0,f1] stay transparent, and
     * the draw is additionally clipped to the band so nothing can leak out. Because BOTH axes are
     * preserved, TRIANGLE and SQUARE both read correctly once bent into the ring.
     */
    static void warpHiCTile(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                            double f0, double f1, BufferedImage tile) {
        if (tile == null) return;
        // Draw at DEVICE resolution. The ring image is device-sized and the graphics carries the
        // HiDPI scale; building this overlay at logical size and letting the transform blow it up
        // made every Hi-C cell soft on 125/150/200% displays. Here the overlay is sized in device
        // pixels and blitted 1:1 with an identity transform, so cells stay crisp.
        AffineTransform tr = g2.getTransform();
        double sxx = tr.getScaleX() > 0 ? tr.getScaleX() : 1.0;
        double syy = tr.getScaleY() > 0 ? tr.getScaleY() : 1.0;
        // Sample at least at the logical (pixel-geometry) resolution: on screen sxx is the HiDPI
        // factor (>= 1); for vector export to a smaller physical page sxx < 1, and using it
        // directly would under-sample the matrix and print a soft Hi-C heatmap.
        double qx = Math.max(sxx, 1.0), qy = Math.max(syy, 1.0);
        int w = (int) Math.round(2 * cx * qx), h = (int) Math.round(2 * cy * qy);
        if (w < 1 || h < 1) return;
        int tileW = tile.getWidth(), tileH = tile.getHeight();
        double rSpan = bandOuter - bandInner, fSpan = f1 - f0;
        if (rSpan <= 0 || fSpan <= 0 || tileW < 1 || tileH < 1) return;

        // Warp ONLY this sector's own bounding box. Scanning the whole ring for every sector made
        // the "All" view cost one full-circle pass per chromosome per Hi-C track — 26 x 2 = 52
        // passes, ~800x the area that actually receives pixels, and ~0.7 s of frozen UI per
        // rebuild once two Hi-C tracks were loaded.
        double[] bb = sectorBounds(cx, cy, bandInner, bandOuter, f0, f1);
        int x0 = clampInt((int) Math.floor(bb[0] * qx), 0, w - 1);
        int x1 = clampInt((int) Math.ceil(bb[1] * qx), 0, w - 1);
        int y0 = clampInt((int) Math.floor(bb[2] * qy), 0, h - 1);
        int y1 = clampInt((int) Math.ceil(bb[3] * qy), 0, h - 1);
        if (x1 < x0 || y1 < y0) return;
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;

        // Bulk read: one getRGB for the whole tile instead of a call per pixel. The destination
        // is written straight into the image's own backing array (no second int[] + setRGB copy).
        int[] src = tile.getRGB(0, 0, tileW, tileH, null, 0, tileW);
        BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int[] dst = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        double cxD = cx * qx, cyD = cy * qy;
        final double TWO_PI = 2.0 * Math.PI;
        for (int py = y0; py <= y1; py++) {
            double dy = (py - cyD) / qy;          // sample pixel -> logical offset
            int row = (py - y0) * bw - x0;
            for (int px = x0; px <= x1; px++) {
                double dx = (px - cxD) / qx;
                double r = Math.sqrt(dx * dx + dy * dy);
                if (r < bandInner || r > bandOuter) continue;
                double theta = Math.atan2(dx, -dy);
                double frac = (theta / TWO_PI + 1.0) % 1.0;
                double ff = frac;
                if (ff < f0) ff += 1.0;               // sector may wrap past 12 o'clock
                if (ff < f0 || ff > f1) continue;
                double t = (ff - f0) / fSpan;
                int tx = clampInt((int) Math.round(t * (tileW - 1)), 0, tileW - 1);
                int ty = clampInt((int) Math.round((r - bandInner) / rSpan * (tileH - 1)), 0, tileH - 1);
                dst[row + px] = src[ty * tileW + tx] | 0xFF000000;
            }
        }
        Shape oldClip = g2.getClip();
        g2.clip(annularPath(cx, cy, bandInner, bandOuter, f0, f1));  // logical -> device clip
        AffineTransform saved = g2.getTransform();
        g2.setTransform(new AffineTransform());   // sample space -> device space
        g2.scale(sxx / qx, syy / qy);             // identity when sxx,syy >= 1 (screen)
        g2.drawImage(out, x0, y0, null);
        g2.setTransform(saved);
        g2.setClip(oldClip);
    }

    /**
     * Bounding box (in logical coordinates, absolute) of the annular sector between radii
     * {@code rIn..rOut} and arc fractions {@code f0..f1} (0 = 12 o'clock, clockwise, may exceed 1
     * to wrap). The extreme points of such a sector are its four corners plus any of the four
     * cardinal directions its arc crosses, at the outer radius.
     */
    static double[] sectorBounds(double cx, double cy, double rIn, double rOut, double f0, double f1) {
        double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE;
        double ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
        for (double f : new double[]{f0, f1}) {
            double a = 2 * Math.PI * f;
            double s = Math.sin(a), c = Math.cos(a);
            for (double r : new double[]{rIn, rOut}) {
                double x = cx + r * s, y = cy - r * c;
                if (x < xmin) xmin = x;
                if (x > xmax) xmax = x;
                if (y < ymin) ymin = y;
                if (y > ymax) ymax = y;
            }
        }
        // Cardinal extremes: 0.25 = 3 o'clock, 0.5 = 6 o'clock, 0.75 = 9 o'clock, 1.0 = 12.
        for (int cycle = 0; cycle <= 1; cycle++) {
            for (int q = 0; q < 4; q++) {
                double f = cycle + q * 0.25;
                if (f < f0 - 1e-9 || f > f1 + 1e-9) continue;
                double a = 2 * Math.PI * f;
                double x = cx + rOut * Math.sin(a), y = cy - rOut * Math.cos(a);
                if (x < xmin) xmin = x;
                if (x > xmax) xmax = x;
                if (y < ymin) ymin = y;
                if (y > ymax) ymax = y;
            }
        }
        return new double[]{xmin, xmax, ymin, ymax};
    }

    /** Rectangle of the i-th Hi-C legend for the chosen corner. Legends stack vertically in
     *  that corner's column (downwards for the top corners, upwards for the bottom corners) so
     *  multiple Hi-C tracks never overlap. Honouring the requested corner is what makes the
     *  "Color legend" menu (Top left / Top right / Bottom left / Bottom right / None) actually
     *  move the legend. */
    static Rectangle legendRect(int w, int h, HiCTrack.LegendPosition pos, int index) {
        final int panelW = 110, panelH = 150, gap = 4;
        final boolean left = pos == HiCTrack.LegendPosition.TOP_LEFT
                || pos == HiCTrack.LegendPosition.BOTTOM_LEFT;
        final boolean topSide = pos == HiCTrack.LegendPosition.TOP_LEFT
                || pos == HiCTrack.LegendPosition.TOP_RIGHT;
        final int x = left ? 4 : w - panelW - 4;
        final int y = topSide
                ? 6 + index * (panelH + gap)
                : h - 6 - panelH - index * (panelH + gap);
        return new Rectangle(x, y, panelW, panelH);
    }

    /** Draw one legend per visible Hi-C track, stacked, BESIDE the plot (never in a ring band). */
    void drawHiCLegends(Graphics2D g2, int w, int h, List<HiCTrack> visibleHics,
                        Map<String, double[]> limitsByTrack, CircularSettings s) {
        if (visibleHics == null || visibleHics.isEmpty()) return;
        java.util.Map<HiCTrack.LegendPosition, Integer> perCorner =
                new java.util.EnumMap<>(HiCTrack.LegendPosition.class);
        for (HiCTrack hic : visibleHics) {
            HiCTrack.LegendPosition pos = hic.getLegendPosition();
            if (pos == HiCTrack.LegendPosition.NONE) continue;
            int idx = perCorner.merge(pos, 1, Integer::sum) - 1;
            Rectangle box = legendRect(w, h, pos, idx);
            drawOneHiCLegend(g2, box.x, box.y, box.width, box.height,
                    hic.getName(), limitsByTrack.get(String.valueOf(hic.getName())),
                    hic.getTransform(), hic.getPalette());
        }
    }

    // ---- labels layer (circos.genomicLabels) -------------------------------------

    /** Tangential lift (px) between the radial stub and the start of the text. */
    private static final double LIFT_PX = 4.0;

    /** Cached label placement from the last layout pass: {anchor, radius, textCentre}. */
    private List<ChrInfo> lastChrs;                 // sectors used by the last ring layout
    private final List<double[]> labelLayout = new ArrayList<>();
    private final List<String> labelTexts = new ArrayList<>();

    /**
     * Lay out the label ring. Each label keeps its GENOMIC anchor (for the cap + upper stem,
     * exactly like the heatmap comb) and gets a text slot pushed clockwise when it would overlap
     * the previous label on the same level — that tangential shift is what bends the diagonal.
     * Stored per label: {a0, a1, radius, textAngle}.
     */
    void layoutLabels(int w, int h, List<ChrInfo> chrs, CircularSettings s) {
        labelLayout.clear();
        labelTexts.clear();
        if (chrs == null || chrs.isEmpty()) return;
        java.util.Map<String, Integer> idx = chromosomeIndex(chrs);
        List<double[]> sec = sectorsFor(chrs, s);
        Font font = new Font("SansSerif", s.labelBold ? Font.BOLD : Font.PLAIN, s.labelFontSize);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        pg.dispose();

        double rOut = ideogramOuterRadius(w, h, s, true);
        // {a0, a1, widthPx} in anchor order; texts aligned with the same indices
        List<double[]> anchors = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (CircularLayer l : layers) {
            if (l.kind != CircularLayer.Kind.LABELS || !l.visible) continue;
            for (LabelRow row : l.labels) {
                Integer i = sectorFor(idx, row.chr);
                if (i == null) continue;
                ChrInfo c = chrs.get(i);
                double f0 = sec.get(i)[0], span = sec.get(i)[1] - f0;
                double vSpan = c.viewSpan();
                if (vSpan <= 0) vSpan = Math.max(1, c.length);
                double a0 = f0 + span * clamp01(((double) row.start - c.viewStart) / vSpan);
                double a1 = f0 + span * clamp01(((double) row.end - c.viewStart) / vSpan);
                if (a1 < a0) { double t = a0; a0 = a1; a1 = t; }
                anchors.add(new double[]{a0, a1, fm.stringWidth(row.text) + 6});
                texts.add(row.text);
            }
        }
        if (anchors.isEmpty()) return;

        Integer[] order = new Integer[anchors.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<double[]> a = anchors;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(
                (a.get(x)[0] + a.get(x)[1]) / 2.0, (a.get(y)[0] + a.get(y)[1]) / 2.0));

        double[] levelEnd = new double[16];
        java.util.Arrays.fill(levelEnd, -1e9);
        for (int oi : order) {
            double a0 = anchors.get(oi)[0], a1 = anchors.get(oi)[1];
            double fReal = (a0 + a1) / 2.0;
            double wpx = anchors.get(oi)[2];
            int level = 0;
            double r = 0, needed = 0, fText = 0;
            for (; level < levelEnd.length; level++) {
                r = rOut + s.labelGapPx + level * (double) s.labelLevelGapPx;
                needed = wpx / (2 * Math.PI * r);
                // nudge the text clockwise past the previous label, but only up to one text
                // width; beyond that the label moves one level further out (so the anchor and
                // the text stay near each other, and the comb's diagonal stays short).
                fText = Math.max(fReal, levelEnd[level] + needed / 2.0);
                if (fText - fReal <= needed) break;
            }
            if (level >= levelEnd.length) level = levelEnd.length - 1;
            r = rOut + s.labelGapPx + level * (double) s.labelLevelGapPx;
            needed = wpx / (2 * Math.PI * r);
            fText = Math.max(fReal, levelEnd[level] + needed / 2.0);
            levelEnd[level] = fText + needed / 2.0;
            labelLayout.add(new double[]{a0, a1, r, fText});
            labelTexts.add(texts.get(oi));
        }
    }

    /**
     * Draw the cached label ring. The connector is the SAME comb as the heatmap
     * (circos.genomicPosTransformLines, direction outside): a horizontal cap over the gene's real
     * interval hugging the ideogram, a radial stem at its midpoint, a diagonal to the label's
     * (tangentially pushed) text slot, and a final radial stem into the horizontal text.
     */
    void drawLabelLayers(Graphics2D g2, int w, int h, CircularSettings s) {
        if (labelLayout.isEmpty()) return;
        int cx = w / 2, cy = h / 2;
        double rOut = ideogramOuterRadius(w, h, s, true);
        Font font = new Font("SansSerif", s.labelBold ? Font.BOLD : Font.PLAIN, s.labelFontSize);
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();
        try {
            if (s.labelShowLines) {
                g2.setColor(s.labelLineColor);
                g2.setStroke(new BasicStroke(0.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                for (int i = 0; i < labelLayout.size(); i++) {
                    double a0 = labelLayout.get(i)[0], a1 = labelLayout.get(i)[1];
                    double r = labelLayout.get(i)[2], fText = labelLayout.get(i)[3];
                    double fReal = (a0 + a1) / 2.0;
                    double hT = r - rOut;
                    if (hT < 2) continue;
                    double rMid = rOut + hT / 3.0;
                    double rLow = rOut + 2 * hT / 3.0;

                    // horizontal cap over the gene's real interval, hugging the ideogram edge
                    g2.fill(annularPath(cx, cy, rOut - 0.4, rOut + 0.4, a0, a1));

                    Point2D pTop = polar(cx, cy, rOut, fReal);
                    Point2D pMid = polar(cx, cy, rMid, fReal);
                    Point2D pLow = polar(cx, cy, rLow, fText);
                    Point2D pBot = polar(cx, cy, r, fText);
                    g2.draw(new Line2D.Double(pTop, pMid));
                    g2.draw(new Line2D.Double(pMid, pLow));
                    g2.draw(new Line2D.Double(pLow, pBot));
                }
            }
            for (int i = 0; i < labelLayout.size(); i++) {
                double r = labelLayout.get(i)[2], fText = labelLayout.get(i)[3];
                drawHorizontalLabel(g2, cx, cy, r, fText, labelTexts.get(i), font, s.labelColor);
            }
        } finally {
            g2.setStroke(oldStroke);
            g2.setColor(oldColor);
        }
    }

    /**
     * Colour-bar legend for the genomic heatmap, drawn OUTSIDE the ring in one of the four
     * corners (or hidden). Uses the same corner rectangles as the Hi-C legends and stacks below
     * them when both want the same corner, so the two never overlap.
     */
    void drawHeatmapLegend(Graphics2D g2, int w, int h, GenomicHeatmap hm, CircularSettings s,
                           List<HiCTrack> visibleHics) {
        if (!s.heatmapLegend || hm == null || s.heatmapLegendPosition == null) return;
        if (s.heatmapLegendPosition == org.broad.igv.hic.HiCTrack.LegendPosition.NONE) return;
        int idx = 0;
        if (visibleHics != null) {
            for (HiCTrack hic : visibleHics) {
                if (hic.getLegendPosition() == s.heatmapLegendPosition) idx++;
            }
        }
        Rectangle box = legendRect(w, h, s.heatmapLegendPosition, idx);
        Font oldFont = g2.getFont();
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        try {
            g2.setColor(new Color(255, 255, 255, 235));
            g2.fillRoundRect(box.x, box.y, box.width, box.height, 8, 8);
            g2.setColor(new Color(120, 120, 120));
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawRoundRect(box.x, box.y, box.width, box.height, 8, 8);

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            String title = "heatmap";
            g2.drawString(title, box.x + 8, box.y + 14);

            int barX = box.x + 10, barY = box.y + 22;
            int barW = 16, barH = box.height - 44;
            int steps = Math.max(2, barH);
            for (int i = 0; i < steps; i++) {
                double t = i / (double) (steps - 1);            // 0 = top (max)
                double v = hm.max - (hm.max - hm.min) * t;
                g2.setColor(divergingColor(v, hm.min, hm.max, s));
                g2.fillRect(barX, barY + i, barW, 2);
            }
            g2.setColor(new Color(90, 90, 90));
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawRect(barX, barY, barW, barH);

            g2.setColor(new Color(60, 60, 60));
            g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g2.drawString(fmtNum(hm.max), barX + barW + 4, barY + 8);
            g2.drawString(fmtNum(hm.min), barX + barW + 4, barY + barH);
            if (hm.min < 0 && hm.max > 0) {
                double a = Math.max(-hm.min, hm.max);
                int zeroY = barY + (int) Math.round((a / (2 * a)) * barH);   // 0 sits at half
                g2.setColor(new Color(60, 60, 60));
                g2.drawString("0", barX + barW + 4, zeroY + 3);
            }
        } finally {
            g2.setFont(oldFont);
            g2.setColor(oldColor);
            g2.setStroke(oldStroke);
        }
    }

    /** Compact number for legend ticks: 1.4 / -0.25 / 12. */
    static String fmtNum(double v) {
        if (Math.abs(v) >= 10) return String.format(java.util.Locale.ROOT, "%.0f", v);
        if (Math.abs(v) >= 1) return String.format(java.util.Locale.ROOT, "%.2f", v);
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** One legend panel: white rounded box, horizontal colour ramp, min/max ticks, transform
     *  label and track name. Package-private so the headless probe can render it directly. */
    static void drawOneHiCLegend(Graphics2D g2, int x, int y, int panelW, int panelH,
                                 String trackName, double[] limits, HiCTransform transform,
                                 HiCColorScale.Palette palette) {
        double vmin = limits == null ? 0 : limits[0];
        double vmax = limits == null ? 1 : limits[1];
        if (!(vmax > vmin)) { vmin = 0; vmax = 1; }
        HiCColorScale.Palette pal = palette != null ? palette : HiCColorScale.Palette.RDYLBU_R;
        Font oldFont = g2.getFont();
        Color oldColor = g2.getColor();
        try {
            g2.setColor(new Color(255, 255, 255, 235));
            g2.fillRoundRect(x, y, panelW, panelH, 8, 8);
            g2.setColor(new Color(120, 120, 120));
            g2.drawRoundRect(x, y, panelW, panelH, 8, 8);

            g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
            FontMetrics fm = g2.getFontMetrics();
            String name = trackName == null ? "Hi-C" : trackName;
            while (fm.stringWidth(name) > panelW - 44 && name.length() > 4) {
                name = name.substring(0, name.length() - 2) + "…";
            }
            String tf = transform == null ? "" : transform.getLabel();
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(name, x + 6, y + 12);
            g2.setColor(new Color(90, 90, 90));
            g2.drawString(tf, x + panelW - 6 - fm.stringWidth(tf), y + 12);

            // Vertical colour bar: narrow ramp, max at TOP / min at BOTTOM. The value->colour
            // mapping is identical to the old horizontal bar (vmin..vmax, NEG_LOG reversed), just
            // rotated 90 degrees so the legend no longer runs wide across the panel margin.
            int barW = 16;
            int barX = x + 8;
            int barTop = y + 20;
            int barH = panelH - 20 - 8;
            if (barH < 24) barH = 24;
            boolean reverse = transform == HiCTransform.NEG_LOG;
            BufferedImage ramp = new BufferedImage(barW, barH, BufferedImage.TYPE_INT_RGB);
            for (int j = 0; j < barH; j++) {
                double pos = barH > 1 ? j / (double) (barH - 1) : 0.0;   // 0 = bottom, 1 = top
                double t = pos;                                          // bottom = vmin, top = vmax
                if (reverse) t = 1.0 - t;                                // keep NEG_LOG behaviour
                int rgb = HiCColorScale.rgb(vmin + (vmax - vmin) * t, vmin, vmax, pal);
                for (int k = 0; k < barW; k++) ramp.setRGB(k, j, rgb);
            }
            g2.drawImage(ramp, barX, barTop, null);
            g2.setColor(new Color(90, 90, 90));
            g2.drawRect(barX, barTop, barW, barH);

            g2.setFont(oldFont.deriveFont(Font.PLAIN, 9f));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.setColor(Color.BLACK);
            String lo = formatCompact(vmin), hi = formatCompact(vmax);
            int labX = barX + barW + 4;
            g2.drawString(hi, labX, barTop + fm2.getAscent());     // max at top
            g2.drawString(lo, labX, barTop + barH - 2);             // min at bottom
        } finally {
            g2.setFont(oldFont);
            g2.setColor(oldColor);
        }
    }

    private static String formatCompact(double v) {
        double a = Math.abs(v);
        if (a < 1e-9) return "0";
        if (a >= 1e6) return String.format(java.util.Locale.ROOT, "%.1fM", v / 1e6);
        if (a >= 1e3) return String.format(java.util.Locale.ROOT, "%.1fk", v / 1e3);
        if (a >= 100) return String.format(java.util.Locale.ROOT, "%.0f", v);
        if (a >= 10) return String.format(java.util.Locale.ROOT, "%.1f", v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Load per-bin scores for bigWig/bedGraph tracks (background, cached). */
    private void ensureDataLoaded(Genome genome) {
        if (genome == null || ringTracks.isEmpty()) return;
        for (Track t : ringTracks) {
            if (!(t instanceof DataTrack)) continue;
            for (String chr : genome.getLongChromosomeNames()) {
                org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
                if (c == null) continue;
                if (!settings.shownChromosomes.isEmpty() && !settings.shownChromosomes.contains(chr)) continue;
                final String key = t.getName() + "|" + chr;
                if (dataCache.containsKey(key) || !dataPending.add(key)) continue;
                final int len = c.getLength();
                featureLoader.submit(() -> {
                    float[] bins = new float[DATA_BINS];
                    int binLen = Math.max(1, len / DATA_BINS);
                    try {
                        for (int i = 0; i < DATA_BINS; i++) {
                            int st = i * binLen;
                            int en = Math.min(len, st + binLen);
                            if (st >= len) { bins[i] = Float.NaN; continue; }
                            // zoom 0 + null frameName -> fresh fine-grained summary per bin
                            // (avoids the coarse whole-genome summary / frame cache thrash that
                            //  made every bin ~constant, so all bars were full height).
                            bins[i] = t.getRegionScore(chr, st, en, 0, RegionScoreType.SCORE, null);
                        }
                    } catch (Throwable ex) {
                        java.util.Arrays.fill(bins, Float.NaN);
                    }
                    dataCache.put(key, bins);
                    dataPending.remove(key);
                    SwingUtilities.invokeLater(this::invalidateRing);
                });
            }
        }
    }

    /**
     * BigWig bin source for one sector. When the whole chromosome is visible we return the cheap
     * whole-chromosome 300-bin summary from {@link #ensureDataLoaded}. When only a window is visible
     * (zoomed in, or a gene-list sector) we re-sample at a finer granularity scoped to that window,
     * so the bars reflect the actual values in view rather than a stretched coarse bin. The fine
     * bins are cached by window signature and computed in the background; the method returns
     * {@code null} while a re-sample is still pending (the ring repaints when it finishes).
     */
    private float[] binsFor(Track track, ChrInfo c) {
        double span = c.viewSpan();
        if (span >= c.length * 0.999) {
            return dataCache.get(track.getName() + "|" + c.chrKey());
        }
        long ws = (long) Math.floor(c.viewStart);
        long we = (long) Math.ceil(c.viewEnd);
        long binBp = Math.max(1, (long) Math.ceil(span / ADAPTIVE_BINS));
        int n = (int) Math.max(2, Math.ceil(span / binBp));
        String key = track.getName() + "|" + c.chrKey() + "|" + ws + "_" + we + "_" + binBp;
        float[] hit = adaptiveCache.get(key);
        if (hit != null) return hit;
        if (adaptivePending.add(key)) {                 // submit at most once per window signature
            final int fn = n, fbp = (int) binBp;
            // zoom=0 maps to the COARSEST precomputed summary, so a small window would get the
            // same value in every bin (flat bars). Pick a zoom whose bin size is <= fbp.
            final int fzoom = zoomForBinSize(c.length, binBp);
            final long fws = ws, fwe = we;
            final String fchr = c.chrKey();
            final DataTrack dt = (DataTrack) track;
            featureLoader.submit(() -> {
                float[] bins = new float[fn];
                try {
                    for (int i = 0; i < fn; i++) {
                        long st = fws + (long) i * fbp;
                        long en = Math.min(fwe, st + fbp);
                        if (st >= fwe) { bins[i] = Float.NaN; continue; }
                        bins[i] = dt.getRegionScore(fchr, (int) st, (int) en, fzoom, RegionScoreType.SCORE, null);
                    }
                } catch (Throwable ex) {
                    java.util.Arrays.fill(bins, Float.NaN);
                }
                adaptiveCache.put(key, bins);
                adaptivePending.remove(key);
                // Soft cap so the cache cannot grow without bound across many zoom levels.
                if (adaptiveCache.size() > 4000) adaptiveCache.clear();
                SwingUtilities.invokeLater(this::invalidateRing);
            });
        }
        return null;
    }

    /** IGV zoom level whose per-bin resolution (chrLen / (2^z * 700)) is at most {@code binBp},
     *  so a small re-sampled window gets fine data instead of the coarse precomputed summary. */
    private static int zoomForBinSize(long chrLength, long binBp) {
        double target = Math.max(1.0, (double) chrLength / ((double) binBp * 700.0));
        int z = (int) Math.ceil(Math.log(target) / Math.log(2.0));
        return Math.max(0, Math.min(z, 22));
    }

    /** Shared Y range across ALL data tracks and the given chromosomes (group scale). */
    private float[] computeSharedYRange(List<ChrInfo> chrs) {
        float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
        for (Track t : ringTracks) {
            if (!(t instanceof DataTrack)) continue;
            for (ChrInfo c : chrs) {
                float[] b = binsFor(t, c);
                if (b == null) continue;
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < mn) mn = v; if (v > mx) mx = v; }
            }
        }
        return mn == Float.POSITIVE_INFINITY ? null : new float[]{mn, mx};
    }

    /** Y ranges per shared group id (tracks with the same cfg.yGroup share one range). */
    private java.util.Map<String, float[]> computeGroupRanges(List<ChrInfo> chrs) {
        java.util.Map<String, float[]> map = new java.util.HashMap<>();
        for (Track t : ringTracks) {
            if (!(t instanceof DataTrack)) continue;
            TrackRingConfig cfg = getRingConfig(t.getName());
            if (cfg.yGroup == null) continue;
            float[] r = map.get(cfg.yGroup);
            if (r == null) { r = new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}; map.put(cfg.yGroup, r); }
            for (ChrInfo c : chrs) {
                float[] b = binsFor(t, c);
                if (b == null) continue;
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < r[0]) r[0] = v; if (v > r[1]) r[1] = v; }
            }
        }
        return map;
    }

    /** Per-gene group ranges: group id -> (sector key -> min/max across all tracks in the group
     *  for that sector). Used when {@link TrackRingConfig#yGroupPerGene} is on, so each gene is
     *  scaled independently instead of being pulled down by a single genome-wide group range. */
    private java.util.Map<String, java.util.Map<String, float[]>> computeGroupRangesPerGene(List<ChrInfo> chrs) {
        java.util.Map<String, java.util.Map<String, float[]>> map = new java.util.HashMap<>();
        for (Track t : ringTracks) {
            if (!(t instanceof DataTrack)) continue;
            TrackRingConfig cfg = getRingConfig(t.getName());
            if (cfg.yGroup == null) continue;
            java.util.Map<String, float[]> perGene = map.computeIfAbsent(cfg.yGroup, k -> new java.util.HashMap<>());
            for (ChrInfo c : chrs) {
                float[] b = binsFor(t, c);
                if (b == null) continue;
                String ck = c.name;   // per-SECTOR key: gene name in gene mode, chromosome in All mode
                float[] r = perGene.get(ck);
                if (r == null) { r = new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}; perGene.put(ck, r); }
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < r[0]) r[0] = v; if (v > r[1]) r[1] = v; }
            }
        }
        return map;
    }

    /** Draw a continuous data track (bigWig/bedGraph) as Bar / Point / Line / Heatmap. */
    private void drawDataRing(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                              List<ChrInfo> chrs, List<double[]> sec, Track track, TrackRingConfig cfg,
                              java.util.function.Function<ChrInfo, float[]> sharedRange) {
        double bandH = bandOuter - bandInner;
        String type = cfg.graphType == null ? "Bar" : cfg.graphType;

        // Track-level global Y-range (cfg.globalYScale): one min/max across all displayed
        // chromosomes from this track's own bins, used only when no shared/group range overrides.
        float[] globalRange = null;
        if (cfg.globalYScale && sharedRange == null) {
            float gmn = Float.POSITIVE_INFINITY, gmx = Float.NEGATIVE_INFINITY;
            for (ChrInfo c : chrs) {
                float[] b = binsFor(track, c);
                if (b == null) continue;
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < gmn) gmn = v; if (v > gmx) gmx = v; }
            }
            if (gmn == Float.POSITIVE_INFINITY) return;
            globalRange = new float[]{gmn, gmx};
        }

        for (int i = 0; i < chrs.size(); i++) {
            float[] b = binsFor(track, chrs.get(i));
            if (b == null) continue;
            ChrInfo c = chrs.get(i);
            double wSpan = c.viewSpan();
            double vStart = c.viewStart;
            double f0 = sec.get(i)[0], span = sec.get(i)[1] - f0;
            int n = b.length;

            // Y range: shared/group (per gene or global), track-global, or per-chromosome.
            float mn, mx;
            float[] sr = (sharedRange != null) ? sharedRange.apply(c) : globalRange;
            if (sr != null) { mn = sr[0]; mx = sr[1]; }
            else {
                mn = Float.POSITIVE_INFINITY; mx = Float.NEGATIVE_INFINITY;
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < mn) mn = v; if (v > mx) mx = v; }
                if (mn == Float.POSITIVE_INFINITY) continue;
            }
            boolean flat = (mx - mn) <= 1e-9f;
            double denom = mx - mn;
            // IGV draws bars from a baseline at value 0 (scaled by the range), not from min.
            double baseR = flat ? bandInner : bandInner + bandH * clamp01((0.0 - mn) / denom);

            if ("Line".equalsIgnoreCase(type)) {
                java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
                boolean pen = false;
                for (int k = 0; k < n; k++) {
                    float v = b[k];
                    if (Float.isNaN(v) || Float.isInfinite(v)) { pen = false; continue; }
                    double t = flat ? 0.5 : clamp01((v - mn) / denom);
                    double a = f0 + span * c.viewFrac(vStart + wSpan * (k + 0.5) / n);
                    Point2D p = polar(cx, cy, bandInner + bandH * t, a);
                    if (!pen) { path.moveTo(p.getX(), p.getY()); pen = true; } else path.lineTo(p.getX(), p.getY());
                }
                g2.setColor(cfg.color);
                g2.setStroke(new BasicStroke(1.2f));
                g2.draw(path);
                g2.setStroke(new BasicStroke(1.0f));
                continue;
            }

            for (int k = 0; k < n; k++) {
                float v = b[k];
                if (Float.isNaN(v) || Float.isInfinite(v)) continue;
                double t = flat ? 0.5 : clamp01((v - mn) / denom);
                double binStart = vStart + wSpan * k / n, binEnd = vStart + wSpan * (k + 1) / n;
                if (binEnd < c.viewStart || binStart > c.viewEnd) continue;   // outside the window
                double a0 = f0 + span * c.viewFrac(binStart);
                double a1 = f0 + span * c.viewFrac(binEnd);
                if ("Heatmap".equalsIgnoreCase(type)) {
                    g2.setColor(ramp(t));
                    g2.fill(annularPath(cx, cy, bandInner, bandOuter, a0, a1));
                } else if ("Point".equalsIgnoreCase(type)) {
                    Point2D p = polar(cx, cy, bandInner + bandH * t, (a0 + a1) / 2.0);
                    g2.setColor(cfg.color);
                    g2.fill(new java.awt.geom.Ellipse2D.Double(p.getX() - 1.2, p.getY() - 1.2, 2.4, 2.4));
                } else { // Bar: from the value-0 baseline to the value radius (IGV wig style)
                    double rBar = bandInner + bandH * t;
                    fillArc(g2, cx, cy, Math.min(baseR, rBar), Math.max(baseR, rBar), a0, a1, cfg.color);
                }
            }
        }
    }

    /** White -> deep-blue sequential ramp for data heatmaps. */
    private static Color ramp(double t) {
        if (t < 0) t = 0; else if (t > 1) t = 1;
        int r = (int) Math.round(250 - 215 * t);
        int g = (int) Math.round(250 - 150 * t);
        int b = (int) Math.round(250 - 70 * t);
        return new Color(clamp8(r), clamp8(g), clamp8(b));
    }

    private static int clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** Draw a feature track's features inside one concentric band (collapsed or expanded rows). */
    private void drawFeatures(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                              List<ChrInfo> chrs, List<double[]> sec, FeatureTrack ft, TrackRingConfig cfg) {
        // Gene-list mode (one sector per gene) stashes the resolved gene under its name so the
        // structure shows even when no gene track is loaded; otherwise fall back to the cache.
        drawFeatureBand(g2, cx, cy, bandInner, bandOuter, chrs, sec, cfg,
                name -> {
                    java.util.List<Feature> g = geneFeatures.get(name);
                    return g != null ? g : featureCache.get(ft.getName() + "|" + name);
                });
    }

    /** Static core of the feature-band drawing (also used by the headless render probe). */
    public static void drawFeatureBand(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                                       List<ChrInfo> chrs, List<double[]> sec, TrackRingConfig cfg,
                                       java.util.function.Function<String, List<Feature>> featsFn) {
        double bandH = bandOuter - bandInner;
        if (bandH < 2) return;
        int maxRows = Math.max(1, (int) (bandH / 3.0));
        double rowH = bandH / maxRows;

        for (int i = 0; i < chrs.size(); i++) {
            ChrInfo c = chrs.get(i);
            // In gene-list mode c.name is the gene name (features are stashed by name); in normal
            // mode c.name and c.chrKey() are identical, so this lookup resolves both cases.
            List<Feature> list = featsFn.apply(c.name);
            if (list == null || list.isEmpty()) continue;
            double f0 = sec.get(i)[0], f1 = sec.get(i)[1], span = f1 - f0;
            double len = Math.max(1, c.length);

            if (!cfg.expanded) {
                double rr = (bandInner + bandOuter) / 2.0;
                for (Feature f : list) {
                    if (f.getEnd() < c.viewStart || f.getStart() > c.viewEnd) continue;
                    double a = f0 + span * c.viewFrac(f.getStart());
                    double b = f0 + span * c.viewFrac(f.getEnd());
                    if (b <= a) b = a + 1e-7;
                    drawFeatureMark(g2, cx, cy, rr, bandH, c, f0, span, f, cfg.color);
                }
            } else {
                List<List<double[]>> rows = new ArrayList<>();
                for (Feature f : list) {
                    if (f.getEnd() < c.viewStart || f.getStart() > c.viewEnd) continue;
                    double a = f0 + span * c.viewFrac(f.getStart());
                    double b = f0 + span * c.viewFrac(f.getEnd());
                    if (b <= a) b = a + 1e-7;
                    int r = 0;
                    while (r < rows.size() && overlapsAny(rows.get(r), a, b)) r++;
                    if (r == rows.size()) {
                        if (rows.size() >= maxRows) continue;   // band full, skip extra features
                        rows.add(new ArrayList<>());
                    }
                    rows.get(r).add(new double[]{a, b});
                    double rr = cfg.stackFromOuter
                            ? bandOuter - (r + 0.5) * rowH      // pin to the outer (top) edge, grow inward
                            : bandInner + (r + 0.5) * rowH;     // pin to the inner (bottom) edge, grow outward
                    drawFeatureMark(g2, cx, cy, rr, rowH, c, f0, span, f, cfg.color);
                }
            }
        }
    }

    /**
     * Draw one feature inside a band. When the feature has enough screen width and carries an
     * exon model, the transcript structure is drawn the way the linear view does it: a thin
     * intron spine, thick CDS blocks and thin UTR blocks, plus chevrons for the strand. Below
     * that width it degrades to a simple mark, so a whole-genome ring stays readable.
     *
     * @param rMid   radius of the band centre
     * @param availH the radial height available to this feature (band height, or a packed row)
     * @param f0    start fraction of this chromosome's sector
     * @param span  angular width of the sector, in fractions of the circle
     */
    private static void drawFeatureMark(Graphics2D g2, double cx, double cy,
                                        double rMid, double availH,
                                        ChrInfo c, double f0, double span,
                                        Feature f, Color color) {
        double a = f0 + span * c.viewFrac(f.getStart());
        double b = f0 + span * c.viewFrac(f.getEnd());
        if (b <= a) b = a + 1e-7;
        double arcPx = arcLength(rMid, (b - a) * 360.0);

        BasicFeature bf = (f instanceof BasicFeature) ? (BasicFeature) f : null;
        List<Exon> exons = bf == null ? null : bf.getExons();

        if (exons == null || exons.isEmpty() || arcPx < 10) {
            // No exon model (or too small to show detail): a single block with a strand chevron
            // if there is room. Thickness stays modest so an overview ring stays clean.
            double h = Math.max(0.7, Math.min(1.6, availH * 0.5));
            fillArc(g2, cx, cy, rMid - h, rMid + h, a, b, color);
            if (arcPx >= 14 && bf != null) {
                drawStrandArrows(g2, cx, cy, rMid, a, b, strandDir(bf),
                        0, contrastColor(color));
            }
            return;
        }

        // Faithful polar port of IGVFeatureRenderer.drawExons(): a thin intron spine, each exon
        // split into UTR (thin) and CDS (thick) via its coding boundaries, and white directional
        // chevrons along the coding strand. Thickness scales with the band so the structure is
        // legible once the gene is wide enough to warrant it.
        double cdsH = Math.max(2.0, availH * 0.46);     // IGV blockHeight (14) -> thick CDS
        double utrH = Math.max(1.2, availH * 0.23);     // IGV NON_CODING_HEIGHT (8) -> thin UTR
        double spineH = 0.5;                            // IGV intron line is 1px (default stroke)
        fillArc(g2, cx, cy, rMid - spineH, rMid + spineH, a, b, color);

        for (Exon exon : exons) {
            long exS = exon.getStart(), exE = exon.getEnd();
            if (exE <= exS) continue;
            long cdS = clamp(exon.getCdStart(), exS, exE);
            long cdE = clamp(exon.getCdEnd(), exS, exE);

            if (exon.isNonCoding()) {
                fillExonSeg(g2, cx, cy, rMid, utrH, f0, span, c, exS, exE, color);   // whole exon UTR
            } else {
                if (cdS > exS) fillExonSeg(g2, cx, cy, rMid, utrH, f0, span, c, exS, cdS, color);  // 5' UTR
                if (cdE < exE) fillExonSeg(g2, cx, cy, rMid, utrH, f0, span, c, cdE, exE, color);  // 3' UTR
                if (exon.getCdStart() < exE && exon.getCdEnd() > exS)
                    fillExonSeg(g2, cx, cy, rMid, cdsH, f0, span, c, cdS, cdE, color);             // CDS
            }
        }

        // Intron chevrons: IGVFeatureRenderer.drawExons() draws the strand markers on each
        // connecting line using the SAME graphics/color as the blocks (not white), via
        // drawStrandArrows(gene.getStrand(), lastExonEndX, pStart, ..., blockGraphics).
        drawIntronArrows(g2, cx, cy, rMid, f0, span, c, exons, strandDir(bf), color);
        // In-block white chevrons inside each exon, mirroring IGV's
        // drawStrandArrows(..., pStart + ARROW_SPACING/2, pEnd, ..., whiteArrowGraphics).
        // Single-exon genes (JUN/JUND) therefore still show transcription direction.
        for (Exon exon : exons) {
            double ea = f0 + span * c.viewFrac(exon.getStart());
            double eb = f0 + span * c.viewFrac(exon.getEnd());
            if (eb <= ea) continue;
            drawStrandArrows(g2, cx, cy, rMid, ea, eb, strandDir(bf),
                    ARROW_SPACING / 2.0, contrastColor(color));
        }
    }

    /** Direction chevrons on the intron lines (between consecutive exons). Mirrors IGV's linear
     *  gene rendering: the markers sit on each 1px connecting line, in the feature colour. */
    private static void drawIntronArrows(Graphics2D g2, double cx, double cy, double r,
                                         double f0, double span, ChrInfo c,
                                         List<Exon> exons, int dir, Color color) {
        if (dir == 0 || exons == null || exons.size() < 2) return;
        List<Exon> sorted = new ArrayList<>(exons);
        sorted.sort(java.util.Comparator.comparingLong(Exon::getStart));
        for (int i = 0; i + 1 < sorted.size(); i++) {
            long gs = sorted.get(i).getEnd();
            long ge = sorted.get(i + 1).getStart();
            if (ge <= gs) continue;
            double ga = f0 + span * c.viewFrac(gs);
            double gb = f0 + span * c.viewFrac(ge);
            if (arcLength(r, (gb - ga) * 360.0) < 6) continue;   // IGV: skip very small regions
            drawStrandArrows(g2, cx, cy, r, ga, gb, dir, 0, color);
        }
    }

    private static long clamp(long v, long lo, long hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Fill one exon (sub)segment [s, e) at a given radial thickness. */
    private static void fillExonSeg(Graphics2D g2, double cx, double cy, double rMid, double h,
                                    double f0, double span, ChrInfo c, long s, long e, Color color) {
        double ea = f0 + span * c.viewFrac(s);
        double eb = f0 + span * c.viewFrac(e);
        if (eb <= ea) return;
        fillArc(g2, cx, cy, rMid - h, rMid + h, ea, eb, color);
    }

    /** +1 for forward (arrow points clockwise), -1 for reverse, 0 when unknown. */
    private static int strandDir(BasicFeature bf) {
        if (bf == null || bf.getStrand() == null) return 0;
        if (bf.getStrand() == Strand.POSITIVE) return 1;
        if (bf.getStrand() == Strand.NEGATIVE) return -1;
        return 0;
    }

    /** A colour that reads against {@code base} (black on light bars, white on dark ones). */
    private static Color contrastColor(Color base) {
        double lum = (0.299 * base.getRed() + 0.587 * base.getGreen() + 0.114 * base.getBlue()) / 255.0;
        return lum > 0.55 ? new Color(20, 20, 20) : new Color(245, 245, 245);
    }

    /** Matches {@code IGVFeatureRenderer.ARROW_SPACING}: one chevron every 30 screen px. */
    private static final int ARROW_SPACING = 30;
    /** Matches {@code IGVFeatureRenderer}'s chevron leg length in collapsed/squished mode
     *  ({@code sz = 2}; 3 when expanded). Kept fixed so the ring arrows look like the linear ones. */
    private static final double ARROW_LEG = 2.0;

    /**
     * Polar port of {@code IGVFeatureRenderer.drawStrandArrows()}. IGV does NOT draw filled
     * triangles: it strokes two 1px lines from an apex to two trailing arms, i.e. a ">" / "<"
     * chevron, every {@link #ARROW_SPACING} px starting at half that spacing. The apex points in
     * the transcription direction. Colour is the caller's responsibility (white inside exon
     * blocks, the feature colour on intron lines) exactly as in the linear renderer.
     *
     * @param startOffsetPx extra leading offset in arc pixels (exons pass ARROW_SPACING/2 to
     *                      mirror IGV's {@code pStart + ARROW_SPACING/2} call).
     */
    private static void drawStrandArrows(Graphics2D g2, double cx, double cy, double r,
                                         double a, double b, int dir, double startOffsetPx,
                                         Color color) {
        if (dir == 0) return;
        double arcPx = arcLength(r, (b - a) * 360.0);
        if (arcPx < 6) return;                         // IGV: skip very small regions
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.0f));           // IGV uses the default 1px stroke
        double first = ARROW_SPACING / 2.0 + startOffsetPx;
        // Narrow features (e.g. a single-exon gene or a collapsed locus) would otherwise have
        // no chevron at all because the first 30px slot does not fit: keep one centred marker
        // so the strand is never lost.
        if (first >= arcPx) {
            chevron(g2, cx, cy, r, a + (b - a) * 0.5, dir);
        } else {
            for (double d = first; d < arcPx; d += ARROW_SPACING) {
                chevron(g2, cx, cy, r, a + (b - a) * (d / arcPx), dir);
            }
        }
        g2.setColor(oldColor);
        g2.setStroke(oldStroke);
    }

    /** One two-stroke ">" / "<" chevron, apex pointing in the transcription direction. */
    private static void chevron(Graphics2D g2, double cx, double cy, double r, double f, int dir) {
        double theta = f * 2 * Math.PI;
        // Unit tangent for increasing fraction (clockwise); multiply by dir for the
        // transcription direction. Apex at P, arms trail by ARROW_LEG along -tangent.
        double ux = Math.cos(theta) * dir, uy = Math.sin(theta) * dir;
        double nx = -uy, ny = ux;
        double px = cx + r * Math.sin(theta), py = cy - r * Math.cos(theta);
        int ax = (int) Math.round(px), ay = (int) Math.round(py);
        int x1 = (int) Math.round(px - ux * ARROW_LEG + nx * ARROW_LEG);
        int y1 = (int) Math.round(py - uy * ARROW_LEG + ny * ARROW_LEG);
        int x2 = (int) Math.round(px - ux * ARROW_LEG - nx * ARROW_LEG);
        int y2 = (int) Math.round(py - uy * ARROW_LEG - ny * ARROW_LEG);
        g2.drawLine(ax, ay, x1, y1);
        g2.drawLine(ax, ay, x2, y2);
    }

    // ------------------------------------------------------------------
    //  Circos-style links
    // ------------------------------------------------------------------

    /**
     * Draw Circos {@code <links>}. Each link joins an interval on chr1 to an interval on chr2
     * with a quadratic Bezier that bows toward the centre of the ring:
     *
     * <ul>
     *   <li>the two ends sit on {@code settings.linkRadius} (a fraction of the ideogram inner
     *       radius), so they never overlap the ideogram;</li>
     *   <li>the single control point sits on the angle bisector at {@code linkBezierRadius},
     *       which is Circos' {@code bezier_radius}; {@code linkBezierPurity} lerps it toward
     *       the Cartesian midpoint so short links do not over-bend;</li>
     *   <li>{@code linkCrest} adds the two extra control points that make the curve leave the
     *       ideogram radially instead of at a tangent;</li>
     *   <li>{@code linkRibbon} replaces the stroke with a filled band whose end width is the
     *       arc length of each interval (Circos {@code ribbon=yes}).</li>
     * </ul>
     *
     * <p>Static so the headless render probe can verify it, exactly like
     * {@link #drawFeatureBand}.</p>
     */
    public static void drawLinks(Graphics2D g2, int w, int h, List<ChrInfo> chrs,
                                 List<RingLink> links, CircularSettings s) {
        drawLinks(g2, w, h, chrs, links, s, -1);
    }

    /**
     * @param innerLimitR when &gt; 0, links are anchored at this radius (the free space inside
     *                    the stacked ring bands) instead of just inside the ideogram, so they
     *                    never cover already-drawn data tracks.
     */
    public static void drawLinks(Graphics2D g2, int w, int h, List<ChrInfo> chrs,
                                 List<RingLink> links, CircularSettings s, double innerLimitR) {
        if (s == null) s = new CircularSettings();
        if (!s.showLinks || links == null || links.isEmpty() || chrs == null || chrs.isEmpty()) return;

        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;

        double rOut = ideogramOuterRadius(w, h, s);
        double rIn = Math.max(R * 0.2, rOut - s.chromosomeHeightPx);
        double baseR = innerLimitR > 0 ? innerLimitR : rIn;
        double rEnd = Math.max(8.0, baseR * s.linkRadius);
        double rBez = rEnd * s.linkBezierRadius;

        List<double[]> sec = sectorsFor(chrs, s);
        Map<String, Integer> index = new HashMap<>();
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        for (int i = 0; i < chrs.size(); i++) {
            String name = chrs.get(i).name;
            index.put(name, i);
            index.put(shortName(name), i);
            if (genome != null) {
                String canonical = genome.getCanonicalChrName(name);
                if (canonical != null) index.put(canonical, i);
            }
        }

        // z-depth: larger z painted last (on top), stable for equal z.
        List<RingLink> ordered = new ArrayList<>(links);
        ordered.sort((a, b) -> Double.compare(a.z, b.z));

        Stroke oldStroke = g2.getStroke();
        Paint oldPaint = g2.getPaint();
        Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int drawn = 0;
        for (RingLink link : ordered) {
            if (drawn >= s.linkRecordLimit) break;

            Integer i1 = resolveSector(index, link.chr1);
            Integer i2 = resolveSector(index, link.chr2);
            if (i1 == null || i2 == null) continue;   // one end on a hidden chromosome

            double[] sector1 = sec.get(i1), sector2 = sec.get(i2);
            ChrInfo ci1 = chrs.get(i1), ci2 = chrs.get(i2);

            double a1s = bpToFrac(link.start1, ci1, sector1);
            double a1e = bpToFrac(link.end1, ci1, sector1);
            double a2s = bpToFrac(link.start2, ci2, sector2);
            double a2e = bpToFrac(link.end2, ci2, sector2);
            if (a1e < a1s) { double t = a1s; a1s = a1e; a1e = t; }
            if (a2e < a2s) { double t = a2s; a2s = a2e; a2e = t; }

            double a1 = (a1s + a1e) / 2.0;
            double a2 = (a2s + a2e) / 2.0;
            // Shortest way round: never route the curve across the 12 o'clock seam.
            double d = a2 - a1;
            d -= Math.rint(d);
            double aMid = a1 + d / 2.0;

            Point2D p1 = polar(cx, cy, rEnd, a1);
            Point2D p2 = polar(cx, cy, rEnd, a2);
            Point2D q = polar(cx, cy, rBez, aMid);

            // bezier_radius_purity: blend the radial control point toward the chord midpoint.
            double purity = clamp01(s.linkBezierPurity);
            double mx = (p1.getX() + p2.getX()) / 2.0;
            double my = (p1.getY() + p2.getY()) / 2.0;
            double qx = q.getX() * purity + mx * (1 - purity);
            double qy = q.getY() * purity + my * (1 - purity);

            Color base = link.color != null
                    ? link.color
                    : (s.linkColorByChromosome ? pickColor(chrs.get(i1).name, null) : s.linkColor);

            // The link BODY is always a ribbon (a 1 bp end is floored at "Min ribbon width"), so
            // no link can collapse into a hairline. The end type only decides the ARROW style:
            // a point end gets a thin arrow, an interval end gets an arrow as wide as the band.
            boolean point1 = link.end1 - link.start1 <= 1;
            boolean point2 = link.end2 - link.start2 <= 1;
            boolean ribbon = s.linkRibbon;

            // Filled areas read much lighter than thin strokes, so ribbons get their own opacity.
            int alpha = Math.min(link.color != null ? link.color.getAlpha() : 255,
                    ribbon ? s.linkRibbonAlpha : s.linkAlpha);
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));

            if (ribbon) {
                double[] e1 = ribbonEnd(a1s, a1e, s.linkRibbonMinDeg);
                double[] e2 = ribbonEnd(a2s, a2e, s.linkRibbonMinDeg);
                boolean wantStart = s.linkArrow == 1 || s.linkArrow == 3;
                boolean wantEnd = s.linkArrow == 2 || s.linkArrow == 3;
                // An interval end gets the flared head built into the polygon; a point end of a
                // mixed link gets a thin triangle on the curve instead.
                boolean bandArrowStart = wantStart && !point1;
                boolean bandArrowEnd = wantEnd && !point2;
                Path2D.Double path = ribbonBand(cx, cy, rEnd, rBez,
                        clamp01(s.linkBezierPurity), e1, e2, s.linkTwist,
                        bandArrowStart, bandArrowEnd, Math.max(3.0, s.linkArrowLength));
                g2.fill(path);
                if (s.linkArrow > 0 && (point1 || point2)) {
                    drawLinkArrows(g2, cx, cy, rEnd, e1, e2, point1, point2, s);
                }
                if (s.linkRibbonStroke && s.linkRibbonStrokeThickness > 0) {
                    // A tapered band is only a few pixels wide mid-span; the outline keeps that
                    // thin section from washing out against the white background.
                    int strokeAlpha = Math.min(255, alpha + 85);
                    g2.setStroke(new BasicStroke(s.linkRibbonStrokeThickness,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), strokeAlpha));
                    g2.draw(path);
                }
            } else {
                g2.setStroke(new BasicStroke(
                        link.thickness > 0 ? link.thickness : s.linkThickness,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (s.linkCrest > 0 && s.linkBezierRadius > 0) {
                    // crest: control points on the end radials make the curve leave the ideogram
                    // perpendicularly. One cubic stays tangent-continuous through the apex, while
                    // two joined quadratics leave a visible kink there.
                    double rCrest = rEnd - Math.abs(rBez - rEnd) * clamp01(s.linkCrest);
                    Point2D c1 = polar(cx, cy, rCrest, a1);
                    Point2D c2 = polar(cx, cy, rCrest, a2);
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(p1.getX(), p1.getY());
                    path.curveTo(c1.getX(), c1.getY(), c2.getX(), c2.getY(),
                            p2.getX(), p2.getY());
                    g2.draw(path);
                } else if (s.linkBezierRadius > 0) {
                    g2.draw(new QuadCurve2D.Double(
                            p1.getX(), p1.getY(), qx, qy, p2.getX(), p2.getY()));
                } else {
                    g2.draw(new Line2D.Double(p1, p2));
                }
                // Both ends are single points here, so the arrow heads are thin ones sitting on
                // the curve (zero-width base) rather than the wide band tips.
                if (s.linkArrow > 0) {
                    drawLinkArrows(g2, cx, cy, rEnd,
                            new double[]{a1, a1}, new double[]{a2, a2}, true, true, s);
                }
            }
            drawn++;
        }

        g2.setStroke(oldStroke);
        g2.setPaint(oldPaint);
        if (oldAA != null) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }

        if (drawn == 0) {
            log.info("Circular links: 0 of " + links.size()
                    + " visible (their chromosomes are not currently shown)");
        }
    }

    /** bp -> fraction of the whole circle, clamped inside one chromosome sector and its window. */
    private static double bpToFrac(long bp, ChrInfo c, double[] sector) {
        return c.fracIn(sector, bp);
    }

    /**
     * Angular extent of one ribbon end.
     * <ul>
     *   <li>a single-point anchor (start == end) collapses to zero width, so the band tapers to
     *       a line at that end;</li>
     *   <li>a real interval keeps its own arc width;</li>
     *   <li>an interval that is still sub-pixel on the ring is widened to {@code minDeg}, so it
     *       does not vanish.</li>
     * </ul>
     */
    /**
     * Angular extent of one ribbon end: the interval's own width, but never narrower than
     * {@code minDeg}. A 1 bp end used to collapse to zero width, which (combined with the
     * mid-span pinch below) is what made every link look like a needle instead of a band.
     */
    static double[] ribbonEnd(double a0, double a1, double minDeg) {
        double mid = (a0 + a1) / 2.0;
        double widthDeg = Math.abs(a1 - a0) * 360.0;
        double w = Math.max(widthDeg, Math.max(0.02, minDeg));
        double half = w / 720.0;
        return new double[]{mid - half, mid + half};
    }

    /**
     * Filled ribbon built the way circlize's {@code circos.link} builds it: TWO quadratic edges,
     * each with its own control point on the bisector of its own pair of ends, closed by straight
     * caps. The band therefore keeps a real width the whole way across — its width at each end is
     * that end's arc — instead of pinching to a needle at mid-span.
     *
     * @param e1       {start,end} angular extent of the first interval (already min-width adjusted)
     * @param e2       same for the second interval
     * @param twist    cross the edges (circlize {@code inverse = TRUE}): edge A runs
     *                 start1 -> end2 and edge B end1 -> start2
     */
    static Path2D.Double ribbonBand(double cx, double cy, double rEnd, double rBez,
                                    double purity, double[] e1, double[] e2, boolean twist,
                                    boolean arrowStart, boolean arrowEnd, double arrLen) {
        double a1s = e1[0], a1e = e1[1], a2s = e2[0], a2e = e2[1];
        double edgeAFrom = a1s, edgeATo = a2s;
        double edgeBFrom = a1e, edgeBTo = a2e;
        // An arrow end pulls the two EDGES back by arrLen and then runs the closing edge along the
        // ring arc: the band flares out into a head whose base is the whole arc at rEnd. That is
        // circos.link's d1x/d2x + arc.points(theta22, theta21, rou2) construction.
        double rFrom = arrowStart ? Math.max(4, rEnd - arrLen) : rEnd;
        double rTo = arrowEnd ? Math.max(4, rEnd - arrLen) : rEnd;

        Point2D A0 = polar(cx, cy, rFrom, edgeAFrom);
        Point2D A1 = polar(cx, cy, rTo, edgeATo);
        Point2D B0 = polar(cx, cy, rFrom, edgeBFrom);
        Point2D B1 = polar(cx, cy, rTo, edgeBTo);
        Point2D cA = linkControl(cx, cy, rEnd, rBez, edgeAFrom, edgeATo, purity);
        Point2D cB = linkControl(cx, cy, rEnd, rBez, edgeBFrom, edgeBTo, purity);
        if (twist) {
            // "Twist" pulls both edges toward the centreline, so the ribbon narrows to a waist at
            // mid-span and reopens. Swapping the edge pairing instead (a real 2D cross) would
            // close the band to a POINT there, which is exactly the hairline look we just fixed.
            final double k = 0.22;
            double mx = (cA.getX() + cB.getX()) / 2.0;
            double my = (cA.getY() + cB.getY()) / 2.0;
            cA = new Point2D.Double(cA.getX() + (mx - cA.getX()) * k,
                    cA.getY() + (my - cA.getY()) * k);
            cB = new Point2D.Double(cB.getX() + (mx - cB.getX()) * k,
                    cB.getY() + (my - cB.getY()) * k);
        }

        Path2D.Double path = new Path2D.Double();
        path.moveTo(A0.getX(), A0.getY());
        path.quadTo(cA.getX(), cA.getY(), A1.getX(), A1.getY());
        if (arrowEnd) {
            // circos.link "big.arrow": both edges stop at rEnd - arr.length and the polygon
            // closes on ONE point at the middle of the arc, so the band narrows into a tip that
            // touches the ring (a triangular head, base = band width).
            Point2D tip = polar(cx, cy, rEnd, (edgeATo + edgeBTo) / 2.0);
            path.lineTo(tip.getX(), tip.getY());
        }
        path.lineTo(B1.getX(), B1.getY());
        path.quadTo(cB.getX(), cB.getY(), B0.getX(), B0.getY());
        if (arrowStart) {
            Point2D tip = polar(cx, cy, rEnd, (edgeAFrom + edgeBFrom) / 2.0);
            path.lineTo(tip.getX(), tip.getY());
        }
        path.closePath();
        return path;
    }

    /**
     * Arrow heads on a link's ends, like circlize's {@code directional} + {@code arr.type =
     * "triangle"}: a triangle whose tip sits on the ring and whose base is {@code linkArrowWidth}
     * times the band width, pointing into the band. 0 = none (default), 1 = start, 2 = end,
     * 3 = both.
     */
    static void drawLinkArrows(Graphics2D g2, double cx, double cy, double rEnd,
                               double[] e1, double[] e2, boolean point1, boolean point2,
                               CircularSettings s) {
        if (s == null || s.linkArrow <= 0) return;
        double len = Math.max(3.0, s.linkArrowLength);
        double scale = Math.max(1.0, s.linkArrowWidth);
        boolean start = s.linkArrow == 1 || s.linkArrow == 3;
        boolean end = s.linkArrow == 2 || s.linkArrow == 3;
        // A point end gets a thin arrow on the curve; an interval end gets an arrow as wide as
        // the band, so the head reads as the band flaring into a tip.
        if (start) drawArrowHead(g2, cx, cy, rEnd, e1, point1 ? len * 0.65 : len,
                point1 ? 1.0 : scale);
        if (end) drawArrowHead(g2, cx, cy, rEnd, e2, point2 ? len * 0.65 : len,
                point2 ? 1.0 : scale);
    }

    /** One arrow head at the ring end of a band whose angular extent is {@code e}. */
    static void drawArrowHead(Graphics2D g2, double cx, double cy, double rEnd,
                              double[] e, double len, double scale) {
        double mid = (e[0] + e[1]) / 2.0;
        Point2D tip = polar(cx, cy, rEnd, mid);
        double half = Math.max(1.4, Math.abs(e[1] - e[0]) * Math.PI * rEnd) * scale;
        double ux = tip.getX() - cx, uy = tip.getY() - cy;
        double n = Math.hypot(ux, uy);
        if (n < 1e-6) return;
        ux /= n;
        uy /= n;                                   // outward radial unit vector
        double bx = tip.getX() - ux * len, by = tip.getY() - uy * len;
        double nx = -uy, ny = ux;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(tip.getX(), tip.getY());
        p.lineTo(bx + nx * half, by + ny * half);
        p.lineTo(bx - nx * half, by - ny * half);
        p.closePath();
        g2.fill(p);
    }

    /**
     * Quadratic control point for a link edge, following circlize's {@code getQuadraticPoints}:
     * radial position {@code rBez} at the midpoint of the shorter arc between the two ends, then
     * blended toward the chord midpoint by {@code bezier_radius_purity}.
     */
    static Point2D linkControl(double cx, double cy, double rEnd, double rBez,
                               double f1, double f2, double purity) {
        double d = f2 - f1;
        d -= Math.rint(d);                            // shortest way round: never cross the seam
        Point2D q = polar(cx, cy, rBez, f1 + d / 2.0);
        Point2D pa = polar(cx, cy, rEnd, f1);
        Point2D pb = polar(cx, cy, rEnd, f2);
        double mx = (pa.getX() + pb.getX()) / 2.0;
        double my = (pa.getY() + pb.getY()) / 2.0;
        return new Point2D.Double(q.getX() * purity + mx * (1 - purity),
                q.getY() * purity + my * (1 - purity));
    }

    private static Integer resolveSector(Map<String, Integer> index, String name) {
        if (name == null) return null;
        Integer i = index.get(name);
        if (i != null) return i;
        String alt = name.startsWith("chr") ? name.substring(3) : "chr" + name;
        i = index.get(alt);
        if (i != null) return i;
        return index.get(shortName(name));
    }

    private static void appendArc(Path2D.Double path, double cx, double cy, double r,
                                  double f0, double f1, int steps) {
        for (int i = 1; i <= steps; i++) {
            Point2D p = polar(cx, cy, r, f0 + (f1 - f0) * i / (double) steps);
            path.lineTo(p.getX(), p.getY());
        }
    }

    private static void appendQuad(Path2D.Double path, double cx, double cy, double r,
                                   double fTo, double qx, double qy) {
        Point2D to = polar(cx, cy, r, fTo);
        path.quadTo(qx, qy, to.getX(), to.getY());
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static boolean overlapsAny(List<double[]> rows, double a, double b) {
        for (double[] iv : rows) if (b > iv[0] && a < iv[1]) return true;
        return false;
    }

    private static void fillArc(Graphics2D g2, double cx, double cy, double rIn, double rOut,
                                double f0, double f1, Color color) {
        if (rOut <= rIn || f1 <= f0) return;
        g2.setColor(color);
        g2.fill(annularPath(cx, cy, rIn, rOut, f0, f1));
    }

    // ------------------------------------------------------------------
    //  Geometry + text
    // ------------------------------------------------------------------

    private static Point2D polar(double cx, double cy, double r, double frac) {
        double theta = frac * 2.0 * Math.PI;
        return new Point2D.Double(cx + r * Math.sin(theta), cy - r * Math.cos(theta));
    }

    private static Path2D annularPath(double cx, double cy, double rIn, double rOut,
                                      double fStart, double fEnd) {
        Path2D.Double path = new Path2D.Double();
        double spanDeg = (fEnd - fStart) * 360.0;
        int steps = Math.max(2, (int) Math.ceil(spanDeg / 0.5) + 1);
        double step = (fEnd - fStart) / steps;
        boolean first = true;
        for (int i = 0; i <= steps; i++) {
            Point2D p = polar(cx, cy, rOut, fStart + step * i);
            if (first) { path.moveTo(p.getX(), p.getY()); first = false; }
            else path.lineTo(p.getX(), p.getY());
        }
        for (int i = steps; i >= 0; i--) {
            Point2D p = polar(cx, cy, rIn, fStart + step * i);
            path.lineTo(p.getX(), p.getY());
        }
        path.closePath();
        return path;
    }

    private static void drawLabelText(Graphics2D g2, double cx, double cy, double r, double frac,
                                      String text, Font font, Color color, boolean tangential) {
        if (tangential) drawTangentialText(g2, cx, cy, r, frac, text, font, color);
        else drawRadialText(g2, cx, cy, r, frac, text, font, color);
    }

    private static void drawTangentialText(Graphics2D g2, double cx, double cy, double r, double frac,
                                           String text, Font font, Color color) {
        if (text == null || text.isEmpty()) return;
        Point2D p = polar(cx, cy, r, frac);
        double rotDeg = frac * 360.0;
        if (rotDeg > 90 && rotDeg < 270) rotDeg += 180;
        drawRotated(g2, p, rotDeg, text, font, color);
    }

    /** Horizontal call-out label: always reads left-to-right. On the right half the text starts
     *  at the anchor and extends right; on the left half it ends at the anchor and extends left. */
    private static void drawHorizontalLabel(Graphics2D g2, double cx, double cy, double r,
                                            double frac, String text, Font font, Color color) {
        if (text == null || text.isEmpty()) return;
        Point2D p = polar(cx, cy, r, frac);
        double deg = frac * 360.0;
        boolean rightSide = deg <= 90 || deg >= 270;
        FontMetrics fm = g2.getFontMetrics(font);
        int x = (int) Math.round(p.getX());
        int y = (int) Math.round(p.getY()) + fm.getAscent() / 2;
        if (!rightSide) x -= fm.stringWidth(text);
        g2.setFont(font);
        g2.setColor(color);
        g2.drawString(text, x, y);
    }

    private static void drawRadialText(Graphics2D g2, double cx, double cy, double r, double frac,
                                       String text, Font font, Color color) {
        if (text == null || text.isEmpty()) return;
        Point2D p = polar(cx, cy, r, frac);
        double rotDeg = frac * 360.0 - 90.0;
        double a = ((rotDeg % 360) + 360) % 360;
        if (a > 90 && a < 270) rotDeg += 180;
        drawRotated(g2, p, rotDeg, text, font, color);
    }

    private static void drawRotated(Graphics2D g2, Point2D p, double rotDeg, String text, Font font, Color color) {
        AffineTransform old = g2.getTransform();
        g2.translate(p.getX(), p.getY());
        g2.rotate(Math.toRadians(rotDeg));
        g2.setFont(font);
        g2.setColor(color);
        FontMetrics fm = g2.getFontMetrics(font);
        g2.drawString(text, -fm.stringWidth(text) / 2, fm.getAscent() / 2 - 1);
        g2.setTransform(old);
    }

    private static double arcLength(double r, double spanDeg) { return r * Math.toRadians(spanDeg); }

    private static void drawCenteredText(Graphics2D g2, String s, int x, int y, Font font, Color color) {
        if (s == null || s.isEmpty()) return;
        g2.setFont(font);
        g2.setColor(color);
        FontMetrics fm = g2.getFontMetrics(font);
        g2.drawString(s, x - fm.stringWidth(s) / 2, y + fm.getAscent() / 2 - 1);
    }

    private static void drawMessage(Graphics2D g2, int w, int h, String text) {
        g2.setColor(Color.GRAY);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (w - fm.stringWidth(text)) / 2, h / 2);
    }

    private static String shortName(String name) {
        if (name == null) return "";
        return name.startsWith("chr") ? name.substring(3) : name;
    }

    /** circlize cytoband.col palette (R/genomic_utils.R). */
    private static Color cytoColor(Cytoband cb) {
        switch (cb.getType()) {
            case 'c': return new Color(217, 47, 39);    // acen
            case 'n': return new Color(255, 255, 255);  // gneg
            case 'v': return new Color(220, 220, 220);  // gvar
            case 't': return new Color(100, 127, 164);  // stalk
            default:
                switch (cb.getStain()) {
                    case 100: return new Color(0, 0, 0);
                    case 75:  return new Color(130, 130, 130);
                    case 66:  return new Color(160, 160, 160);
                    case 50:  return new Color(200, 200, 200);
                    case 33:  return new Color(210, 210, 210);
                    case 25:  return new Color(200, 200, 200);
                    default:
                        int g = 255 - (int) (cb.getStain() * 2.55);
                        if (g < 0) g = 0;
                        return new Color(g, g, g);
                }
        }
    }

    static long niceStep(long length, int nTicks) {
        double raw = (double) length / Math.max(1, nTicks);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double nice;
        if (norm <= 1.0) nice = 1.0;
        else if (norm <= 2.0) nice = 2.0;
        else if (norm <= 5.0) nice = 5.0;
        else nice = 10.0;
        return Math.max(1, (long) (nice * mag));
    }

    static String mbLabel(long bp) {
        if (bp <= 0) return "0Mb";
        double mb = bp / 1_000_000.0;
        if (mb >= 10) return String.format("%.0fMb", mb);
        return String.format("%.1fMb", mb).replace(".0Mb", "Mb");
    }

    /** Format a tick label using a unit chosen from the tick interval (Mb / kb / bp). */
    static String tickLabel(long bp, long step) {
        if (step >= 1_000_000L) {
            double v = bp / 1_000_000.0;
            return (step % 1_000_000L == 0) ? String.format("%.0fMb", v) : String.format("%.1fMb", v);
        } else if (step >= 1_000L) {
            double v = bp / 1_000.0;
            return (step % 1_000L == 0) ? String.format("%.0fkb", v) : String.format("%.1fkb", v);
        } else {
            return bp + "bp";
        }
    }

    static String formatMb(long bp) { return mbLabel(bp); }

    public static class ChrInfo {
        final String name;
        final long length;
        final double weight;
        final List<Cytoband> cytobands;
        final Color color;
        /** Visible bp window inside this chromosome; the whole chromosome by default. */
        final double viewStart;
        final double viewEnd;
        /**
         * The real chromosome that supplies data/features/cytobands for this sector. Null means
         * the sector's own {@link #name} is the chromosome (normal "All"/single modes). In the
         * gene-list mode each sector is a gene interval but its data comes from the chromosome
         * the gene lives on.
         */
        final String realChr;

        public ChrInfo(String name, long length, List<Cytoband> cytobands, Color color) {
            this(name, length, length, cytobands, color, 0, length, null);
        }

        public ChrInfo(String name, long length, double weight, List<Cytoband> cytobands, Color color) {
            this(name, length, weight, cytobands, color, 0, length, null);
        }

        public ChrInfo(String name, long length, double weight, List<Cytoband> cytobands, Color color,
                       double viewStart, double viewEnd) {
            this(name, length, weight, cytobands, color, viewStart, viewEnd, null);
        }

        public ChrInfo(String name, long length, double weight, List<Cytoband> cytobands, Color color,
                       double viewStart, double viewEnd, String realChr) {
            this.name = name;
            this.length = length;
            this.weight = weight;
            this.cytobands = cytobands;
            this.color = color;
            this.realChr = realChr;
            double s = Math.max(0, Math.min(viewStart, length));
            double e = Math.max(s + 1, Math.min(viewEnd, length));
            this.viewStart = s;
            this.viewEnd = e;
        }

        /** Key used to look up data/features: the real chromosome, or the sector name. */
        String chrKey() {
            return realChr != null ? realChr : name;
        }

        /** bp length covered by the visible window. */
        double viewSpan() {
            return Math.max(1, viewEnd - viewStart);
        }

        /** bp -> 0..1 position inside the visible window (clamped). */
        double viewFrac(double bp) {
            double t = (bp - viewStart) / viewSpan();
            return t < 0 ? 0 : (t > 1 ? 1 : t);
        }

        /** bp -> absolute fraction of the full circle, given this chromosome's sector. */
        double fracIn(double[] sector, double bp) {
            return sector[0] + (sector[1] - sector[0]) * viewFrac(bp);
        }
    }
}
