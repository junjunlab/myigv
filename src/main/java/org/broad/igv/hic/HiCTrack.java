package org.broad.igv.hic;

import htsjdk.tribble.Feature;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.ui.IGV;
import org.broad.igv.track.AbstractTrack;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.Track;
import org.broad.igv.track.RenderContext;
import org.broad.igv.track.TrackClickEvent;
import org.broad.igv.track.TrackMenuUtils;
import org.broad.igv.track.TrackType;
import org.broad.igv.ui.panel.IGVPopupMenu;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.broad.igv.util.ResourceLocator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IGV track that renders a Hi-C contact matrix.
 * <p>
 * Two display modes mirror pyGenomeTracks:
 * <ul>
 *   <li>{@code TRIANGLE} — contacts rotated 45°, x is the genomic midpoint and y the
 *       distance between the two anchors (hic_matrix)</li>
 *   <li>{@code SQUARE} — plain matrix with genomic position on both axes (hic_matrix_square)</li>
 * </ul>
 */
public class HiCTrack extends AbstractTrack {

    public enum Mode {
        TRIANGLE, SQUARE
    }

    private final HiCSource data;
    private Mode mode = Mode.TRIANGLE;

    /**
     * Mirror the whole plot vertically (the "inverted triangle" orientation): the
     * diagonal baseline moves to the top edge and the apex hangs down. Applied to the
     * composited image, so the matrix, every TAD overlay and any overlay added later
     * always share the same orientation.
     */
    private boolean flipped = false;

    /** Where the colour scale legend is drawn inside the track. */
    public enum LegendPosition {
        TOP_LEFT("Top left"), TOP_RIGHT("Top right"),
        BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right"), NONE("None");

        private final String label;

        LegendPosition(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private static final Logger log = LogManager.getLogger(HiCTrack.class);

    private LegendPosition legendPosition = LegendPosition.TOP_LEFT;

    private HiCColorScale.Palette palette = HiCColorScale.Palette.RDYLBU_R;

    /** Ramp anchors for {@link HiCColorScale.Palette#CUSTOM}. */
    private int[][] customAnchors;

    private Color customLow = new Color(49, 54, 149);
    private Color customMid = new Color(255, 255, 191);
    private Color customHigh = new Color(165, 0, 38);

    /**
     * Maximum separation plotted in triangle mode, in base pairs. NaN means automatic:
     * the plot fills the track height, which keeps every contact cell square at any zoom.
     */
    private double depth = Double.NaN;

    /**
     * Triangle vertical zoom: the fraction of the visible region that spans the track
     * height, so 1.0 shows the whole triangle up to its apex. NaN = automatic, which
     * keeps the 45 degree mapping undistorted and renders every contact cell as a square.
     */
    private double heightRatio = Double.NaN;

    /**
     * Forced bin size in bp for multi-resolution files, or -1 to follow the zoom level
     * automatically (one bin per pixel keeps the plot free of aliasing stripes).
     */
    private int forcedResolution = -1;

    /** Value transform applied before colour mapping; log1p matches pyGenomeTracks' template. */
    private HiCTransform transform = HiCTransform.LOG1P;

    /** TAD / domain outlines drawn on top of the matrix, one per attached BED track. */
    private final List<HiCDomainOverlay> domainOverlays = new ArrayList<>();

    /** Explicit color limits (raw counts); NaN means "compute automatically". */
    private double minValue = Double.NaN;
    private double maxValue = Double.NaN;

    public HiCTrack(ResourceLocator locator, HiCSource data) {
        super(locator, locator.getPath(), locator.getFileName());
        this.data = data;
        setHeight(220);
    }

    public HiCSource getData() {
        return data;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isFlipped() {
        return flipped;
    }

    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
    }

    public LegendPosition getLegendPosition() {
        return legendPosition;
    }

    public void setLegendPosition(LegendPosition position) {
        this.legendPosition = (position == null ? LegendPosition.NONE : position);
    }

    /** Colour limits for the current view, cached because sampling the matrix is not free. */
    /** Identity of the colour limits for one view; shared by the renderer and the legend. */
    private String limitsKey(String chr, double origin, double end, int width) {
        // Deliberately not data.getBinSize(): with several facets visible the render thread
        // switches resolution between panels, so that value drifts and the legend would keep
        // re-sampling the matrix on the EDT. Width is what fixes the sampling grid.
        return chr + "|" + (long) origin + "|" + (long) end + "|" + width + "|" + transform
                + "|" + minValue + "|" + maxValue + "|" + forcedResolution;
    }

    private double[] limitsFor(String chr, double origin, double end, int width) {
        String key = limitsKey(chr, origin, end, width);
        double[] cached = limitsCache.get(key);
        if (cached != null) {
            return cached;
        }
        double[] limits = HiCRenderer.colorLimits(data, chr, origin, end, end - origin,
                minValue, maxValue, transform);
        limitsCache.put(key, limits);
        return limits;
    }

    public HiCTransform getTransform() {
        return transform;
    }

    public void setTransform(HiCTransform transform) {
        this.transform = (transform == null ? HiCTransform.LINEAR : transform);
    }

    public HiCColorScale.Palette getPalette() {
        return palette;
    }

    public void setPalette(HiCColorScale.Palette palette) {
        this.palette = (palette == null ? HiCColorScale.Palette.RDYLBU_R : palette);
    }

    public double getDepth() {
        return depth;
    }

    public void setDepth(double depth) {
        this.depth = depth;
    }

    public double getHeightRatio() {
        return heightRatio;
    }

    public void setHeightRatio(double heightRatio) {
        this.heightRatio = heightRatio;
    }

    public void setColorLimits(double min, double max) {
        this.minValue = min;
        this.maxValue = max;
    }

    /**
     * Lower explicit colour limit, in raw contact counts.
     *
     * @return the configured minimum, or {@link Double#NaN} when no limit is set and the
     *         value is computed automatically from the visible matrix ("auto" scaling)
     */
    public double getMinValue() {
        return minValue;
    }

    /**
     * Upper explicit colour limit, in raw contact counts.
     *
     * @return the configured maximum, or {@link Double#NaN} when no limit is set and the
     *         value is computed automatically from the visible matrix ("auto" scaling)
     */
    public double getMaxValue() {
        return maxValue;
    }

    /**
     * Forced bin size (resolution) for multi-resolution Hi-C files, in base pairs.
     *
     * @return the forced bin size in bp, or {@code -1} when the resolution follows the
     *         zoom level automatically
     */
    public int getForcedResolution() {
        return forcedResolution;
    }

    /**
     * Force a bin size (resolution) for multi-resolution Hi-C files, or restore the
     * zoom-dependent automatic selection. This is the single place that applies a
     * resolution change: the "Resolution" menu items and external callers share it.
     *
     * @param bp bin size in base pairs; {@code -1} (or any value &lt;= 0) selects automatic
     *           zoom-based resolution
     */
    public void setForcedResolution(int bp) {
        this.forcedResolution = bp;
        repaintSelf();
    }

    /**
     * Ramp anchors for the {@link HiCColorScale.Palette#CUSTOM} colour palette.
     *
     * @return the anchor array, or {@code null} when no custom ramp has been configured
     *         (one of the built-in palettes is in use)
     */
    public int[][] getCustomAnchors() {
        return customAnchors;
    }

    @Override
    public TrackType getTrackType() {
        return TrackType.OTHER;
    }

    @Override
    public void load(ReferenceFrame frame) {
        // The matrix is fully loaded at construction time; nothing to do per frame.
    }

    @Override
    public boolean isReadyToPaint(ReferenceFrame frame) {
        return data != null;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Background renderer. A whole-chromosome view can take ~1 s to inflate all the
     * blocks it needs, which is unacceptable on the event thread - the UI would freeze
     * and clicks would be dropped. The last image stays on screen until the new one is
     * ready.
     */
    private static final ExecutorService RENDER_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hi-c-render");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Drawable width of the last paint, used by "Make track square". */
    private transient int lastDrawnWidth;

    /** Square display has already been given a square track height once. */
    private transient boolean squareHeightAdjusted;

    /**
     * Finished frames, one per view. A faceted session paints this same track instance once
     * per panel, each with a different key. With a single slot the panels evicted each other,
     * and every completion repainted all of them, so the losers were resubmitted forever -
     * the "cannot stop flickering" report.
     */
    private transient final Map<String, BufferedImage> frameCache =
            Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 8;
                }
            });

