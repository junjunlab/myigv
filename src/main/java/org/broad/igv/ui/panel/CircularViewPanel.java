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
import org.broad.igv.feature.Strand;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.Globals;
import org.broad.igv.util.ChromosomeColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
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
        public boolean showTicks = true;
        public boolean showTickLabels = true;
        public boolean tickLabelsOutside = true;   // orientation "out" (coords above the band)
        public double  tickSpacingMb = 0;           // 0 = auto (~3 ticks/chromosome)
        public int     tickLabelSize = 7;
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
        public double  linkRibbonMinDeg = 0.15;
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
        /** Cap on the number of links drawn per frame (Circos {@code record_limit}). */
        public int     linkRecordLimit = 5000;
        public boolean linkColorByChromosome = false;
        /** Anchor links inside the stacked ring bands instead of on top of them. */
        public boolean linkEndsInsideTracks = true;

        public CircularSettings copy() {
            CircularSettings s = new CircularSettings();
            s.showTicks = showTicks; s.showTickLabels = showTickLabels;
            s.tickLabelsOutside = tickLabelsOutside; s.tickSpacingMb = tickSpacingMb;
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
            s.linkColorByChromosome = linkColorByChromosome;
            s.linkEndsInsideTracks = linkEndsInsideTracks;
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
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();
    private final Map<String, float[]> dataCache = new ConcurrentHashMap<>();   // bigWig/bedGraph bins
    private final Set<String> dataPending = ConcurrentHashMap.newKeySet();
    private static final int DATA_BINS = 300;                                    // bins per chromosome
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
        public boolean expanded = false;
        public Color color = new Color(60, 90, 170);
        /** Data-track rendering: "Bar" (default, IGV wig style), "Point", "Line", "Heatmap". */
        public String graphType = "Bar";
        /** Y-axis range: false = per-chromosome scale (default), true = global across all chromosomes. */
        public boolean globalYScale = false;
        /** Non-null = this track shares a Y range with tracks of the same group id (group scale). */
        public String yGroup = null;
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
    private final List<RingLink> fileLinks = new ArrayList<>();    // from "Load links…"
    private final List<RingLink> trackLinks = new ArrayList<>();   // auto-collected from InteractionTracks

    /** Replace the user-loaded links (from a Circos / BEDPE file). */
    public void setRingLinks(List<RingLink> links) {
        fileLinks.clear();
        if (links != null) fileLinks.addAll(links);
        invalidateRing();
        log.info("Circular links: " + fileLinks.size() + " user link(s)");
    }

    /** Replace the links auto-collected from loaded interaction tracks. */
    public void setTrackLinks(List<RingLink> links) {
        trackLinks.clear();
        if (links != null) trackLinks.addAll(links);
        invalidateRing();
    }

    public void clearRingLinks() {
        fileLinks.clear();
        trackLinks.clear();
        invalidateRing();
    }

    public int getRingLinkCount() {
        return fileLinks.size() + trackLinks.size();
    }

    private List<RingLink> allLinks() {
        if (trackLinks.isEmpty()) return new ArrayList<>(fileLinks);
        List<RingLink> all = new ArrayList<>(fileLinks.size() + trackLinks.size());
        all.addAll(fileLinks);
        all.addAll(trackLinks);
        return all;
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
        ringKey = null;
        repaint();
    }

    /** Force a redraw after settings changed (settings are read live during paint). */
    public void refresh() {
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
            zoom.clear(); rangeZoom.clear(); featureCache.clear(); pendingLoads.clear();
            loadAttempts.clear(); lastAttemptMs.clear(); trackLoaded.clear();
            dataCache.clear(); dataPending.clear();
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

        String key = gid + "|" + chr + "|" + bw + "x" + bh;
        if (!key.equals(ringKey) || ringImage == null) {
            ringImage = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = ringImage.createGraphics();
            ig.scale(sx, sy);   // draw in logical coords, mapped to device pixels
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            ig.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            if (Globals.CHR_ALL.equals(chr)) {
                List<ChrInfo> chrs = buildAll(genome, zoom, rangeZoom, settings);
                drawAll(ig, w, h, chrs, settings);
                drawTrackRings(ig, w, h, chrs, settings);
                // Links are an independent z-layer drawn last, like Circos, so they sit on top.
                drawLinks(ig, w, h, chrs, allLinks(), settings, linkAnchorRadius(w, h, settings));
            } else {
                ChrInfo one = buildOne(genome, chr, rangeZoom, settings);
                if (one == null) drawMessage(ig, w, h, "Chromosome not found: " + chr);
                else {
                    drawSingle(ig, w, h, one, settings);
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
            ringKey = key;
        }
        // blit the device-resolution image back at logical size; the panel transform
        // scales it to exactly bw x bh device pixels -> 1:1, sharp.
        g2.drawImage(ringImage, 0, 0, w, h, null);
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
            zoomRange(one, frac, zoomIn);
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

        List<double[]> sec = computeSectors(chrs, settings.gapDeg);
        for (int i = 0; i < chrs.size(); i++) {
            if (frac >= sec.get(i)[0] && frac < sec.get(i)[1]) return chrs.get(i).name;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Settings dialog  (opened from the in-panel toolbar)
    // ------------------------------------------------------------------

    public void showSettingsDialog(Component parent) {
        final CircularSettings s = settings;

        JCheckBox cbTicks = new JCheckBox("Show tick marks", s.showTicks);
        JCheckBox cbTickLabels = new JCheckBox("Show tick labels", s.showTickLabels);
        JCheckBox cbChrLabels = new JCheckBox("Show chromosome labels", s.showChromosomeLabels);
        JComboBox<String> cbPos = new JComboBox<>(new String[]{"Outside (above the band)", "Inside (below the band)"});
        cbPos.setSelectedIndex(s.tickLabelsOutside ? 0 : 1);
        JSpinner spSpace = new JSpinner(new SpinnerNumberModel(s.tickSpacingMb, 0.0, 5000.0, 10.0));
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
        p.add(cbTicks);
        p.add(cbTickLabels);
        p.add(cbChrLabels);
        p.add(row("Tick label position:", cbPos));
        p.add(row("Tick spacing (Mb, 0 = auto):", spSpace));
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

        s.showTicks = cbTicks.isSelected();
        s.showTickLabels = cbTickLabels.isSelected();
        s.showChromosomeLabels = cbChrLabels.isSelected();
        s.tickLabelsOutside = cbPos.getSelectedIndex() == 0;
        s.tickSpacingMb = ((Number) spSpace.getValue()).doubleValue();
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

    // ------------------------------------------------------------------
    //  "All" — cytoband ideogram arc per chromosome
    // ------------------------------------------------------------------

    private static void drawAll(Graphics2D g2, int w, int h, List<ChrInfo> chrs, CircularSettings s) {
        if (chrs == null || chrs.isEmpty()) { drawMessage(g2, w, h, "Circular view — no chromosomes"); return; }
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rOut = R * s.bandOuter;
        double rIn = Math.max(R * 0.2, rOut - s.chromosomeHeightPx);

        List<double[]> sec = computeSectors(chrs, s.gapDeg);
        double chrLabelR = rOut + ((s.showTickLabels && s.tickLabelsOutside) ? 24 : 16);
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

        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        String id = genome != null ? genome.getDisplayName() : "";
        drawCenteredText(g2, id, cx, cy, new Font("SansSerif", Font.PLAIN, 15), Color.DARK_GRAY);
    }

    private static void drawSingle(Graphics2D g2, int w, int h, ChrInfo chr, CircularSettings s) {
        if (chr == null || chr.length <= 0) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rOut = R * s.bandOuter;
        double rIn = Math.max(R * 0.2, rOut - s.chromosomeHeightPx);

        drawIdeogramBand(g2, cx, cy, rIn, rOut, 0.0, 1.0, chr);
        drawBandTicks(g2, cx, cy, rIn, rOut, 0.0, 1.0, chr, 360.0, s);

        drawCenteredText(g2, shortName(chr.name), cx, cy,
                new Font("SansSerif", Font.BOLD, Math.max(10, s.chromosomeLabelSize + 6)), Color.DARK_GRAY);
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

    /** Tick lines + Mb labels, parameterised like Circos ticks (orientation, spacing, size). */
    private static void drawBandTicks(Graphics2D g2, double cx, double cy,
                                      double rIn, double rOut,
                                      double fStart, double fEnd, ChrInfo c, double spanDeg,
                                      CircularSettings s) {
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
        // angular spacing between adjacent ticks (used to decide if labels fit)
        double stepDeg = spanDeg * ((double) step / viewLen);

        long first = (long) (Math.ceil(c.viewStart / (double) step) * step);
        for (long p = first; p <= (long) c.viewEnd; p += step) {
            if (p < c.viewStart) continue;
            double f = fStart + span * c.viewFrac(p);

            if (s.showTicks) {
                Point2D a = polar(cx, cy, rIn - 2, f), b = polar(cx, cy, rOut + 2, f);
                g2.setColor(new Color(60, 60, 60));
                g2.setStroke(new BasicStroke(0.6f));
                g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            }

            if (s.showTickLabels) {
                // Coordinate labels are ALWAYS tangential — radial coordinate text crosses the
                // band and collides with neighbours. The "Radial" option only rotates the
                // chromosome numbers (see drawAll), which is the standard circular-genome look.
                double labelR = s.tickLabelsOutside ? (rOut + 8) : (rIn - 8);
                String txt = tickLabel(p, step);            // Mb / kb / bp by interval
                int tw = g2.getFontMetrics(tickFont).stringWidth(txt);
                if (arcLength(labelR, stepDeg) < tw + 6) continue;   // adjacent labels would collide
                drawTangentialText(g2, cx, cy, labelR, f, txt, tickFont, new Color(70, 70, 70));
            }
        }
    }

    // ------------------------------------------------------------------
    //  Feature tracks as concentric bands inside the ideogram
    // ------------------------------------------------------------------

    /** Tracks to render as concentric feature bands (set by IGVContentPane from the Data Tracks list). */
    public void setRingTracks(List<Track> tracks) {
        ringTracks.clear();
        if (tracks != null) {
            for (Track t : tracks) {
                if (isRingTrackHidden(t)) continue;
                if (t instanceof FeatureTrack || t instanceof DataTrack) ringTracks.add(t);  // order == top->bottom == outer->inner
            }
        }
        log.info("Circular ring tracks: " + ringTracks.size());
        ringKey = null;
        repaint();
    }

    /** Remove a track from the ring (e.g. the RefSeq gene track) without unloading it. */
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
        double cursor = Math.max(R * 0.2, R * s.bandOuter - s.chromosomeHeightPx) - 5;
        for (Track t : ringTracks) {
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandInner = cursor - cfg.heightPx;
            if (bandInner < 24) break;
            cursor = bandInner - 5;
        }
        return cursor > 24 ? cursor : -1;
    }

    /** Per-track ring band config (created with defaults on first use). */
    public TrackRingConfig getRingConfig(String trackName) {
        return ringConfig.computeIfAbsent(trackName, k -> new TrackRingConfig());
    }

    public void invalidateRing() { ringKey = null; repaint(); }

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
        if (ringTracks.isEmpty() || chrs == null || chrs.isEmpty()) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double cursor = Math.max(R * 0.2, R * s.bandOuter - s.chromosomeHeightPx) - 5;
        List<double[]> sec = computeSectors(chrs, s.gapDeg);
        float[] globalShared = s.ySharedAcrossTracks ? computeSharedYRange(chrs) : null;
        java.util.Map<String, float[]> groups = computeGroupRanges(chrs);
        for (Track t : ringTracks) {                       // order == top->bottom == outer->inner
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandOuter = cursor;
            double bandInner = bandOuter - cfg.heightPx;
            if (bandInner < 24) break;
            float[] shared = cfg.yGroup != null ? groups.get(cfg.yGroup) : globalShared;
            drawOneRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, t, cfg, shared);
            cursor = bandInner - 5;
        }
    }

    private void drawTrackRingsSingle(Graphics2D g2, int w, int h, ChrInfo chr, CircularSettings s) {
        if (ringTracks.isEmpty()) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double cursor = Math.max(R * 0.2, R * s.bandOuter - s.chromosomeHeightPx) - 5;
        List<ChrInfo> chrs = Collections.singletonList(chr);
        List<double[]> sec = new ArrayList<>();
        sec.add(new double[]{0.0, 1.0, 360.0});
        float[] globalShared = s.ySharedAcrossTracks ? computeSharedYRange(chrs) : null;
        java.util.Map<String, float[]> groups = computeGroupRanges(chrs);
        for (Track t : ringTracks) {
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandOuter = cursor, bandInner = bandOuter - cfg.heightPx;
            if (bandInner < 24) break;
            float[] shared = cfg.yGroup != null ? groups.get(cfg.yGroup) : globalShared;
            drawOneRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, t, cfg, shared);
            cursor = bandInner - 5;
        }
    }

    /** Dispatch one track to the right ring renderer (BED/feature -> marks; bigWig/bedGraph -> heatmap). */
    private void drawOneRing(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                             List<ChrInfo> chrs, List<double[]> sec, Track t, TrackRingConfig cfg, float[] shared) {
        if (t instanceof FeatureTrack) {
            drawFeatures(g2, cx, cy, bandInner, bandOuter, chrs, sec, (FeatureTrack) t, cfg);
        } else if (t instanceof DataTrack) {
            drawDataRing(g2, cx, cy, bandInner, bandOuter, chrs, sec, (DataTrack) t, cfg, shared);
        }
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

    /** Shared Y range across ALL data tracks and the given chromosomes (group scale). */
    private float[] computeSharedYRange(List<ChrInfo> chrs) {
        float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
        for (Track t : ringTracks) {
            if (!(t instanceof DataTrack)) continue;
            for (ChrInfo c : chrs) {
                float[] b = dataCache.get(t.getName() + "|" + c.name);
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
                float[] b = dataCache.get(t.getName() + "|" + c.name);
                if (b == null) continue;
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < r[0]) r[0] = v; if (v > r[1]) r[1] = v; }
            }
        }
        return map;
    }

    /** Draw a continuous data track (bigWig/bedGraph) as Bar / Point / Line / Heatmap. */
    private void drawDataRing(Graphics2D g2, double cx, double cy, double bandInner, double bandOuter,
                              List<ChrInfo> chrs, List<double[]> sec, Track track, TrackRingConfig cfg, float[] shared) {
        double bandH = bandOuter - bandInner;
        String type = cfg.graphType == null ? "Bar" : cfg.graphType;
        boolean global = cfg.globalYScale || shared != null;

        // Global Y-range: one min/max across all displayed chromosomes (optional).
        float gmn = Float.POSITIVE_INFINITY, gmx = Float.NEGATIVE_INFINITY;
        if (shared != null) {
            gmn = shared[0];
            gmx = shared[1];
        } else if (global) {
            for (ChrInfo c : chrs) {
                float[] b = dataCache.get(track.getName() + "|" + c.name);
                if (b == null) continue;
                for (float v : b) if (!Float.isNaN(v) && !Float.isInfinite(v)) { if (v < gmn) gmn = v; if (v > gmx) gmx = v; }
            }
            if (gmn == Float.POSITIVE_INFINITY) return;
        }

        for (int i = 0; i < chrs.size(); i++) {
            float[] b = dataCache.get(track.getName() + "|" + chrs.get(i).name);
            if (b == null) continue;
            ChrInfo c = chrs.get(i);
            double len = Math.max(1, c.length);
            double f0 = sec.get(i)[0], span = sec.get(i)[1] - f0;
            int n = b.length;

            // Y range: per-chromosome (default) or global.
            float mn, mx;
            if (global) { mn = gmn; mx = gmx; }
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
                    double a = f0 + span * c.viewFrac(len * (k + 0.5) / n);
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
                double binStart = len * k / n, binEnd = len * (k + 1) / n;
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
        drawFeatureBand(g2, cx, cy, bandInner, bandOuter, chrs, sec, cfg,
                name -> featureCache.get(ft.getName() + "|" + name));
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
                    double rr = bandInner + (r + 0.5) * rowH;
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
                        Math.min(2.4, Math.max(1.2, availH * 0.4)), color);
            }
            return;
        }

        // Faithful polar port of IGVFeatureRenderer.drawExons(): a thin intron spine, each exon
        // split into UTR (thin) and CDS (thick) via its coding boundaries, and white directional
        // chevrons along the coding strand. Thickness scales with the band so the structure is
        // legible once the gene is wide enough to warrant it.
        double cdsH = Math.max(2.0, availH * 0.46);     // IGV blockHeight (14) -> thick CDS
        double utrH = Math.max(1.2, availH * 0.23);     // IGV NON_CODING_HEIGHT (8) -> thin UTR
        double spineH = Math.max(0.4, availH * 0.10);
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

        drawStrandArrows(g2, cx, cy, rMid, a, b, strandDir(bf),
                Math.min(8.0, Math.max(2.0, availH * 0.34)), color);
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

    /** Chevrons along the gene showing the direction of transcription. */
    private static void drawStrandArrows(Graphics2D g2, double cx, double cy, double r,
                                         double a, double b, int dir, double size, Color color) {
        if (dir == 0 || size <= 0) return;
        double arcPx = arcLength(r, (b - a) * 360.0);
        int n = Math.max(1, (int) Math.round(arcPx / 26.0));
        g2.setColor(contrastColor(color));
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.5 : (i + 0.5) / n;
            double f = a + (b - a) * t;
            double theta = f * 2 * Math.PI;
            // Tangent for increasing fraction (clockwise): (cos, sin).
            double tx = Math.cos(theta) * dir, ty = Math.sin(theta) * dir;
            double nx = -ty, ny = tx;
            double px = cx + r * Math.sin(theta), py = cy - r * Math.cos(theta);
            Polygon tri = new Polygon();
            tri.addPoint((int) Math.round(px + tx * size), (int) Math.round(py + ty * size));
            tri.addPoint((int) Math.round(px - tx * size * 0.55 + nx * size * 0.8),
                    (int) Math.round(py - ty * size * 0.55 + ny * size * 0.8));
            tri.addPoint((int) Math.round(px - tx * size * 0.55 - nx * size * 0.8),
                    (int) Math.round(py - ty * size * 0.55 - ny * size * 0.8));
            g2.fillPolygon(tri);
        }
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

        double rOut = R * s.bandOuter;
        double rIn = Math.max(R * 0.2, rOut - s.chromosomeHeightPx);
        double baseR = innerLimitR > 0 ? innerLimitR : rIn;
        double rEnd = Math.max(8.0, baseR * s.linkRadius);
        double rBez = rEnd * s.linkBezierRadius;

        List<double[]> sec = computeSectors(chrs, s.gapDeg);

        // Chromosome name -> sector index, tolerant of "chr" prefixes and genome aliases.
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

            // A ribbon's width at each end is that interval's OWN width: a single-point end
            // collapses to a point (the band tapers to a line there), an interval end keeps its
            // arc width. Both ends being points means a band would have no area at all, so we
            // fall back to a stroked chord.
            boolean point1 = link.end1 - link.start1 <= 1;
            boolean point2 = link.end2 - link.start2 <= 1;
            boolean ribbon = s.linkRibbon && !(point1 && point2);

            // Filled areas read much lighter than thin strokes, so ribbons get their own opacity.
            int alpha = Math.min(link.color != null ? link.color.getAlpha() : 255,
                    ribbon ? s.linkRibbonAlpha : s.linkAlpha);
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));

            if (ribbon) {
                double[] e1 = ribbonEnd(a1s, a1e, point1, s.linkRibbonMinDeg);
                double[] e2 = ribbonEnd(a2s, a2e, point2, s.linkRibbonMinDeg);

                // Building the band from two beziers that share one control point makes both
                // edges converge on that point, so the ribbon pinches to a needle at its apex.
                // Instead sample the centreline and offset it by a linearly interpolated
                // half-width: the silhouette stays a smooth wedge (an arc), and a point end
                // tapers to a small rounded tip rather than a spike.
                Path2D.Double path = ribbonBand(cx, cy, rEnd, e1[0], e1[1], e2[0], e2[1],
                        qx, qy, s.linkTwist);
                g2.fill(path);
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
    private static double[] ribbonEnd(double a0, double a1, boolean point, double minDeg) {
        double mid = (a0 + a1) / 2.0;
        if (point) {
            return new double[]{mid, mid};
        }
        double widthDeg = (a1 - a0) * 360.0;
        if (minDeg > 0 && widthDeg < minDeg) {
            double half = minDeg / 720.0;
            return new double[]{mid - half, mid + half};
        }
        return new double[]{a0, a1};
    }

    /**
     * Filled ribbon with a linearly tapering width: sample the quadratic centreline from the
     * first interval's midpoint to the second's and offset each sample by an interpolated
     * half-width. Unlike two beziers sharing one control point, this never pinches to a needle.
     *
     * @param a1s,a1e angular extent of the first interval
     * @param a2s,a2e angular extent of the second interval
     * @param qx,qy   bezier control point (already purity-adjusted)
     */
    private static Path2D.Double ribbonBand(double cx, double cy, double rEnd,
                                            double a1s, double a1e,
                                            double a2s, double a2e,
                                            double qx, double qy,
                                            boolean twist) {
        Point2D p0 = polar(cx, cy, rEnd, (a1s + a1e) / 2.0);
        Point2D p2 = polar(cx, cy, rEnd, (a2s + a2e) / 2.0);

        // Half-width in pixels = half the arc length the interval covers at the end radius.
        // A floor of 0.5 px keeps a single-point end a rounded tip instead of a spike.
        double h1 = Math.max(0.5, Math.abs(a1e - a1s) * Math.PI * rEnd);
        double h2 = Math.max(0.5, Math.abs(a2e - a2s) * Math.PI * rEnd);

        final int steps = 64;
        double[] lx = new double[steps + 1], ly = new double[steps + 1];
        double[] rx = new double[steps + 1], ry = new double[steps + 1];

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double mt = 1 - t;
            double px = mt * mt * p0.getX() + 2 * mt * t * qx + t * t * p2.getX();
            double py = mt * mt * p0.getY() + 2 * mt * t * qy + t * t * p2.getY();
            double dx = 2 * mt * (qx - p0.getX()) + 2 * t * (p2.getX() - qx);
            double dy = 2 * mt * (qy - p0.getY()) + 2 * t * (p2.getY() - qy);
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) {
                dx = p2.getX() - p0.getX();
                dy = p2.getY() - p0.getY();
                len = Math.max(1e-9, Math.hypot(dx, dy));
            }
            double nx = -dy / len, ny = dx / len;
            double h = h1 + (h2 - h1) * t;
            // A twist rotates the cross-section through 180 degrees along the length: the band
            // narrows to a waist at mid-span and reopens on the other side. That is a smooth
            // twist, unlike swapping the four corners, which leaves a spike.
            double off = twist ? h * Math.cos(Math.PI * t) : h;
            lx[i] = px + nx * off; ly[i] = py + ny * off;
            rx[i] = px - nx * off; ry[i] = py - ny * off;
        }

        Path2D.Double path = new Path2D.Double();
        path.moveTo(lx[0], ly[0]);
        for (int i = 1; i <= steps; i++) path.lineTo(lx[i], ly[i]);
        for (int i = steps; i >= 0; i--) path.lineTo(rx[i], ry[i]);
        path.closePath();
        return path;
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

        public ChrInfo(String name, long length, List<Cytoband> cytobands, Color color) {
            this(name, length, length, cytobands, color);
        }

        public ChrInfo(String name, long length, double weight, List<Cytoband> cytobands, Color color) {
            this(name, length, weight, cytobands, color, 0, length);
        }

        public ChrInfo(String name, long length, double weight, List<Cytoband> cytobands, Color color,
                       double viewStart, double viewEnd) {
            this.name = name;
            this.length = length;
            this.weight = weight;
            this.cytobands = cytobands;
            this.color = color;
            double s = Math.max(0, Math.min(viewStart, length));
            double e = Math.max(s + 1, Math.min(viewEnd, length));
            this.viewStart = s;
            this.viewEnd = e;
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
