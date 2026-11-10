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
 * Circular / ring genome view, drawn in the classic Circos ideogram style.
 *
 * <ul>
 *   <li>"All" (whole genome): every chromosome is a cytoband-striped ideogram arc, scaled by
 *       length, grouped clockwise around the ring with gaps. Rotated chromosome numbers sit
 *       outside; small 0Mb/NMb tick labels run along each band (tangential, as in Circos).</li>
 *   <li>Single chromosome: a full 360&deg; ideogram ring with the same tick labels.</li>
 *   <li>Ctrl + mouse-wheel over a chromosome (in "All" mode) zooms that chromosome's sector
 *       in/out; the extra space is taken from / given back to the other chromosomes.</li>
 *   <li>If a species has no cytoband (karyotype) data, chromosomes are drawn as solid colour
 *       blocks instead of striped ideograms.</li>
 * </ul>
 *
 * <p>The geometry follows Circos conventions (clockwise from the top; tick labels tangential
 * and placed just inside the band, i.e. {@code orientation = in}). Core draw routines are
 * static and take a plain {@link ChrInfo} list so they can be exercised headlessly.</p>
 */
public class CircularViewPanel extends JPanel implements IGVEventObserver {

    private static final double BAND_INNER = 0.78;
    private static final double BAND_OUTER = 0.90;
    private static final int    MARGIN     = 44;    // room for rotated labels outside
    private static final double GAP_DEG    = 1.5;   // gap between chromosomes ("All")

    private static final Color BG = Color.WHITE;
    private static final Color CENTROMERE = new Color(200, 30, 30);
    private static final Color NO_CYTO_FALLBACK = new Color(190, 190, 190);

    /** Per-chromosome zoom factors (Ctrl+wheel in "All" mode). Keyed by chromosome name. */
    private final Map<String, Double> zoom = new HashMap<>();

    // Offscreen cache: the ring only depends on (genome, chromosome, panel size, zoom), not
    // on pan/zoom of the linear view, so avoid re-rendering thousands of segments per refresh.
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

    @Override
    public void receiveEvent(IGVEvent event) {
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
        if (gid != lastGenomeId) {          // new genome -> drop stale zoom
            zoom.clear();
            lastGenomeId = gid;
            ringKey = null;
        }

        String chr = FrameManager.getDefaultFrame().getChrName();
        if (chr == null || chr.isEmpty()) chr = genome.getHomeChromosome();

        String key = gid + "|" + chr + "|" + w + "x" + h;
        if (!key.equals(ringKey) || ringImage == null) {
            ringImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = ringImage.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (Globals.CHR_ALL.equals(chr)) {
                drawAll(ig, w, h, buildAll(genome, zoom));
            } else {
                ChrInfo one = buildOne(genome, chr);
                if (one == null) drawMessage(ig, w, h, "Chromosome not found: " + chr);
                else drawSingle(ig, w, h, one);
            }
            ig.dispose();
            ringKey = key;
        }
        g2.drawImage(ringImage, 0, 0, null);
    }

    // ------------------------------------------------------------------
    //  Ctrl + wheel -> zoom the hovered chromosome sector ("All" mode only)
    // ------------------------------------------------------------------

    private void handleWheel(MouseWheelEvent e) {
        if (!e.isControlDown()) return;                 // plain wheel is ignored
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) return;
        String cur = FrameManager.getDefaultFrame().getChrName();
        if (!Globals.CHR_ALL.equals(cur)) return;       // only in the whole-genome ring

        String target = chromosomeAt(e.getX(), e.getY(), genome);
        if (target == null) return;