    /** Last finished frame of any view, shown while a new one is still rendering. */
    private transient volatile BufferedImage lastFrame;

    /** Views whose render is queued or running; per key, so facets do not cancel each other. */
    private transient final Set<String> pendingKeys =
            Collections.synchronizedSet(new LinkedHashSet<>());

    /** Bound on queued views: a drag repaints per pixel and only the newest ones matter. */
    private static final int MAX_PENDING = 12;

    /**
     * Marks {@code key} as queued. When more than {@link #MAX_PENDING} views are waiting the
     * oldest is dropped; its task sees the key missing and returns without rendering, which
     * keeps the supersede behaviour a single-slot pending key used to give us.
     */
    private boolean enqueueRender(String key) {
        synchronized (pendingKeys) {
            if (!pendingKeys.add(key)) {
                return false;
            }
            while (pendingKeys.size() > MAX_PENDING) {
                java.util.Iterator<String> it = pendingKeys.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

    /** Matrix-only images, one per view (see frameCache). */
    private transient final Map<String, BufferedImage> matrixCache =
            Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 8;
                }
            });

    /** Colour limits per view, sampled off the EDT by the renderer. */
    private transient final Map<String, double[]> limitsCache =
            Collections.synchronizedMap(new LinkedHashMap<String, double[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                    return size() > 16;
                }
            });

    /** Cached colour ramp of the legend bar, keyed by size and limits. */
    private transient BufferedImage rampImage;
    private transient String rampKey = "";

    private static String rampSignature(double vmin, double vmax,
                                        HiCColorScale.Palette palette, int[][] anchors) {
        return vmin + "|" + vmax + "|" + palette + "|" + (anchors == null ? 0 : anchors.length)
                + "|" + (anchors == null ? 0 : java.util.Arrays.deepHashCode(anchors));
    }

    @Override
    public void render(RenderContext context, Rectangle rect) {

        Graphics2D g = context.getGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(rect.x, rect.y, rect.width, rect.height);

        if (data == null || rect.width < 1 || rect.height < 1) {
            return;
        }

        final String chr = context.getChr();
        final double origin = context.getOrigin();
        final double end = context.getEndLocation();
        if (end <= origin || data.getChromBinRange(chr) == null) {
            return;
        }

        final int w = rect.width;
        final int h = rect.height;
        lastDrawnWidth = w;

        // Swing exposes the OS HiDPI scale (1.5x at 150% Windows scaling) as a transform on
        // this Graphics2D. Rendering the cache at logical w x h and letting drawImage upscale
        // it stretches each logical pixel into alternating 1/2 device pixels, so a 45 degree
        // outline reads as a chain of square beads. Render the cache at device resolution and
        // map it back 1:1 instead.
        final AffineTransform deviceTx = g.getTransform();
        final double deviceScale = Math.max(1.0, Math.min(3.0,
                Math.max(Math.abs(deviceTx.getScaleX()), Math.abs(deviceTx.getScaleY()))));
        final int dw = Math.max(1, (int) Math.ceil(w * deviceScale));
        final int dh = Math.max(1, (int) Math.ceil(h * deviceScale));

        if (mode == Mode.SQUARE && !squareHeightAdjusted && w > 0 && Math.abs(h - w) > 2) {
            squareHeightAdjusted = true;
            SwingUtilities.invokeLater(() -> {
                setHeight(w + 2);
                repaintSelf();
            });
        }
        final Mode renderMode = mode;
        final double renderDepth = depth;
        final double renderRatio = heightRatio;
        final HiCTransform renderTransform = transform;
        final double minV = minValue;
        final double maxV = maxValue;
        final HiCColorScale.Palette renderPalette = palette;
        final int[][] anchors = customAnchors;
        final boolean renderFlipped = flipped;

        final String matrixKey = chr + "|" + (long) origin + "|" + (long) end + "|" + w + "|" + h
                + "|" + renderMode + "|" + renderDepth + "|" + renderRatio + "|" + minV + "|" + maxV
                + "|" + renderTransform + "|" + forcedResolution
                + "|" + renderPalette + "|" + (anchors == null ? 0 : anchors.length)
                + "|" + dw + "x" + dh;
        final String key = matrixKey + "|" + overlaySignature() + "|" + renderFlipped;

        BufferedImage hit = frameCache.get(key);
        if (hit != null && hit.getWidth() == dw && hit.getHeight() == dh) {
            g.drawImage(hit, rect.x, rect.y, w, h, null);
        } else {
            if (enqueueRender(key)) {
                RENDER_POOL.submit(() -> {
                    boolean handedOff = false;
                    try {
                        // Another request for this exact view already replaced the frame (or
                        // the track was unloaded); nothing left to do.
                        if (!pendingKeys.contains(key)) {
                            return;
                        }
                        // Resolution has to match the zoom level, otherwise a whole-chromosome
                        // view samples ~30 bins per pixel and the matrix looks striped.
                        double bpPerPixel = (end - origin) / Math.max(1, w);
                        if (forcedResolution > 0) {
                            data.setBinSize(forcedResolution);
                        } else {
                            data.autoSelectBinSize(bpPerPixel, w);
                        }

                        BufferedImage base = matrixCache.get(matrixKey);
                        double[] usedLimits = null;
                        if (base == null || base.getWidth() != dw || base.getHeight() != dh) {
                            base = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
                            if (renderMode == Mode.TRIANGLE) {
                                usedLimits = HiCRenderer.paintTriangle(base, data, chr, origin, end,
                                        renderDepth, renderRatio, minV, maxV, renderTransform,
                                        renderPalette, anchors);
                            } else {
                                usedLimits = HiCRenderer.paintSquare(base, data, chr, origin, end,
                                        minV, maxV, renderTransform, renderPalette, anchors);
                            }
                            matrixCache.put(matrixKey, base);
                        }

                        // Composite: matrix + overlays, so restyling only redraws the strokes.
                        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
                        Graphics2D composite = img.createGraphics();
                        composite.drawImage(base, 0, 0, null);
                        composite.dispose();
                        paintDomainOverlays(img, chr, origin, end, dw, dh, renderMode, renderRatio);

                        // Mirror the finished composite rather than the geometry. The matrix and
                        // every overlay flip together, so a BED bound *after* flipping is aligned
                        // automatically and HiCDomainOverlay needs no knowledge of the orientation.
                        final BufferedImage frame;
                        if (renderFlipped) {
                            frame = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
                            Graphics2D mirror = frame.createGraphics();
                            mirror.drawImage(img, 0, dh, dw, -dh, null);
                            mirror.dispose();
                        } else {
                            frame = img;
                        }

                        final double[] limitsForLegend = usedLimits;
                        SwingUtilities.invokeLater(() -> {
                            if (limitsForLegend != null) {
                                // Publish the limits the image was actually drawn with, so the
                                // legend never re-samples the matrix on the EDT (that sampling
                                // inflates .hic blocks and used to stall the UI) and can never
                                // disagree with the colours on screen.
                                limitsCache.put(limitsKey(chr, origin, end, w), limitsForLegend);
                            }
                            frameCache.put(key, frame);
                            lastFrame = frame;
                            pendingKeys.remove(key);
                            repaintSelf();
                        });
                        handedOff = true;
                    } finally {
                        if (!handedOff) {
                            // Never leave a view marked as pending: after a supersede or a
                            // failure it would never be rendered again.
                            pendingKeys.remove(key);
                        }
                    }
                });
            }
            // Show the most recent finished frame while the new one is being computed.
            BufferedImage fallback = lastFrame;
            if (fallback != null) {
                g.drawImage(fallback, rect.x, rect.y, w, h, null);
            }
        }

        // The legend is drawn outside the cached image: it stays upright when the plot is
        // flipped, and changing its position never forces a matrix re-render.
        paintColorLegend(g, rect, chr, origin, end);

        g.setColor(new Color(200, 200, 200));
        g.drawRect(rect.x, rect.y, rect.width - 1, rect.height - 1);
    }

    /**
     * Geometry of the legend box for this paint, or {@code null} when it must not be drawn.
     * <p>
     * Vertical size follows the *visible* part of the track: a tall track that is scrolled, or
     * a window too short for the panel, is painted with a clip shorter than its rect, and a
     * box anchored at rect.y would lose its bottom edge - exactly where the lower value label
     * sits (the "legend has no 0" report).
     * <p>
     * The horizontal anchor stays on the track rect, never on the clip: a partial repaint (a
     * popup closing, a tooltip) has a clip narrower than the track, and anchoring on the clip
     * moved the box into the middle of the matrix while the previous legend stayed on screen,
     * which showed up as two legends at once.
     */
    static Rectangle legendBox(Rectangle rect, Rectangle clip, LegendPosition position) {
        final int barW = 12;
        final int boxW = barW + 46;
        final int top = rect.y + 8;
        int bottom = rect.y + rect.height - 4;
        if (clip != null) {
            bottom = Math.min(bottom, clip.y + clip.height - 4);
        }
        // 36 px of chrome: title line above the bar, a little padding below it.
        if (bottom - top < 78 || rect.width < boxW + 16) {
            return null;
        }
        final int barH = Math.max(28, Math.min(90, bottom - top - 36));
        final boolean left = position == LegendPosition.TOP_LEFT || position == LegendPosition.BOTTOM_LEFT;
        final boolean topSide = position == LegendPosition.TOP_LEFT || position == LegendPosition.TOP_RIGHT;
        final int x = left ? rect.x + 8 : rect.x + rect.width - boxW - 8;
        final int boxH = barH + 30;
        final int boxY = topSide ? top : (bottom - boxH);
        final Rectangle box = new Rectangle(x, boxY, boxW, boxH);
        if (clip != null && !clip.contains(box)) {
            // Partially damaged track: skip this pass. The legend comes back with the next
            // full repaint instead of being drawn a second time somewhere else.
            return null;
        }
        return box;
    }

    /** Vertical colour bar matching the palette and value transform used for the matrix. */
    private void paintColorLegend(Graphics2D g, Rectangle rect, String chr,
                                  double origin, double end) {
        if (legendPosition == LegendPosition.NONE || data == null || rect.width < 80) {
            return;
        }
        double[] limits = limitsFor(chr, origin, end, rect.width);
        if (limits == null || !(limits[1] > limits[0])) {
            return;
        }
        final double vmin = limits[0];
        final double vmax = limits[1];
        final HiCColorScale.Palette drawPalette = transform.reversesRamp()
                ? HiCColorScale.Palette.CUSTOM : palette;
        final int[][] drawAnchors = transform.reversesRamp()
                ? HiCColorScale.reversedAnchors(palette, customAnchors) : customAnchors;

        final Rectangle box = legendBox(rect, g.getClipBounds(), legendPosition);
        if (box == null) {
            return;
        }
        final int barW = 12;
        final int barH = box.height - 30;
        final int barX = box.x + 7;
        final int barY = box.y + 20;
        final int x = box.x;
        final int y = box.y;
        final int boxW = box.width;
        final int boxH = box.height;

        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        try {
            g.setColor(new Color(255, 255, 255, 225));
            g.fillRect(x, y, boxW, boxH);
            g.setColor(new Color(130, 130, 130));
            g.drawRect(x, y, boxW, boxH);

            // Paint the ramp as one image rather than one drawLine per row. At a fractional
            // device scale (150% Windows scaling gives a 1.5x transform) a 1 px logical line
            // lands between device pixel rows, so alternating rows get blended with the white
            // panel behind them and the bar shows horizontal white seams. An opaque image is
            // resampled as a whole and cannot leak the background.
            if (rampImage == null || rampImage.getWidth() != barW || rampImage.getHeight() != barH
                    || !rampKey.equals(rampSignature(vmin, vmax, drawPalette, drawAnchors))) {
                rampImage = new BufferedImage(barW, barH, BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < barH; i++) {
                    double v = vmax - (i / (double) Math.max(1, barH - 1)) * (vmax - vmin);
                    int rgb = HiCColorScale.rgb(v, vmin, vmax, drawPalette, drawAnchors);
                    for (int j = 0; j < barW; j++) {
                        rampImage.setRGB(j, i, rgb);
                    }
                }
                rampKey = rampSignature(vmin, vmax, drawPalette, drawAnchors);
            }
            Object oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(rampImage, barX, barY, barW, barH, null);
            if (oldInterp != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
            }
            g.setColor(new Color(90, 90, 90));
            g.drawRect(barX, barY, barW, barH);

            g.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
            g.setColor(Color.DARK_GRAY);
            g.drawString(transform.getLabel(), x + 7, y + 13);
            g.setColor(Color.BLACK);
            g.drawString(formatLimit(vmax), barX + barW + 4, barY + 9);
            // Beside the bar, baseline on its last row, so the two numbers bracket the ramp.
            // The box is sized from the visible clip above, which keeps this label inside it.
            g.drawString(formatLimit(vmin), barX + barW + 4, barY + barH);
        } finally {
            g.setFont(oldFont);
            g.setColor(oldColor);
        }
    }

    private static String formatLimit(double v) {
        double a = Math.abs(v);
        if (a < 1e-9) {
            return "0";
        }
        if (a >= 100) {
            return String.format("%.0f", v);
        }
        if (a >= 10) {
            return String.format("%.1f", v);
        }
        return String.format("%.2f", v);
    }

    /**
     * Render the matrix into a standalone image (used for image export and tests).
     */
    public BufferedImage renderToImage(String chr, double origin, double end, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        if (mode == Mode.TRIANGLE) {
            HiCRenderer.paintTriangle(img, data, chr, origin, end, depth, heightRatio,
                    minValue, maxValue, transform, palette, customAnchors);
        } else {
            HiCRenderer.paintSquare(img, data, chr, origin, end, minValue, maxValue,
                    transform, palette, customAnchors);
        }
        if (flipped) {
            BufferedImage mirror = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = mirror.createGraphics();
            g2.drawImage(img, 0, height, width, -height, null);
            g2.dispose();
            return mirror;
        }
        return img;
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    @Override
    public IGVPopupMenu getPopupMenu(TrackClickEvent te) {

        IGVPopupMenu menu = TrackMenuUtils.getPopupMenu(Collections.singletonList(this), "Menu", te);

        // A heat map derives its colors from the palette, not from the track color, so the
        // generic color items would silently do nothing here.
        removeMenuItem(menu, "Change Track Color...");
        removeMenuItem(menu, "Change Track Color (Negative Values or Strand)...");

        // The linear view has the real click event (keeps dialogs parented to the clicked
        // component); every parameter change repaints through the shared onChange hook.
        attachSubmenus(menu, te, () -> repaintTrack(te));
        return menu;
    }

    /**
     * Attach the Hi-C parameter submenus to an arbitrary popup menu, for example the
     * circular genome view's own right-click menu. The three submenus are exactly the ones
     * {@link #getPopupMenu(TrackClickEvent)} exposes ("Hi-C display", "Hi-C color" and
     * "TAD domains") and are built by the same methods, so both views stay in sync.
     *
     * @param menu     popup menu the submenus are appended to
     * @param track    Hi-C track whose parameters the submenus edit
     * @param onChange run after every parameter change so the owning view can refresh;
     *                 may be {@code null}, which is treated as a no-op
     */
    public static void addHiCSubmenus(javax.swing.JPopupMenu menu, HiCTrack track, Runnable onChange) {
        if (menu == null || track == null) {
            return;
        }
        Runnable notify = onChange;
        if (notify == null) {
            notify = () -> { };
        }
        track.attachSubmenus(menu, null, notify);
    }

    /** Single attachment point shared by the linear and circular popup menus. */
    private void attachSubmenus(JPopupMenu menu, TrackClickEvent te, Runnable onChange) {
        menu.addSeparator();
        menu.add(buildDisplayMenu(te, onChange));
        menu.add(buildColorMenu(te, onChange));
        menu.add(buildDomainMenu(te, onChange));
    }

    private JMenu buildDisplayMenu(TrackClickEvent te, Runnable onChange) {

        JMenu displayMenu = new JMenu("Hi-C display");
        ButtonGroup group = new ButtonGroup();

        JRadioButtonMenuItem triangle = new JRadioButtonMenuItem("Triangle (rotated 45°)", mode == Mode.TRIANGLE);
        triangle.addActionListener(e -> {
            setMode(Mode.TRIANGLE);
            onChange.run();
        });
        group.add(triangle);
        displayMenu.add(triangle);

        JRadioButtonMenuItem square = new JRadioButtonMenuItem("Square (matrix)", mode == Mode.SQUARE);
        square.addActionListener(e -> {
            setMode(Mode.SQUARE);
            squareHeightAdjusted = true;
            makeTrackSquare();
            onChange.run();
        });
        group.add(square);
        displayMenu.add(square);

        displayMenu.addSeparator();

        JCheckBoxMenuItem flip = new JCheckBoxMenuItem("Flip vertically (inverted triangle)", flipped);
        flip.setToolTipText("Mirror the whole track: the diagonal moves to the top and all TAD overlays flip with it.");
        flip.addActionListener(e -> {
            setFlipped(flip.isSelected());
            onChange.run();
        });
        displayMenu.add(flip);

        JMenu legendMenu = new JMenu("Color legend");
        ButtonGroup legendGroup = new ButtonGroup();
        for (LegendPosition lp : LegendPosition.values()) {
            JRadioButtonMenuItem legendItem = new JRadioButtonMenuItem(lp.getLabel(),
                    lp == legendPosition);
            legendItem.addActionListener(e -> {
                setLegendPosition(lp);
                onChange.run();
            });
            legendGroup.add(legendItem);
            legendMenu.add(legendItem);
        }
        displayMenu.add(legendMenu);

        displayMenu.addSeparator();

        JMenuItem setDepth = new JMenuItem("Set maximum depth...");
        setDepth.addActionListener(e -> {
            String current = Double.isNaN(depth) ? "" : String.valueOf((long) depth);
            String input = JOptionPane.showInputDialog(menuParent(te),
                    "Maximum distance to plot (bp), blank = automatic:", current);
            if (input == null) {
                return;
            }
            try {
                double value = input.trim().isEmpty() ? Double.NaN : Double.parseDouble(input.trim());
                if (Double.isNaN(value) || value > 0) {
                    setDepth(value);
                    onChange.run();
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(menuParent(te),
                        "Please enter a positive number, or leave the field blank for automatic.");
            }
        });
        displayMenu.add(setDepth);

        JMenuItem setRatio = new JMenuItem("Set triangle height (0-1)...");
        setRatio.addActionListener(e -> {
            String current = Double.isNaN(heightRatio) ? "" : String.valueOf(heightRatio);
            String input = JOptionPane.showInputDialog(menuParent(te),
                    "Triangle height, 0-1 (1 = whole triangle up to the apex), blank = automatic:",
                    current);
            if (input == null) {
                return;
            }
            try {
                double value = input.trim().isEmpty() ? Double.NaN : Double.parseDouble(input.trim());
                if (!Double.isNaN(value) && (value <= 0 || value > 1.25)) {
                    JOptionPane.showMessageDialog(menuParent(te),
                            "Please enter a value between 0 and 1.");
                    return;
                }
                setHeightRatio(value);
                onChange.run();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(menuParent(te),
                        "Please enter a number between 0 and 1, or leave the field blank.");
            }
        });
        displayMenu.add(setRatio);

        displayMenu.addSeparator();

        JMenu resolutionMenu = new JMenu("Resolution");
        List<Integer> resolutions = data.getResolutions();
        ButtonGroup resolutionGroup = new ButtonGroup();

        JRadioButtonMenuItem autoRes = new JRadioButtonMenuItem("Automatic", forcedResolution < 0);
        autoRes.setEnabled(!resolutions.isEmpty());
        autoRes.addActionListener(e -> {
            setForcedResolution(-1);
            onChange.run();
        });
        resolutionGroup.add(autoRes);
        resolutionMenu.add(autoRes);

        if (!resolutions.isEmpty()) {
            resolutionMenu.addSeparator();
            for (int bp : resolutions) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(formatBinSize(bp), forcedResolution == bp);
                item.addActionListener(e -> {
                    setForcedResolution(bp);
                    onChange.run();
                });
                resolutionGroup.add(item);
                resolutionMenu.add(item);
            }
        }
        displayMenu.add(resolutionMenu);

        displayMenu.addSeparator();

        JMenuItem makeSquare = new JMenuItem("Make track square (height = width)");
        makeSquare.setEnabled(mode == Mode.SQUARE);
        makeSquare.addActionListener(e -> {
            squareHeightAdjusted = true;
            makeTrackSquare();
            onChange.run();
        });
        displayMenu.add(makeSquare);

        return displayMenu;
    }

    /**
     * TAD / domain outlines: pick any number of loaded BED tracks, then tune the stroke.
     * Style changes apply to every outline of this Hi-C track, which keeps the controls
     * small; per-track colours are assigned automatically so two BEDs stay distinguishable.
     */
    private JMenu buildDomainMenu(TrackClickEvent te, Runnable onChange) {

        // Reflect the orientation here too: overlays are mirrored with the matrix, so a BED
        // bound after flipping lands on the same (inverted) geometry without extra settings.
        JMenu menu = new JMenu((domainOverlays.isEmpty()
                ? "TAD domains" : "TAD domains (" + domainOverlays.size() + ")")
                + (flipped ? " [flipped]" : ""));

        List<FeatureTrack> featureTracks = collectFeatureTracks();

        if (featureTracks.isEmpty()) {
            JMenuItem none = new JMenuItem("No BED / feature tracks loaded");
            none.setEnabled(false);
            menu.add(none);
            return menu;
        }

        // A checked JMenuItem always dismisses its popup, so selecting three files meant three
        // right-clicks. The dialog lets all of them be picked in one go; the per-file items
        // below stay for the common single toggle.
        JMenuItem multi = new JMenuItem("Select multiple...");
        // Defer: opening a modal dialog straight from a menu item can leave the popup painted
        // until the dialog is dismissed.
        multi.addActionListener(e ->
                SwingUtilities.invokeLater(() -> showDomainDialog(te, featureTracks, onChange)));
        menu.add(multi);
        menu.addSeparator();

        // One submenu per BED file: the stroke of each overlay is independent, so width,
        // style, colour and fill all live with the file they belong to. The menu icon shows
        // the current outline colour, which makes two overlays easy to tell apart.
        for (FeatureTrack ft : featureTracks) {
            HiCDomainOverlay ov = findOverlay(ft);
            JMenu fileMenu = new JMenu(ft.getName());
            if (ov != null) {
                fileMenu.setIcon(colorChip(ov.getLineColor()));
            }
            JCheckBoxMenuItem show = new JCheckBoxMenuItem("Show outline", ov != null);
            show.addActionListener(e -> {
                if (show.isSelected()) {
                    addOverlay(ft);
                } else {
                    removeOverlay(ft);
                }
                onChange.run();
            });
            fileMenu.add(show);
            if (ov != null) {
                List<HiCDomainOverlay> only = Collections.singletonList(ov);
                fileMenu.addSeparator();
                fileMenu.add(buildWidthMenu(only, te, onChange, "Line width"));
                fileMenu.add(buildStyleMenu(only, te, onChange, "Line style"));
                fileMenu.add(buildLineColorMenu(only, te, onChange, "Line color"));
                fileMenu.add(buildFillColorMenu(only, te, onChange, "Fill color"));
                fileMenu.add(buildFillOpacityMenu(only, te, onChange));
            }
            menu.add(fileMenu);
        }

        if (!domainOverlays.isEmpty()) {
            JMenuItem clear = new JMenuItem("Clear all");
            clear.addActionListener(e -> {
                for (HiCDomainOverlay ov : new ArrayList<>(domainOverlays)) {
                    if (ov.getTrack() != null) {
                        HiCDomainOverlay.release(ov.getTrack());
                    }
                }
                domainOverlays.clear();
                onChange.run();
            });
            menu.add(clear);
        }

        if (domainOverlays.size() > 1) {
            JMenu allMenu = new JMenu("All outlines");
            allMenu.add(buildWidthMenu(domainOverlays, te, onChange, "Line width"));
            allMenu.add(buildStyleMenu(domainOverlays, te, onChange, "Line style"));
            allMenu.add(buildLineColorMenu(domainOverlays, te, onChange, "Line color"));
            allMenu.add(buildFillColorMenu(domainOverlays, te, onChange, "Fill color"));
            allMenu.add(buildFillOpacityMenu(domainOverlays, te, onChange));
            menu.addSeparator();
            menu.add(allMenu);
        }

        return menu;
    }

    /** Small colour swatch used as a menu icon so overlays are identifiable at a glance. */
    private static Icon colorChip(Color color) {
        BufferedImage img = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color == null ? Color.BLACK : color);
        g.fillRect(1, 1, 10, 10);
        g.setColor(new Color(110, 110, 110));
        g.drawRect(1, 1, 10, 10);
        g.dispose();
        return new ImageIcon(img);
    }

    private JMenu buildWidthMenu(List<HiCDomainOverlay> targets, TrackClickEvent te, Runnable onChange, String label) {
        JMenu widthMenu = new JMenu(label);
        ButtonGroup group = new ButtonGroup();
        float current = targets.isEmpty() ? 2f : targets.get(0).getLineWidth();
        for (float px : new float[]{1f, 1.5f, 2f, 2.5f, 3f}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(px + " px", Math.abs(px - current) < 0.01f);
            item.addActionListener(e -> {
                for (HiCDomainOverlay ov : targets) {
                    ov.setLineWidth(px);
                }
                onChange.run();
            });
            group.add(item);
            widthMenu.add(item);
        }
        return widthMenu;
    }

    private JMenu buildStyleMenu(List<HiCDomainOverlay> targets, TrackClickEvent te, Runnable onChange, String label) {
        JMenu styleMenu = new JMenu(label);
        ButtonGroup group = new ButtonGroup();
        HiCDomainOverlay.LineStyle current = targets.isEmpty()
                ? HiCDomainOverlay.LineStyle.SOLID : targets.get(0).getLineStyle();
        for (HiCDomainOverlay.LineStyle ls : HiCDomainOverlay.LineStyle.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(ls.getLabel(), ls == current);
            item.addActionListener(e -> {
                for (HiCDomainOverlay ov : targets) {
                    ov.setLineStyle(ls);
                }
                onChange.run();
            });
            group.add(item);
            styleMenu.add(item);
        }
        return styleMenu;
    }

    /** Preset colours plus the colour chooser, so one overlay can be recoloured in two clicks. */
    private JMenu buildLineColorMenu(List<HiCDomainOverlay> targets, TrackClickEvent te, Runnable onChange, String label) {
        JMenu colorMenu = new JMenu(label);
        Color current = targets.isEmpty() ? Color.BLACK : targets.get(0).getLineColor();
        for (int i = 0; i < 6; i++) {
            Color preset = HiCDomainOverlay.defaultColor(i);
            JMenuItem item = new JMenuItem(colorName(i), colorChip(preset));
            if (preset.equals(current)) {
                item.setText(item.getText() + "  (current)");
            }
            item.addActionListener(e -> {
                for (HiCDomainOverlay ov : targets) {
                    ov.setLineColor(preset);
                }
                onChange.run();
            });
            colorMenu.add(item);
        }
        colorMenu.addSeparator();
        JMenuItem more = new JMenuItem("More colors...");
        more.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(
                    menuParent(te),
                    "Outline color", current);
            if (chosen != null) {
                for (HiCDomainOverlay ov : targets) {
                    ov.setLineColor(chosen);
                }
                onChange.run();
            }
        });
        colorMenu.add(more);
        return colorMenu;
    }

    private static String colorName(int index) {
        switch (Math.floorMod(index, 6)) {
            case 0: return "Black";
            case 1: return "Red";
            case 2: return "Green";
            case 3: return "Purple";
            case 4: return "Orange";
            default: return "Blue";
        }
    }

    private JMenu buildFillColorMenu(List<HiCDomainOverlay> targets, TrackClickEvent te, Runnable onChange, String label) {
        JMenu fillMenu = new JMenu(label);
        for (int i = 0; i < 6; i++) {
            Color preset = HiCDomainOverlay.defaultColor(i);
            JMenuItem item = new JMenuItem(colorName(i), colorChip(preset));
            item.addActionListener(e -> {
                for (HiCDomainOverlay ov : targets) {
                    ov.setFillColor(preset);
                }
                onChange.run();
            });
            fillMenu.add(item);
        }
        fillMenu.addSeparator();
        JMenuItem more = new JMenuItem("More colors...");
        more.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(
                    menuParent(te),
                    "Fill color", targets.isEmpty() ? Color.BLACK : targets.get(0).getFillColor());
            if (chosen != null) {
                for (HiCDomainOverlay ov : targets) {
                    ov.setFillColor(chosen);
                }
                onChange.run();
            }
        });
        fillMenu.add(more);
        return fillMenu;
    }

    private JMenu buildFillOpacityMenu(List<HiCDomainOverlay> targets, TrackClickEvent te, Runnable onChange) {
        JMenu fillMenu = new JMenu("Fill opacity");
        ButtonGroup group = new ButtonGroup();
        int currentAlpha = targets.isEmpty() ? 0 : targets.get(0).getFillAlpha();
        for (int pct : new int[]{0, 15, 30, 50, 100}) {
            int alpha = Math.round(pct * 255f / 100f);
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(pct + "%",
                    Math.abs(alpha - currentAlpha) <= 2);
            item.setToolTipText(pct == 0 ? "Fully transparent (default)" : null);
            item.addActionListener(e -> {
                for (HiCDomainOverlay ov : targets) {
                    ov.setFillAlpha(alpha);
                }
                onChange.run();
            });
            group.add(item);
            fillMenu.add(item);
        }
        return fillMenu;
    }

    private JMenu buildColorMenu(TrackClickEvent te, Runnable onChange) {

        JMenu colorMenu = new JMenu("Hi-C color");

        JMenu transformMenu = new JMenu("Transform");
        ButtonGroup transformGroup = new ButtonGroup();
        for (HiCTransform t : HiCTransform.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(t.getLabel(), t == transform);
            item.addActionListener(e -> {
                setTransform(t);
                onChange.run();
            });
            transformGroup.add(item);
            transformMenu.add(item);
        }
        colorMenu.add(transformMenu);
        colorMenu.addSeparator();

        JMenu paletteMenu = new JMenu("Palette");
        ButtonGroup group = new ButtonGroup();
        for (HiCColorScale.Palette p : HiCColorScale.Palette.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(p.getLabel(), p == palette);
            item.addActionListener(e -> {
                if (p == HiCColorScale.Palette.CUSTOM && !editCustomColors(te)) {
                    return;
                }
                setPalette(p);
                onChange.run();
            });
            group.add(item);
            paletteMenu.add(item);
        }
        colorMenu.add(paletteMenu);

        colorMenu.addSeparator();

        JMenuItem setMax = new JMenuItem("Set maximum value...");
        setMax.addActionListener(e -> editLimit(te, "Maximum contact value", true, onChange));
        colorMenu.add(setMax);

        JMenuItem setMin = new JMenuItem("Set minimum value...");
        setMin.addActionListener(e -> editLimit(te, "Minimum contact value", false, onChange));
        colorMenu.add(setMin);

        JMenuItem reset = new JMenuItem("Reset to automatic");
        reset.addActionListener(e -> {
            setColorLimits(Double.NaN, Double.NaN);
            onChange.run();
        });
        colorMenu.add(reset);

        return colorMenu;
    }

    /**
     * Ask for low / optional middle / high colors and build a ramp.
     *
     * @return true when the user supplied a usable ramp
     */
    private boolean editCustomColors(TrackClickEvent te) {

        Component parent = menuParent(te);

        Color low = JColorChooser.showDialog(parent, "Low contact color", customLow);
        if (low == null) {
            return false;
        }
        Color high = JColorChooser.showDialog(parent, "High contact color", customHigh);
        if (high == null) {
            return false;
        }

        Color mid = null;
        int addMid = JOptionPane.showConfirmDialog(parent,
                "Add a middle color? (two-color ramp otherwise)",
                "Custom palette", JOptionPane.YES_NO_CANCEL_OPTION);
        if (addMid == JOptionPane.CANCEL_OPTION) {
            return false;
        }
        if (addMid == JOptionPane.YES_OPTION) {
            mid = JColorChooser.showDialog(parent, "Middle contact color", customMid);
            if (mid == null) {
                return false;
            }
        }

        int[][] anchors = HiCColorScale.buildCustomAnchors(low, mid, high);
        if (anchors == null) {
            return false;
        }
        this.customAnchors = anchors;
        this.customLow = low;
        this.customMid = mid;
        this.customHigh = high;
        return true;
    }

    private void editLimit(TrackClickEvent te, String what, boolean isMax, Runnable onChange) {
        double current = isMax ? maxValue : minValue;
        String defaultValue = Double.isNaN(current) ? "" : formatValue(current);
        String input = JOptionPane.showInputDialog(menuParent(te),
                what + " (blank = automatic):", defaultValue);
        if (input == null) {
            return;
        }
        try {
            double value = input.trim().isEmpty() ? Double.NaN : Double.parseDouble(input.trim());
            if (isMax) {
                setColorLimits(minValue, value);
            } else {
                setColorLimits(value, maxValue);
            }
            onChange.run();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(menuParent(te),
                    "Please enter a number, or leave the field blank for automatic scaling.");
        }
    }

    private static String formatBinSize(int bp) {
        if (bp >= 1_000_000 && bp % 1_000_000 == 0) {
            return (bp / 1_000_000) + " Mb";
        }
        if (bp >= 1_000 && bp % 1_000 == 0) {
            return (bp / 1_000) + " kb";
        }
        return bp + " bp";
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String formatValue(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static void removeMenuItem(JPopupMenu menu, String text) {
        List<Component> toRemove = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem && text.equals(((JMenuItem) c).getText())) {
                toRemove.add(c);
            }
        }
        for (Component c : toRemove) {
            menu.remove(c);
        }
    }

    /**
     * Repaint the whole track. Repainting only the clicked component leaves other
     * regions (and other tiles) showing the previous display mode.
     */
    /**
     * Square display needs a square canvas: both axes span the same interval, so cells are
     * only square when height equals width. Grow the track once, the way pyGenomeTracks
     * derives the track height from the matrix proportions.
     */
    private void makeTrackSquare() {
        int target = lastDrawnWidth;
        if (target > 0 && Math.abs(getHeight() - target) > 2) {
            setHeight(target + 2);
        }
    }

    // ------------------------------------------------------------------
    // TAD / domain overlays
    // ------------------------------------------------------------------

    private String overlaySignature() {
        if (domainOverlays.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (HiCDomainOverlay ov : domainOverlays) {
            sb.append(ov.styleKey()).append(';');
        }
        return sb.toString();
    }

    /** True when at least one TAD / domain BED overlay is attached. */
    public boolean hasDomainOverlays() {
        return !domainOverlays.isEmpty();
    }

    /** Composite every attached TAD/domain BED outline onto an existing matrix image. Public so
     *  the circular view can bake the same overlays into its linear tile before the polar warp,
     *  giving the ring exactly the TAD outlines the linear track shows. */
    public void paintDomainOverlays(BufferedImage img, String chr, double origin, double end,
                                    int w, int h, Mode renderMode, double heightRatioValue) {

        if (domainOverlays.isEmpty()) {
            return;
        }
        List<HiCDomainOverlay> snapshot = new ArrayList<>(domainOverlays);

        double span = end - origin;
        double bpPerPixel = span / Math.max(1, w);
        double depthPerPixel = HiCRenderer.depthPerPixel(span, w, h, heightRatioValue);
        int queryStart = (int) Math.max(0, origin);
        int queryEnd = (int) Math.min(data.getChromosomeLength(chr), Math.ceil(end));

        // Strokes are drawn on a 2x supersampled transparent layer and averaged down. A 1-2 px
        // diagonal drawn straight onto an RGB heat map only lands 1-3 dark pixels per row, and
        // the anti-aliased edges blend into the bright matrix, so the eye reads a chain of dots
        // rather than a line. Supersampling (plus VALUE_STROKE_PURE, which stops Java2D from
        // snapping thin strokes to whole pixels) gives a continuous, evenly covered stroke.
        final int ss = (long) w * h <= 3_000_000L ? 2 : 1;
        BufferedImage layer = ss > 1
                ? new BufferedImage(w * ss, h * ss, BufferedImage.TYPE_INT_ARGB)
                : img;

        Graphics2D g2 = layer.createGraphics();
        if (ss > 1) {
            g2.scale(ss, ss);
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        try {
            for (HiCDomainOverlay ov : snapshot) {
                resolveTrack(ov);
                List<Feature> features = ov.features(chr, queryStart, queryEnd);
                if (features.isEmpty()) {
                    continue;
                }
                if (renderMode == Mode.TRIANGLE) {
                    ov.paintTriangle(g2, features, origin, w, h, bpPerPixel, depthPerPixel);
                } else {
                    ov.paintSquare(g2, features, origin, end, w, h);
                }
            }
        } finally {
            g2.dispose();
        }

        if (ss > 1) {
            Graphics2D out = img.createGraphics();
            out.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            out.drawImage(layer, 0, 0, w, h, null);
            out.dispose();
        }
    }

    /** Session restore may run before the BED tracks exist, so binding is resolved lazily. */
    private void resolveTrack(HiCDomainOverlay ov) {
        if (ov.getTrack() != null || !IGV.hasInstance()) {
            return;
        }
        for (Track t : IGV.getInstance().getAllTracks()) {
            if (!(t instanceof FeatureTrack)) {
                continue;
            }
            if (ov.getTrackName() != null && ov.getTrackName().equals(t.getName())) {
                final FeatureTrack featureTrack = (FeatureTrack) t;
                ov.setTrack(featureTrack);
                SwingUtilities.invokeLater(() -> HiCDomainOverlay.acquire(featureTrack));
                return;
            }
        }
    }

    private HiCDomainOverlay findOverlay(Track track) {
        String trackPath = null;
        try {
            ResourceLocator locator = track.getResourceLocator();
            trackPath = locator == null ? null : locator.getPath();
        } catch (RuntimeException ignored) {
            // tracks without a locator are matched by name only
        }
        for (HiCDomainOverlay ov : domainOverlays) {
            if (ov.getTrack() == track) {
                return ov;
            }
            // The bound instance can differ from the one the panel shows (session restore,
            // "Reload session", two FeatureTracks for one file). Fall back to the file path so
            // the menu checkbox and removeOverlay always agree with what is actually drawn.
            if (trackPath != null && ov.getTrackPath() != null && !ov.getTrackPath().isEmpty()
                    && trackPath.equals(ov.getTrackPath())) {
                return ov;
            }
            if (ov.getTrack() == null && ov.getTrackName() != null
                    && ov.getTrackName().equals(track.getName())) {
                return ov;
            }
        }
        return null;
    }

    private void addOverlay(FeatureTrack track) {
        if (findOverlay(track) != null) {
            return;
        }
        String path = null;
        try {
            ResourceLocator locator = track.getResourceLocator();
            path = locator == null ? null : locator.getPath();
        } catch (Exception ignored) {
            // tracks without a locator simply bind by name
        }
        HiCDomainOverlay overlay = new HiCDomainOverlay(track.getName(), path,
                HiCDomainOverlay.defaultColor(domainOverlays.size()));
        overlay.setTrack(track);
        domainOverlays.add(overlay);
        HiCDomainOverlay.acquire(track);
    }

    private void removeOverlay(Track track) {
        HiCDomainOverlay found = findOverlay(track);
        if (found != null) {
            domainOverlays.remove(found);
            if (found.getTrack() != null) {
                HiCDomainOverlay.release(found.getTrack());
            }
        }
    }

    /** Feature tracks that can be drawn on top of the matrix, in panel order. */
    private List<FeatureTrack> collectFeatureTracks() {
        List<FeatureTrack> tracks = new ArrayList<>();
        if (IGV.hasInstance()) {
            for (Track t : IGV.getInstance().getAllTracks()) {
                if (t instanceof FeatureTrack) {
                    tracks.add((FeatureTrack) t);
                }
            }
        }
        return tracks;
    }

    /**
     * Checkbox list for choosing several domain files at once. Selecting them one by one from
     * the popup menu is painful because every click closes the popup, and a plain JList cannot
     * show which files are already overlaid.
     */
    private void showDomainDialog(TrackClickEvent te, List<FeatureTrack> candidates, Runnable onChange) {

        Component parent = menuParent(te);
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, "TAD domains - " + getName(),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        final List<JCheckBox> boxes = new ArrayList<>();
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        for (FeatureTrack ft : candidates) {
            JCheckBox box = new JCheckBox(ft.getName(), findOverlay(ft) != null);
            box.putClientProperty("track", ft);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            boxes.add(box);
            list.add(box);
        }
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(320, Math.min(280, 28 * candidates.size() + 16)));
        scroll.setBorder(null);

        JLabel hint = new JLabel("Tick the BED files to outline, then press Enter.");
        hint.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));

        JButton selectAll = new JButton("All");
        JButton selectNone = new JButton("None");
        selectAll.addActionListener(e -> boxes.forEach(b -> b.setSelected(true)));
        selectNone.addActionListener(e -> boxes.forEach(b -> b.setSelected(false)));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            applyDomainSelection(candidates, boxes);
            dialog.dispose();
            onChange.run();
        });
        cancel.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        buttons.add(selectAll);
        buttons.add(selectNone);
        buttons.add(Box.createHorizontalStrut(18));
        buttons.add(cancel);
        buttons.add(ok);

        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(hint, BorderLayout.NORTH);
        dialog.getContentPane().add(scroll, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /** Apply the checked set: add overlays for new files, drop the unchecked ones. */
    private void applyDomainSelection(List<FeatureTrack> candidates, List<JCheckBox> boxes) {
        for (int i = 0; i < candidates.size() && i < boxes.size(); i++) {
            FeatureTrack ft = candidates.get(i);
            boolean wanted = boxes.get(i).isSelected();
            boolean present = findOverlay(ft) != null;
            if (wanted && !present) {
                addOverlay(ft);
            } else if (!wanted && present) {
                removeOverlay(ft);
            }
        }
    }

    /** Parent component for menu dialogs; null when no originating mouse event exists. */
    private static Component menuParent(TrackClickEvent te) {
        return (te == null || te.getMouseEvent() == null)
                ? null : te.getMouseEvent().getComponent();
    }

    private void repaintSelf() {
        if (IGV.hasInstance()) {
            IGV.getInstance().repaint(Collections.singletonList(this));
        }
    }

    private void repaintTrack(TrackClickEvent te) {
        if (IGV.hasInstance()) {
            IGV.getInstance().repaint(Collections.singletonList(this));
        } else if (te != null && te.getMouseEvent() != null) {
            Component component = te.getMouseEvent().getComponent();
            if (component != null && component.getParent() != null) {
                component.getParent().repaint();
            }
        }
    }

    /**
     * IGV calls this when the track is removed. Without it the matrix file handle, the
     * decompressed block cache and every domain overlay outlive the track - and a BED hidden
     * by an overlay would stay hidden for good because its reference count never drops.
     */
    @Override
    public void unload() {
        super.unload();
        for (HiCDomainOverlay overlay : new ArrayList<>(domainOverlays)) {
            if (overlay.getTrack() != null) {
                HiCDomainOverlay.release(overlay.getTrack());
            }
        }
        domainOverlays.clear();

        frameCache.clear();
        lastFrame = null;
        pendingKeys.clear();
        matrixCache.clear();
        limitsCache.clear();
        rampImage = null;
        rampKey = "";

        if (data != null) {
            try {
                data.close();
            } catch (IOException e) {
                log.error("Error closing Hi-C file", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Session persistence
    // ------------------------------------------------------------------

    @Override
    public void marshalXML(Document document, Element element) {
        super.marshalXML(document, element);
        element.setAttribute("mode", mode.name());
        element.setAttribute("flipped", String.valueOf(flipped));
        element.setAttribute("legendPosition", legendPosition.name());
        if (!Double.isNaN(depth)) {
            element.setAttribute("depth", String.valueOf((long) depth));
        }
        if (!Double.isNaN(heightRatio)) {
            element.setAttribute("heightRatio", String.valueOf(heightRatio));
        }
        element.setAttribute("palette", palette.name());
        element.setAttribute("transform", transform.name());
        if (forcedResolution > 0) {
            element.setAttribute("resolution", String.valueOf(forcedResolution));
        }
        element.setAttribute("customLow", toHex(customLow));
        element.setAttribute("customHigh", toHex(customHigh));
        if (customMid != null) {
            element.setAttribute("customMid", toHex(customMid));
        }

        for (HiCDomainOverlay ov : domainOverlays) {
            Element child = document.createElement("DomainOverlay");
            child.setAttribute("track", ov.getTrackName());
            if (ov.getTrackPath() != null) {
                child.setAttribute("path", ov.getTrackPath());
            }
            child.setAttribute("lineColor", toHex(ov.getLineColor()));
            child.setAttribute("lineWidth", String.valueOf(ov.getLineWidth()));
            child.setAttribute("lineStyle", ov.getLineStyle().name());
            child.setAttribute("fillColor", toHex(ov.getFillColor()));
            child.setAttribute("fillAlpha", String.valueOf(ov.getFillAlpha()));
            element.appendChild(child);
        }
    }

    @Override
    public void unmarshalXML(Element element, Integer version) {
        super.unmarshalXML(element, version);

        // Re-restoring a session must not leak references: every overlay released here would
        // otherwise keep its BED track hidden forever (uses never drops back to zero).
        for (HiCDomainOverlay previous : domainOverlays) {
            if (previous.getTrack() != null) {
                HiCDomainOverlay.release(previous.getTrack());
            }
        }
        domainOverlays.clear();
        NodeList overlays = element.getElementsByTagName("DomainOverlay");
        for (int i = 0; i < overlays.getLength(); i++) {
            Node node = overlays.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element child = (Element) node;
            String trackName = child.getAttribute("track");
            if (trackName == null || trackName.isEmpty()) {
                continue;
            }
            HiCDomainOverlay ov = new HiCDomainOverlay(trackName,
                    child.hasAttribute("path") ? child.getAttribute("path") : null,
                    HiCDomainOverlay.defaultColor(i));
            try {
                if (child.hasAttribute("lineColor")) {
                    ov.setLineColor(Color.decode(child.getAttribute("lineColor")));
                }
                if (child.hasAttribute("lineWidth")) {
                    ov.setLineWidth(Float.parseFloat(child.getAttribute("lineWidth")));
                }
                if (child.hasAttribute("lineStyle")) {
                    ov.setLineStyle(HiCDomainOverlay.LineStyle.valueOf(child.getAttribute("lineStyle")));
                }
                if (child.hasAttribute("fillColor")) {
                    ov.setFillColor(Color.decode(child.getAttribute("fillColor")));
                }
                if (child.hasAttribute("fillAlpha")) {
                    ov.setFillAlpha(Integer.parseInt(child.getAttribute("fillAlpha")));
                }
            } catch (RuntimeException ignored) {
                // keep defaults for malformed attributes
            }
            domainOverlays.add(ov);
        }

        if (element.hasAttribute("mode")) {
            try {
                mode = Mode.valueOf(element.getAttribute("mode"));
            } catch (IllegalArgumentException ignored) {
                mode = Mode.TRIANGLE;
            }
        }
        if (element.hasAttribute("flipped")) {
            flipped = Boolean.parseBoolean(element.getAttribute("flipped"));
        }
        if (element.hasAttribute("legendPosition")) {
            try {
                legendPosition = LegendPosition.valueOf(element.getAttribute("legendPosition"));
            } catch (IllegalArgumentException ignored) {
                legendPosition = LegendPosition.TOP_LEFT;
            }
        }
        if (element.hasAttribute("customLow") && element.hasAttribute("customHigh")) {
            try {
                customLow = Color.decode(element.getAttribute("customLow"));
                customHigh = Color.decode(element.getAttribute("customHigh"));
                customMid = element.hasAttribute("customMid")
                        ? Color.decode(element.getAttribute("customMid")) : null;
                customAnchors = HiCColorScale.buildCustomAnchors(customLow, customMid, customHigh);
            } catch (NumberFormatException ignored) {
                customAnchors = null;
            }
        }
        if (element.hasAttribute("resolution")) {
            try {
                forcedResolution = Integer.parseInt(element.getAttribute("resolution"));
            } catch (NumberFormatException ignored) {
                forcedResolution = -1;
            }
        }
        if (element.hasAttribute("transform")) {
            try {
                transform = HiCTransform.valueOf(element.getAttribute("transform"));
            } catch (IllegalArgumentException ignored) {
                transform = HiCTransform.LOG1P;
            }
        }
        if (element.hasAttribute("palette")) {
            try {
                palette = HiCColorScale.Palette.valueOf(element.getAttribute("palette"));
            } catch (IllegalArgumentException ignored) {
                palette = HiCColorScale.Palette.RDYLBU_R;
            }
        }
        if (element.hasAttribute("heightRatio")) {
            try {
                heightRatio = Double.parseDouble(element.getAttribute("heightRatio"));
            } catch (NumberFormatException ignored) {
                heightRatio = Double.NaN;
            }
        }
        if (element.hasAttribute("depth")) {
            try {
                depth = Double.parseDouble(element.getAttribute("depth"));
            } catch (NumberFormatException ignored) {
                depth = Double.NaN;
            }
        }
    }
}
