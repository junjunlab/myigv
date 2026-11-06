package org.broad.igv.sam.reader;

import htsjdk.samtools.BAMIndexer;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Creates a BAM index (.bai) for a local BAM file.
 * <p>
 * IGV normally requires a pre-existing index and pops up a dialog asking the user for its
 * path. For local files we can do better: build the index in-place, next to the BAM, so the
 * file just loads. The index is written to a temporary file first and moved into place only
 * after it is complete, so an interrupted run never leaves a truncated .bai behind.
 */
public class BamIndexBuilder {

    private static final Logger log = LogManager.getLogger(BamIndexBuilder.class);

    private BamIndexBuilder() {
        // Utility class
    }

    /**
     * Build an index for a local BAM file.
     *
     * @param bamFile   the BAM file to index
     * @param indexFile target index file (typically {@code <bam>.bai})
     * @return the index file that was written
     * @throws IOException if the BAM is missing, not coordinate sorted, the target directory is
     *                     not writable, or the indexing itself fails
     */
    public static File createIndex(File bamFile, File indexFile) throws IOException {

        if (bamFile == null || !bamFile.isFile()) {
            throw new IOException("BAM file not found: " + (bamFile == null ? "null" : bamFile.getAbsolutePath()));
        }

        File dir = indexFile.getAbsoluteFile().getParentFile();
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("Cannot create index, directory does not exist: " + dir);
        }
        if (!dir.canWrite()) {
            throw new IOException("Cannot create index, directory is not writable: " + dir.getAbsolutePath());
        }

        File tmpFile = new File(dir, indexFile.getName() + ".tmp");
        if (tmpFile.exists() && !tmpFile.delete()) {
            throw new IOException("Cannot overwrite temporary index file: " + tmpFile.getAbsolutePath());
        }

        try (SamReader reader = SamReaderFactory.makeDefault()
                .validationStringency(ValidationStringency.SILENT)
                .enable(SamReaderFactory.Option.INCLUDE_SOURCE_IN_RECORDS)
                .open(bamFile)) {

            SAMFileHeader header = reader.getFileHeader();
            SAMFileHeader.SortOrder sortOrder = header.getSortOrder();
            if (sortOrder != SAMFileHeader.SortOrder.coordinate && sortOrder != SAMFileHeader.SortOrder.unknown) {
                throw new IOException("BAM file is not coordinate sorted (header sort order = " + sortOrder
                        + "). Please sort it with 'samtools sort' before loading.");
            }

            log.info("Creating BAM index " + indexFile.getAbsolutePath() + " for " + bamFile.getName());
            BAMIndexer.createIndex(reader, tmpFile);

        } catch (IOException e) {
            deleteQuietly(tmpFile);
            throw e;
        } catch (RuntimeException e) {
            // htsjdk reports out-of-order records and I/O problems as runtime exceptions
            deleteQuietly(tmpFile);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException(message, e);
        }

        if (!tmpFile.isFile() || tmpFile.length() == 0) {
            deleteQuietly(tmpFile);
            throw new IOException("Indexing produced no output for " + bamFile.getName());
        }

        try {
            Files.move(tmpFile.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmpFile);
            throw e;
        }

        log.info("Created BAM index " + indexFile.getAbsolutePath());
        return indexFile;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("Could not delete temporary file " + file.getAbsolutePath());
        }
    }
}