        double z = zoom.getOrDefault(target, 1.0);
        double f = e.getWheelRotation() < 0 ? 1.12 : 1.0 / 1.12;  // wheel up = zoom in
        z = Math.max(0.25, Math.min(25.0, z * f));
        zoom.put(target, z);
        ringKey = null;                                 // invalidate cache
        repaint();
    }

    /** Which chromosome sector the point falls in (angle-based), or null. */
    private String chromosomeAt(int x, int y, Genome genome) {
        List<ChrInfo> chrs = buildAll(genome, zoom);
        if (chrs.isEmpty()) return null;
        int cx = getWidth() / 2, cy = getHeight() / 2;
        double R = Math.min(getWidth(), getHeight()) / 2.0 - MARGIN;
        if (R <= 0) return null;
        double rIn = R * BAND_INNER, rOut = R * BAND_OUTER;

        double dx = x - cx, dy = y - cy;
        double r = Math.hypot(dx, dy);
        if (r < rIn * 0.6 || r > R + MARGIN) return null;    // must hover the ring region

        double theta = Math.atan2(dx, -dy);                  // clockwise from top
        double frac = theta / (2 * Math.PI);
        if (frac < 0) frac += 1.0;

        List<double[]> sec = computeSectors(chrs);
        for (int i = 0; i < chrs.size(); i++) {
            double[] s = sec.get(i);
            if (frac >= s[0] && frac < s[1]) return chrs.get(i).name;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Genome -> ChrInfo list (with zoom-weighted allocation)
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
    //  Public static entry points (also used by the headless render probe)
    // ------------------------------------------------------------------

    public static void drawAllChromosomes(Graphics2D g2, int w, int h, List<ChrInfo> chrs) {
        drawAll(g2, w, h, chrs);
    }

    public static void drawSingleChromosome(Graphics2D g2, int w, int h, ChrInfo chr) {
        drawSingle(g2, w, h, chr);
    }

    // ------------------------------------------------------------------
    //  Sector layout shared by painting and hit-testing
    // ------------------------------------------------------------------

    /** Returns, per chromosome, {fStart, fEnd, spanDeg} using zoom-weighted allocation. */
    static List<double[]> computeSectors(List<ChrInfo> chrs) {
        List<double[]> out = new ArrayList<>();
        double totalW = 0;
        for (ChrInfo c : chrs) totalW += c.weight;
        int n = chrs.size();
        double spanTotal = 360.0 - GAP_DEG * n;
        double frac = 0.0;
        for (ChrInfo c : chrs) {
            double spanDeg = totalW > 0 ? spanTotal * (c.weight / totalW) : 0;
            double f0 = frac;
            double f1 = frac + spanDeg / 360.0;
            out.add(new double[]{f0, f1, spanDeg});
            frac = f1 + GAP_DEG / 360.0;
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  "All" — cytoband ideogram arc per chromosome
    // ------------------------------------------------------------------

    private static void drawAll(Graphics2D g2, int w, int h, List<ChrInfo> chrs) {
        if (chrs == null || chrs.isEmpty()) { drawMessage(g2, w, h, "Circular view — no chromosomes"); return; }
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rIn = R * BAND_INNER, rOut = R * BAND_OUTER;

        List<double[]> sec = computeSectors(chrs);
        for (int i = 0; i < chrs.size(); i++) {
            ChrInfo c = chrs.get(i);
            double f0 = sec.get(i)[0], f1 = sec.get(i)[1], spanDeg = sec.get(i)[2];

            drawIdeogramBand(g2, cx, cy, rIn, rOut, f0, f1, c);
            drawBandTicks(g2, cx, cy, rIn, rOut, f0, f1, c.length, spanDeg);

            double fMid = (f0 + f1) / 2.0;
            drawTangentialText(g2, cx, cy, rOut + 16, fMid, shortName(c.name),
                    new Font("SansSerif", Font.BOLD, 12), Color.BLACK);
        }

        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        String id = genome != null ? genome.getDisplayName() : "";
        drawCenteredText(g2, id, cx, cy, new Font("SansSerif", Font.PLAIN, 15), Color.DARK_GRAY);
    }

    // ------------------------------------------------------------------
    //  Single chromosome — full 360deg ideogram ring + ticks
    // ------------------------------------------------------------------

    private static void drawSingle(Graphics2D g2, int w, int h, ChrInfo chr) {
        if (chr == null || chr.length <= 0) return;
        int cx = w / 2, cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 20) return;
        double rIn = R * BAND_INNER, rOut = R * BAND_OUTER;

        drawIdeogramBand(g2, cx, cy, rIn, rOut, 0.0, 1.0, chr);
        drawBandTicks(g2, cx, cy, rIn, rOut, 0.0, 1.0, chr.length, 360.0);

        drawCenteredText(g2, shortName(chr.name), cx, cy,
                new Font("SansSerif", Font.BOLD, 18), Color.DARK_GRAY);
    }

    // ------------------------------------------------------------------
    //  Ideogram band (cytoband stripes, or colour block when no karyotype)
    // ------------------------------------------------------------------

    private static void drawIdeogramBand(Graphics2D g2, double cx, double cy,
                                         double rIn, double rOut,
                                         double fStart, double fEnd, ChrInfo c) {
        boolean hasCyto = c.cytobands != null && !c.cytobands.isEmpty();

        if (hasCyto) {
            // base fill so gaps look clean
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
            // thin radial separators -> ladder look
            g2.setColor(new Color(150, 150, 150));
            g2.setStroke(new BasicStroke(0.5f));
            for (Cytoband cb : c.cytobands) {
                double f = fStart + span * ((double) cb.getEnd() / L);
                Point2D a = polar(cx, cy, rIn, f), b = polar(cx, cy, rOut, f);
                g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            }
        } else {
            // No karyotype data for this species/chromosome -> solid colour block.
            g2.setColor(c.color != null ? c.color : NO_CYTO_FALLBACK);
            g2.fill(annularPath(cx, cy, rIn, rOut, fStart, fEnd));
        }

        // chromosome-level black outline
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(annularPath(cx, cy, rIn, rOut, fStart, fEnd));
        g2.setStroke(new BasicStroke(1.0f));
    }

    /** Tick lines across the band + small Mb labels just inside the band (circos style). */
    private static void drawBandTicks(Graphics2D g2, double cx, double cy,
                                      double rIn, double rOut,
                                      double fStart, double fEnd, long length, double spanDeg) {
        if (spanDeg < 3.0) return;
        long step = niceStep(length, 3);
        double span = fEnd - fStart;
        Font tickFont = new Font("SansSerif", Font.PLAIN, 7);
        for (long p = 0; p <= length; p += step) {
            double f = fStart + span * ((double) p / length);
            Point2D a = polar(cx, cy, rIn - 2, f), b = polar(cx, cy, rOut + 2, f);
            g2.setColor(new Color(60, 60, 60));
            g2.setStroke(new BasicStroke(0.6f));
            g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());

            double labelR = rIn - 8;
            String txt = mbLabel(p);
            int tw = g2.getFontMetrics(tickFont).stringWidth(txt);
            if (arcLength(labelR, spanDeg) < tw + 10) continue;
            drawTangentialText(g2, cx, cy, labelR, f, txt, tickFont, new Color(70, 70, 70));
        }
    }

    // ------------------------------------------------------------------
    //  Geometry + text helpers
    // ------------------------------------------------------------------

    /** Polar point, clockwise starting from the top (12 o'clock). */
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

    /** Text whose baseline follows the circle tangent (flipped upright on the bottom half). */
    private static void drawTangentialText(Graphics2D g2, double cx, double cy, double r,
                                           double frac, String text, Font font, Color color) {
        if (text == null || text.isEmpty()) return;
        Point2D p = polar(cx, cy, r, frac);
        double rotDeg = frac * 360.0;
        if (rotDeg > 90 && rotDeg < 270) rotDeg += 180;
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

    /** Centromere (acen) red; gpos grayscale by stain; gvar/stalk light gray. */
    private static Color cytoColor(Cytoband cb) {
        char t = cb.getType();
        if (t == 'c') return CENTROMERE;
        if (t != 'p') return new Color(200, 200, 200);
        int shade = 255 - (int) (cb.getStain() * 1.8);
        if (shade < 55) shade = 55;
        if (shade > 255) shade = 255;
        return new Color(shade, shade, shade);
    }

    /** Round a span up to a "nice" step (1,2,5 x 10^k) targeting ~nTicks ticks. */
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

    /** Compact tick label, e.g. 0Mb / 90Mb / 180Mb (circos multiplier + suffix style). */
    static String mbLabel(long bp) {
        if (bp <= 0) return "0Mb";
        double mb = bp / 1_000_000.0;
        if (mb >= 10) return String.format("%.0fMb", mb);
        return String.format("%.1fMb", mb).replace(".0Mb", "Mb");
    }

    static String formatMb(long bp) { return mbLabel(bp); }

    /** Chromosome descriptor: name, genomic length, layout weight (= length * zoom), cytobands, colour. */
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
