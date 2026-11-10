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
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Circular / ring genome view.
 *
 * <p>When the genome is shown "All" (whole-genome view) it draws every chromosome as a
 * coloured arc segment around the ring, scaled by genomic length with small gaps between
 * chromosomes. When a single chromosome is selected it draws that chromosome as a full
 * 360&deg; ideogram ring (cytobands if available) with radial ticks and Mb labels.</p>
 *
 * <p>The geometry follows the Circos convention (clockwise; here we start at the top,
 * 12 o'clock, and sweep clockwise). The core draw routines are static and take a plain
 * {@link ChrInfo} list so they can be exercised headlessly by a render probe.</p>
 */
public class CircularViewPanel extends JPanel implements IGVEventObserver {

    // ----- layout constants (relative to the ring radius R) -----
    private static final double BAND_INNER = 0.80;   // ideogram band inner radius
    private static final double BAND_OUTER = 0.93;   // ideogram band outer radius
    private static final int    MARGIN     = 36;     // reserved for outside labels
    private static final double GAP_DEG    = 0.35;   // per-boundary gap between chromosomes ("All")
    private static final double LABEL_MIN_SPAN_DEG = 2.0; // only label chromosomes wider than this ("All")

    public CircularViewPanel() {
        setBackground(new Color(245, 245, 245));
        setOpaque(true);
        // Redraw the ring whenever the chromosome / range changes (covers genome load too).
        IGVEventBus.getInstance().subscribe(FrameManager.ChangeEvent.class, this);
        IGVEventBus.getInstance().subscribe(org.broad.igv.event.RefreshEvent.class, this);
    }

    @Override
    public void receiveEvent(IGVEvent event) {
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) {
            drawPlaceholder(g2, w, h, "Circular view — no genome loaded");
            return;
        }

        String chr = FrameManager.getDefaultFrame().getChrName();
        if (chr == null || chr.isEmpty()) {
            chr = genome.getHomeChromosome();
        }

        if (Globals.CHR_ALL.equals(chr)) {
            drawAllChromosomes(g2, w, h, buildAll(genome));
        } else {
            ChrInfo one = buildOne(genome, chr);
            if (one == null) {
                drawPlaceholder(g2, w, h, "Chromosome not found: " + chr);
            } else {
                drawSingleChromosome(g2, w, h, one);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Genome -> ChrInfo list
    // ------------------------------------------------------------------

    /** Build the list of chromosomes for the whole-genome ("All") ring. */
    static List<ChrInfo> buildAll(Genome genome) {
        List<ChrInfo> list = new ArrayList<>();
        for (String name : genome.getLongChromosomeNames()) {
            org.broad.igv.feature.Chromosome c = genome.getChromosome(name);
            if (c == null) continue;
            long len = c.getLength();
            if (len <= 0) continue;
            list.add(new ChrInfo(name, len, ChromosomeColors.getColor(name), genome.getCytobands(name)));
        }
        return list;
    }

    /** Build the single selected chromosome. */
    static ChrInfo buildOne(Genome genome, String chr) {
        org.broad.igv.feature.Chromosome c = genome.getChromosome(chr);
        if (c == null) return null;
        return new ChrInfo(chr, c.getLength(), ChromosomeColors.getColor(chr), genome.getCytobands(chr));
    }

    // ------------------------------------------------------------------
    //  "All" — every chromosome as a coloured arc segment
    // ------------------------------------------------------------------

    public static void drawAllChromosomes(Graphics2D g2, int w, int h, List<ChrInfo> chrs) {
        if (chrs == null || chrs.isEmpty()) {
            drawPlaceholder(g2, w, h, "Circular view — no chromosomes");
            return;
        }
        int cx = w / 2;
        int cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 10) return;
        double rIn = R * BAND_INNER;
        double rOut = R * BAND_OUTER;

        long total = 0;
        for (ChrInfo c : chrs) total += c.length;
        if (total <= 0) return;

        // Reserve gap space between chromosomes.
        int n = chrs.size();
        double gapTotal = GAP_DEG * n;            // degrees consumed by gaps
        double spanTotal = 360.0 - gapTotal;      // degrees available for chromosome arcs
        double gc = (double) total;

        double frac = 0.0; // start at top, sweep clockwise
        for (ChrInfo c : chrs) {
            double spanDeg = spanTotal * (c.length / gc);
            double fStart = frac;
            double fEnd = frac + spanDeg / 360.0;
            frac = fEnd + GAP_DEG / 360.0;

            // Ideogram band segment, coloured by chromosome.
            Path2D band = annularPath(cx, cy, rIn, rOut, fStart, fEnd);
            g2.setColor(c.color);
            g2.fill(band);
            g2.setColor(new Color(120, 120, 120));
            g2.draw(band);

            // Label only chromosomes wide enough to be readable.
            if (spanDeg >= LABEL_MIN_SPAN_DEG) {
                double fMid = (fStart + fEnd) / 2.0;
                Point2D p = polar(cx, cy, R + 14, fMid);
                drawCenteredText(g2, shortName(c.name), (int) p.getX(), (int) p.getY(),
                        new Font("SansSerif", Font.PLAIN, 9), Color.DARK_GRAY);
            }
        }

        // Faint guide circles.
        g2.setColor(new Color(210, 210, 210));
        drawCircle(g2, cx, cy, rIn);
        drawCircle(g2, cx, cy, rOut);

        // Genome name in the centre.
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        String id = genome != null ? genome.getDisplayName() : "";
        drawCenteredText(g2, id, cx, cy, new Font("SansSerif", Font.ITALIC, 11), Color.GRAY);
    }

    // ------------------------------------------------------------------
    //  Single chromosome — full 360deg ideogram ring + ticks
    // ------------------------------------------------------------------

    public static void drawSingleChromosome(Graphics2D g2, int w, int h, ChrInfo chr) {
        if (chr == null || chr.length <= 0) return;
        int cx = w / 2;
        int cy = h / 2;
        double R = Math.min(w, h) / 2.0 - MARGIN;
        if (R <= 10) return;
        double rIn = R * BAND_INNER;
        double rOut = R * BAND_OUTER;

        boolean hasCyto = chr.cytobands != null && !chr.cytobands.isEmpty();

        if (hasCyto) {
            // Classic ideogram look: white base, grayscale bands, black centromere.
            Path2D base = annularPath(cx, cy, rIn, rOut, 0.0, 1.0);
            g2.setColor(Color.WHITE);
            g2.fill(base);
            double L = chr.length;
            for (Cytoband cb : chr.cytobands) {
                double fStart = (double) cb.getStart() / L;
                double fEnd = (double) cb.getEnd() / L;
                // tiny inset so neighbouring bands show a thin separator
                double inset = Math.min(0.0015, (fEnd - fStart) * 0.06);
                fStart += inset;
                fEnd -= inset;
                if (fEnd <= fStart) continue;
                Path2D seg = annularPath(cx, cy, rIn, rOut, fStart, fEnd);
                g2.setColor(cytoColor(cb));
                g2.fill(seg);
            }
        } else {
            // No cytoband data: solid chromosome-coloured ring.
            Path2D base = annularPath(cx, cy, rIn, rOut, 0.0, 1.0);
            g2.setColor(chr.color);
            g2.fill(base);
        }
        // Outline.
        Path2D outline = annularPath(cx, cy, rIn, rOut, 0.0, 1.0);
        g2.setColor(Color.DARK_GRAY);
        g2.draw(outline);
        g2.setColor(new Color(210, 210, 210));
        drawCircle(g2, cx, cy, rIn);
        drawCircle(g2, cx, cy, rOut);

        // Radial ticks at "nice" intervals with Mb labels.
        long step = niceStep(chr.length, 10);
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        for (long p = 0; p <= chr.length; p += step) {
            double f = (double) p / chr.length;
            Point2D a = polar(cx, cy, rIn - 3, f);
            Point2D b = polar(cx, cy, rOut + 3, f);
            g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
            Point2D lp = polar(cx, cy, rOut + 13, f);
            drawCenteredText(g2, formatMb(p), (int) lp.getX(), (int) lp.getY(),
                    new Font("SansSerif", Font.PLAIN, 9), Color.DARK_GRAY);
        }

        // Chromosome name in the centre.
        drawCenteredText(g2, shortName(chr.name), cx, cy,
                new Font("SansSerif", Font.BOLD, 16), Color.DARK_GRAY);
    }

    // ------------------------------------------------------------------
    //  Geometry helpers
    // ------------------------------------------------------------------

    /** Polar point, clockwise starting from the top (12 o'clock). */
    private static Point2D polar(double cx, double cy, double r, double frac) {
        double theta = frac * 2.0 * Math.PI;
        double x = cx + r * Math.sin(theta);
        double y = cy - r * Math.cos(theta);
        return new Point2D.Double(x, y);
    }

    /** Closed annular sector path between two fractional positions. */
    private static Path2D annularPath(double cx, double cy, double rIn, double rOut,
                                      double fStart, double fEnd) {
        Path2D.Double path = new Path2D.Double();
        double spanDeg = (fEnd - fStart) * 360.0;
        int steps = Math.max(2, (int) Math.ceil(spanDeg / 0.5) + 1);
        double step = (fEnd - fStart) / steps;
        boolean first = true;
        for (int i = 0; i <= steps; i++) {
            double f = fStart + step * i;
            Point2D p = polar(cx, cy, rOut, f);
            if (first) { path.moveTo(p.getX(), p.getY()); first = false; }
            else path.lineTo(p.getX(), p.getY());
        }
        for (int i = steps; i >= 0; i--) {
            double f = fStart + step * i;
            Point2D p = polar(cx, cy, rIn, f);
            path.lineTo(p.getX(), p.getY());
        }
        path.closePath();
        return path;
    }

    private static void drawCircle(Graphics2D g2, double cx, double cy, double r) {
        g2.draw(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    private static void drawCenteredText(Graphics2D g2, String s, int x, int y, Font font, Color color) {
        if (s == null || s.isEmpty()) return;
        Font old = g2.getFont();
        g2.setFont(font);
        g2.setColor(color);
        FontMetrics fm = g2.getFontMetrics(font);
        int tw = fm.stringWidth(s);
        int th = fm.getAscent();
        g2.drawString(s, x - tw / 2, y + th / 2 - 1);
        g2.setFont(old);
    }

    private static void drawPlaceholder(Graphics2D g2, int w, int h, String text) {
        g2.setColor(Color.GRAY);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text);
        g2.drawString(text, (w - tw) / 2, h / 2);
    }

    private static String shortName(String name) {
        if (name == null) return "";
        return name.startsWith("chr") ? name.substring(3) : name;
    }

    /** Cytoband grayscale; centromere (acen) in black. */
    private static Color cytoColor(Cytoband cb) {
        char t = cb.getType();
        if (t == 'c') return Color.BLACK;           // centromere
        int shade = 255 - (int) (cb.getStain() * 1.8); // 0 (gneg) -> 255, 100 (gpos100) -> 73
        if (shade < 40) shade = 40;
        if (shade > 255) shade = 255;
        return new Color(shade, shade, shade);
    }

    /** Round a span up to a "nice" step (1,2,5 x 10^k) targeting ~nTicks ticks. */
    static long niceStep(long length, int nTicks) {
        double raw = (double) length / nTicks;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double nice;
        if (norm <= 1.0) nice = 1.0;
        else if (norm <= 2.0) nice = 2.0;
        else if (norm <= 5.0) nice = 5.0;
        else nice = 10.0;
        return Math.max(1, (long) (nice * mag));
    }

    static String formatMb(long bp) {
        if (bp <= 0) return "0";
        double mb = bp / 1_000_000.0;
        if (mb >= 10) return String.format("%.0f Mb", mb);
        return String.format("%.1f Mb", mb).replace(".0 Mb", " Mb");
    }

    /** Lightweight chromosome descriptor consumed by the static draw routines. */
    public static class ChrInfo {
        final String name;
        final long length;
        final Color color;
        final List<Cytoband> cytobands;

        public ChrInfo(String name, long length, Color color, List<Cytoband> cytobands) {
            this.name = name;
            this.length = length;
            this.color = color;
            this.cytobands = cytobands;
        }
    }
}
