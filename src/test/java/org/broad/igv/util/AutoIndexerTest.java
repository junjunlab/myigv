package org.broad.igv.util;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/**
 * Tests for {@link AutoIndexer} -- automatic sorting and indexing of local GTF/GFF files.
 */
public class AutoIndexerTest {

    private static final String SORTED_GTF =
            "chr1\tsrc\tgene\t1000\t2000\t.\t+\t.\tgene_id \"G1\"; gene_name \"A\";\n" +
            "chr1\tsrc\tgene\t3000\t4000\t.\t+\t.\tgene_id \"G2\"; gene_name \"B\";\n" +
            "chr2\tsrc\tgene\t500\t900\t.\t-\t.\tgene_id \"G3\"; gene_name \"C\";\n";

    private static final String UNSORTED_GTF =
            "chr1\tsrc\tgene\t5000\t6000\t.\t+\t.\tgene_id \"G1\"; gene_name \"A\";\n" +
            "chr1\tsrc\tgene\t1000\t2000\t.\t+\t.\tgene_id \"G2\"; gene_name \"B\";\n" +
            "chr2\tsrc\tgene\t2000\t3000\t.\t-\t.\tgene_id \"G3\"; gene_name \"C\";\n" +
            "chr1\tsrc\tgene\t3000\t4000\t.\t+\t.\tgene_id \"G4\"; gene_name \"D\";\n";

    @Test
    public void testIsSorted() throws Exception {
        File sorted = writeFile("sorted.gtf", SORTED_GTF);
        assertTrue(AutoIndexer.isSorted(sorted));
    }

    @Test
    public void testIsSortedDetectsOutOfOrderStarts() throws Exception {
        File unsorted = writeFile("unsorted.gtf",
                "chr1\tsrc\tgene\t5000\t6000\t.\t+\t.\tgene_id \"G1\";\n" +
                "chr1\tsrc\tgene\t1000\t2000\t.\t+\t.\tgene_id \"G2\";\n");
        assertFalse(AutoIndexer.isSorted(unsorted));
    }

    @Test
    public void testIsSortedDetectsChromosomeReappearing() throws Exception {
        File unsorted = writeFile("reappear.gtf",
                "chr1\tsrc\tgene\t1000\t2000\t.\t+\t.\tgene_id \"G1\";\n" +
                "chr2\tsrc\tgene\t1000\t2000\t.\t+\t.\tgene_id \"G2\";\n" +
                "chr1\tsrc\tgene\t3000\t4000\t.\t+\t.\tgene_id \"G3\";\n");
        assertFalse(AutoIndexer.isSorted(unsorted));
    }

    @Test
    public void testSortedCopyName() {
        File dir = new File(System.getProperty("java.io.tmpdir"));
        assertEquals(new File(dir, "genes.sorted.gtf"),
                AutoIndexer.sortedCopyOf(new File(dir, "genes.gtf")));
        assertEquals(new File(dir, "genes.sorted.gff3"),
                AutoIndexer.sortedCopyOf(new File(dir, "genes.gff3")));
    }

    @Test
    public void testIsLocalAnnotationFile() {
        assertTrue(AutoIndexer.isLocalAnnotationFile("C:\\data\\genes.gtf"));
        assertTrue(AutoIndexer.isLocalAnnotationFile("/data/genes.gff"));
        assertTrue(AutoIndexer.isLocalAnnotationFile("/data/genes.gff3"));
        assertTrue(AutoIndexer.isLocalAnnotationFile("/data/genes.gvf"));

        assertFalse(AutoIndexer.isLocalAnnotationFile("/data/genes.gtf.gz"));
        assertFalse(AutoIndexer.isLocalAnnotationFile("https://example.org/genes.gtf"));
        assertFalse(AutoIndexer.isLocalAnnotationFile("/data/sample.bam"));
        assertFalse(AutoIndexer.isLocalAnnotationFile(null));
    }

    /**
     * Unsorted input: a sorted copy is written, indexed, and its path is returned.
     */
    @Test
    public void testSortsAndIndexesUnsortedFile() throws Exception {
        File dir = Files.createTempDirectory("myigv_autoindex").toFile();
        File input = new File(dir, "genes.gtf");
        Files.write(input.toPath(), UNSORTED_GTF.getBytes(StandardCharsets.UTF_8));

        String result = AutoIndexer.ensureSortedAndIndexed(input.getAbsolutePath(), null);

        File sorted = new File(dir, "genes.sorted.gtf");
        assertEquals(sorted.getAbsolutePath(), result);
        assertTrue("sorted copy should exist", sorted.isFile());
        assertTrue("index should exist", new File(sorted.getPath() + ".idx").isFile());
        assertTrue("sorted copy should be detected as sorted", AutoIndexer.isSorted(sorted));

        // chr1 records must be grouped together and ordered by start
        List<String> lines = Files.readAllLines(sorted.toPath(), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).startsWith("chr1\t"));
        assertTrue(lines.get(0).contains("\t1000\t"));
        assertTrue(lines.get(1).contains("\t3000\t"));
        assertTrue(lines.get(2).contains("\t5000\t"));
        assertTrue(lines.get(3).startsWith("chr2\t"));
    }

    /**
     * Sorted input: no copy is made, the original file keeps its path and gains an index.
     */
    @Test
    public void testIndexesSortedFileInPlace() throws Exception {
        File dir = Files.createTempDirectory("myigv_autoindex").toFile();
        File input = new File(dir, "genes.gtf");
        Files.write(input.toPath(), SORTED_GTF.getBytes(StandardCharsets.UTF_8));

        String result = AutoIndexer.ensureSortedAndIndexed(input.getAbsolutePath(), null);

        assertEquals(input.getAbsolutePath(), result);
        assertTrue(new File(input.getPath() + ".idx").isFile());
        assertFalse("no sorted copy should be created", new File(dir, "genes.sorted.gtf").exists());
    }

    /**
     * A gzip annotation is decompressed once, then sorted and indexed like a plain file.
     */
    @Test
    public void testGzipAnnotationIsDecompressedAndIndexed() throws Exception {
        File dir = Files.createTempDirectory("myigv_autoindex").toFile();
        File gz = new File(dir, "genes.gtf.gz");
        try (OutputStream out = new GZIPOutputStream(new FileOutputStream(gz))) {
            out.write(UNSORTED_GTF.getBytes(StandardCharsets.UTF_8));
        }

        String result = AutoIndexer.ensureSortedAndIndexed(gz.getAbsolutePath(), null);

        assertTrue("expected a derived .gtf, got " + result,
                result.endsWith(".gtf") || result.endsWith(".sorted.gtf"));
        assertTrue("derived file should exist", new File(result).isFile());
        assertTrue("derived file should be indexed", new File(result + ".idx").isFile());
        assertFalse("the .gz itself must not be indexed in place",
                new File(gz.getAbsolutePath() + ".idx").exists());
        assertTrue("original .gz must be left in place", gz.isFile());
    }

    private static File writeFile(String name, String content) throws Exception {
        File dir = Files.createTempDirectory("myigv_autoindex").toFile();
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
