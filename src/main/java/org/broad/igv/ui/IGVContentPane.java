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
import org.broad.igv.ui.util.ApplicationStatusBar;

import javax.swing.*;
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

        // Circular (ring) view placeholder.  Blank for now -- not drawn yet.
        circularPanel = new JPanel(new BorderLayout());
        circularPanel.setBackground(new Color(245, 245, 245));
        circularPanel.setBorder(BorderFactory.createTitledBorder("环形视图（占位）"));
        JLabel circularPlaceholder = new JLabel("环形视图占位区 — 暂未绘制", SwingConstants.CENTER);
        circularPlaceholder.setForeground(Color.GRAY);
        circularPanel.add(circularPlaceholder, BorderLayout.CENTER);

        // Horizontal split: left = horizontal (linear) genome browser, right = circular placeholder.
        viewSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mainPanel, circularPanel);
        viewSplitPane.setDividerSize(4);
        viewSplitPane.setResizeWeight(0.7);
        viewSplitPane.setOneTouchExpandable(false);
        viewSplitPane.setContinuousLayout(true);

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
     *  - both on  -> horizontal (left) + circular placeholder (right) side by side
     *  - only horizontal -> horizontal fills the center
     *  - only circular  -> circular placeholder fills the center
     * The window is never left empty.
     */
    public void updateViewLayout() {
        if (currentCenter != null) {
            remove(currentCenter);
        }
        if (showHorizontalView && showCircularView) {
            viewSplitPane.setLeftComponent(mainPanel);
            viewSplitPane.setRightComponent(circularPanel);
            add(viewSplitPane, BorderLayout.CENTER);
            currentCenter = viewSplitPane;
        } else if (showHorizontalView) {
            add(mainPanel, BorderLayout.CENTER);
            currentCenter = mainPanel;
        } else {
            add(circularPanel, BorderLayout.CENTER);
            currentCenter = circularPanel;
        }
        revalidate();
        repaint();
    }

}
