package org.broad.igv.bedpe;

import java.awt.geom.Path2D;

/**
 * Cubic Bézier arch used by the "Bézier arc" style.
 * <p>
 * The two control points sit on verticals through the ends, at 4/3 of the requested apex
 * height. With that ratio a cubic Bézier peaks at exactly the requested height at its
 * midpoint, so switching between the circular and the Bézier style keeps the envelope
 * (which loop is taller) identical and only changes the curvature.
 */
public final class BezierArc {

    private BezierArc() {
        // utility
    }

    /**
     * @param x0     left end in pixels
     * @param x1     right end in pixels
     * @param baseY  y of the baseline (the chromosome axis)
     * @param height apex height above (or below, when {@code up} is false) the baseline
     * @param up     true for arcs above the baseline
     * @param closed close the path along the baseline, for filling
     */
    public static Path2D.Double path(double x0, double x1, double baseY, double height,
                                     boolean up, boolean closed) {
        double dir = up ? -1 : 1;
        double apex = baseY + dir * height;
        double ctrlY = baseY + dir * (4.0 * height / 3.0);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0, baseY);
        p.curveTo(x0, ctrlY, x1, ctrlY, x1, baseY);
        if (closed) {
            p.lineTo(x0, baseY);
            p.closePath();
        }
        return p;
    }

    /** Hit test for a Bézier arch: distance to the sampled curve, not to a bounding box. */
    public static class Hit implements BedPEShape {

        private static final int SAMPLES = 24;
        private final double[] xs = new double[SAMPLES + 1];
        private final double[] ys = new double[SAMPLES + 1];

        public Hit(double x0, double x1, double baseY, double height, boolean up) {
            double dir = up ? -1 : 1;
            double c1y = baseY + dir * (4.0 * height / 3.0);
            for (int i = 0; i <= SAMPLES; i++) {
                double t = i / (double) SAMPLES;
                double mt = 1 - t;
                double b0 = mt * mt * mt;
                double b1 = 3 * mt * mt * t;
                double b2 = 3 * mt * t * t;
                double b3 = t * t * t;
                xs[i] = b0 * x0 + b1 * x0 + b2 * x1 + b3 * x1;
                ys[i] = b0 * baseY + b1 * c1y + b2 * c1y + b3 * baseY;
            }
        }

        @Override
        public boolean contains(double x, double y) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < xs.length - 1; i++) {
                best = Math.min(best, segmentDistance(x, y, xs[i], ys[i], xs[i + 1], ys[i + 1]));
            }
            return best <= 3.5;
        }

        private static double segmentDistance(double px, double py,
                                              double ax, double ay, double bx, double by) {
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            double qx = ax + t * dx - px;
            double qy = ay + t * dy - py;
            return Math.sqrt(qx * qx + qy * qy);
        }
    }
}
