package org.broad.igv.hic;

/**
 * Value transform applied to raw contact counts before colour mapping.
 * <p>
 * Mirrors pyGenomeTracks' {@code transform} option ({@code HiCMatrixTrack}), where the
 * matrix is transformed first and the colour limits are then derived from the transformed
 * values. Hi-C counts decay quickly with distance, so a linear ramp usually compresses
 * most of the map into the lowest colour; {@code log1p} is the template default there.
 */
public enum HiCTransform {

    LINEAR("Linear"),
    LOG1P("log1p"),
    LOG("log"),
    NEG_LOG("-log");

    private final String label;

    HiCTransform(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** {@code -log} is {@code log} drawn with the colour ramp reversed. */
    public boolean reversesRamp() {
        return this == NEG_LOG;
    }

    /** log-based transforms need a positive floor because contact counts are often zero. */
    public boolean needsPositiveFloor() {
        return this == LOG || this == NEG_LOG;
    }

    /**
     * @param value raw contact count
     * @param floor smallest positive count in the current sample, used for zeros in the
     *              {@code log} / {@code -log} modes (pyGenomeTracks replaces zeros with it)
     */
    public double apply(double value, double floor) {
        switch (this) {
            case LOG1P:
                return Math.log10(1 + Math.max(value, 0));
            case LOG:
                return Math.log10(Math.max(value, floor));
            case NEG_LOG:
                // Negating the data would invert the min/max ordering; instead the ramp is
                // reversed at draw time, which is what "-log" means visually.
                return Math.log10(Math.max(value, floor));
            default:
                return value;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
