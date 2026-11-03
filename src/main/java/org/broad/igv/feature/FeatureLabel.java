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
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.broad.igv.feature;

import org.broad.igv.prefs.Constants;
import org.broad.igv.prefs.PreferencesManager;

/**
 * Decides which string is drawn next to a feature in an expanded annotation track.
 * <p>
 * IGV has always drawn {@link IGVFeature#getName()}, which for RefSeq is the gene symbol
 * ({@code name2}) and for GTF is {@code gene_name} — i.e. the same text on every transcript row
 * of a gene.  The transcript accession is already available as {@link IGVFeature#getIdentifier()}
 * (RefSeq {@code NM_/NR_} accession, GTF {@code transcript_id}), so this class maps a small
 * set of label modes onto those fields.
 */
public final class FeatureLabel {

    /** Gene name (current IGV behavior). */
    public static final String GENE = "name";
    /** Transcript accession / transcript_id, e.g. NM_003865.3 or ENST00000382410. */
    public static final String ID = "id";
    /** Readable transcript name, e.g. HESX1-201 (GTF transcript_name); falls back to the accession. */
    public static final String TRANSCRIPT_NAME = "transcript_name";
    /** Gene name followed by the accession, e.g. {@code HESX1 (NM_003865.3)}. */
    public static final String GENE_AND_ID = "gene_and_id";

    private FeatureLabel() {
    }

    /**
     * The application-wide default mode, used when a track has no explicit label field.
     */
    public static String defaultMode() {
        String mode = PreferencesManager.getPreferences().get(Constants.TRANSCRIPT_LABEL_MODE);
        return (mode == null || mode.trim().isEmpty()) ? GENE : mode.trim();
    }

    /**
     * @param mode    one of the constants above (or any feature attribute name)
     * @param feature feature being drawn
     * @return the label to draw, never null when a name or identifier is available
     */
    public static String resolve(String mode, IGVFeature feature) {
        if (feature == null) {
            return null;
        }
        if (mode == null || mode.trim().isEmpty()) {
            mode = GENE;
        }

        switch (mode) {
            case ID:
                return firstNonEmpty(feature.getIdentifier(), feature.getName());
            case TRANSCRIPT_NAME: {
                String readable = feature.getAttribute(TRANSCRIPT_NAME);
                return firstNonEmpty(readable, firstNonEmpty(feature.getIdentifier(), feature.getName()));
            }
            case GENE_AND_ID: {
                String gene = feature.getName();
                String id = feature.getIdentifier();
                if (id == null || id.trim().isEmpty()) {
                    return gene;
                }
                if (gene == null || gene.trim().isEmpty() || gene.equals(id)) {
                    return id;
                }
                return gene + " (" + id + ")";
            }
            case GENE:
                return feature.getName();
            default:
                // Any other value is treated as a feature attribute name (e.g. transcript_id, gene_id).
                return feature.getDisplayName(mode);
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return (first != null && !first.trim().isEmpty()) ? first : second;
    }
}
