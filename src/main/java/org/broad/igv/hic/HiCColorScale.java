package org.broad.igv.hic;

import java.awt.Color;

/**
 * Color mapping for Hi-C contact matrices.
 * <p>
 * A track's {@code color} property has no effect on a heat map, so the palette is chosen
 * here instead. {@code RdYlBu_r} matches pyGenomeTracks' default
 * ({@code DEFAULT_MATRIX_COLORMAP}): low contact frequency is dark blue, high frequency
 * passes through yellow to dark red.
 */
public final class HiCColorScale {

    /** Available palettes, ordered low -> high contact frequency. */
    public enum Palette {

        RDYLBU_R("RdYlBu_r", new int[][]{
                {49, 54, 149}, {69, 117, 180}, {116, 173, 209}, {171, 207, 236},
                {224, 243, 248}, {255, 255, 191}, {254, 224, 144}, {253, 174, 97},
                {244, 109, 67}, {215, 48, 39}, {165, 0, 38}
        }),

        VIRIDIS("Viridis", new int[][]{
                {68, 1, 84}, {72, 40, 120}, {62, 74, 137}, {49, 104, 142},
                {38, 130, 142}, {31, 158, 137}, {53, 183, 121}, {109, 205, 89},
                {180, 222, 44}, {253, 231, 37}
        }),

        REDS("Reds", new int[][]{
                {255, 245, 240}, {254, 224, 210}, {252, 187, 161}, {252, 146, 114},
                {251, 106, 74}, {239, 59, 44}, {203, 24, 29}, {153, 0, 13}, {103, 0, 13}
        }),

        BLUES("Blues", new int[][]{
                {247, 251, 255}, {222, 235, 247}, {198, 219, 239}, {158, 202, 225},
                {107, 174, 214}, {66, 146, 198}, {33, 113, 181}, {8, 81, 156}, {8, 48, 107}
        }),

        GREYS("Greys", new int[][]{
                {255, 255, 255}, {240, 240, 240}, {217, 217, 217}, {189, 189, 189},
                {150, 150, 150}, {115, 115, 115}, {82, 82, 82}, {37, 37, 37}, {0, 0, 0}
        }),

        /** Anchors supplied by the user through "Custom colors...". */
        CUSTOM("Custom...", null);

        private final String label;
        private final int[][] anchors;

        Palette(String label, int[][] anchors) {
            this.label = label;
            this.anchors = anchors;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private HiCColorScale() {
        // Utility class
    }

    public static int rgb(double value, double min, double max) {
        return rgb(value, min, max, Palette.RDYLBU_R);
    }

    /**
     * @param value   contact value
     * @param min     value mapped to the first anchor color
     * @param max     value mapped to the last anchor color
     * @param palette palette to sample
     * @return packed RGB
     */
    public static int rgb(double value, double min, double max, Palette palette) {

        if (Double.isNaN(value)) {
            return 0xFFFFFF;
        }

        int[][] anchors = anchorsFor(palette, null);

        double t = (max > min) ? (value - min) / (max - min) : 0;
        if (t <= 0) {
            return pack(anchors[0]);
        }
        if (t >= 1) {
            return pack(anchors[anchors.length - 1]);
        }

        double scaled = t * (anchors.length - 1);
        int index = (int) scaled;
        double frac = scaled - index;

        int[] c0 = anchors[index];
        int[] c1 = anchors[index + 1];

        int r = (int) Math.round(c0[0] + (c1[0] - c0[0]) * frac);
        int g = (int) Math.round(c0[1] + (c1[1] - c0[1]) * frac);
        int b = (int) Math.round(c0[2] + (c1[2] - c0[2]) * frac);

        return (r << 16) | (g << 8) | b;
    }

    public static Color color(double value, double min, double max, Palette palette) {
        return new Color(rgb(value, min, max, palette));
    }

    /**
     * @param customAnchors anchors used when {@code palette} is {@link Palette#CUSTOM}
     */
    public static int rgb(double value, double min, double max, Palette palette, int[][] customAnchors) {
        if (Double.isNaN(value)) {
            return 0xFFFFFF;
        }
        int[][] anchors = anchorsFor(palette, customAnchors);
        double t = (max > min) ? (value - min) / (max - min) : 0;
        if (t <= 0) {
            return pack(anchors[0]);
        }
        if (t >= 1) {
            return pack(anchors[anchors.length - 1]);
        }
        double scaled = t * (anchors.length - 1);
        int index = (int) scaled;
        double frac = scaled - index;
        int[] c0 = anchors[index];
        int[] c1 = anchors[index + 1];
        int r = (int) Math.round(c0[0] + (c1[0] - c0[0]) * frac);
        int g = (int) Math.round(c0[1] + (c1[1] - c0[1]) * frac);
        int b = (int) Math.round(c0[2] + (c1[2] - c0[2]) * frac);
        return (r << 16) | (g << 8) | b;
    }

    static int[][] anchorsFor(Palette palette, int[][] customAnchors) {
        if (palette == Palette.CUSTOM
                && customAnchors != null && customAnchors.length >= 2) {
            return customAnchors;
        }
        if (palette == null || palette == Palette.CUSTOM) {
            return Palette.RDYLBU_R.anchors;
        }
        return palette.anchors;
    }

    /**
     * @return the anchors of {@code palette} in reverse order, used by the {@code -log} transform
     */
    public static int[][] reversedAnchors(Palette palette, int[][] customAnchors) {
        int[][] src = anchorsFor(palette, customAnchors);
        int[][] out = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[src.length - 1 - i];
        }
        return out;
    }

    /**
     * Build a smooth ramp from one, two or three user chosen colors.
     *
     * @return 11 anchor colors, or null when inputs are unusable
     */
    public static int[][] buildCustomAnchors(Color low, Color mid, Color high) {
        if (low == null || high == null) {
            return null;
        }
        int[][] anchors = new int[11][];
        if (mid == null) {
            for (int i = 0; i < 11; i++) {
                anchors[i] = lerp(rgbOf(low), rgbOf(high), i / 10.0);
            }
        } else {
            for (int i = 0; i < 11; i++) {
                double t = i / 10.0;
                anchors[i] = (t <= 0.5)
                        ? lerp(rgbOf(low), rgbOf(mid), t * 2)
                        : lerp(rgbOf(mid), rgbOf(high), (t - 0.5) * 2);
            }
        }
        return anchors;
    }

    private static int[] rgbOf(Color c) {
        return new int[]{c.getRed(), c.getGreen(), c.getBlue()};
    }

    private static int[] lerp(int[] a, int[] b, double t) {
        return new int[]{
                (int) Math.round(a[0] + (b[0] - a[0]) * t),
                (int) Math.round(a[1] + (b[1] - a[1]) * t),
                (int) Math.round(a[2] + (b[2] - a[2]) * t)
        };
    }

    private static int pack(int[] c) {
        return (c[0] << 16) | (c[1] << 8) | c[2];
    }
}
