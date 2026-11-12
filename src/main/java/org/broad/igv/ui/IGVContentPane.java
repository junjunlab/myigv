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
import org.broad.igv.bedpe.InteractionTrack;
import org.broad.igv.ui.commandbar.IGVCommandBar;
import org.broad.igv.ui.panel.CircularExporter;
import org.broad.igv.ui.panel.CircularViewPanel;
import org.broad.igv.ui.panel.MainPanel;
import org.broad.igv.ui.panel.TrackPanel;
import org.broad.igv.track.DataTrack;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.Track;
import org.broad.igv.hic.HiCTrack;
import org.broad.igv.ui.util.ApplicationStatusBar;

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.basic.BasicBorders;
import java.awt.*;
import java.io.File;


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
        // Drag a row to reorder it. The list order is the ring order: top row = outermost band.
        dataColumnList.setDragEnabled(true);
        dataColumnList.setDropMode(javax.swing.DropMode.INSERT);
        dataColumnList.setTransferHandler(new TrackReorderHandler());

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
                // Compare against the same filtered list the column is built from: comparing with
                // igv.getAllTracks() would never match once a track is hidden (e.g. a BED owned by
                // a Hi-C domain overlay) and would rebuild the whole ring every 2 seconds.
                java.util.List<Track> now = circularColumnTracks();
                if (now.size() != dataColumnTracks.size()) refreshDataColumn();
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
     * Set BOTH view flags at once and rebuild the layout. The menu offers three mutually exclusive
     * modes — Horizontal only, Circular only, Both — so switching from "Horizontal only" to "Both"
     * must flip the circular flag without first passing through "neither" (which
     * {@link #updateViewLayout()} would snap back to Horizontal and leave both on). Never leaves the
     * window empty.
     */
    public void setViewMode(boolean horizontal, boolean circular) {
        if (!horizontal && !circular) horizontal = true;   // at least one view must stay visible
        this.showHorizontalView = horizontal;
        this.showCircularView = circular;
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

        JCheckBoxMenuItem cbAxis = new JCheckBoxMenuItem("Show coordinate axis", s.showAxis);
        cbAxis.addActionListener(e -> { s.showAxis = cbAxis.isSelected(); panel.refresh(); });
        JCheckBoxMenuItem cbTicks = new JCheckBoxMenuItem("Show tick marks", s.showTicks);
        cbTicks.addActionListener(e -> { s.showTicks = cbTicks.isSelected(); panel.refresh(); });
        JCheckBoxMenuItem cbTickLabels = new JCheckBoxMenuItem("Show tick labels", s.showTickLabels);
        cbTickLabels.addActionListener(e -> { s.showTickLabels = cbTickLabels.isSelected(); panel.refresh(); });
        JCheckBoxMenuItem cbChr = new JCheckBoxMenuItem("Show chromosome labels", s.showChromosomeLabels);
        cbChr.addActionListener(e -> { s.showChromosomeLabels = cbChr.isSelected(); panel.refresh(); });
        menu.add(cbAxis);
        menu.add(cbTicks);
        menu.add(cbTickLabels);
        menu.add(cbChr);

        JMenuItem chrSel = new JMenuItem("Chromosomes to show (All mode)…");
        chrSel.addActionListener(e -> showChromosomeSelectionDialog(panel));
        menu.add(chrSel);

        // ---- Single-chromosome arc span: 1 = 360° (default), 0.5 = 180°, etc. ----
        JMenu arcMenu = new JMenu("Single-chromosome arc");
        ButtonGroup arcGroup = new ButtonGroup();
        double[] arcPresets = {1.0, 0.75, 0.5, 1.0 / 3.0, 0.25};
        for (double frac : arcPresets) {
            String fresh = String.format(java.util.Locale.ROOT, "%.2f", frac);
            JRadioButtonMenuItem arcItem =
                    new JRadioButtonMenuItem(Math.round(frac * 360) + "°  (" + fresh + ")");
            arcItem.setSelected(Math.abs(s.singleArcFraction - frac) < 1e-6);
            arcItem.addActionListener(e -> { s.singleArcFraction = frac; panel.refresh(); });
            arcGroup.add(arcItem);
            arcMenu.add(arcItem);
        }
        arcMenu.addSeparator();
        JMenuItem arcCustom = new JMenuItem("Custom (0–1)…");
        arcCustom.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Ring arc fraction for single-chromosome mode (0–1, 1 = 360°):",
                    String.format(java.util.Locale.ROOT, "%.2f", s.singleArcFraction));
            if (v == null) return;
            try {
                double f = Double.parseDouble(v.trim());
                if (f > 0 && f <= 1.0) { s.singleArcFraction = f; panel.refresh(); }
            } catch (NumberFormatException ignore) { /* keep current */ }
        });
        arcMenu.add(arcCustom);
        menu.add(arcMenu);

        // ---- Where the arc's 0 position sits (degrees clockwise from top; default top) ----
        JMenu arcStartMenu = new JMenu("Single-chromosome arc start");
        ButtonGroup arcStartGroup = new ButtonGroup();
        String[] startNames = {"Top", "Right", "Bottom", "Left"};
        double[] startDegs = {0, 90, 180, 270};
        double curStart = ((s.singleArcStartDeg % 360) + 360) % 360;
        for (int i = 0; i < startNames.length; i++) {
            final double deg = startDegs[i];
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(startNames[i] + "  (" + (int) deg + "°)");
            it.setSelected(Math.abs(curStart - deg) < 1e-6);
            it.addActionListener(e -> { s.singleArcStartDeg = deg; panel.refresh(); });
            arcStartGroup.add(it);
            arcStartMenu.add(it);
        }
        arcStartMenu.addSeparator();
        JMenuItem startCustom = new JMenuItem("Custom (degrees)…");
        startCustom.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Arc start position, degrees clockwise from top (0=top, 90=right, 180=bottom, 270=left):",
                    String.valueOf(Math.round(curStart)));
            if (v == null) return;
            try {
                double deg = Double.parseDouble(v.trim());
                s.singleArcStartDeg = ((deg % 360) + 360) % 360;
                panel.refresh();
            } catch (NumberFormatException ignore) { /* keep current */ }
        });
        arcStartMenu.add(startCustom);
        menu.add(arcStartMenu);
        menu.addSeparator();

        // Links / heatmap import + their parameters moved to the Import menu and to the
        // per-layer context menus in the Data Tracks column.

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

        JMenuItem axisWidth = new JMenuItem("Axis line thickness… ("
                + String.format(java.util.Locale.ROOT, "%.2f", s.axisLineWidth) + " px)");
        axisWidth.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Coordinate axis line thickness in pixels (0.25 – 6.0):",
                    String.format(java.util.Locale.ROOT, "%.2f", s.axisLineWidth));
            if (v == null) return;
            try {
                double px = Double.parseDouble(v.trim());
                if (px >= 0.25 && px <= 6.0) { s.axisLineWidth = px; panel.refresh(); }
            } catch (NumberFormatException ignore) { /* keep current */ }
        });
        menu.add(axisWidth);

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

        // ---- Import (circular-view-only data files) : FIRST button in the toolbar ----
        JButton importBtn = new JButton("Import ▾");
        JPopupMenu importMenu = new JPopupMenu();
        JMenuItem impLinks = new JMenuItem("Links  (BEDPE / Circos links)…");
        impLinks.addActionListener(e -> loadLinksFromFile(panel));
        importMenu.add(impLinks);
        JMenuItem impHeat = new JMenuItem("Heatmap  (BED with value columns)…");
        impHeat.addActionListener(e -> loadHeatmapFromFile(panel));
        importMenu.add(impHeat);
        JMenuItem impLabels = new JMenuItem("Labels  (BED + name column)…");
        impLabels.addActionListener(e -> loadLabelsFromFile(panel));
        importMenu.add(impLabels);
        importMenu.addSeparator();
        JMenuItem impHint = new JMenuItem("Imported data is drawn in the circular view only");
        impHint.setEnabled(false);
        importMenu.add(impHint);
        importBtn.addActionListener(e -> importMenu.show(importBtn, 0, importBtn.getHeight()));
        bar.add(importBtn);

        options.addActionListener(e -> menu.show(options, 0, options.getHeight()));
        bar.add(options);

        // ---- Export the current circular view as a publication-ready figure ----
        JButton export = new JButton("Export ▾");
        JPopupMenu exportMenu = new JPopupMenu();
        for (CircularExporter.Format f : CircularExporter.Format.values()) {
            JMenuItem item = new JMenuItem("Export " + f.name() + "…");
            item.addActionListener(e -> exportCircularView(panel, f));
            exportMenu.add(item);
        }
        exportMenu.addSeparator();
        JMenuItem hint = new JMenuItem("Size, resolution and file are chosen in the dialog");
        hint.setEnabled(false);
        exportMenu.add(hint);
        export.addActionListener(e -> exportMenu.show(export, 0, export.getHeight()));
        bar.add(export);
        return bar;
    }

    /** Export menu action: run the export dialog, then confirm where the figure was written. */
    private void exportCircularView(CircularViewPanel panel, CircularExporter.Format fmt) {
        File out = CircularExporter.showDialog(this, panel, fmt);
        if (out != null) {
            JOptionPane.showMessageDialog(this,
                    "Exported " + fmt.name() + ":\n" + out.getAbsolutePath(),
                    "Export circular view", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Small helper: a checkbox menu item wired to a setter callback. */
    private static JCheckBoxMenuItem linkCheck(String text, boolean initial,
                                               java.util.function.Consumer<Boolean> setter) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, initial);
        item.addActionListener(e -> setter.accept(item.isSelected()));
        return item;
    }

    /**
     * Append every Circos-link parameter to a popup. Shared by the "View options" dropdown and
     * the Data Tracks right-click menu, so both expose exactly the same set of actions.
     */
    /** Load a Circos / BEDPE link file directly into the circular links layer, bypassing the
     *  track-format router (so a .bed / .links file works too). Reuses BedPEParser, which already
     *  auto-detects UTF-8 / UTF-16LE / UTF-16BE with BOM. */
    private void loadLinksFromFile(CircularViewPanel panel) {
        java.awt.FileDialog fd = new java.awt.FileDialog(
                (java.awt.Frame) null, "Load Circos / BEDPE links", java.awt.FileDialog.LOAD);
        fd.setVisible(true);
        String dir = fd.getDirectory();
        String file = fd.getFile();
        if (dir == null || file == null) return;
        String path = dir + file;
        try {
            org.broad.igv.util.ResourceLocator locator = new org.broad.igv.util.ResourceLocator(path);
            org.broad.igv.feature.genome.Genome genome =
                    org.broad.igv.feature.genome.GenomeManager.getInstance().getCurrentGenome();
            org.broad.igv.bedpe.BedPEParser.Dataset ds =
                    org.broad.igv.bedpe.BedPEParser.parse(locator, genome);
            java.util.List<CircularViewPanel.RingLink> rings = new java.util.ArrayList<>();
            for (org.broad.igv.bedpe.BedPEFeature f : ds.features) {
                if (f.chr1 == null || f.chr2 == null) continue;
                CircularViewPanel.RingLink l = new CircularViewPanel.RingLink(
                        f.chr1, f.start1, f.end1, f.chr2, f.start2, f.end2);
                l.score = f.getScore();
                l.color = f.getColor();
                l.thickness = f.getThickness();
                l.label = f.getValueString();
                rings.add(l);
            }
            // Circular-only layer: no IGV track, so the horizontal view stays untouched.
            circularDrawingArea.addLinksLayer(file, path, rings);
            refreshDataColumn();
            circularDrawingArea.refresh();
            log.info("Loaded " + rings.size() + " circular links from " + path);
        } catch (Throwable ex) {
            log.error("Failed to load links from " + path, ex);
            javax.swing.JOptionPane.showMessageDialog(panel, "Could not load links:\n" + ex.getMessage(),
                    "Load links", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Load one or more genomic heatmaps (rows x samples); each becomes its own ring band. */
    private void loadHeatmapFromFile(CircularViewPanel panel) {
        java.awt.FileDialog fd = new java.awt.FileDialog(
                (java.awt.Frame) null, "Load genomic heatmap (BED/TSV rows x samples)", java.awt.FileDialog.LOAD);
        fd.setMultipleMode(true);
        fd.setVisible(true);
        java.io.File[] files = fd.getFiles();
        if (files == null || files.length == 0) {
            String dir = fd.getDirectory(), file = fd.getFile();
            if (dir == null || file == null) return;
            files = new java.io.File[]{new java.io.File(dir, file)};
        }
        int loaded = 0;
        for (java.io.File f : files) {
            String path = f.getAbsolutePath();
            String file = f.getName();
            try {
                org.broad.igv.util.ResourceLocator locator = new org.broad.igv.util.ResourceLocator(path);
                CircularViewPanel.GenomicHeatmap hm = CircularViewPanel.parseHeatmap(locator);
                if (hm == null) {
                    javax.swing.JOptionPane.showMessageDialog(panel,
                            "No numeric heatmap rows found in:\n" + file,
                            "Load heatmap", javax.swing.JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                circularDrawingArea.setHeatmapLayer(file, path, hm);
                loaded++;
                log.info("Loaded heatmap from " + path + " (" + hm.rows.size() + " rows, " + hm.samples + " samples)");
            } catch (Throwable ex) {
                log.error("Failed to load heatmap from " + path, ex);
                javax.swing.JOptionPane.showMessageDialog(panel, "Could not load heatmap:\n" + ex.getMessage(),
                        "Load heatmap", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        }
        if (loaded > 0) {
            refreshDataColumn();
            circularDrawingArea.refresh();
        }
    }

    /** Import one or more labels files: each becomes its own ring of text labels (genomicLabels). */
    private void loadLabelsFromFile(CircularViewPanel panel) {
        java.awt.FileDialog fd = new java.awt.FileDialog(
                (java.awt.Frame) null, "Import labels (BED, 4th column = name)",
                java.awt.FileDialog.LOAD);
        fd.setMultipleMode(true);
        fd.setVisible(true);
        java.io.File[] files = fd.getFiles();
        if (files == null || files.length == 0) {
            String dir = fd.getDirectory(), file = fd.getFile();
            if (dir == null || file == null) return;
            files = new java.io.File[]{new java.io.File(dir, file)};
        }
        int loaded = 0;
        for (java.io.File f : files) {
            String path = f.getAbsolutePath();
            String file = f.getName();
            try {
                org.broad.igv.util.ResourceLocator locator = new org.broad.igv.util.ResourceLocator(path);
                java.util.List<CircularViewPanel.LabelRow> rows = CircularViewPanel.parseLabels(locator);
                if (rows.isEmpty()) {
                    JOptionPane.showMessageDialog(panel,
                            "No chr / start / end / name rows found in:\n" + file,
                            "Import labels", JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                circularDrawingArea.addLabelsLayer(file, path, rows);
                loaded++;
                log.info("Loaded " + rows.size() + " circular labels from " + path);
            } catch (Throwable ex) {
                log.error("Failed to load labels from " + path, ex);
                JOptionPane.showMessageDialog(panel, "Could not load labels:\n" + ex.getMessage(),
                        "Import labels", JOptionPane.ERROR_MESSAGE);
            }
        }
        if (loaded > 0) {
            refreshDataColumn();
            circularDrawingArea.refresh();
        }
    }

    /** Per-layer labels parameters (font, colours, inside/outside). Edits write to THIS layer, so
     *  two imported labels files keep independent styles. */
    private void addLabelItems(JPopupMenu popup, CircularViewPanel panel,
                               CircularViewPanel.CircularLayer layer) {
        CircularViewPanel.CircularSettings s = panel.getSettings();
        // Effective values (this layer's override, else the global default) for the menu labels.
        boolean inside = layer.labelInside(s);
        boolean inward = layer.labelInward(s);
        boolean showLines = layer.labelShowLines(s);
        boolean bold = layer.labelBold(s);
        int fontSize = layer.labelFontSize(s);
        int gap = layer.labelGapPx(s);
        int height = layer.labelHeightPx(s);
        int spacing = layer.labelLevelGapPx(s);
        Color labelCol = layer.labelColor(s);
        Color lineCol = layer.labelLineColor(s);

        JCheckBoxMenuItem outside = new JCheckBoxMenuItem("Labels outside the ideogram", !inside);
        outside.addActionListener(e -> { layer.lblInside = !outside.isSelected(); panel.refresh(); });
        popup.add(outside);
        popup.add(linkCheck("Read labels toward the centre", inward,
                v -> { layer.lblInward = v; panel.refresh(); }));
        popup.add(linkCheck("Show connector lines", showLines,
                v -> { layer.lblShowLines = v; panel.refresh(); }));
        popup.add(linkCheck("Bold labels", bold,
                v -> { layer.lblBold = v; panel.refresh(); }));

        JMenuItem font = new JMenuItem("Font size… (" + fontSize + " pt)");
        font.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Label font size (pt):", fontSize);
            if (v != null) {
                try {
                    layer.lblFontSize = Math.max(4, Math.min(36, Integer.parseInt(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(font);

        JMenuItem textCol = new JMenuItem("Label colour…");
        textCol.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Label colour", labelCol);
            if (c != null) { layer.lblColor = c; panel.refresh(); }
        });
        popup.add(textCol);

        JMenuItem lineColItem = new JMenuItem("Connector colour…");
        lineColItem.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Connector colour", lineCol);
            if (c != null) { layer.lblLineColor = c; panel.refresh(); }
        });
        popup.add(lineColItem);

        JMenuItem connLen = new JMenuItem("Connector height… (" + gap + " px)");
        connLen.setToolTipText("Distance from the ideogram edge to the label ring "
                + "(the comb lives in this gap).");
        connLen.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Connector height, ideogram edge to label ring (px):", gap);
            if (v != null) {
                try {
                    layer.lblGapPx = Math.max(2, Math.min(80, Integer.parseInt(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(connLen);

        JMenuItem labH = new JMenuItem("Label height… (" + height + " px)");
        labH.setToolTipText("Radial room reserved for the label ring (circlize labels_height).");
        labH.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Label ring height (px):", height);
            if (v != null) {
                try {
                    layer.lblHeightPx = Math.max(10, Math.min(160, Integer.parseInt(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(labH);

        JMenuItem spacingItem = new JMenuItem("Label spacing… (" + spacing + " px)");
        spacingItem.setToolTipText("Gap between two adjacent labels when they are tiled end to end.");
        spacingItem.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Gap between adjacent labels (px):", spacing);
            if (v != null) {
                try {
                    layer.lblLevelGapPx = Math.max(0, Math.min(30, Integer.parseInt(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(spacingItem);
    }

    public static void addLinkItems(JPopupMenu popup, CircularViewPanel panel) {
        CircularViewPanel.CircularSettings s = panel.getSettings();

        // Links are loaded through the normal File > Load from File path: a BEDPE / Circos link
        // file becomes an interaction track, which the circular view picks up automatically.
        popup.add(linkCheck("Show links", s.showLinks,
                v -> { s.showLinks = v; panel.refresh(); }));

        popup.add(linkCheck("Draw inside data tracks", s.linkEndsInsideTracks,
                v -> { s.linkEndsInsideTracks = v; panel.refresh(); }));

        // Import + the heatmap parameters live in the Import menu and on the layer rows of the
        // Data Tracks column, not here (this submenu is the ribbon styling of an interaction
        // track that was loaded through File > Load from File).
        popup.addSeparator();

        popup.add(linkCheck("Ribbon (filled bands)", s.linkRibbon,
                v -> { s.linkRibbon = v; panel.refresh(); }));
        popup.add(linkCheck("Twist ribbons", s.linkTwist,
                v -> { s.linkTwist = v; panel.refresh(); }));
        popup.add(linkCheck("Colour by chromosome", s.linkColorByChromosome,
                v -> { s.linkColorByChromosome = v; panel.refresh(); }));

        JMenuItem colour = new JMenuItem("Colour…");
        colour.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Link colour", s.linkColor);
            if (c != null) { s.linkColor = c; panel.refresh(); }
        });
        popup.add(colour);

        JMenuItem thickness = new JMenuItem("Thickness… (" + s.linkThickness + " px)");
        thickness.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Link thickness (px):", s.linkThickness);
            if (v != null) {
                try { s.linkThickness = Math.max(0.1f, Float.parseFloat(v.trim())); panel.refresh(); }
                catch (NumberFormatException ignore) { }
            }
        });
        popup.add(thickness);

        // ---- arrow heads (circlize directional + arr.type = triangle) ----
        JMenu arrowMenu = new JMenu("Arrows");
        ButtonGroup arrowGroup = new ButtonGroup();
        String[] arrowNames = {"None", "Start (first interval)", "End (second interval)",
                "Both ends"};
        for (int k = 0; k < arrowNames.length; k++) {
            final int mode = k;
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(arrowNames[k]);
            it.setSelected(s.linkArrow == mode);
            it.addActionListener(e -> { s.linkArrow = mode; panel.refresh(); });
            arrowGroup.add(it);
            arrowMenu.add(it);
        }
        arrowMenu.addSeparator();
        JMenuItem arrLen = new JMenuItem("Arrow length (thin links)… ("
                + Math.round(s.linkArrowLength) + " px)");
        arrLen.setToolTipText("Length of the small arrow heads drawn on stroked (non-ribbon) links.");
        arrLen.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Arrow length for thin links (pixels):",
                    Math.round(s.linkArrowLength));
            if (v != null) {
                try {
                    s.linkArrowLength = Math.max(3, Math.min(80, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        arrowMenu.add(arrLen);
        JMenuItem arrLenR = new JMenuItem("Arrow height (ribbons)… ("
                + String.format(java.util.Locale.ROOT, "%.1f", s.linkArrowLenRatio) + "x band)");
        arrLenR.setToolTipText("Ribbon arrow heads are triangles whose base equals the band width; "
                + "this is how far the tip reaches inward as a multiple of that width.");
        arrLenR.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Ribbon arrow height as a multiple of the band width (0.2 - 5.0):",
                    String.format(java.util.Locale.ROOT, "%.1f", s.linkArrowLenRatio));
            if (v != null) {
                try {
                    s.linkArrowLenRatio = Math.max(0.2, Math.min(5.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        arrowMenu.add(arrLenR);
        popup.add(arrowMenu);

        JMenuItem linkAlpha = new JMenuItem("Transparency (0 = solid)… (" + (255 - s.linkAlpha) + ")");
        linkAlpha.setToolTipText("0 draws solid links; raise it to let overlapping links show through.");
        linkAlpha.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Link transparency (0 = solid, 255 = invisible):", 255 - s.linkAlpha);
            if (v != null) {
                try {
                    int t = Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
                    s.linkAlpha = 255 - t;
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(linkAlpha);
        popup.addSeparator();

        JMenuItem radius = new JMenuItem("End radius (0-1)… (" + s.linkRadius + ")");
        radius.setToolTipText("Fraction of the ideogram inner radius. Lower it to keep links clear of the data rings.");
        radius.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Link end radius, as a fraction of the ideogram inner radius (0.05-1):", s.linkRadius);
            if (v != null) {
                try {
                    s.linkRadius = Math.max(0.05, Math.min(1.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(radius);

        JMenuItem bezier = new JMenuItem("Bezier radius (0-1)… (" + s.linkBezierRadius + ")");
        bezier.setToolTipText("Circos bezier_radius: radial position of the control point. 0 = straight chord.");
        bezier.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Bezier control radius (0 = straight chord):", s.linkBezierRadius);
            if (v != null) {
                try {
                    s.linkBezierRadius = Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(bezier);

        JMenuItem crest = new JMenuItem("Crest (0-1)… (" + s.linkCrest + ")");
        crest.setToolTipText("Circos crest: makes links meet the ideogram perpendicularly. 0 disables.");
        crest.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Crest (0 disables):", s.linkCrest);
            if (v != null) {
                try {
                    s.linkCrest = Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(crest);

        JMenuItem minWidth = new JMenuItem("Min ribbon width (deg)… (" + s.linkRibbonMinDeg + ")");
        minWidth.setToolTipText("Widens ribbon ends so links with tiny anchors stay visible.");
        minWidth.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Minimum ribbon end width, in degrees:", s.linkRibbonMinDeg);
            if (v != null) {
                try { s.linkRibbonMinDeg = Math.max(0, Double.parseDouble(v.trim())); panel.refresh(); }
                catch (NumberFormatException ignore) { }
            }
        });
        popup.add(minWidth);

        JMenuItem ribbonAlpha = new JMenuItem("Ribbon transparency (0 = solid)… (" + (255 - s.linkRibbonAlpha) + ")");
        ribbonAlpha.setToolTipText("0 draws solid ribbons; raise it to let overlaps show through.");
        ribbonAlpha.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Ribbon transparency (0 = solid, 255 = invisible):", 255 - s.linkRibbonAlpha);
            if (v != null) {
                try {
                    int t = Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
                    s.linkRibbonAlpha = 255 - t;
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(ribbonAlpha);

        popup.add(linkCheck("Outline ribbons", s.linkRibbonStroke,
                v -> { s.linkRibbonStroke = v; panel.refresh(); }));

        JMenuItem maxLinks = new JMenuItem("Max links… (" + s.linkRecordLimit + ")");
        maxLinks.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Maximum links to draw:", s.linkRecordLimit);
            if (v != null) {
                try { s.linkRecordLimit = Math.max(1, Integer.parseInt(v.trim())); panel.refresh(); }
                catch (NumberFormatException ignore) { }
            }
        });
        popup.add(maxLinks);
    }

    /**
     * Drag a row in the Data Tracks list to change its position. The list order is the ring
     * order (top row = outermost band), so the circular view is refreshed immediately.
     */
    private class TrackReorderHandler extends TransferHandler {


        private final DataFlavor flavor = new DataFlavor(Integer.class, "data-track-index");
        private int draggedIndex = -1;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            draggedIndex = dataColumnList.getSelectedIndex();
            if (draggedIndex < 0) return null;
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{flavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return flavor.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) {
                    return draggedIndex;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(flavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                int from = (Integer) support.getTransferable().getTransferData(flavor);
                JList.DropLocation drop = (JList.DropLocation) support.getDropLocation();
                int to = drop.getIndex();
                if (from < 0 || to < 0) return false;
                if (to == from) return true;   // dropped onto its own row: genuinely nothing to do
                if (to > from + 1) to--;       // only shift the target when the drop lands more than
                                               // one slot below; otherwise a row dragged down by a
                                               // single slot (or to the very end) would be swallowed
                                               // as a no-op and never reach the innermost band
                moveDataRow(from, to);
                return true;
            } catch (Exception e) {
                log.error("Could not reorder data tracks", e);
                return false;
            }
        }
    }

    /** Move a row (layer or track) in the data column; the ring band order follows it. */
    private void moveDataRow(int from, int to) {
        if (from < 0 || from >= dataColumnRows.size()) return;
        to = Math.max(0, Math.min(dataColumnRows.size() - 1, to));
        Object moved = dataColumnRows.remove(from);
        dataColumnRows.add(to, moved);
        dataColumnList.setSelectedIndex(to);
        // refreshDataColumn() preserves this order, re-derives the heatmap band slot and pushes
        // both to the panel, so dragging a row moves that band in or out on the ring.
        refreshDataColumn();
        circularDrawingArea.refresh();
    }

    /** Tracks that get a band on the ring (and therefore take part in the row order). */
    private static boolean isBandTrack(Track t) {
        return t instanceof FeatureTrack || t instanceof DataTrack || t instanceof HiCTrack;
    }

    /** Row label for a circular-only layer: name + what it contains + where it is drawn. */
    private String layerLabel(CircularViewPanel.CircularLayer l) {
        String tag = l.summary();
        CircularViewPanel.CircularSettings s = circularDrawingArea.getSettings();
        // Make the inside/outside mode explicit: an "outside" layer ignores the row order, which
        // is otherwise very confusing when dragging does not move it on the ring.
        String where = "";
        if (l.kind == CircularViewPanel.CircularLayer.Kind.HEATMAP) {
            where = l.heatmapOutside(s) ? "   · outside" : "   · inside";
        } else if (l.kind == CircularViewPanel.CircularLayer.Kind.LABELS) {
            where = l.labelInside(s) ? "   · inside" : "   · outside";
        }
        return l.name + "   (" + tag + ")" + where + (l.visible ? "" : "   (hidden)");
    }

    /** Row label for the data column; hidden ring tracks are marked so removal is visible. */
    private String rowLabel(Track t) {
        String label = t.getDisplayName();
        return circularDrawingArea.isRingTrackHidden(t) ? label + "   (hidden)" : label;
    }

    /** Heatmap-ring parameters: shown on a heatmap layer row's context menu. */
    static void addHeatmapItems(JPopupMenu popup, CircularViewPanel panel,
                                CircularViewPanel.CircularLayer layer) {
        CircularViewPanel.CircularSettings s = panel.getSettings();
        // Per-layer heatmap settings: every edit writes to THIS layer's overrides, so several
        // imported heatmap files keep independent colours / height / connection track.
        popup.add(linkCheck("Show heatmap connectors", layer.heatConnectors(s),
                v -> { layer.heatConnectors = v; panel.refresh(); }));
        // Per-layer inside/outside: an inner heatmap can be moved outside on its own.
        popup.add(linkCheck("Heatmap outside the ideogram", layer.heatmapOutside(s),
                v -> { layer.heatOutside = v; panel.refresh(); }));

        int curHeight = layer.heatHeight(s);
        JMenuItem hmHeight = new JMenuItem("Heatmap height… (" + curHeight + " px)");
        hmHeight.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel, "Heatmap band height (pixels):",
                    curHeight);
            if (v != null) {
                try {
                    layer.heatHeightPx = Math.max(8, Integer.parseInt(v.trim()));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(hmHeight);

        int curConn = layer.heatConnPx(s);
        JMenuItem hmConn = new JMenuItem("Heatmap connection height… (" + curConn + " px)");
        hmConn.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(panel,
                    "Height of the connection track between the ideogram and the heatmap\n"
                            + "(this is where the circlize comb lines are drawn):",
                    curConn);
            if (v != null) {
                try {
                    layer.heatConnPx = Math.max(0, Integer.parseInt(v.trim()));
                    panel.refresh();
                } catch (NumberFormatException ignore) { }
            }
        });
        popup.add(hmConn);

        // ---- legend (outside the ring), same corner semantics as the Hi-C legend ----
        popup.addSeparator();
        popup.add(linkCheck("Show heatmap legend", s.heatmapLegend,
                v -> { s.heatmapLegend = v; panel.refresh(); }));
        JMenu legendPos = new JMenu("Legend position");
        ButtonGroup lg = new ButtonGroup();
        for (org.broad.igv.hic.HiCTrack.LegendPosition p
                : org.broad.igv.hic.HiCTrack.LegendPosition.values()) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(p.toString());
            it.setSelected(s.heatmapLegendPosition == p);
            it.addActionListener(e -> { s.heatmapLegendPosition = p; panel.refresh(); });
            lg.add(it);
            legendPos.add(it);
        }
        popup.add(legendPos);

        // ---- colours: 3 stops when the range straddles 0, otherwise 2 stops ----
        popup.addSeparator();
        CircularViewPanel.GenomicHeatmap hm = layer.heatmap;
        boolean straddles = hm != null && hm.min < 0 && hm.max > 0;
        JMenu colours = new JMenu(straddles ? "Heatmap colours (low / 0 / high)"
                : "Heatmap colours (low / high)");
        JMenuItem low = new JMenuItem("Low (min)…");
        low.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Heatmap low colour", layer.heatLow(s));
            if (c != null) { layer.heatLow = c; panel.refresh(); }
        });
        colours.add(low);
        JMenuItem mid = new JMenuItem("Mid (0)…");
        mid.setEnabled(straddles);
        if (!straddles) mid.setToolTipText(
                "Only used when the data range contains 0 (negative and positive values).");
        mid.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Heatmap mid colour", layer.heatMid(s));
            if (c != null) { layer.heatMid = c; panel.refresh(); }
        });
        colours.add(mid);
        JMenuItem high = new JMenuItem("High (max)…");
        high.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Heatmap high colour", layer.heatHigh(s));
            if (c != null) { layer.heatHigh = c; panel.refresh(); }
        });
        colours.add(high);
        colours.addSeparator();
        JMenuItem resetCols = new JMenuItem("Reset (green / black / red)");
        resetCols.addActionListener(e -> {
            layer.heatLow = new Color(0, 150, 0);
            layer.heatMid = new Color(0, 0, 0);
            layer.heatHigh = new Color(215, 40, 40);
            panel.refresh();
        });
        colours.add(resetCols);
        popup.add(colours);
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

    /**
     * Tracks shown in the circular Data Tracks column: loaded AND visible. A track that is
     * hidden is not listed and gets no ring band of its own — which is exactly what should
     * happen to a BED used as a Hi-C TAD/domain overlay (HiCDomainOverlay.acquire() hides it,
     * because its shapes are drawn ON the Hi-C ring instead).
     */
    private List<Track> circularColumnTracks() {
        List<Track> out = new ArrayList<>();
        if (igv == null) return out;
        List<Track> all = igv.getAllTracks();
        if (all == null) return out;
        for (Track t : all) {
            if (t != null && t.isVisible()) out.add(t);
        }
        return out;
    }

    /** Data Tracks column rows in DRAW ORDER (top = outermost ring band). An entry is either a
     *  {@link CircularViewPanel.CircularLayer} (circular-only imported file) or a {@link Track}.
     *  Dragging a row therefore reorders the ring bands, whichever kind it is. */
    private final java.util.List<Object> dataColumnRows = new ArrayList<>();

    public void refreshDataColumn() {
        if (igv == null || igv.getContentPane() == null) return;   // during construction contentPane is null
        List<CircularViewPanel.CircularLayer> layers = circularDrawingArea.getLayers();
        List<Track> tracks = circularColumnTracks();

        // Keep the order the user dragged things into. New layers/tracks are APPENDED, so the
        // first thing imported stays on the top row (outermost band) and later imports go inner —
        // matching "lower row = more inner track".
        List<Object> rows = new ArrayList<>();
        for (Object oldRow : dataColumnRows) {
            if (layers.contains(oldRow) || tracks.contains(oldRow)) rows.add(oldRow);
        }
        for (CircularViewPanel.CircularLayer l : layers) {
            if (!rows.contains(l)) rows.add(l);
        }
        for (Track t : tracks) {
            if (!rows.contains(t)) rows.add(t);
        }

        DefaultListModel<String> model = new DefaultListModel<>();
        dataColumnTracks.clear();
        int bandBefore = 0;          // band-capable rows above the current row
        int rowIdx = 0;              // absolute row position (0 = top), NOT the add order
        for (Object row : rows) {
            if (row instanceof CircularViewPanel.CircularLayer) {
                CircularViewPanel.CircularLayer l = (CircularViewPanel.CircularLayer) row;
                l.rowIndex = rowIdx;
                model.addElement(layerLabel(l));
                if (l.kind == CircularViewPanel.CircularLayer.Kind.HEATMAP && l.visible) {
                    // Each heatmap layer is its own ring band at its row position (several
                    // heatmaps stack, like several labels layers).
                    l.bandIndex = bandBefore;
                } else if (l.kind == CircularViewPanel.CircularLayer.Kind.LABELS
                        && l.visible && !l.labels.isEmpty()) {
                    // Each labels layer is its own ring band at its row position: the first
                    // visible labels row sits outermost, the next one an inner band, and so on.
                    l.bandIndex = bandBefore;
                }
                rowIdx++;
                continue;
            }
            Track t = (Track) row;
            model.addElement(rowLabel(t));
            dataColumnTracks.add(t);
            if (isBandTrack(t) && t.isVisible()) bandBefore++;
            rowIdx++;
        }
        dataColumnRows.clear();
        dataColumnRows.addAll(rows);
        if (model.isEmpty()) model.addElement("(no tracks loaded)");
        dataColumnList.setModel(model);
        circularDrawingArea.setRingTracks(new ArrayList<>(dataColumnTracks));   // ordered top->bottom

        // Any interaction track that is already loaded also feeds the circular links, so a
        // normal ".bedpe" import shows up on the ring without a second load step.
        List<CircularViewPanel.RingLink> autoLinks = new ArrayList<>();
        for (Track t : dataColumnTracks) {
            if (!(t instanceof InteractionTrack)) continue;
            if (circularDrawingArea.isRingTrackHidden(t)) continue;
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

    /**
     * Delete tracks for real. Unlike hiding, the track is unloaded from the session: it leaves
     * the Data Tracks list, the horizontal view and the ring, and a later import of the same
     * file starts from a clean slate (no leftover "(hidden)" marker, no stale cached matrix).
     */
    private void deleteTracks(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        List<Track> unique = new ArrayList<>(new LinkedHashSet<>(tracks));
        unique.removeIf(java.util.Objects::isNull);
        if (unique.isEmpty()) return;
        // Drop the ring-side state first: nothing should try to draw a track that is unloading.
        for (Track t : unique) circularDrawingArea.forgetRingTrack(t);
        try {
            if (igv != null) igv.deleteTracks(unique);
            else IGV.getInstance().deleteTracks(unique);
        } finally {
            // Rebuild the list from IGV's remaining tracks even if a panel refused the removal,
            // so the column can never show a row that the ring no longer draws.
            refreshDataColumn();
            circularDrawingArea.refresh();
            if (igv != null) igv.repaint();
        }
    }

    /**
     * Delete every selected Data Tracks row at once: circular-only layers are removed from the
     * ring, real tracks are unloaded from the session. Used by the multi-select "Delete selected".
     */
    private void deleteSelectedRows(int[] sel) {
        if (sel == null || sel.length == 0) return;
        List<CircularViewPanel.CircularLayer> layerSel = new ArrayList<>();
        List<Track> trackSel = new ArrayList<>();
        for (int si : sel) {
            if (si < 0 || si >= dataColumnRows.size()) continue;
            Object o = dataColumnRows.get(si);
            if (o instanceof CircularViewPanel.CircularLayer) {
                layerSel.add((CircularViewPanel.CircularLayer) o);
            } else if (o instanceof Track) {
                trackSel.add((Track) o);
            }
        }
        for (CircularViewPanel.CircularLayer l : layerSel) circularDrawingArea.removeLayer(l);
        if (!trackSel.isEmpty()) {
            deleteTracks(trackSel);   // unloads the tracks and refreshes the column + ring
        } else {
            refreshDataColumn();
            circularDrawingArea.refresh();
            if (igv != null) igv.repaint();
        }
        log.info("Deleted " + (layerSel.size() + trackSel.size()) + " selected circular row(s)");
    }

    /**
     * Context menu for a circular-only layer row: the parameters that belong to THAT layer
     * (ribbon styling for a links file, ring geometry for a heatmap) plus removal. This is why
     * they are no longer buried in View options.
     */
    private void showLayerPopup(MouseEvent e, CircularViewPanel.CircularLayer layer) {
        JPopupMenu menu = new JPopupMenu();
        final boolean heat = layer.kind == CircularViewPanel.CircularLayer.Kind.HEATMAP;

        String showLabel = heat ? "Show heatmap"
                : (layer.kind == CircularViewPanel.CircularLayer.Kind.LABELS
                        ? "Show labels" : "Show this links layer");
        menu.add(linkCheck(showLabel, layer.visible, v -> {
            circularDrawingArea.setLayerVisible(layer, v);
            refreshDataColumn();
        }));
        menu.addSeparator();

        boolean labels = layer.kind == CircularViewPanel.CircularLayer.Kind.LABELS;
        if (heat) addHeatmapItems(menu, circularDrawingArea, layer);
        else if (labels) addLabelItems(menu, circularDrawingArea, layer);
        else addLinkItems(menu, circularDrawingArea);

        menu.addSeparator();
        JMenuItem remove = new JMenuItem(heat ? "Remove heatmap from circular view…"
                : (layer.kind == CircularViewPanel.CircularLayer.Kind.LABELS
                        ? "Remove labels from circular view…"
                        : "Remove links from circular view…"));
        remove.addActionListener(a -> {
            circularDrawingArea.removeLayer(layer);
            refreshDataColumn();
            circularDrawingArea.refresh();
            if (igv != null) igv.repaint();
            log.info("Removed circular layer " + layer.name);
        });
        menu.add(remove);
        menu.show(dataColumnList, e.getX(), e.getY());
    }

    /** Right-click popup on the data column: per-track ring band height + collapse/expand. */
    private void maybeShowTrackPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int idx = dataColumnList.locationToIndex(e.getPoint());
        if (idx < 0 || idx >= dataColumnRows.size()) return;
        // A circular-only layer row has its own menu (ribbon / heatmap parameters + Remove).
        final Object hitRow = dataColumnRows.get(idx);
        if (hitRow instanceof CircularViewPanel.CircularLayer) {
            int[] sel = dataColumnList.getSelectedIndices();
            boolean inSel = false;
            for (int si : sel) if (si == idx) { inSel = true; break; }
            if (inSel && sel.length > 1) {
                // Multiple rows selected: offer a bulk delete of every selected row (layers + tracks).
                JPopupMenu menu = new JPopupMenu();
                JMenuItem del = new JMenuItem("Delete selected (" + sel.length + ")");
                del.addActionListener(a -> deleteSelectedRows(sel));
                menu.add(del);
                menu.show(dataColumnList, e.getX(), e.getY());
                return;
            }
            dataColumnList.setSelectedIndex(idx);
            showLayerPopup(e, (CircularViewPanel.CircularLayer) hitRow);
            return;
        }
        // Keep an existing multi-selection if the click landed inside it; else select just this row.
        boolean inSel = false;
        for (int si : dataColumnList.getSelectedIndices()) if (si == idx) { inSel = true; break; }
        if (!inSel) dataColumnList.setSelectedIndex(idx);

        final int[] sel = dataColumnList.getSelectedIndices();
        final java.util.List<CircularViewPanel.TrackRingConfig> cfgs = new ArrayList<>();
        final java.util.List<CircularViewPanel.TrackRingConfig> featureCfgs = new ArrayList<>();
        final java.util.List<CircularViewPanel.TrackRingConfig> dataCfgs = new ArrayList<>();
        final java.util.List<String> ringNames = new ArrayList<>();
        final java.util.List<String> linkNames = new ArrayList<>();
        final java.util.List<Track> ringTracksSel = new ArrayList<>();
        final java.util.List<Track> linkTracksSel = new ArrayList<>();
        final java.util.List<HiCTrack> hicTracks = new ArrayList<>();
        final java.util.List<CircularViewPanel.TrackRingConfig> hicCfgs = new ArrayList<>();
        for (int si : sel) {
            if (si < 0 || si >= dataColumnRows.size()) continue;
            if (!(dataColumnRows.get(si) instanceof Track)) continue;   // layer rows: own menu
            Track t = (Track) dataColumnRows.get(si);
            // An interaction track *is* the links layer, so the only ring option it needs is
            // removal; there is no band to size or recolour.
            if (t instanceof InteractionTrack) {
                linkNames.add(t.getName());
                linkTracksSel.add(t);
                continue;
            }
            // A Hi-C track IS drawn as a matrix band on the ring, but it is neither a
            // FeatureTrack nor a DataTrack. Collect it before the band-type filter drops it;
            // keep its config out of featureCfgs / dataCfgs so no unrelated items leak in.
            if (t instanceof HiCTrack) {
                HiCTrack hic = (HiCTrack) t;
                CircularViewPanel.TrackRingConfig hc = circularDrawingArea.getRingConfig(t.getName());
                cfgs.add(hc);
                hicCfgs.add(hc);
                ringNames.add(t.getName());
                ringTracksSel.add(t);
                hicTracks.add(hic);
                continue;
            }
            // Only FeatureTrack / DataTrack are drawn as bands on the ring. Other tracks in the
            // list (e.g. the reference sequence) have no ring band, so they get no ring options.
            if (!(t instanceof FeatureTrack) && !(t instanceof DataTrack)) continue;
            CircularViewPanel.TrackRingConfig c =
                    circularDrawingArea.getRingConfig(t.getName());
            cfgs.add(c);
            ringNames.add(t.getName());
            ringTracksSel.add(t);
            if (t instanceof FeatureTrack) featureCfgs.add(c);
            else dataCfgs.add(c);
        }

        if (cfgs.isEmpty() && linkNames.isEmpty() && hicTracks.isEmpty()) {
            // Nothing selected is drawn on the ring and nothing supplies links (e.g. the
            // reference sequence track): there is no meaningful action, so show no popup.
            return;
        }

        if (cfgs.isEmpty()) {
            // Only interaction tracks selected: they contribute links, so allow removing those.
            JPopupMenu menu = new JPopupMenu();
            String manyLinks = linkNames.size() > 1 ? " (" + linkNames.size() + " tracks)" : "";
            JMenuItem removeLinks = new JMenuItem("Delete track" + manyLinks);
            removeLinks.addActionListener(a -> {
                try {
                    deleteTracks(new ArrayList<>(linkTracksSel));
                    log.info("Deleted links track(s) " + linkNames
                            + "; links remaining=" + circularDrawingArea.getRingLinkCount());
                } catch (Exception ex) {
                    log.error("Failed to delete links track", ex);
                }
            });
            menu.add(removeLinks);
            menu.addSeparator();
            JMenu linksMenu = new JMenu("Links");
            addLinkItems(linksMenu.getPopupMenu(), circularDrawingArea);
            menu.add(linksMenu);
            menu.show(dataColumnList, e.getX(), e.getY());
            return;
        }

        final CircularViewPanel.TrackRingConfig cfg = cfgs.get(0);
        final int n = cfgs.size();
        final String many = n > 1 ? " (" + n + " tracks)" : "";

        JPopupMenu menu = new JPopupMenu();
        final boolean onlyHic = !hicTracks.isEmpty() && hicTracks.size() == ringTracksSel.size();
        final int shownHeight = onlyHic ? cfg.hicHeightPx : cfg.heightPx;
        JMenuItem height = new JMenuItem("Track height… (" + shownHeight + " px" + many + ")");
        height.addActionListener(a -> {
            String v = JOptionPane.showInputDialog(this, "Ring track height (pixels):", shownHeight);
            if (v != null) {
                try {
                    int nh = Math.max(8, Integer.parseInt(v.trim()));
                    for (CircularViewPanel.TrackRingConfig c : cfgs) {
                        if (hicCfgs.contains(c)) c.hicHeightPx = nh;   // Hi-C uses its own taller band
                        else c.heightPx = nh;
                    }
                    circularDrawingArea.invalidateRing();
                } catch (NumberFormatException ignore) { }
            }
        });
        menu.add(height);

        // "Expanded" only means something for feature tracks: it stacks overlapping features.
        if (!featureCfgs.isEmpty()) {
            JCheckBoxMenuItem expanded = new JCheckBoxMenuItem(
                    "Expanded (stack features)", featureCfgs.get(0).expanded);
            expanded.addActionListener(a -> {
                for (CircularViewPanel.TrackRingConfig c : featureCfgs) c.expanded = expanded.isSelected();
                circularDrawingArea.invalidateRing();
            });
            menu.add(expanded);

            // Stacking direction for expanded feature tracks: pin transcript rows to the outer
            // (top) edge and grow inward, or the default inner (bottom) edge and grow outward.
            JCheckBoxMenuItem stackOuter = new JCheckBoxMenuItem(
                    "Stack from outer edge", featureCfgs.get(0).stackFromOuter);
            stackOuter.addActionListener(a -> {
                for (CircularViewPanel.TrackRingConfig c : featureCfgs) c.stackFromOuter = stackOuter.isSelected();
                circularDrawingArea.invalidateRing();
            });
            menu.add(stackOuter);
        }

        // Graph type and the two Y-axis options only apply to numeric data tracks
        // (bigWig / bedGraph); a BED or gene track has no values to scale or plot.
        if (!dataCfgs.isEmpty()) {
            final CircularViewPanel.TrackRingConfig dcfg = dataCfgs.get(0);

            JMenu graph = new JMenu("Graph type");
            ButtonGroup gg = new ButtonGroup();
            for (String tp : new String[]{"Bar", "Point", "Line", "Heatmap"}) {
                JRadioButtonMenuItem mi = new JRadioButtonMenuItem(tp, tp.equals(dcfg.graphType));
                mi.addActionListener(a -> {
                    for (CircularViewPanel.TrackRingConfig c : dataCfgs) c.graphType = tp;
                    circularDrawingArea.invalidateRing();
                });
                gg.add(mi);
                graph.add(mi);
            }
            menu.add(graph);

            JMenu yaxis = new JMenu("Y-axis range");
            ButtonGroup yg = new ButtonGroup();
            JRadioButtonMenuItem yPer = new JRadioButtonMenuItem(
                    "Per chromosome (default)", !dcfg.globalYScale);
            yPer.addActionListener(a -> {
                for (CircularViewPanel.TrackRingConfig c : dataCfgs) c.globalYScale = false;
                circularDrawingArea.invalidateRing();
            });
            JRadioButtonMenuItem yGlobal = new JRadioButtonMenuItem(
                    "All chromosomes (unified)", dcfg.globalYScale);
            yGlobal.addActionListener(a -> {
                for (CircularViewPanel.TrackRingConfig c : dataCfgs) c.globalYScale = true;
                circularDrawingArea.invalidateRing();
            });
            yg.add(yPer);
            yg.add(yGlobal);
            yaxis.add(yPer);
            yaxis.add(yGlobal);
            menu.add(yaxis);

            JMenu yscale = new JMenu("Y-axis scale (multi-sample)");
            ButtonGroup ysg = new ButtonGroup();
            JRadioButtonMenuItem ySelf = new JRadioButtonMenuItem(
                    "Self scale (default)", dcfg.yGroup == null);
            ySelf.addActionListener(a -> {
                for (CircularViewPanel.TrackRingConfig c : dataCfgs) { c.yGroup = null; c.yGroupPerGene = false; }
                circularDrawingArea.invalidateRing();
            });
            JRadioButtonMenuItem yGroupItem = new JRadioButtonMenuItem(
                    "Group scale (selected" + (dataCfgs.size() > 1 ? ", " + dataCfgs.size() + " tracks" : "") + ")",
                    dcfg.yGroup != null && !dcfg.yGroupPerGene);
            yGroupItem.addActionListener(a -> {
                String gid = "g" + System.nanoTime();
                for (CircularViewPanel.TrackRingConfig c : dataCfgs) { c.yGroup = gid; c.yGroupPerGene = false; }
                circularDrawingArea.invalidateRing();
            });
            JRadioButtonMenuItem yGroupPerGene = new JRadioButtonMenuItem(
                    "Group scale each gene panel", dcfg.yGroup != null && dcfg.yGroupPerGene);
            yGroupPerGene.addActionListener(a -> {
                String gid = "g" + System.nanoTime();
                for (CircularViewPanel.TrackRingConfig c : dataCfgs) { c.yGroup = gid; c.yGroupPerGene = true; }
                circularDrawingArea.invalidateRing();
            });
            ysg.add(ySelf);
            ysg.add(yGroupItem);
            ysg.add(yGroupPerGene);
            yscale.add(ySelf);
            yscale.add(yGroupItem);
            yscale.add(yGroupPerGene);
            menu.add(yscale);
        }

        // ---- Hi-C: ring band height + the LINEAR track's own parameter submenus (reused via
        // addHiCSubmenus, never reimplemented) so every Hi-C setting is reachable here too. ----
        if (!hicTracks.isEmpty()) {
            menu.addSeparator();
            for (HiCTrack hic : hicTracks) {
                final CircularViewPanel.TrackRingConfig hc =
                        circularDrawingArea.getRingConfig(hic.getName());
                JMenuItem hicHeight = new JMenuItem("Hi-C ring height… (" + hc.hicHeightPx + " px)");
                hicHeight.addActionListener(a -> {
                    String v = JOptionPane.showInputDialog(this, "Hi-C ring height (pixels):", hc.hicHeightPx);
                    if (v != null) {
                        try {
                            int nh = Math.max(8, Integer.parseInt(v.trim()));
                            for (HiCTrack h : hicTracks) {
                                circularDrawingArea.getRingConfig(h.getName()).hicHeightPx = nh;
                            }
                            circularDrawingArea.invalidateRing();
                        } catch (NumberFormatException ignore) { }
                    }
                });
                menu.add(hicHeight);
            }
            // Never pass a null onChange: the linear Hi-C track must repaint too when a
            // parameter changes, otherwise the two views would disagree.
            final Runnable onChange = () -> {
                circularDrawingArea.invalidateRing();
                circularDrawingArea.repaint();
                igv.repaint();
            };
            for (HiCTrack hic : hicTracks) {
                HiCTrack.addHiCSubmenus(menu, hic, onChange);
            }
        }

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
        JMenuItem removeRing = new JMenuItem("Delete track" + many);
        removeRing.addActionListener(a -> {
            try {
                // A mixed selection can also contain interaction tracks, which contribute links.
                List<Track> doomed = new ArrayList<>(ringTracksSel);
                doomed.addAll(linkTracksSel);
                deleteTracks(doomed);
                log.info("Deleted track(s): bands=" + ringNames + " links=" + linkNames
                        + "; links remaining=" + circularDrawingArea.getRingLinkCount());
            } catch (Exception ex) {
                log.error("Failed to delete track", ex);
            }
        });
        menu.add(removeRing);

        // Link settings live in View options > Links; a band track (feature / data) does not
        // supply links, so it does not get the submenu here.
        menu.show(dataColumnList, e.getX(), e.getY());
    }

}
