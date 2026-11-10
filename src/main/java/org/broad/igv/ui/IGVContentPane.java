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
import org.broad.igv.ui.panel.MainPanel;
import org.broad.igv.ui.panel.TrackPanel;
import org.broad.igv.track.Track;
import org.broad.igv.ui.util.ApplicationStatusBar;

import javax.swing.*;
import java.util.List;
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
    private JSplitPane viewSplitPane;
    private JSplitPane outerSplitPane;
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

        // Circular (ring) view placeholder.  Blank for now -- not drawn yet.
        circularPanel = new JPanel(new BorderLayout());
        circularPanel.setBackground(new Color(245, 245, 245));
        circularPanel.setBorder(BorderFactory.createTitledBorder("Circular View"));
        JLabel circularPlaceholder = new JLabel("Circular view — not drawn yet", SwingConstants.CENTER);
        circularPlaceholder.setForeground(Color.GRAY);
        circularPanel.add(circularPlaceholder, BorderLayout.CENTER);

        // Left "data column" panel (used whenever the circular view is shown).
        // Lists imported tracks; placeholder for now, later used to pick tracks for the ring.
        dataColumnPanel = new JPanel(new BorderLayout());
        dataColumnPanel.setBorder(BorderFactory.createTitledBorder("Data Tracks"));
        dataColumnList = new JList<>();
        dataColumnList.setBackground(new Color(248, 248, 248));
        dataColumnPanel.add(new JScrollPane(dataColumnList), BorderLayout.CENTER);

        updateViewLayout();

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
            // Four-column layout: data column | horizontal view | circular view.
            // The horizontal view keeps IGV's original appearance (name column + data area together).
            viewSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mainPanel, circularPanel);
            configureSplit(viewSplitPane, 0.7);
            outerSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, dataColumnPanel, viewSplitPane);
            configureSplit(outerSplitPane, 0.18);
            add(outerSplitPane, BorderLayout.CENTER);
            currentCenter = outerSplitPane;
        } else if (showHorizontalView) {
            add(mainPanel, BorderLayout.CENTER);
            currentCenter = mainPanel;
        } else if (showCircularView) {
            // Only circular: data column (imported tracks) | circular drawing panel.
            refreshDataColumn();
            viewSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, dataColumnPanel, circularPanel);
            configureSplit(viewSplitPane, 0.18);
            add(viewSplitPane, BorderLayout.CENTER);
            currentCenter = viewSplitPane;
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
     * Populate the left data-column list with the currently loaded tracks.
     * Used by circular-involving layouts as the "imported data" column.
     */
    public void refreshDataColumn() {
        if (igv == null) return;
        List<Track> tracks = igv.getAllTracks();
        DefaultListModel<String> model = new DefaultListModel<>();
        if (tracks != null) {
            for (Track t : tracks) {
                model.addElement(t.getDisplayName());
            }
        }
        if (model.isEmpty()) {
            model.addElement("(no tracks loaded)");
        }
        dataColumnList.setModel(model);
    }

}
