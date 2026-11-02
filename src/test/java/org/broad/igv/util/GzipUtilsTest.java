package org.broad.igv.util;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

/**
 * Tests for {@link GzipUtils} -- transparent decompression of gzip annotations / fasta.
 */
public class GzipUtilsTest {

    private static final String GTF =
            "chr1\tsrc\tgene\t1000\t2000\t.\t+\t.\tgene_id \"G1\"; gene_name \"A\";\n" +
            "chr1\tsrc\ttranscript\t1000\t2000\t.\t+\t.\tgene_id \"G1\";\n";

    @Test
    public void testStripCompressionSuffix() {
        assertEquals("/data/a.gtf", GzipUtils.stripCompressionSuffix("/data/a.gtf.gz"));
        assertEquals("/data/a.gtf", GzipUtils.stripCompressionSuffix("/data/a.gtf.bgz"));
        assertEquals("/data/a.gtf", GzipUtils.stripCompressionSuffix("/data/a.gtf"));
        assertTrue(GzipUtils.hasCompressionSuffix("a.GTF.GZ"));
        assertFalse(GzipUtils.hasCompressionSuffix("a.gtf"));
    }

    @Test
    public void testDecompressCreatesSibling() throws Exception {
        Path dir = Files.createTempDirectory("gzip-test");
        File gz = writeGzip(dir.resolve("genes.gtf.gz"), GTF);

        String result = GzipUtils.decompressToSibling(gz.getAbsolutePath());

        File plain = new File(result);
        assertTrue("expected a .gtf sibling", result.endsWith(".gtf"));
        assertTrue(plain.isFile());
        assertEquals(GTF, new String(Files.readAllBytes(plain.toPath()), StandardCharsets.UTF_8));
        assertTrue("source must be left in place", gz.isFile());
    }

    @Test
    public void testReusesExistingSibling() throws Exception {
        Path dir = Files.createTempDirectory("gzip-test");
        File gz = writeGzip(dir.resolve("genes.gtf.gz"), GTF);
        File plain = dir.resolve("genes.gtf").toFile();
        Files.write(plain.toPath(), "PRE-EXISTING".getBytes(StandardCharsets.UTF_8));
        // Make the sibling strictly newer than the source so it is considered up to date.
        assertTrue(plain.setLastModified(gz.lastModified() + 10_000));

        String result = GzipUtils.decompressToSibling(gz.getAbsolutePath());

        assertEquals(plain.getAbsolutePath(), result);
        assertEquals("PRE-EXISTING", new String(Files.readAllBytes(plain.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void testNonCompressedUnchanged() throws Exception {
        Path dir = Files.createTempDirectory("gzip-test");
        File txt = dir.resolve("genes.gtf").toFile();
        Files.write(txt.toPath(), GTF.getBytes(StandardCharsets.UTF_8));
        assertEquals(txt.getAbsolutePath(), GzipUtils.decompressToSibling(txt.getAbsolutePath()));
    }

    @Test
    public void testGzSuffixButNotGzipIsLeftAlone() throws Exception {
        Path dir = Files.createTempDirectory("gzip-test");
        File fake = dir.resolve("genes.gtf.gz").toFile();
        Files.write(fake.toPath(), GTF.getBytes(StandardCharsets.UTF_8));
        assertEquals(fake.getAbsolutePath(), GzipUtils.decompressToSibling(fake.getAbsolutePath()));
    }

    @Test
    public void testMissingFileUnchanged() {
        String missing = new File(System.getProperty("java.io.tmpdir"), "does-not-exist.gtf.gz").getAbsolutePath();
        assertEquals(missing, GzipUtils.decompressToSibling(missing));
    }

    private static File writeGzip(Path path, String content) throws Exception {
        try (OutputStream out = new GZIPOutputStream(new FileOutputStream(path.toFile()))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return path.toFile();
    }
}
