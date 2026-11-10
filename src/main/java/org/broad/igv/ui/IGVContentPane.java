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
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.ui;

import org.broad.igv.logging.*;
import org.broad.igv.bedpe.BedPEFeature;
import org.broad.igv.bedpe.BedPEParser;
import org.broad.igv.bedpe.InteractionTrack;
import org.broad.igv.ui.commandbar.IGVCommandBar;
import org.broad.igv.ui.panel.CircularViewPanel;
import org.broad.igv.ui.panel.MainPanel;
import org.broad.igv.ui.panel.TrackPanel;
import org.broad.igv.track.Track;
import org.broad.igv.ui.util.ApplicationStatusBar;
import org.broad.igv.util.ResourceLocator;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.basic.BasicBorders;
import java.awt.*;


/**
 * The content pane for the IGV main window.
 */
public class IGVContentPane extends JPanel {


    private static Logger log = LogManager.getLogger(IGVContentPane.class);

    private JPanel commandBarPanel;
    private IGVCommandBar igvCommandBar;
    private MainPanel mainPanel;
    private ApplicationStatusBar statusBar;

    // Horizontal / circular view toggles.  "horizontal" is the existing linear
    // genome browser (MainPanel); "circular" is a placeholder panel that is not
    // drawn yet (reserved for the ring view).  Both can be shown at once.
    private JPanel circularPanel;
    private JPanel dataColumnPanel;
    private JList<String> dataColumnList;
    private final List<Track> dataColumnTracks = new ArrayList<>();
    private CircularViewPanel circularDrawingArea;
    private JSplitPane viewSplitPane;
    private Component currentCenter;
    private boolean showHorizontalView = true;
    private boolean showCircularView = false;

    private IGV igv;

