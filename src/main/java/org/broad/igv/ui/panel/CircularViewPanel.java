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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        public CircularSettings copy() {
            CircularSettings s = new CircularSettings();
            s.showTicks = showTicks; s.showTickLabels = showTickLabels;
            s.tickLabelsOutside = tickLabelsOutside; s.tickSpacingMb = tickSpacingMb;
            s.tickLabelSize = tickLabelSize; s.showChromosomeLabels = showChromosomeLabels;
            s.chromosomeLabelSize = chromosomeLabelSize; s.labelTangential = labelTangential;
            s.bandInner = bandInner; s.bandOuter = bandOuter; s.gapDeg = gapDeg;
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
        if (gid != lastGenomeId) { zoom.clear(); lastGenomeId = gid; ringKey = null; }

        String chr = FrameManager.getDefaultFrame().getChrName();
        if (chr == null || chr.isEmpty()) chr = genome.getHomeChromosome();

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
                drawAll(ig, w, h, buildAll(genome, zoom), settings);
            } else {
                ChrInfo one = buildOne(genome, chr);
                if (one == null) drawMessage(ig, w, h, "Chromosome not found: " + chr);
                else drawSingle(ig, w, h, one, settings);
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
        List<ChrInfo> chrs = buildAll(genome, zoom);
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
        p.add(row("Label orientation:", cbOrient));
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

    static List<ChrInfo> buildAll(Genome genome, Map<String, Double> zoomMap) {
        List<ChrInfo> list = new ArrayList<>();
        for (String name : genome.getLongChromosomeNames()) {
            org.broad.igv.feature.Chromosome c = genome.getChromosome(name);
            if (c == null) continue;
            long len = c.getLength();
            if (len <= 0) continue;
            double z = zoomMap != null ? zoomMap.getOrDefault(name, 1.0) : 1.0;
            list.add(new ChrInfo(name, len, len * z, genome.getCytobands(name),
                    ChromosomeColors.getColor(name)));
        }
        return list;
    }

    static ChrInfo buildOne(Genome genome, String chr) {
        org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
        if (c == null) return null;
        return new ChrInfo(chr, c.getLength(), c.getLength(), genome.getCytobands(chr),
                ChromosomeColors.getColor(chr));
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

    static List<double[]> computeSectors(List<ChrInfo> chrs, double gapDeg) {
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
                new Font("SansSerif", Font.BOLD, 18), Color.DARK_GRAY);
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
        long step = s.tickStepBp(length);
        double span = fEnd - fStart;
        Font tickFont = new Font("SansSerif", Font.PLAIN, s.tickLabelSize);

        for (long p = 0; p <= length; p += step) {
            double f = fStart + span * ((double) p / length);

            if (s.showTicks) {
                Point2D a = polar(cx, cy, rIn - 2, f), b = polar(cx, cy, rOut + 2, f);
                g2.setColor(new Color(60, 60, 60));
                g2.setStroke(new BasicStroke(0.6f));
                g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            }

            if (s.showTickLabels) {
                double labelR = s.tickLabelsOutside ? (rOut + 8) : (rIn - 8);
                String txt = mbLabel(p);
                int tw = g2.getFontMetrics(tickFont).stringWidth(txt);
                if (arcLength(labelR, spanDeg) < tw + 10) continue;
                drawLabelText(g2, cx, cy, labelR, f, txt, tickFont, new Color(70, 70, 70), s.labelTangential);
            }
        }
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
