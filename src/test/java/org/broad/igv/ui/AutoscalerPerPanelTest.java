package org.broad.igv.ui;

import org.broad.igv.prefs.Constants;
import org.broad.igv.prefs.PreferencesManager;
import org.broad.igv.renderer.DataRange;
import org.broad.igv.track.*;
import org.broad.igv.ui.panel.FrameManager;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Group autoscale in gene list mode: each panel can be scaled to its own data instead of all
 * panels sharing one range.
 */
public class AutoscalerPerPanelTest {

    @After
    public void tearDown() {
        FrameManager.setFrames(Arrays.asList(FrameManager.getDefaultFrame()));
        PreferencesManager.getPreferences().put(Constants.GROUP_AUTOSCALE_PER_PANEL, true);
    }

    @Test
    public void testPerPanelScaling() {

        ReferenceFrame frame1 = new ReferenceFrame("Frame1");
        ReferenceFrame frame2 = new ReferenceFrame("Frame2");
        FrameManager.setFrames(Arrays.asList(frame1, frame2));

        StubTrack track1 = new StubTrack("track1");
        StubTrack track2 = new StubTrack("track2");

        // Panel 1 has a much higher signal than panel 2
        track1.setInViewRange("Frame1", new Range(0, 100));
        track2.setInViewRange("Frame1", new Range(0, 80));
        track1.setInViewRange("Frame2", new Range(0, 10));
        track2.setInViewRange("Frame2", new Range(0, 8));

        track1.setAttributeValue(AttributeManager.GROUP_AUTOSCALE, "1");
        track2.setAttributeValue(AttributeManager.GROUP_AUTOSCALE, "1");

        PreferencesManager.getPreferences().put(Constants.GROUP_AUTOSCALE_PER_PANEL, true);
        Autoscaler.autoscale(Arrays.asList(track1, track2));

        // Each panel gets its own maximum, shared by both tracks in the group
        assertEquals(100f, track1.getDataRange(frame1).getMaximum(), 0.001);
        assertEquals(100f, track2.getDataRange(frame1).getMaximum(), 0.001);
        assertEquals(10f, track1.getDataRange(frame2).getMaximum(), 0.001);
        assertEquals(10f, track2.getDataRange(frame2).getMaximum(), 0.001);
    }

    @Test
    public void testSharedRangeWhenOptionDisabled() {

        ReferenceFrame frame1 = new ReferenceFrame("Frame1");
        ReferenceFrame frame2 = new ReferenceFrame("Frame2");
        FrameManager.setFrames(Arrays.asList(frame1, frame2));

        StubTrack track1 = new StubTrack("track1");
        StubTrack track2 = new StubTrack("track2");
        track1.setInViewRange("Frame1", new Range(0, 100));
        track2.setInViewRange("Frame1", new Range(0, 80));
        track1.setInViewRange("Frame2", new Range(0, 10));
        track2.setInViewRange("Frame2", new Range(0, 8));

        track1.setAttributeValue(AttributeManager.GROUP_AUTOSCALE, "1");
        track2.setAttributeValue(AttributeManager.GROUP_AUTOSCALE, "1");

        PreferencesManager.getPreferences().put(Constants.GROUP_AUTOSCALE_PER_PANEL, false);
        Autoscaler.autoscale(Arrays.asList(track1, track2));

        // One range for everything, as in stock IGV
        assertEquals(100f, track1.getDataRange(frame1).getMaximum(), 0.001);
        assertEquals(100f, track1.getDataRange(frame2).getMaximum(), 0.001);
        assertEquals(100f, track2.getDataRange(frame2).getMaximum(), 0.001);
    }

    /**
     * Minimal track that reports a fixed in-view range per panel.
     */
    private static class StubTrack extends AbstractTrack implements ScalableTrack {

        private final Map<String, Range> ranges = new HashMap<>();

        StubTrack(String name) {
            super(null, name, name);
        }

        void setInViewRange(String frameName, Range range) {
            ranges.put(frameName, range);
        }

        @Override
        public Range getInViewRange(ReferenceFrame frame) {
            return ranges.get(frame.getName());
        }

        @Override
        public boolean isVisible() {
            return true;
        }

        @Override
        public void render(RenderContext context, java.awt.Rectangle rect) {
            // No drawing needed for these tests
        }

        @Override
        public void load(ReferenceFrame frame) {
            // Nothing to load
        }

        @Override
        public boolean isReadyToPaint(ReferenceFrame frame) {
            return true;
        }

        @Override
        public DataRange getDataRange() {
            return super.getDataRange();
        }
    }
}