    /**
     * Creates new form IGV
     */
    public IGVContentPane(IGV igv) {

        setOpaque(true);    // Required by Swing

        this.igv = igv;

        // Create components

        setLayout(new BorderLayout());

        commandBarPanel = new JPanel();
        BoxLayout layout = new BoxLayout(commandBarPanel, BoxLayout.PAGE_AXIS);

        commandBarPanel.setLayout(layout);
        add(commandBarPanel, BorderLayout.NORTH);

        igvCommandBar = new IGVCommandBar();
        igvCommandBar.setMinimumSize(new Dimension(250, 33));
        igvCommandBar.setBorder(new BasicBorders.MenuBarBorder(Color.GRAY, Color.GRAY));
        igvCommandBar.setAlignmentX(Component.BOTTOM_ALIGNMENT);
        commandBarPanel.add(igvCommandBar);


        mainPanel = new MainPanel(igv);

        // Left "data column" inside the circular view panel: lists imported tracks.
        // Reserved for picking tracks to draw on the ring (placeholder for now).
        dataColumnPanel = new JPanel(new BorderLayout());
        dataColumnPanel.setBorder(BorderFactory.createTitledBorder("Data Tracks"));
        dataColumnList = new JList<>();
        dataColumnList.setBackground(new Color(248, 248, 248));
        dataColumnPanel.add(new JScrollPane(dataColumnList), BorderLayout.CENTER);
        // Right-click a track in the data column to set its ring band height / expand mode.
        dataColumnList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowTrackPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowTrackPopup(e); }
        });
        // Ctrl / Shift click to select multiple tracks; the popup applies to all selected.
        dataColumnList.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Drawing area of the circular view: live ring genome plot (ideogram of the
        // selected chromosome, or all chromosomes when the view is "All").
        circularDrawingArea = new CircularViewPanel();

        // Circular view panel = [Data Tracks column | drawing area].  The Data Tracks
        // column lives inside the circular panel, on its left.
        JSplitPane circularSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, dataColumnPanel, circularDrawingArea);
        configureSplit(circularSplit, 0.2);

        circularPanel = new JPanel(new BorderLayout());
        circularPanel.setBorder(BorderFactory.createTitledBorder("Circular View"));
        // Parameter toolbar at the top of the circular view panel (ticks / labels / spacing).
        circularPanel.add(buildCircularToolbar(circularDrawingArea), BorderLayout.NORTH);
        circularPanel.add(circularSplit, BorderLayout.CENTER);

        updateViewLayout();

        // Test hook: enable the circular view AFTER construction (IGV.contentPane is assigned by
        // then). Enabling during construction would NPE in igv.getAllTracks().
        if (Boolean.getBoolean("myigv.circular.autotest")) {
            SwingUtilities.invokeLater(() -> setShowCircularView(true));
        }

        // Keep the ring's track list in sync with the loaded tracks (genome / track changes),
        // regardless of which events fire: cheap poll every 2s.
        javax.swing.Timer trackSync = new javax.swing.Timer(2000, e -> {
            try {
                java.util.List<Track> now = igv.getAllTracks();
                if (now != null && now.size() != dataColumnTracks.size()) refreshDataColumn();
            } catch (Throwable ignore) { }
        });
        trackSync.setRepeats(true);
        trackSync.start();

        statusBar = new ApplicationStatusBar();
        statusBar.setDebugGraphicsOptions(javax.swing.DebugGraphics.NONE_OPTION);
        add(statusBar, BorderLayout.SOUTH);
    }

    @Override
    public Dimension getPreferredSize() {
        return UIConstants.preferredSize;
    }

    /**
     * Reset the default status message, which is the number of tracks loaded.
     */
    public void resetStatusMessage() {
        statusBar.setMessage("" + igv.getVisibleTrackCount() + " tracks loaded");

    }

    public MainPanel getMainPanel() {
        return mainPanel;
    }

    public IGVCommandBar getCommandBar() {
        return igvCommandBar;
    }


    public ApplicationStatusBar getStatusBar() {

        return statusBar;
    }

    public boolean isShowHorizontalView() {
        return showHorizontalView;
    }

    public void setShowHorizontalView(boolean showHorizontalView) {
        this.showHorizontalView = showHorizontalView;
        updateViewLayout();
    }

    public boolean isShowCircularView() {
        return showCircularView;
    }

    public void setShowCircularView(boolean showCircularView) {
        this.showCircularView = showCircularView;
        updateViewLayout();
    }

    /**
     * Rebuild the center region according to the horizontal / circular view toggles:
     *  - both on  -> [data column | horizontal view (intact) | circular]  (4 visual columns)
     *  - only horizontal -> horizontal fills the center (IGV's original look)
     *  - only circular  -> [data column | circular]
     *  - neither -> fall back to horizontal so the window is not empty
     */
    public void updateViewLayout() {
        if (currentCenter != null) {
            remove(currentCenter);
        }
        if (showHorizontalView && showCircularView) {
            // Horizontal view (intact, IGV's original look) on the left, circular panel
            // on the right. The circular panel internally holds the Data Tracks column
            // (its left) and the drawing area (its right) — see circularSplit in ctor.
            refreshDataColumn();
            viewSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mainPanel, circularPanel);
            configureSplit(viewSplitPane, 0.5);
            add(viewSplitPane, BorderLayout.CENTER);
            currentCenter = viewSplitPane;
            // Set an ABSOLUTE half position immediately: a proportional setDividerLocation(0.5)
            // on a not-yet-sized pane lands at 0 and then jumps to 0.5 on layout — the
            // "starts small then pops to half" effect. Use the real width when known.
            int availW = getWidth();
            if (availW > 0) {
                viewSplitPane.setDividerLocation(availW / 2);
            } else {
                SwingUtilities.invokeLater(() -> viewSplitPane.setDividerLocation(0.5));
            }
        } else if (showHorizontalView) {
            add(mainPanel, BorderLayout.CENTER);
            currentCenter = mainPanel;
        } else if (showCircularView) {
            // Only circular: the circular panel (Data Tracks column | drawing area) fills
            // the whole center. The horizontal view is hidden.
            refreshDataColumn();
            add(circularPanel, BorderLayout.CENTER);
            currentCenter = circularPanel;
        } else {
            add(mainPanel, BorderLayout.CENTER);
            currentCenter = mainPanel;
            showHorizontalView = true;
        }
        revalidate();
        repaint();
    }

    private void configureSplit(JSplitPane sp, double resizeWeight) {
        sp.setDividerSize(4);
        sp.setResizeWeight(resizeWeight);
        sp.setOneTouchExpandable(false);
        sp.setContinuousLayout(true);
    }

    /**
     * Parameter toolbar at the top of the Circular View panel: quick tick/label controls
     * plus a full "Circular View Settings…" dialog.
     */
    private JComponent buildCircularToolbar(CircularViewPanel panel) {
        CircularViewPanel.CircularSettings s = panel.getSettings();
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // Everything folded into a single dropdown so the panel top stays uncluttered.
        JButton options = new JButton("View options ▾");
        JPopupMenu menu = new JPopupMenu();

        JCheckBoxMenuItem cbTicks = new JCheckBoxMenuItem("Show tick marks", s.showTicks);
        cbTicks.addActionListener(e -> { s.showTicks = cbTicks.isSelected(); panel.refresh(); });
        JCheckBoxMenuItem cbTickLabels = new JCheckBoxMenuItem("Show tick labels", s.showTickLabels);
        cbTickLabels.addActionListener(e -> { s.showTickLabels = cbTickLabels.isSelected(); panel.refresh(); });
        JCheckBoxMenuItem cbChr = new JCheckBoxMenuItem("Show chromosome labels", s.showChromosomeLabels);
        cbChr.addActionListener(e -> { s.showChromosomeLabels = cbChr.isSelected(); panel.refresh(); });
        menu.add(cbTicks);
        menu.add(cbTickLabels);
        menu.add(cbChr);

        JMenuItem chrSel = new JMenuItem("Chromosomes to show (All mode)…");
        chrSel.addActionListener(e -> showChromosomeSelectionDialog(panel));
        menu.add(chrSel);

        JMenuItem showAllRing = new JMenuItem("Show all ring tracks");
        showAllRing.addActionListener(e -> { panel.clearHiddenRingTracks(); refreshDataColumn(); });
        menu.add(showAllRing);
        menu.addSeparator();

        // ---- Circos links (bezier chords / ribbons between two genomic intervals) ----
        JMenuItem loadLinks = new JMenuItem("Load links (BEDPE / Circos)…");
        loadLinks.addActionListener(e -> loadCircularLinks(panel));

        JMenuItem clearLinks = new JMenuItem("Clear links");
        clearLinks.addActionListener(e -> { panel.clearRingLinks(); panel.refresh(); });

        JCheckBoxMenuItem cbLinks = new JCheckBoxMenuItem("Show links", s.showLinks);
        cbLinks.addActionListener(e -> { s.showLinks = cbLinks.isSelected(); panel.refresh(); });

        JMenu linkApp = new JMenu("Link appearance");
        linkApp.add(linkCheck("Ribbon (filled bands)", s.linkRibbon,
                v -> { s.linkRibbon = v; panel.refresh(); }));
        linkApp.add(linkCheck("Twist ribbons", s.linkTwist,
                v -> { s.linkTwist = v; panel.refresh(); }));
        linkApp.add(linkCheck("Colour by chromosome", s.linkColorByChromosome,
                v -> { s.linkColorByChromosome = v; panel.refresh(); }));
        linkApp.addSeparator();

        JMenuItem linkColour = new JMenuItem("Colour…");
        linkColour.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Link colour", s.linkColor);
            if (c != null) { s.linkColor = c; panel.refresh(); }
        });
        linkApp.add(linkColour);

        JMenuItem linkThickness = new JMenuItem("Thickness… (" + s.linkThickness + ")");
        linkThickness.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this, "Link thickness (px):", s.linkThickness);
            if (v != null) {
                try { s.linkThickness = Math.max(0.1f, Float.parseFloat(v.trim())); panel.refresh(); }
                catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkThickness);

        JMenuItem linkAlpha = new JMenuItem("Opacity (0-255)… (" + s.linkAlpha + ")");
        linkAlpha.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this, "Link opacity (0-255):", s.linkAlpha);
            if (v != null) {
                try {
                    s.linkAlpha = Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkAlpha);
        linkApp.addSeparator();

        JMenuItem linkRadius = new JMenuItem("End radius (0-1)… (" + s.linkRadius + ")");
        linkRadius.setToolTipText("Fraction of the ideogram inner radius. Lower it to keep links clear of the data rings.");
        linkRadius.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this,
                    "Link end radius, as a fraction of the ideogram inner radius (0.05-1):", s.linkRadius);
            if (v != null) {
                try {
                    s.linkRadius = Math.max(0.05, Math.min(1.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkRadius);

        JMenuItem linkBez = new JMenuItem("Bezier radius (0-1)… (" + s.linkBezierRadius + ")");
        linkBez.setToolTipText("Circos bezier_radius: radial position of the curve's control point. 0 = straight chord.");
        linkBez.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this,
                    "Bezier control radius (0 = straight chord):", s.linkBezierRadius);
            if (v != null) {
                try {
                    s.linkBezierRadius = Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkBez);

        JMenuItem linkCrest = new JMenuItem("Crest (0-1)… (" + s.linkCrest + ")");
        linkCrest.setToolTipText("Circos crest: makes links meet the ideogram perpendicularly. 0 disables.");
        linkCrest.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this, "Crest (0 disables):", s.linkCrest);
            if (v != null) {
                try {
                    s.linkCrest = Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkCrest);

        JMenuItem linkMinWidth = new JMenuItem("Min ribbon width (deg)… (" + s.linkRibbonMinDeg + ")");
        linkMinWidth.setToolTipText("Widens ribbon ends so links with tiny anchors stay visible.");
        linkMinWidth.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this,
                    "Minimum ribbon end width, in degrees:", s.linkRibbonMinDeg);
            if (v != null) {
                try {
                    s.linkRibbonMinDeg = Math.max(0, Double.parseDouble(v.trim()));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkMinWidth);

        JMenuItem ribbonAlpha = new JMenuItem("Ribbon opacity (0-255)… (" + s.linkRibbonAlpha + ")");
        ribbonAlpha.setToolTipText("Filled ribbons need more opacity than thin links to read well.");
        ribbonAlpha.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this, "Ribbon opacity (0-255):", s.linkRibbonAlpha);
            if (v != null) {
                try {
                    s.linkRibbonAlpha = Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(ribbonAlpha);

        linkApp.add(linkCheck("Outline ribbons", s.linkRibbonStroke,
                v -> { s.linkRibbonStroke = v; panel.refresh(); }));

        JMenuItem linkMax = new JMenuItem("Max links… (" + s.linkRecordLimit + ")");
        linkMax.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(this, "Maximum links to draw:", s.linkRecordLimit);
            if (v != null) {
                try { s.linkRecordLimit = Math.max(1, Integer.parseInt(v.trim())); panel.refresh(); }
                catch (NumberFormatException ignore) { }
            }
        });
        linkApp.add(linkMax);

        menu.add(loadLinks);
        menu.add(clearLinks);
        menu.add(cbLinks);
        menu.add(linkApp);
        menu.addSeparator();

        JMenu ticksPos = new JMenu("Tick labels position");
        JRadioButtonMenuItem outside = new JRadioButtonMenuItem("Outside (above band)", s.tickLabelsOutside);
        JRadioButtonMenuItem inside = new JRadioButtonMenuItem("Inside (below band)", !s.tickLabelsOutside);
        ButtonGroup g1 = new ButtonGroup(); g1.add(outside); g1.add(inside);
        outside.addActionListener(e -> { s.tickLabelsOutside = true; panel.refresh(); });
        inside.addActionListener(e -> { s.tickLabelsOutside = false; panel.refresh(); });
        ticksPos.add(outside); ticksPos.add(inside);
        menu.add(ticksPos);

        JMenu numOrient = new JMenu("Number orientation");
        JRadioButtonMenuItem tang = new JRadioButtonMenuItem("Tangential", s.labelTangential);
        JRadioButtonMenuItem rad = new JRadioButtonMenuItem("Radial", !s.labelTangential);
        ButtonGroup g2 = new ButtonGroup(); g2.add(tang); g2.add(rad);
        tang.addActionListener(e -> { s.labelTangential = true; panel.refresh(); });
        rad.addActionListener(e -> { s.labelTangential = false; panel.refresh(); });
        numOrient.add(tang); numOrient.add(rad);
        menu.add(numOrient);

        JMenu yscale = new JMenu("Multi-sample Y axis");
        ButtonGroup ybg = new ButtonGroup();
        JRadioButtonMenuItem ySelf = new JRadioButtonMenuItem("Per sample (default)", !s.ySharedAcrossTracks);
        ySelf.addActionListener(e -> { s.ySharedAcrossTracks = false; panel.refresh(); });
        JRadioButtonMenuItem yGroup = new JRadioButtonMenuItem("Shared (group)", s.ySharedAcrossTracks);
        yGroup.addActionListener(e -> { s.ySharedAcrossTracks = true; panel.refresh(); });
        ybg.add(ySelf);
        ybg.add(yGroup);
        yscale.add(ySelf);
        yscale.add(yGroup);
        menu.add(yscale);
        menu.addSeparator();

        JMenuItem spacing = new JMenuItem("Tick spacing (Mb)…");
        spacing.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Tick spacing in Mb (0 = auto):", s.tickSpacingMb);
            if (v != null) {
                try { s.tickSpacingMb = Double.parseDouble(v.trim()); panel.refresh(); } catch (NumberFormatException ignore) { }
            }
        });
        menu.add(spacing);

        JMenuItem labelSize = new JMenuItem("Tick label size…");
        labelSize.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Tick label font size:", s.tickLabelSize);
            if (v != null) {
                try { s.tickLabelSize = Integer.parseInt(v.trim()); panel.refresh(); } catch (NumberFormatException ignore) { }
            }
        });
        menu.add(labelSize);

        JMenuItem chrLabelSize = new JMenuItem("Chromosome label size…");
        chrLabelSize.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Chromosome label font size:", s.chromosomeLabelSize);
            if (v != null) {
                try { s.chromosomeLabelSize = Integer.parseInt(v.trim()); panel.refresh(); } catch (NumberFormatException ignore) { }
            }
        });
        menu.add(chrLabelSize);

        JMenuItem chrHeight = new JMenuItem("Chromosome height (px)…");
        chrHeight.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Chromosome ring height in pixels:", s.chromosomeHeightPx);
            if (v != null) {
                try { s.chromosomeHeightPx = Math.max(8, Integer.parseInt(v.trim())); panel.refresh(); } catch (NumberFormatException ignore) { }
            }
        });
        menu.add(chrHeight);
        menu.addSeparator();

        JCheckBoxMenuItem solid = new JCheckBoxMenuItem("Solid chromosome colour (override karyotype)", s.solidColor);
        solid.addActionListener(e -> { s.solidColor = solid.isSelected(); panel.refresh(); });
        menu.add(solid);

        JMenuItem chrColour = new JMenuItem("Chromosome colour…");
        chrColour.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(panel, "Chromosome colour", s.chromosomeColor);
            if (picked != null) { s.chromosomeColor = picked; s.solidColor = true; panel.refresh(); }
        });
        menu.add(chrColour);
        menu.addSeparator();

        JMenuItem reset = new JMenuItem("Reset zoom");
        reset.addActionListener(e -> panel.resetZoom());
        menu.add(reset);

        JMenuItem all = new JMenuItem("All settings…");
        all.addActionListener(e -> panel.showSettingsDialog(SwingUtilities.getWindowAncestor(panel)));
        menu.add(all);

        options.addActionListener(e -> menu.show(options, 0, options.getHeight()));
        bar.add(options);
        return bar;
    }

    /** Small helper: a checkbox menu item wired to a setter callback. */
    private static JCheckBoxMenuItem linkCheck(String text, boolean initial,
                                               java.util.function.Consumer<Boolean> setter) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, initial);
        item.addActionListener(e -> setter.accept(item.isSelected()));
        return item;
    }

    /**
     * Load a Circos-link / BEDPE file straight into the circular view. No track is created and
     * the file's extension does not matter: we call {@link BedPEParser#parse} directly, which
     * detects UTF-8 / UTF-16 (with or without BOM), canonicalises chromosome names and skips
     * malformed rows. Routing the file through {@code TrackLoader} instead would send a
     * {@code .bed} / {@code .links} file to the plain BED codec, which silently drops the links.
     */
    private void loadCircularLinks(CircularViewPanel panel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Circos links (BEDPE / links file)");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Links / BEDPE (*.bed, *.bedpe, *.links, *.txt, *.gz)",
                "bed", "bedpe", "links", "txt", "gz"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        try {
            BedPEParser.Dataset dataset =
                    BedPEParser.parse(new ResourceLocator(file.getAbsolutePath()), genome);

            List<CircularViewPanel.RingLink> links = new ArrayList<>();
            for (BedPEFeature f : dataset.features) {
                CircularViewPanel.RingLink link = new CircularViewPanel.RingLink(
                        f.chr1, f.start1, f.end1, f.chr2, f.start2, f.end2);
                link.score = f.getScore();
                link.color = f.getColor();
                link.thickness = Math.max(0, f.getThickness());
                link.label = f.chr1 + ":" + f.start1 + "-" + f.end1
                        + "  \u2194  " + f.chr2 + ":" + f.start2 + "-" + f.end2;
                if (f.columnValues != null) {
                    // Circos puts per-link options in the 7th column, e.g.
                    // "color=blue,thickness=5p,z=10".  BedPEParser stores every raw column.
                    parseLinkOptions(f.columnValues.get("col7"), link);
                }
                links.add(link);
            }

            panel.setRingLinks(links);
            panel.refresh();
            resetStatusMessage();
            JOptionPane.showMessageDialog(this,
                    "Loaded " + links.size() + " link(s) from " + file.getName(),
                    "Circular links", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            log.error("Failed to load circular links from " + file, ex);
            JOptionPane.showMessageDialog(this,
                    "Could not load links from " + file.getName() + "\n" + ex.getMessage(),
                    "Circular links", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Parse the Circos 7th-column option string ({@code key=value,key=value}) and apply the
     * subset that affects drawing. Unknown keys and unparseable values are ignored, matching
     * Circos' tolerance of values such as {@code thickness=5p}.
     */
    private static void parseLinkOptions(String options, CircularViewPanel.RingLink link) {
        if (options == null || options.indexOf('=') < 0) {
            return;
        }
        for (String part : options.split("[,\\s]+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toLowerCase();
            String value = part.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "color":
                    case "colour": {
                        Color c = parseColour(value);
                        if (c != null) link.color = c;
                        break;
                    }
                    case "thickness":
                        link.thickness = Math.round(Float.parseFloat(value.replace("p", "")));
                        break;
                    case "z":
                        link.z = Double.parseDouble(value);
                        break;
                    case "score":
                        link.score = Double.parseDouble(value);
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException ignore) {
                // Circos allows units ("5p"); values we cannot parse simply keep their default.
            }
        }
    }

    /** Circos colour names plus {@code #rrggbb} and {@code r,g,b}. */
    private static Color parseColour(String value) {
        if (value == null || value.isEmpty()) return null;
        String v = value.trim();
        try {
            if (v.startsWith("#")) return Color.decode(v);
            if (v.matches("\\d{1,3},\\d{1,3},\\d{1,3}")) {
                String[] p = v.split(",");
                return new Color(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
            return (Color) Color.class.getField(v.toLowerCase()).get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Populate the left data-column list with the currently loaded tracks.
     * Used by circular-involving layouts as the "imported data" column.
     */
    /** Dialog to pick which chromosomes are shown in the "All" ring (checkbox list, all/none). */
    private void showChromosomeSelectionDialog(CircularViewPanel panel) {
        Genome genome = GenomeManager.getInstance().getCurrentGenome();
        if (genome == null) return;
        CircularViewPanel.CircularSettings s = panel.getSettings();
        List<String> names = genome.getLongChromosomeNames();

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        List<JCheckBox> boxes = new ArrayList<>();
        boolean showAll = s.shownChromosomes == null || s.shownChromosomes.isEmpty();
        for (String n : names) {
            JCheckBox cb = new JCheckBox(n, showAll || s.shownChromosomes.contains(n));
            boxes.add(cb);
            listPanel.add(cb);
        }

        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setPreferredSize(new Dimension(240, Math.min(420, 26 * Math.max(4, names.size()) + 12)));

        JPanel content = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton all = new JButton("Select all");
        all.addActionListener(a -> boxes.forEach(b -> b.setSelected(true)));
        JButton none = new JButton("Clear");
        none.addActionListener(a -> boxes.forEach(b -> b.setSelected(false)));
        buttons.add(all);
        buttons.add(none);
        content.add(buttons, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        int r = JOptionPane.showConfirmDialog(panel, content, "Chromosomes to show (All mode)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        Set<String> selected = new HashSet<>();
        for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) selected.add(names.get(i));
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "Select at least one chromosome.",
                    "No chromosome selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        s.shownChromosomes.clear();
        if (selected.size() < names.size()) s.shownChromosomes.addAll(selected);  // all -> empty (= show all)
        panel.refresh();
    }

    public void refreshDataColumn() {
        if (igv == null || igv.getContentPane() == null) return;   // during construction contentPane is null
        List<Track> tracks = igv.getAllTracks();
        DefaultListModel<String> model = new DefaultListModel<>();
        dataColumnTracks.clear();
        if (tracks != null) {
            for (Track t : tracks) {
                model.addElement(t.getDisplayName());
                dataColumnTracks.add(t);
            }
        }
        if (model.isEmpty()) model.addElement("(no tracks loaded)");
        dataColumnList.setModel(model);
        circularDrawingArea.setRingTracks(new ArrayList<>(dataColumnTracks));   // ordered top->bottom

        // Any interaction track that is already loaded also feeds the circular links, so a
        // normal ".bedpe" import shows up on the ring without a second load step.
        List<CircularViewPanel.RingLink> autoLinks = new ArrayList<>();
        for (Track t : dataColumnTracks) {
            if (!(t instanceof InteractionTrack)) continue;
            List<BedPEFeature> features = ((InteractionTrack) t).getAllFeatures();
            if (features == null) continue;
            for (BedPEFeature f : features) {
                CircularViewPanel.RingLink link = new CircularViewPanel.RingLink(
                        f.chr1, f.start1, f.end1, f.chr2, f.start2, f.end2);
                link.score = f.getScore();
                link.color = f.getColor();
                link.thickness = Math.max(0, f.getThickness());
                autoLinks.add(link);
            }
        }
        circularDrawingArea.setTrackLinks(autoLinks);
    }

    /** Right-click popup on the data column: per-track ring band height + collapse/expand. */
    private void maybeShowTrackPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int idx = dataColumnList.locationToIndex(e.getPoint());
        if (idx < 0 || idx >= dataColumnTracks.size()) return;
        // Keep an existing multi-selection if the click landed inside it; else select just this row.
        boolean inSel = false;
        for (int si : dataColumnList.getSelectedIndices()) if (si == idx) { inSel = true; break; }
        if (!inSel) dataColumnList.setSelectedIndex(idx);

        final int[] sel = dataColumnList.getSelectedIndices();
        final java.util.List<CircularViewPanel.TrackRingConfig> cfgs = new ArrayList<>();
        for (int si : sel) {
            if (si >= 0 && si < dataColumnTracks.size())
                cfgs.add(circularDrawingArea.getRingConfig(dataColumnTracks.get(si).getName()));
        }
        if (cfgs.isEmpty()) return;
        final CircularViewPanel.TrackRingConfig cfg = cfgs.get(0);
        final int n = cfgs.size();
        final String many = n > 1 ? " (" + n + " tracks)" : "";

        JPopupMenu menu = new JPopupMenu();
        JMenuItem height = new JMenuItem("Track height… (" + cfg.heightPx + " px" + many + ")");
        height.addActionListener(a -> {
            String v = JOptionPane.showInputDialog(this, "Ring track height (pixels):", cfg.heightPx);
            if (v != null) {
                try {
                    int h = Math.max(8, Integer.parseInt(v.trim()));
                    for (CircularViewPanel.TrackRingConfig c : cfgs) c.heightPx = h;
                    circularDrawingArea.invalidateRing();
                } catch (NumberFormatException ignore) { }
            }
        });
        menu.add(height);

        JCheckBoxMenuItem expanded = new JCheckBoxMenuItem("Expanded (stack features)", cfg.expanded);
        expanded.addActionListener(a -> {
            for (CircularViewPanel.TrackRingConfig c : cfgs) c.expanded = expanded.isSelected();
            circularDrawingArea.invalidateRing();
        });
        menu.add(expanded);

        JMenu graph = new JMenu("Graph type");
        ButtonGroup gg = new ButtonGroup();
        for (String tp : new String[]{"Bar", "Point", "Line", "Heatmap"}) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(tp, tp.equals(cfg.graphType));
            mi.addActionListener(a -> {
                for (CircularViewPanel.TrackRingConfig c : cfgs) c.graphType = tp;
                circularDrawingArea.invalidateRing();
            });
            gg.add(mi);
            graph.add(mi);
        }
        menu.add(graph);

        JMenu yaxis = new JMenu("Y-axis range");
        ButtonGroup yg = new ButtonGroup();
        JRadioButtonMenuItem yPer = new JRadioButtonMenuItem("Per chromosome (default)", !cfg.globalYScale);
        yPer.addActionListener(a -> {
            for (CircularViewPanel.TrackRingConfig c : cfgs) c.globalYScale = false;
            circularDrawingArea.invalidateRing();
        });
        JRadioButtonMenuItem yGlobal = new JRadioButtonMenuItem("All chromosomes (unified)", cfg.globalYScale);
        yGlobal.addActionListener(a -> {
            for (CircularViewPanel.TrackRingConfig c : cfgs) c.globalYScale = true;
            circularDrawingArea.invalidateRing();
        });
        yg.add(yPer);
        yg.add(yGlobal);
        yaxis.add(yPer);
        yaxis.add(yGlobal);
        menu.add(yaxis);

        JMenu yscale = new JMenu("Y-axis scale (multi-sample)");
        ButtonGroup ysg = new ButtonGroup();
        JRadioButtonMenuItem ySelf = new JRadioButtonMenuItem("Self scale (default)", cfg.yGroup == null);
        ySelf.addActionListener(a -> {
            for (CircularViewPanel.TrackRingConfig c : cfgs) c.yGroup = null;
            circularDrawingArea.invalidateRing();
        });
        JRadioButtonMenuItem yGroupItem = new JRadioButtonMenuItem(
                "Group scale (selected" + (n > 1 ? ", " + n + " tracks" : "") + ")", cfg.yGroup != null);
        yGroupItem.addActionListener(a -> {
            String gid = "g" + System.nanoTime();
            for (CircularViewPanel.TrackRingConfig c : cfgs) c.yGroup = gid;
            circularDrawingArea.invalidateRing();
        });
        ysg.add(ySelf);
        ysg.add(yGroupItem);
        yscale.add(ySelf);
        yscale.add(yGroupItem);
        menu.add(yscale);

        JMenuItem colorItem = new JMenuItem("Colour…");
        colorItem.addActionListener(a -> {
            Color c = JColorChooser.showDialog(this, "Track colour", cfg.color);
            if (c != null) {
                for (CircularViewPanel.TrackRingConfig cc : cfgs) cc.color = c;
                circularDrawingArea.invalidateRing();
            }
        });
        menu.add(colorItem);

        menu.addSeparator();
        JMenuItem removeRing = new JMenuItem("Remove track from ring" + many);
        removeRing.addActionListener(a -> {
            for (int si : sel) {
                if (si >= 0 && si < dataColumnTracks.size())
                    circularDrawingArea.removeRingTrack(dataColumnTracks.get(si).getName());
            }
        });
        menu.add(removeRing);

        menu.show(dataColumnList, e.getX(), e.getY());
    }

}
