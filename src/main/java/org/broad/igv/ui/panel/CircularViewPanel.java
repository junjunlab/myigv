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
import org.broad.igv.feature.Cytoband;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.Globals;
import org.broad.igv.util.ChromosomeColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import htsjdk.tribble.Feature;
import org.broad.igv.track.FeatureTrack;
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

    /** User-configurable rendering parameters. */
    public static class CircularSettings {
        public boolean showTicks = true;
        public boolean showTickLabels = true;
        public boolean tickLabelsOutside = true;   // orientation "out" (coords above the band)
        public double  tickSpacingMb = 0;           // 0 = auto (~3 ticks/chromosome)
        public int     tickLabelSize = 7;
        public boolean showChromosomeLabels = true;
        public int     chromosomeLabelSize = 12;
        public boolean labelTangential = true;       // tangential (circos label_tangential) vs radial
        public double  bandInner = 0.78;
        public double  bandOuter = 0.90;
        public double  gapDeg = 1.5;
        /** When true, fill every chromosome with chromosomeColor (ignore cytoband stripes). */
        public boolean solidColor = false;
        /** Solid chromosome fill colour (used when solidColor; also the default for species w/o karyotype). */
        public Color   chromosomeColor = new Color(93, 145, 190);
        /** Chromosomes to show in "All" mode; empty = show all. */
        public Set<String> shownChromosomes = new HashSet<>();

        public CircularSettings copy() {
            CircularSettings s = new CircularSettings();
            s.showTicks = showTicks; s.showTickLabels = showTickLabels;
            s.tickLabelsOutside = tickLabelsOutside; s.tickSpacingMb = tickSpacingMb;
            s.tickLabelSize = tickLabelSize; s.showChromosomeLabels = showChromosomeLabels;
            s.chromosomeLabelSize = chromosomeLabelSize; s.labelTangential = labelTangential;
            s.bandInner = bandInner; s.bandOuter = bandOuter; s.gapDeg = gapDeg;
            s.solidColor = solidColor; s.chromosomeColor = chromosomeColor;
            s.shownChromosomes = new HashSet<>(shownChromosomes);
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

    private BufferedImage ringImage;
    private String ringKey;
    private int lastGenomeId = 0;

    // ---- feature tracks drawn as concentric bands inside the ideogram ----
    private final List<Track> ringTracks = new ArrayList<>();
    private final Map<String, TrackRingConfig> ringConfig = new HashMap<>();
    private final Map<String, List<Feature>> featureCache = new ConcurrentHashMap<>();
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();
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
    }

    public CircularViewPanel() {
        setBackground(BG);
        setOpaque(true);
        IGVEventBus.getInstance().subscribe(FrameManager.ChangeEvent.class, this);
        IGVEventBus.getInstance().subscribe(org.broad.igv.event.RefreshEvent.class, this);
        addMouseWheelListener(this::handleWheel);
    }

    /** Current settings (for the settings dialog / callers). */
    public CircularSettings getSettings() { return settings; }

    /** Clear all per-chromosome zoom factors. */
    public void resetZoom() {
        zoom.clear();
        ringKey = null;
        repaint();
    }

    /** Force a redraw after settings changed (settings are read live during paint). */
    public void refresh() {
        ringKey = null;
        repaint();
    }

    @Override
    public void receiveEvent(IGVEvent event) { repaint(); }

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
            zoom.clear(); featureCache.clear(); pendingLoads.clear();
            lastGenomeId = gid; ringKey = null;
        }

        String chr = FrameManager.getDefaultFrame().getChrName();
        if (chr == null || chr.isEmpty()) chr = genome.getHomeChromosome();

        ensureFeaturesLoaded(genome);

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
                List<ChrInfo> chrs = buildAll(genome, zoom, settings);
                drawAll(ig, w, h, chrs, settings);
                drawTrackRings(ig, w, h, chrs, settings);
            } else {
                ChrInfo one = buildOne(genome, chr, settings);
                if (one == null) drawMessage(ig, w, h, "Chromosome not found: " + chr);
                else { drawSingle(ig, w, h, one, settings); drawTrackRingsSingle(ig, w, h, one, settings); }
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
    //  Ctrl + wheel -> zoom hovered chromosome sector ("All" mode)
    // ------------------------------------------------------------------

    private void handleWheel(MouseWheelEvent e) {
        if (!e.isControlDown()) return;
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) return;
        if (!Globals.CHR_ALL.equals(FrameManager.getDefaultFrame().getChrName())) return;

        String target = chromosomeAt(e.getX(), e.getY(), genome);
        if (target == null) return;

        double z = zoom.getOrDefault(target, 1.0);
        double f = e.getWheelRotation() < 0 ? 1.12 : 1.0 / 1.12;
        z = Math.max(0.25, Math.min(25.0, z * f));
        zoom.put(target, z);
        ringKey = null;
        repaint();
    }

    private String chromosomeAt(int x, int y, Genome genome) {
        List<ChrInfo> chrs = buildAll(genome, zoom, settings);
        if (chrs.isEmpty()) return null;
        int cx = getWidth() / 2, cy = getHeight() / 2;
        double R = Math.min(getWidth(), getHeight()) / 2.0 - MARGIN;
        if (R <= 0) return null;
        double rIn = R * settings.bandInner, rOut = R * settings.bandOuter;

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
            list.add(new ChrInfo(name, len, len * z, cyto, color));
        }
        return list;
    }

    static ChrInfo buildOne(Genome genome, String chr, CircularSettings s) {
        org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
        if (c == null) return null;
        List<Cytoband> cyto = (s != null && s.solidColor) ? null : genome.getCytobands(chr);
        Color color = (s != null && s.solidColor) ? s.chromosomeColor : pickColor(chr, cyto);
        return new ChrInfo(chr, c.getLength(), c.getLength(), cyto, color);
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
        double rIn = R * s.bandInner, rOut = R * s.bandOuter;

        List<double[]> sec = computeSectors(chrs, s.gapDeg);
        double chrLabelR = rOut + ((s.showTickLabels && s.tickLabelsOutside) ? 24 : 16);
        for (int i = 0; i < chrs.size(); i++) {
            ChrInfo c = chrs.get(i);
            double f0 = sec.get(i)[0], f1 = sec.get(i)[1], spanDeg = sec.get(i)[2];

            drawIdeogramBand(g2, cx, cy, rIn, rOut, f0, f1, c);
            drawBandTicks(g2, cx, cy, rIn, rOut, f0, f1, c.length, spanDeg, s);

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
        double rIn = R * s.bandInner, rOut = R * s.bandOuter;

        drawIdeogramBand(g2, cx, cy, rIn, rOut, 0.0, 1.0, chr);
        drawBandTicks(g2, cx, cy, rIn, rOut, 0.0, 1.0, chr.length, 360.0, s);

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
            double L = c.length, span = fEnd - fStart;
            for (Cytoband cb : c.cytobands) {
                double f0 = fStart + span * ((double) cb.getStart() / L);
                double f1 = fStart + span * ((double) cb.getEnd() / L);
                if (f1 <= f0) continue;
                g2.setColor(cytoColor(cb));
                g2.fill(annularPath(cx, cy, rIn, rOut, f0, f1));
            }
            g2.setColor(new Color(150, 150, 150));
            g2.setStroke(new BasicStroke(0.5f));
            for (Cytoband cb : c.cytobands) {
                double f = fStart + span * ((double) cb.getEnd() / L);
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
                                      double fStart, double fEnd, long length, double spanDeg,
                                      CircularSettings s) {
        if (!s.showTicks && !s.showTickLabels) return;
        if (spanDeg < 1.0) return;

        // Tick interval follows the available arc: a zoomed chromosome (large spanDeg) gets
        // finer ticks, a tiny one gets coarser — so labels stay ~70px apart ("reasonable ruler").
        long step;
        if (s.tickSpacingMb > 0) {
            step = Math.max(1, Math.round(s.tickSpacingMb * 1_000_000.0));
        } else {
            double midR = (rIn + rOut) / 2.0;
            double arcPx = arcLength(midR, spanDeg);
            int targetTicks = Math.max(2, (int) Math.round(arcPx / 70.0));
            step = niceStep(length, targetTicks);
        }
        double span = fEnd - fStart;
        Font tickFont = new Font("SansSerif", Font.PLAIN, s.tickLabelSize);
        // angular spacing between adjacent ticks (used to decide if labels fit)
        double stepDeg = spanDeg * ((double) step / Math.max(1, length));

        for (long p = 0; p <= length; p += step) {
            double f = fStart + span * ((double) p / length);

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
        if (tracks != null) ringTracks.addAll(tracks);
        ringKey = null;
        repaint();
    }

    /** Per-track ring band config (created with defaults on first use). */
    public TrackRingConfig getRingConfig(String trackName) {
        return ringConfig.computeIfAbsent(trackName, k -> new TrackRingConfig());
    }

    public void invalidateRing() { ringKey = null; repaint(); }

    /** Load features for all ring feature-tracks / chromosomes in the background (cached). */
    private void ensureFeaturesLoaded(Genome genome) {
        if (genome == null || ringTracks.isEmpty()) return;
        for (Track t : ringTracks) {
            if (!(t instanceof FeatureTrack)) continue;
            FeatureTrack ft = (FeatureTrack) t;
            for (String chr : genome.getLongChromosomeNames()) {
                org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
                if (c == null) continue;
                if (!settings.shownChromosomes.isEmpty() && !settings.shownChromosomes.contains(chr)) continue;
                final String key = ft.getName() + "|" + chr;
                if (featureCache.containsKey(key) || !pendingLoads.add(key)) continue;
                final int len = c.getLength();
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
                    featureCache.put(key, list);
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
        double cursor = R * s.bandInner - 5;
        List<double[]> sec = computeSectors(chrs, s.gapDeg);
        for (Track t : ringTracks) {
            if (!(t instanceof FeatureTrack)) continue;
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandOuter = cursor;
            double bandInner = bandOuter - cfg.heightPx;
            if (bandInner < 24) break;
            drawFeatures(g2, cx, cy, bandInner, bandOuter, chrs, sec, (FeatureTrack) t, cfg);
            cursor = bandInner - 5;
        }
    }

    private void drawTrackRingsSingle(Graphics2D g2, int w, int h, ChrInfo chr, CircularSettings s) {
        if (ringTracks.isEmpty()) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double cursor = R * s.bandInner - 5;
        List<ChrInfo> chrs = Collections.singletonList(chr);
        List<double[]> sec = new ArrayList<>();
        sec.add(new double[]{0.0, 1.0, 360.0});
        for (Track t : ringTracks) {
            if (!(t instanceof FeatureTrack)) continue;
            TrackRingConfig cfg = getRingConfig(t.getName());
            double bandOuter = cursor, bandInner = bandOuter - cfg.heightPx;
            if (bandInner < 24) break;
            drawFeatures(g2, cx, cy, bandInner, bandOuter, chrs, sec, (FeatureTrack) t, cfg);
            cursor = bandInner - 5;
        }
    }

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
                double thick = Math.min(3.0, bandH * 0.6);
                for (Feature f : list) {
                    double a = f0 + span * clamp01(f.getStart() / len);
                    double b = f0 + span * clamp01(f.getEnd() / len);
                    if (b <= a) b = a + 1e-7;
                    fillArc(g2, cx, cy, rr - thick / 2, rr + thick / 2, a, b, cfg.color);
                }
            } else {
                List<List<double[]>> rows = new ArrayList<>();
                for (Feature f : list) {
                    double a = f0 + span * clamp01(f.getStart() / len);
                    double b = f0 + span * clamp01(f.getEnd() / len);
                    if (b <= a) b = a + 1e-7;
                    int r = 0;
                    while (r < rows.size() && overlapsAny(rows.get(r), a, b)) r++;
                    if (r == rows.size()) {
                        if (rows.size() >= maxRows) continue;   // band full, skip extra features
                        rows.add(new ArrayList<>());
                    }
                    rows.get(r).add(new double[]{a, b});
                    double rr = bandInner + (r + 0.5) * rowH;
                    fillArc(g2, cx, cy, rr - rowH * 0.42, rr + rowH * 0.42, a, b, cfg.color);
                }
            }
        }
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

        public ChrInfo(String name, long length, List<Cytoband> cytobands, Color color) {
            this(name, length, length, cytobands, color);
        }

        public ChrInfo(String name, long length, double weight, List<Cytoband> cytobands, Color color) {
            this.name = name;
            this.length = length;
            this.weight = weight;
            this.cytobands = cytobands;
            this.color = color;
        }
    }
}
