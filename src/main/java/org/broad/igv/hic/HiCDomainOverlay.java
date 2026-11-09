package org.broad.igv.hic;

import htsjdk.tribble.Feature;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.Track;
import org.broad.igv.util.ResourceLocator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import org.broad.igv.ui.IGV;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a set of domain intervals (typically a TAD caller's BED) on top of a Hi-C matrix.
 * <p>
 * Triangle display: each domain becomes a closed triangle whose base sits on the diagonal
 * and whose apex is at the domain midpoint, at a separation of {@code end - start}.
 * Square display: the domain becomes the square spanned by the same interval on both axes.
 */
public final class HiCDomainOverlay {

    /** Stroke style for the outline. */
    public enum LineStyle {
        SOLID("Solid"), DASHED("Dashed"), DOTTED("Dotted");

        private final String label;

        LineStyle(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Default outline colours; the first domain track stays black, matching print conventions.
     * Red comes second on purpose: the default RdYlBu_r matrix is dominated by blue, so a blue
     * outline on the second overlay is nearly invisible.
     */
    private static final Color[] PALETTE = {
            new Color(0x00, 0x00, 0x00),
            new Color(0xD6, 0x27, 0x28),
            new Color(0x2C, 0xA0, 0x2C),
            new Color(0x94, 0x67, 0xBD),
            new Color(0xFF, 0x7F, 0x0E),
            new Color(0x1F, 0x77, 0xB4)
    };

    private final String trackName;
    private final String trackPath;
    private Color lineColor;
    private float lineWidth = 2f;
    private LineStyle lineStyle = LineStyle.SOLID;
    private Color fillColor = new Color(0x00, 0x00, 0x00);
    private int fillAlpha = 0;

    private transient FeatureTrack track;

    public HiCDomainOverlay(String trackName, String trackPath, Color lineColor) {
        this.trackName = trackName;
        this.trackPath = trackPath;
        this.lineColor = (lineColor == null ? PALETTE[0] : lineColor);
    }

    /**
     * A BED track that is drawn on top of a Hi-C matrix should disappear from the panel,
     * otherwise the same intervals are shown twice. Counting references keeps the track
     * hidden while any Hi-C track still uses it, and restores it once the last overlay is
     * removed.
     * <p>
     * Keyed by file path (falling back to the track name) rather than by object identity:
     * a restored session can hold a different {@code FeatureTrack} instance for the same
     * file than the one the panel is showing, and identity matching then hides a track that
     * no overlay can ever un-hide again.
     */
    private static final Map<String, Integer> OVERLAY_USES = new HashMap<>();

    /** Instances this class has hidden, so they can be shown again without asking the panel. */
    private static final Map<String, List<FeatureTrack>> HIDDEN_INSTANCES = new HashMap<>();

    private static String keyOf(Track track) {
        if (track == null) {
            return "";
        }
        try {
            ResourceLocator locator = track.getResourceLocator();
            if (locator != null && locator.getPath() != null) {
                return locator.getPath();
            }
        } catch (RuntimeException ignored) {
            // tracks without a locator fall through to the name
        }
        return track.getName() == null ? "" : track.getName();
    }

    /** Bind a feature track to an overlay, hiding it from the track panel. */
    public static void acquire(FeatureTrack target) {
        if (target == null) {
            return;
        }
        String key = keyOf(target);
        Integer uses = OVERLAY_USES.get(key);
        if (uses == null) {
            OVERLAY_USES.put(key, 1);
        } else {
            OVERLAY_USES.put(key, uses + 1);
        }
        List<FeatureTrack> hidden = HIDDEN_INSTANCES.computeIfAbsent(key, k -> new ArrayList<>());
        if (!hidden.contains(target)) {
            hidden.add(target);
        }
        target.setVisible(false);
        refreshPanels();
    }

    /** Release a binding, restoring the track when nothing else overlays it. */
    public static void release(FeatureTrack target) {
        if (target == null) {
            return;
        }
        String key = keyOf(target);
        Integer uses = OVERLAY_USES.get(key);
        if (uses != null && uses > 1) {
            OVERLAY_USES.put(key, uses - 1);
            return;
        }
        OVERLAY_USES.remove(key);
        // Always restore, even if the track was already invisible when the overlay grabbed
        // it. A session saved while the overlay was active stores the BED track as
        // visible="false", so "was it visible when we hid it" cannot distinguish "hidden by
        // the overlay" from "hidden by the user" - and leaving it hidden means the track can
        // never be brought back from the menu.
        showAgain(key, target);
        refreshPanels();
    }

    private static void showAgain(String key, FeatureTrack fallback) {
        fallback.setVisible(true);
        List<FeatureTrack> hidden = HIDDEN_INSTANCES.remove(key);
        if (hidden != null) {
            for (FeatureTrack t : hidden) {
                t.setVisible(true);
            }
        }
        if (!IGV.hasInstance()) {
            return;
        }
        for (Track t : IGV.getInstance().getAllTracks()) {
            if (t instanceof FeatureTrack && t != fallback && key.equals(keyOf(t))) {
                t.setVisible(true);
            }
        }
    }

    private static void refreshPanels() {
        if (IGV.hasInstance()) {
            IGV.getInstance().revalidateTrackPanels();
        }
    }

    /** Colour for the n-th domain track added to a Hi-C track. */
    public static Color defaultColor(int index) {
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    public String getTrackName() {
        return trackName;
    }

    public String getTrackPath() {
        return trackPath;
    }

    public Color getLineColor() {
        return lineColor;
    }

    public void setLineColor(Color lineColor) {
        this.lineColor = lineColor == null ? PALETTE[0] : lineColor;
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public void setLineWidth(float lineWidth) {
        this.lineWidth = Math.max(0.5f, lineWidth);
    }

    public LineStyle getLineStyle() {
        return lineStyle;
    }

    public void setLineStyle(LineStyle lineStyle) {
        this.lineStyle = (lineStyle == null ? LineStyle.SOLID : lineStyle);
    }

    public Color getFillColor() {
        return fillColor;
    }

    public void setFillColor(Color fillColor) {
        this.fillColor = fillColor == null ? Color.BLACK : fillColor;
    }

    public int getFillAlpha() {
        return fillAlpha;
    }

    /** @param alpha 0 (fully transparent, the default) to 255 */
    public void setFillAlpha(int alpha) {
        this.fillAlpha = Math.max(0, Math.min(255, alpha));
    }

    /**
     * Bind to a concrete track. Resolved lazily because a restored session may not have
     * created the feature tracks yet when the Hi-C track is deserialised.
     */
    public void setTrack(FeatureTrack track) {
        this.track = track;
    }

    public FeatureTrack getTrack() {
        return track;
    }

    /** @return features overlapping the interval, or an empty list when unavailable */
    public List<Feature> features(String chr, int start, int end) {
        if (track == null) {
            return new ArrayList<>();
        }
        List<Feature> features = track.getFeatures(chr, start, end);
        if (features != null && !features.isEmpty()) {
            return features;
        }
        // BED files frequently use bare names ("X") while UCSC genomes use "chrX". Retry with
        // the other convention before giving up, otherwise the overlay silently stays empty.
        String alias = aliasOf(chr);
        if (alias != null) {
            List<Feature> retry = track.getFeatures(alias, start, end);
            if (retry != null && !retry.isEmpty()) {
                return retry;
            }
        }
        return new ArrayList<>();
    }

    private static String aliasOf(String chr) {
        if (chr == null || chr.isEmpty()) {
            return null;
        }
        return chr.startsWith("chr") ? chr.substring(3) : "chr" + chr;
    }

    /** Signature used in the render cache key. */
    public String styleKey() {
        return trackName + ":" + lineColor.getRGB() + ":" + lineWidth + ":" + lineStyle
                + ":" + fillColor.getRGB() + ":" + fillAlpha;
    }

    private Stroke stroke() {
        switch (lineStyle) {
            case DASHED:
                return new BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{6f, 4f}, 0f);
            case DOTTED:
                return new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        10f, new float[]{1f, 3f}, 0f);
            default:
                // Round joins keep the staircase of a 45 degree diagonal from showing sharp
                // corners; combined with supersampling the stroke reads as one continuous line.
                return new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
    }

    /**
     * @param bpPerPixel   horizontal scale
     * @param depthPerPixel vertical bases per pixel; the triangle apex sits at
     *                      {@code (end - start) / depthPerPixel} pixels above the diagonal
     */
    public void paintTriangle(Graphics2D g, List<Feature> features, double origin,
                              int w, int h, double bpPerPixel, double depthPerPixel) {

        if (features.isEmpty() || bpPerPixel <= 0 || depthPerPixel <= 0) {
            return;
        }
        Shape oldClip = g.getClip();
        g.clipRect(0, 0, w, h);

        Stroke stroke = stroke();
        Color fill = fillAlpha > 0
                ? new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), fillAlpha)
                : null;

        for (Feature f : features) {
            double start = f.getStart();
            double end = f.getEnd();
            if (end <= start) {
                continue;
            }

            double x1 = (start - origin) / bpPerPixel;
            double x2 = (end - origin) / bpPerPixel;
            if (x2 < -2 || x1 > w + 2) {
                continue;
            }
            if (x2 - x1 < 2) {
                // Shorter than a couple of pixels: keep it visible.
                double mid = (x1 + x2) / 2;
                x1 = mid - 1;
                x2 = mid + 1;
            }
            double xApex = (x1 + x2) / 2;
            double yBase = h - 1;
            double yApex = yBase - (end - start) / depthPerPixel;

            Path2D path = new Path2D.Double();
            path.moveTo(x1, yBase);
            path.lineTo(xApex, yApex);
            path.lineTo(x2, yBase);
            path.closePath();

            if (fill != null) {
                g.setColor(fill);
                g.fill(path);
            }
            g.setColor(lineColor);
            g.setStroke(stroke);
            g.draw(path);
        }
        g.setClip(oldClip);
    }

    /** Square display: the domain spans the same interval on both axes, i.e. a box. */
    public void paintSquare(Graphics2D g, List<Feature> features, double origin, double end,
                            int w, int h) {

        double span = end - origin;
        if (features.isEmpty() || span <= 0) {
            return;
        }
        Shape oldClip = g.getClip();
        g.clipRect(0, 0, w, h);

        Stroke stroke = stroke();
        Color fill = fillAlpha > 0
                ? new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), fillAlpha)
                : null;

        double bpX = span / w;
        double bpY = span / h;

        for (Feature f : features) {
            double start = f.getStart();
            double stop = f.getEnd();
            if (stop <= start) {
                continue;
            }
            double x1 = (start - origin) / bpX;
            double x2 = (stop - origin) / bpX;
            double y1 = (start - origin) / bpY;
            double y2 = (stop - origin) / bpY;
            if (x2 < 0 || x1 > w || y2 < 0 || y1 > h) {
                continue;
            }
            double x = Math.min(x1, x2);
            double y = Math.min(y1, y2);
            double width = Math.max(1.5, Math.abs(x2 - x1));
            double height = Math.max(1.5, Math.abs(y2 - y1));

            Rectangle2D box = new Rectangle2D.Double(x, y, width, height);
            if (fill != null) {
                g.setColor(fill);
                g.fill(box);
            }
            g.setColor(lineColor);
            g.setStroke(stroke);
            g.draw(box);
        }
        g.setClip(oldClip);
    }
}
