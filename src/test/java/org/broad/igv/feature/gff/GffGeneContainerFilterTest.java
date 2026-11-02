package org.broad.igv.feature.gff;

import org.broad.igv.feature.BasicFeature;
import org.broad.igv.feature.IGVFeature;
import org.broad.igv.feature.tribble.GFFCodec;
import org.broad.igv.track.FeatureSource;
import org.broad.igv.track.TribbleFeatureSource;
import org.broad.igv.util.ResourceLocator;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A GTF/GFF file contains one "gene" container record per gene, in addition to the transcripts.
 * Rendered as-is the gene record becomes a solid bar spanning the whole gene that hides the
 * transcript models underneath, so {@link GFFFeatureSource} drops it.
 */
public class GffGeneContainerFilterTest {

    private static final String GTF_WITH_TRANSCRIPTS =
            "chr1\tsrc\tgene\t1000\t5000\t.\t+\t.\tgene_id \"ENSG1\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\ttranscript\t1000\t5000\t.\t+\t.\tgene_id \"ENSG1\"; transcript_id \"ENST1\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\texon\t1000\t1500\t.\t+\t.\tgene_id \"ENSG1\"; transcript_id \"ENST1\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\texon\t3000\t3500\t.\t+\t.\tgene_id \"ENSG1\"; transcript_id \"ENST1\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\ttranscript\t2000\t6000\t.\t+\t.\tgene_id \"ENSG1\"; transcript_id \"ENST2\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\texon\t2000\t2500\t.\t+\t.\tgene_id \"ENSG1\"; transcript_id \"ENST2\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\texon\t5500\t6000\t.\t+\t.\tgene_id \"ENSG1\"; transcript_id \"ENST2\"; gene_name \"GENEA\";\n";

    private static final String GTF_GENE_ONLY =
            "chr1\tsrc\tgene\t1000\t5000\t.\t+\t.\tgene_id \"ENSG1\"; gene_name \"GENEA\";\n";

    @Test
    public void testGeneContainerRecordsAreDropped() throws Exception {
        List<BasicFeature> features = render(GTF_WITH_TRANSCRIPTS);

        List<BasicFeature> transcripts = new ArrayList<>();
        for (BasicFeature f : features) {
            assertEquals("a bare gene container should not be rendered", false, isBareGene(f));
            if ("transcript".equals(f.getType())) {
                transcripts.add(f);
            }
        }
        assertEquals(2, transcripts.size());
        for (BasicFeature t : transcripts) {
            assertTrue("transcript models must keep their exons", t.hasExons());
        }
    }

    /**
     * A gene-only annotation (no transcript/exon records) must still be visible.
     */
    @Test
    public void testGeneOnlyFileIsStillRendered() throws Exception {
        List<BasicFeature> features = render(GTF_GENE_ONLY);
        assertEquals(1, features.size());
        assertEquals("gene", features.get(0).getType());
    }

    private static boolean isBareGene(BasicFeature f) {
        String type = f.getType();
        return type != null && type.equalsIgnoreCase("gene") && !f.hasExons();
    }

    @SuppressWarnings("unchecked")
    private static List<BasicFeature> render(String content) throws Exception {
        File dir = Files.createTempDirectory("gff_filter").toFile();
        File gtf = new File(dir, "probe.gtf");
        Files.write(gtf.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ResourceLocator locator = new ResourceLocator(gtf.getAbsolutePath());
        FeatureSource tribble = TribbleFeatureSource.getFeatureSource(locator, null);
        FeatureSource source = new GFFFeatureSource(tribble, GFFCodec.Version.GTF);

        Iterator<IGVFeature> iter = (Iterator<IGVFeature>) (Iterator<?>) source.getFeatures("chr1", 0, 100000);
        List<BasicFeature> result = new ArrayList<>();
        while (iter.hasNext()) {
            result.add((BasicFeature) iter.next());
        }
        return result;
    }
}
