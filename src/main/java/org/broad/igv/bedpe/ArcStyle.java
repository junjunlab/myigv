package org.broad.igv.bedpe;

/**
 * Shape of the curve used to draw a loop / interaction.
 * <p>
 * {@code CIRCULAR} is the historical IGV look: a circular arc whose radius follows the
 * feature width, so nested loops stay tangent at the baseline - the feet meet the axis at an
 * angle. {@code ELLIPSE} is the half ellipse with semi-axes (width/2, height), which gives
 * vertical feet and a rounded top; that is the look used by HiCExplorer / pyGenomeTracks
 * loop figures. {@code BEZIER} is a cubic Bézier arch with the same apex height: much flatter
 * on top, useful when many overlapping loops would otherwise hide each other.
 */
public enum ArcStyle {

    CIRCULAR("Circular arc"),
    ELLIPSE("Ellipse arc"),
    BEZIER("Bezier arc");

    private final String label;

    ArcStyle(String label) {
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
