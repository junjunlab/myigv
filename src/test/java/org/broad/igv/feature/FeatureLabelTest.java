package org.broad.igv.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link FeatureLabel} -- how a transcript label is chosen from the feature fields.
 */
public class FeatureLabelTest {

    private static BasicFeature transcript() {
        BasicFeature f = new BasicFeature("chr3", 57187000, 57385270, Strand.POSITIVE);
        f.setName("HESX1");
        f.setIdentifier("NM_003865.3");
        f.setAttribute("transcript_name", "HESX1-201");
        f.setAttribute("gene_id", "ENSG00000163660");
        return f;
    }

    @Test
    public void testGeneName() {
        assertEquals("HESX1", FeatureLabel.resolve(FeatureLabel.GENE, transcript()));
        assertEquals("HESX1", FeatureLabel.resolve(null, transcript()));
        assertEquals("HESX1", FeatureLabel.resolve("", transcript()));
    }

    @Test
    public void testTranscriptId() {
        assertEquals("NM_003865.3", FeatureLabel.resolve(FeatureLabel.ID, transcript()));
    }

    @Test
    public void testReadableTranscriptName() {
        assertEquals("HESX1-201", FeatureLabel.resolve(FeatureLabel.TRANSCRIPT_NAME, transcript()));
    }

    @Test
    public void testGeneAndId() {
        assertEquals("HESX1 (NM_003865.3)", FeatureLabel.resolve(FeatureLabel.GENE_AND_ID, transcript()));
    }

    @Test
    public void testCustomAttribute() {
        assertEquals("ENSG00000163660", FeatureLabel.resolve("gene_id", transcript()));
    }

    @Test
    public void testFallsBackWhenIdentifierMissing() {
        BasicFeature f = transcript();
        f.setIdentifier(null);
        assertEquals("HESX1", FeatureLabel.resolve(FeatureLabel.ID, f));
        assertEquals("HESX1", FeatureLabel.resolve(FeatureLabel.GENE_AND_ID, f));
    }

    @Test
    public void testReadableNameFallsBackToIdWhenAttributeMissing() {
        BasicFeature f = transcript();
        f.getAttributes().remove("transcript_name");
        assertEquals("NM_003865.3", FeatureLabel.resolve(FeatureLabel.TRANSCRIPT_NAME, f));
    }

    @Test
    public void testGeneAndIdDoesNotRepeatSameValue() {
        BasicFeature f = transcript();
        f.setName("NM_003865.3");
        assertEquals("NM_003865.3", FeatureLabel.resolve(FeatureLabel.GENE_AND_ID, f));
    }

    @Test
    public void testNullFeature() {
        assertNull(FeatureLabel.resolve(FeatureLabel.ID, null));
    }
}
