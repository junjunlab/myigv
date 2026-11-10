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
import org.broad.igv.ui.commandbar.IGVCommandBar;
import org.broad.igv.ui.panel.CircularViewPanel;
import org.broad.igv.ui.panel.MainPanel;
import org.broad.igv.ui.panel.TrackPanel;
import org.broad.igv.track.Track;
import org.broad.igv.ui.util.ApplicationStatusBar;

import javax.swing.*;
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
            configureSplit(viewSplitPane, 0.7);
            add(viewSplitPane, BorderLayout.CENTER);
            currentCenter = viewSplitPane;
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
    }

    /** Right-click popup on the data column: per-track ring band height + collapse/expand. */
    private void maybeShowTrackPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int idx = dataColumnList.locationToIndex(e.getPoint());
        if (idx < 0 || idx >= dataColumnTracks.size()) return;
        dataColumnList.setSelectedIndex(idx);
        Track track = dataColumnTracks.get(idx);
        CircularViewPanel.TrackRingConfig cfg = circularDrawingArea.getRingConfig(track.getName());

        JPopupMenu menu = new JPopupMenu();
        JMenuItem height = new JMenuItem("Track height… (" + cfg.heightPx + " px)");
        height.addActionListener(a -> {
            String v = JOptionPane.showInputDialog(this, "Ring track height (pixels):", cfg.heightPx);
            if (v != null) {
                try { cfg.heightPx = Math.max(8, Integer.parseInt(v.trim())); circularDrawingArea.invalidateRing(); }
                catch (NumberFormatException ignore) { }
            }
        });
        menu.add(height);

        JCheckBoxMenuItem expanded = new JCheckBoxMenuItem("Expanded (stack features)", cfg.expanded);
        expanded.addActionListener(a -> { cfg.expanded = expanded.isSelected(); circularDrawingArea.invalidateRing(); });
        menu.add(expanded);

        menu.addSeparator();
        JMenuItem removeRing = new JMenuItem("Remove track from ring");
        removeRing.addActionListener(a -> circularDrawingArea.removeRingTrack(track.getName()));
        menu.add(removeRing);

        menu.show(dataColumnList, e.getX(), e.getY());
    }

}
