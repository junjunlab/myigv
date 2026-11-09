package org.broad.igv.hic;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Draws Hi-C matrices into an image.
 * <p>
 * Kept separate from {@link HiCTrack} so it can be exercised without a running IGV
 * instance (tests, image export).
 */
public final class HiCRenderer {

    private static final int WHITE = 0xFFFFFF;

    private HiCRenderer() {
        // Utility class
    }

    /**
     * Vertical bases per pixel used by {@link #paintTriangle}.
     * <p>
     * Automatic mode keeps the 45 degree mapping undistorted (twice the horizontal scale);
     * an explicit height ratio maps that fraction of the region onto the track height.
     * Exposed so overlays (TAD outlines) land on exactly the same scale as the matrix.
     */
    public static double depthPerPixel(double span, int w, int h, double heightRatio) {
        if (!Double.isNaN(heightRatio) && heightRatio > 0) {
            return Math.min(heightRatio, 1.25) * span / Math.max(1, h);
        }
        return 2.0 * (span / Math.max(1, w));
    }

    /**
     * Triangle display: each contact is drawn at the midpoint of its two anchors, with
     * the vertical distance representing genomic separation (the classic Hi-C "pyramid").
     *
     * @param depth       optional hard limit on separation in bp, NaN for none
     * @param heightRatio optional vertical zoom: the given fraction of the visible region
     *                    fills the track height, so 1.0 puts the apex of the whole triangle
     *                    on the top edge. NaN keeps the axis undistorted (square cells).
     */
    public static double[] paintTriangle(BufferedImage img, HiCSource data, String chr,
                                     double origin, double end, double depth, double heightRatio,
                                     double minValue, double maxValue,
                                     HiCTransform transform,
                                     HiCColorScale.Palette palette, int[][] customAnchors) {

        final int w = img.getWidth();
        final int h = img.getHeight();
        final double span = end - origin;
        if (span <= 0 || h < 1) {
            return null;
        }
        final double bpPerPixel = span / w;

        final double depthPerPixel;
        final double clipDistance;
        final int windowFirstBin;
        final int windowLastBin;

        if (!Double.isNaN(heightRatio) && heightRatio > 0) {
            // Explicit vertical zoom: the requested slice of the region fills the track.
            // Contacts are restricted to the window so that the plot is a true triangle -
            // at ratio 1 the two anchors can only meet at the centre of the window, which
            // is what makes the apex land on the top edge.
            final double visible = Math.min(heightRatio, 1.25) * span;
            depthPerPixel = depthPerPixel(span, w, h, heightRatio);
            clipDistance = visible;
            windowFirstBin = data.binAt(chr, origin);
            windowLastBin = data.binAt(chr, Math.max(origin, end - 1));
        } else {
            // Automatic: one B x B contact cell occupies B bp horizontally but 2 * B bp of
            // separation vertically (the 45 degree rotation maps (i, j) to x = (i + j) / 2,
            // y = j - i). The vertical bp-per-pixel must therefore be twice the horizontal
            // one, otherwise every cell is stretched instead of being a square.
            depthPerPixel = depthPerPixel(span, w, h, heightRatio);
            final double visible = h * depthPerPixel;
            clipDistance = (!Double.isNaN(depth) && depth > 0)
                    ? Math.min(depth, visible) : Double.POSITIVE_INFINITY;
            windowFirstBin = -1;
            windowLastBin = -1;
        }

        final HiCTransform tf = (transform == null) ? HiCTransform.LINEAR : transform;
        double[] limits = colorLimits(data, chr, origin, end, span, minValue, maxValue, tf);
        final double vmin = limits[0];
        final double vmax = limits[1];
        final double floor = limits[2];

        final HiCColorScale.Palette drawPalette = tf.reversesRamp() ? HiCColorScale.Palette.CUSTOM : palette;
        final int[][] drawAnchors = tf.reversesRamp()
                ? HiCColorScale.reversedAnchors(palette, customAnchors) : customAnchors;

        // Fill a row buffer and hand it over in one call: setRGB per pixel costs an
        // bounds/type check plus a store per pixel, which dominates whole-chromosome views.
        final int[] row = new int[w];

        for (int py = 0; py < h; py++) {
            // Top row = largest separation, bottom row = the diagonal itself.
            double distance = (h - 0.5 - py) * depthPerPixel;
            double half = distance / 2;

            if (distance > clipDistance) {
                // Beyond the requested range: leave the row empty rather than compressing
                // the remaining data into the leftover pixels.
                java.util.Arrays.fill(row, WHITE);
                img.setRGB(0, py, w, 1, row, 0, w);
                continue;
            }

            for (int px = 0; px < w; px++) {
                double center = origin + (px + 0.5) * bpPerPixel;
                int bin1 = data.binAt(chr, center - half);
                int bin2 = data.binAt(chr, center + half);

                int rgb = WHITE;
                if (bin1 >= 0 && bin2 >= 0
                        && (windowFirstBin < 0 || (bin1 >= windowFirstBin && bin2 <= windowLastBin))) {
                    double v = data.getValue(Math.min(bin1, bin2), Math.max(bin1, bin2));
                    rgb = HiCColorScale.rgb(tf.apply(v, floor), vmin, vmax, drawPalette, drawAnchors);
                }
                row[px] = rgb;
            }
            img.setRGB(0, py, w, 1, row, 0, w);
        }
        return limits;
    }

