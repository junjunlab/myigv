package org.broad.igv.feature;

import org.junit.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/**
 * Tests for {@link GeneSearchIndex} -- gene name lookup from user supplied GTF / GFF files.
 */
public class GeneSearchIndexTest {

    private static final String GTF_CONTENT =
            "chr1\tHAVANA\tgene\t101\t200\t.\t+\t.\tgene_id \"ENSG0000001.1\"; gene_name \"SOX1\"; gene_biotype \"protein_coding\";\n" +
            "chr1\tHAVANA\tgene\t301\t400\t.\t-\t.\tgene_id \"ENSG0000002.1\"; gene_name \"SOX2\";\n" +
            "chr1\tHAVANA\texon\t101\t150\t.\t+\t.\tgene_id \"ENSG0000001.1\"; transcript_id \"ENST1\"; gene_name \"SOX1\";\n" +
            "chr2\tHAVANA\tgene\t501\t600\t.\t+\t.\tgene_id \"ENSG0000003.1\"; gene_name \"BRCA1\";\n";

    private static final String GFF3_CONTENT =
            "##gff-version 3\n" +
            "chr1\tsrc\tgene\t101\t200\t.\t+\t.\tID=gene:ENSG10;Name=TP53;gene_id=ENSG10\n" +
            "chr1\tsrc\texon\t101\t150\t.\t+\t.\tID=exon:1;Parent=gene:ENSG10\n";

    private static final String TRANSCRIPT_ONLY_CONTENT =
            "chr1\tsrc\ttranscript\t101\t150\t.\t+\t.\tgene_id \"G1\"; transcript_id \"T1\"; gene_name \"GENEA\";\n" +
            "chr1\tsrc\ttranscript\t201\t400\t.\t+\t.\tgene_id \"G1\"; transcript_id \"T2\"; gene_name \"GENEA\";\n";

    @Test
    public void testGtfGeneNames() throws Exception {
        GeneSearchIndex index = buildIndex(writeFile(".gtf", GTF_CONTENT, false));

        assertTrue(index.isReady());
        assertEquals(3, index.getFeatureCount());

        // Symbol lookup is case insensitive, coordinates converted to 0-based
        List<IGVNamedFeature> sox1 = index.exact("sox1");
        assertEquals(1, sox1.size());
        assertEquals("SOX1", sox1.get(0).getName());
        assertEquals("chr1", sox1.get(0).getChr());
        assertEquals(100, sox1.get(0).getStart());
        assertEquals(200, sox1.get(0).getEnd());

        // Ensembl gene id is indexed as an alias
        List<IGVNamedFeature> byId = index.exact("ENSG0000002.1");
        assertEquals(1, byId.size());
        assertEquals("SOX2", byId.get(0).getName());

        // Prefix (autocomplete) matching
        assertEquals(2, index.prefix("SOX", 10).size());
        assertEquals(1, index.prefix("BRC", 10).size());
        assertTrue(index.prefix("NOPE", 10).isEmpty());
    }

    @Test
    public void testGff3Attributes() throws Exception {
        GeneSearchIndex index = buildIndex(writeFile(".gff3", GFF3_CONTENT, false));

        assertTrue(index.isReady());
        assertEquals(1, index.getFeatureCount());

        assertEquals("TP53", index.exact("TP53").get(0).getName());
        assertEquals("TP53", index.exact("gene:ENSG10").get(0).getName());
    }

    @Test
    public void testTranscriptOnlyFile() throws Exception {
        GeneSearchIndex index = buildIndex(writeFile(".gtf", TRANSCRIPT_ONLY_CONTENT, false));

        assertTrue(index.isReady());
        assertEquals(1, index.getFeatureCount());

        IGVNamedFeature gene = index.exact("GENEA").get(0);
        // Transcripts merged into a single gene span
        assertEquals(100, gene.getStart());
        assertEquals(400, gene.getEnd());
    }

    @Test
    public void testGzipFile() throws Exception {
        GeneSearchIndex index = buildIndex(writeFile(".gtf.gz", GTF_CONTENT, true));

        assertTrue(index.isReady());
        assertEquals(3, index.getFeatureCount());
        assertEquals("SOX1", index.exact("SOX1").get(0).getName());
    }

    @Test
    public void testNotReadyBeforeBuild() throws Exception {
        File file = writeFile(".gtf", GTF_CONTENT, false);
        GeneSearchIndex index = new GeneSearchIndex(file.getAbsolutePath(), "test.gtf");

        assertFalse(index.isReady());
        assertTrue(index.exact("SOX1").isEmpty());
        assertTrue(index.prefix("SOX", 10).isEmpty());
    }

    @Test
    public void testParseAttributes() {
        java.util.Map<String, String> gtf =
                GeneSearchIndex.parseAttributes("gene_id \"ENSG1\"; gene_name \"SOX1\";");
        assertEquals("ENSG1", gtf.get("gene_id"));
        assertEquals("SOX1", gtf.get("gene_name"));

        java.util.Map<String, String> gff3 =
                GeneSearchIndex.parseAttributes("ID=gene:1;Name=TP53");
        assertEquals("gene:1", gff3.get("ID"));
        assertEquals("TP53", gff3.get("Name"));
    }

    @Test
    public void testIsGeneAnnotationPath() {
        assertTrue(GeneSearchSources.isGeneAnnotationPath("/data/genes.gtf"));
        assertTrue(GeneSearchSources.isGeneAnnotationPath("/data/GENES.GTF"));
        assertTrue(GeneSearchSources.isGeneAnnotationPath("/data/genes.gtf.gz"));
        assertTrue(GeneSearchSources.isGeneAnnotationPath("genes.gff3"));
        assertTrue(GeneSearchSources.isGeneAnnotationPath("genes.gff"));
        assertTrue(GeneSearchSources.isGeneAnnotationPath("genes.gvf"));

        assertFalse(GeneSearchSources.isGeneAnnotationPath("sample.bam"));
        assertFalse(GeneSearchSources.isGeneAnnotationPath("peaks.bed"));
        assertFalse(GeneSearchSources.isGeneAnnotationPath("variants.vcf.gz"));
        assertFalse(GeneSearchSources.isGeneAnnotationPath(null));
    }

    @Test
    public void testIndexCacheReturnsSameInstance() throws Exception {
        File file = writeFile(".gtf", GTF_CONTENT, false);
        GeneSearchIndex first = GeneSearchSources.getIndex(file.getAbsolutePath(), "a.gtf");
        GeneSearchIndex second = GeneSearchSources.getIndex(file.getAbsolutePath(), "b.gtf");
        assertSame(first, second);
        assertNull(GeneSearchSources.getIndex(null));
        GeneSearchSources.clear();
    }

    private static GeneSearchIndex buildIndex(File file) {
        GeneSearchIndex index = new GeneSearchIndex(file.getAbsolutePath(), file.getName());
        index.build();
        return index;
    }

    private static File writeFile(String suffix, String content, boolean gzip) throws Exception {
        File file = Files.createTempFile("myigv_genes", suffix).toFile();
        file.deleteOnExit();
        if (gzip) {
            try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(file));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8))) {
                writer.write(content);
            }
        } else {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
