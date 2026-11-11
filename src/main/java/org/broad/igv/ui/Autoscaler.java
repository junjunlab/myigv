package org.broad.igv.ui;

import org.broad.igv.prefs.Constants;
import org.broad.igv.prefs.PreferencesManager;
import org.broad.igv.renderer.DataRange;
import org.broad.igv.track.*;
import org.broad.igv.ui.panel.FrameManager;
import org.broad.igv.ui.panel.ReferenceFrame;

import java.util.*;

public class Autoscaler {

    public static void autoscale(Collection<Track> trackList) {
        Map<String, List<Track>> autoscaleGroups = new HashMap<>();
        // "Group scale each gene panel": ungrouped tracks that merely have plain autoscale on
        // are collected into ONE implicit group, so every sample in a gene panel shares a
        // single Y range instead of each track scaling itself. Explicit GROUP_AUTOSCALE
        // groups are still honoured, so users can keep several independent groups.
        boolean perPanel = FrameManager.isGeneListMode()
                && FrameManager.getFrames().size() > 1 && isPerPanelAutoscale();
        List<Track> implicitGroup = new ArrayList<>();

        for (Track track : trackList) {
            if (!track.isVisible()) continue;

            String asGroup = track.getAttributeValue(AttributeManager.GROUP_AUTOSCALE);
            if (asGroup != null) {
                if (!autoscaleGroups.containsKey(asGroup)) {
                    autoscaleGroups.put(asGroup, new ArrayList<Track>());
                }
                if (track instanceof MergedTracks) {
                    for (Track mt : ((MergedTracks) track).getMemberTracks()) {
                        autoscaleGroups.get(asGroup).add(mt);
                    }
                } else {
                    autoscaleGroups.get(asGroup).add(track);
                }
            } else if (track.getAutoScale()) {
                if (track instanceof MergedTracks) {
                    List<Track> memberTracks = new ArrayList(((MergedTracks) track).getMemberTracks());
                    if (perPanel) implicitGroup.addAll(memberTracks);
                    else autoscaleGroup(memberTracks);
                } else {
                    if (perPanel) implicitGroup.add(track);
                    else autoscaleGroup(Arrays.asList(track));
                }
            }
        }

        if (perPanel && !implicitGroup.isEmpty()) {
            autoscaleGroup(implicitGroup);      // dispatches to autoscalePerPanel()
        }
        for (List<Track> tracks : autoscaleGroups.values()) {
            autoscaleGroup(tracks);
        }
    }

    /**
     * Autoscaling happens here.  Ungrouped tracks are treated as groups of size 1.
     * *
     * @param trackList -- list of tracks to be autoscaled as a group.
     */
    private static void autoscaleGroup(List<Track> trackList) {
        List<ReferenceFrame> frames =
                FrameManager.isGeneListMode() ? FrameManager.getFrames() :
                        Arrays.asList(FrameManager.getDefaultFrame());

        // In gene list mode each panel can be scaled to its own data, so that (for example) a high
        // coverage gene does not flatten the signal of the others.
        if (FrameManager.isGeneListMode() && frames.size() > 1 && isPerPanelAutoscale()) {
            autoscalePerPanel(trackList, frames);
            return;
        }

        List<Range> inViewRanges = Collections.synchronizedList(new ArrayList<>());
        for (Track track : trackList) {
            if (track instanceof ScalableTrack) {
                for (ReferenceFrame frame : frames) {
                    Range r = ((ScalableTrack) track).getInViewRange(frame);
                    if (r != null) {
                        inViewRanges.add(r);
                    }
                }
            }
        }

        if (inViewRanges.size() > 0) {
            Range inter = computeScale(inViewRanges);
            for (Track track : trackList) {
                DataRange dr = track.getDataRange();
                DataRange newDR = createDataRange(dr, inter);
                track.setDataRange(newDR);   // also clears any per-panel ranges
            }
        }
    }

    /**
     * Compute an independent data range for every panel, shared by all tracks in the group.
     */
    private static void autoscalePerPanel(List<Track> trackList, List<ReferenceFrame> frames) {

        for (ReferenceFrame frame : frames) {

            List<Range> inViewRanges = new ArrayList<>();
            for (Track track : trackList) {
                if (track instanceof ScalableTrack) {
                    Range r = ((ScalableTrack) track).getInViewRange(frame);
                    if (r != null) {
                        inViewRanges.add(r);
                    }
                }
            }

            if (inViewRanges.isEmpty()) {
                continue;
            }

            Range inter = computeScale(inViewRanges);
            for (Track track : trackList) {
                DataRange dr = track.getDataRange(frame);
                DataRange newDR = createDataRange(dr, inter);
                if (track instanceof AbstractTrack) {
                    ((AbstractTrack) track).setDataRange(frame, newDR);
                }
            }
        }
    }

    private static DataRange createDataRange(DataRange current, Range inter) {
        float min = Math.min(0, inter.min);
        float base = min < 0 ? 0 : min;
        float max = inter.max;
        // Pathological case where min ~= max  (no data in view)
        if (max - min <= (2 * Float.MIN_VALUE)) {
            max = min + 1;
        }
        DataRange newDR = new DataRange(min, base, max, current.isDrawBaseline());
        newDR.setType(current.getType());
        return newDR;
    }

    /**
     * @return true if group autoscale should be computed separately for each gene-list panel.
     */
    public static boolean isPerPanelAutoscale() {
        try {
            return PreferencesManager.getPreferences().getAsBoolean(Constants.GROUP_AUTOSCALE_PER_PANEL);
        } catch (Exception e) {
            return true;
        }
    }


    public static Range computeScale(List<Range> ranges) {

        float min = 0;
        float max = 0;
        if (ranges.size() > 0) {
            max = ranges.get(0).max;
            min = ranges.get(0).min;
            for (int i = 1; i < ranges.size(); i++) {
                Range r = ranges.get(i);
                max = Math.max(r.max, max);
                min = Math.min(r.min, min);

            }
        }
        return new Range(min, max);
    }
}