    /**
     * Square display: genomic position on both axes, so the matrix stays aligned with the
     * chromosome ruler above it. A B x B contact cell is square only when the drawable area
     * is square, which is why {@link HiCTrack} resizes itself to height = width when the
     * display is switched to square (pyGenomeTracks derives the track height the same way).
     */
    public static double[] paintSquare(BufferedImage img, HiCSource data, String chr,
                                   double origin, double end,
                                   double minValue, double maxValue,
                                   HiCTransform transform,
                                   HiCColorScale.Palette palette, int[][] customAnchors) {

        final int w = img.getWidth();
        final int h = img.getHeight();
        final double span = end - origin;
        if (span <= 0 || w < 1 || h < 1) {
            return null;
        }

        final HiCTransform tf = (transform == null) ? HiCTransform.LINEAR : transform;
        double[] limits = colorLimits(data, chr, origin, end, span, minValue, maxValue, tf);
        final double vmin = limits[0];
        final double vmax = limits[1];
        final double floor = limits[2];

        final HiCColorScale.Palette drawPalette = tf.reversesRamp() ? HiCColorScale.Palette.CUSTOM : palette;
        final int[][] drawAnchors = tf.reversesRamp()
                ? HiCColorScale.reversedAnchors(palette, customAnchors) : customAnchors;

        // The column -> bin mapping only depends on x, so it is computed once instead of
        // once per pixel, and each row goes back to the image in a single setRGB call.
        final int[] binXs = new int[w];
        for (int px = 0; px < w; px++) {
            binXs[px] = data.binAt(chr, origin + (px + 0.5) * (span / w));
        }
        final int[] row = new int[w];
        final double bpPerRow = span / h;

        for (int py = 0; py < h; py++) {
            int binY = data.binAt(chr, origin + (py + 0.5) * bpPerRow);

            for (int px = 0; px < w; px++) {
                int binX = binXs[px];

                int rgb = WHITE;
                if (binX >= 0 && binY >= 0) {
                    double v = data.getValue(Math.min(binX, binY), Math.max(binX, binY));
                    rgb = HiCColorScale.rgb(tf.apply(v, floor), vmin, vmax, drawPalette, drawAnchors);
                }
                row[px] = rgb;
            }
            img.setRGB(0, py, w, 1, row, 0, w);
        }
        return limits;
    }

    /**
     * Colour limits, following pyGenomeTracks: the matrix is transformed first, then the
     * 80th percentile of near-diagonal contacts becomes the maximum (so isolated hot spots
     * do not flatten the map) and the median of long-range contacts becomes the minimum.
     *
     * @return {transformedMin, transformedMax, positiveFloor}
     */
    static double[] colorLimits(HiCSource data, String chr, double origin, double end,
                                double horizon, double minValue, double maxValue,
                                HiCTransform transform) {

        final HiCTransform tf = (transform == null) ? HiCTransform.LINEAR : transform;

        // Consider bins over the whole visible distance range, not just the viewport.
        int[] bins = data.getBinsOverlapping(chr, (int) Math.floor(origin - horizon),
                (int) Math.ceil(end + horizon));
        if (bins.length < 2) {
            return new double[]{0, 1, 1};
        }

        // vmax sample: contacts one bin away from the diagonal
        List<Double> adjacent = new ArrayList<>();
        for (int i = 0; i + 1 < bins.length; i++) {
            double v = data.getValue(bins[i], bins[i + 1]);
            if (v > 0) {
                adjacent.add(v);
            }
        }

        // vmin sample: contacts separated by roughly the viewport width. If that distance
        // leaves too few values (narrow window, sparse matrix), shorten it, as pyGenomeTracks
        // does, so the minimum is still derived from real data.
        int wanted = Math.max(1, (int) Math.round((end - origin) / Math.max(1, data.getBinSize())));
        List<Double> distant = new ArrayList<>();
        // Shrink the probe distance geometrically rather than one bin at a time: on a sparse
        // matrix the old loop could issue millions of getValue calls (each a possible block
        // inflate) for a single set of colour limits.
        for (int offset = wanted; ; ) {
            distant.clear();
            for (int i = 0; i + offset < bins.length; i++) {
                double v = data.getValue(bins[i], bins[i + offset]);
                if (v > 0) {
                    distant.add(v);
                }
            }
            if (distant.size() > 5 || offset <= 1) {
                break;
            }
            final int next = offset / 2;
            offset = (next == offset) ? offset - 1 : next;
        }

        // Floor for log transforms: smallest positive count in the sample.
        double floor = Math.min(minPositive(adjacent), minPositive(distant));
        if (!(floor > 0)) {
            floor = 1;
        }

        // User supplied limits are raw counts, so transform them too.
        if (!Double.isNaN(minValue) && !Double.isNaN(maxValue) && maxValue > minValue) {
            return new double[]{tf.apply(minValue, floor), tf.apply(maxValue, floor), floor};
        }

        double vmax;
        if (adjacent.isEmpty()) {
            vmax = tf.apply(1, floor);
        } else {
            Collections.sort(adjacent);
            double raw = adjacent.get(Math.min(adjacent.size() - 1, (int) (adjacent.size() * 0.8)));
            vmax = tf.apply(raw, floor);
        }

        // The ramp always starts at zero, which is the HiCExplorer / pyGenomeTracks default.
        // Anchoring the minimum on the median of long-range contacts (the old behaviour)
        // squeezed the whole low end into a single dark colour and made the legend start at
        // an arbitrary value instead of zero.
        double vmin = tf.apply(0, floor);

        if (vmin >= vmax) {
            vmax = vmin + (tf == HiCTransform.LINEAR ? 1 : 1e-3);
        }
        return new double[]{vmin, vmax, floor};
    }

    private static double minPositive(List<Double> values) {
        double min = Double.NaN;
        for (double v : values) {
            if (v > 0 && (Double.isNaN(min) || v < min)) {
                min = v;
            }
        }
        return min;
    }
}
