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

package org.broad.igv.feature.genome.fasta;

import org.broad.igv.logging.*;
import org.broad.igv.Globals;
import org.broad.igv.exceptions.DataLoadException;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.ParsingUtils;
import htsjdk.tribble.readers.AsciiLineReader;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author Jim Robinson
 * @date 5/1/12
 */
public class FastaUtils {

    private static Logger log = LogManager.getLogger(FastaUtils.class);

    static Pattern WHITE_SPACE = Pattern.compile("\\s+");

    /**
     * Creates an index for the provided fasta file
     * inputPath can be a URL, outputPath must point to a file.
     *
     * @param inputPath
     * @param outputPath
     * @return
     * @throws org.broad.igv.exceptions.DataLoadException If the fasta file cannot be indexed, for instance
     *                                                    because the lines are of an uneven length
     */
    public static void createIndexFile(String inputPath, String outputPath) throws DataLoadException, IOException {
        createIndexFile(inputPath, outputPath, false);
    }

    /**
     * @param quiet when true no error dialog is shown and failures are rethrown to the caller
     *              (used by the automatic indexing path).
     */
    public static void createIndexFile(String inputPath, String outputPath, boolean quiet) throws DataLoadException, IOException {

        AsciiLineReader reader = null;
        BufferedWriter writer = null;

        try {
            log.info("Creating index file at " + outputPath);
            reader = new AsciiLineReader(ParsingUtils.openInputStream(inputPath));
            writer = new BufferedWriter(new FileWriter(outputPath));
            String line = null;
            String curContig = null;
            Set<String> allContigs = new HashSet<String>();
            int basesPerLine = -1, bytesPerLine = -1;
            long location = 0, size = 0, lastPosition = 0;

            int basesThisLine, bytesThisLine;
            int numInconsistentLines = -1;
            boolean haveTasks = true;
            //Number of blank lines in the current contig.
            //-1 for not set
            int numBlanks = -1;
            int lastBlankLineNum = -1;
            int curLineNum = 0;


            //We loop through, generating a new FastaSequenceIndexEntry
            //every time we see a new header line, or when the file ends.
            while (haveTasks) {
                line = reader.readLine();
                curLineNum++;

                if (line == null || line.startsWith(">")) {
                    //The last line can have a different number of bases/bytes
                    if (numInconsistentLines >= 2) {
                        throw new DataLoadException("Fasta file has uneven line lengths in contig " + curContig, inputPath);
                    }

                    //Done with old contig
                    if (curContig != null) {
                        writeLine(writer, curContig, size, location, basesPerLine, bytesPerLine);
                    }

                    if (line == null) {
                        haveTasks = false;
                        break;
                    }

                    //Header line
                    curContig = WHITE_SPACE.split(line)[0];
                    curContig = curContig.substring(1);
                    if (allContigs.contains(curContig)) {
                        throw new DataLoadException("Contig '" + curContig + "' found multiple times in file.", inputPath);
                    } else {
                        allContigs.add(curContig);
                    }

                    //Should be starting position of next line
                    location = reader.getPosition();
                    size = 0;
                    basesPerLine = -1;
                    bytesPerLine = -1;
                    numInconsistentLines = -1;
                } else {
                    basesThisLine = line.length();
                    bytesThisLine = (int) (reader.getPosition() - lastPosition);

                    //Calculate stats per line if first line, otherwise
                    //check for consistency
                    if (numInconsistentLines < 0) {
                        basesPerLine = basesThisLine;
                        bytesPerLine = bytesThisLine;
                        numInconsistentLines = 0;
                        numBlanks = 0;
                    } else {
                        if ((basesPerLine != basesThisLine || bytesPerLine != bytesThisLine) && basesThisLine > 0) {
                            numInconsistentLines++;
                        }
                    }

                    //Empty line. This is allowed if it's at the end of the contig);
                    if (basesThisLine == 0) {
                        numBlanks++;
                        lastBlankLineNum = curLineNum;
                    } else if (numBlanks >= 1) {
                        throw new DataLoadException(String.format("Blank line at line number %d, followed by data line at %d, in contig %s\nBlank lines are only allowed at the end of a contig", lastBlankLineNum, curLineNum, curContig), inputPath);
                    }

                    size += basesThisLine;
                }
                lastPosition = reader.getPosition();
            }
        } catch (Exception e) {
            if (!quiet) {
                MessageUtils.showErrorMessage("Could not create index file: " + outputPath, e);
            }
            if (Globals.isTesting() || quiet) throw e;
        } finally {
            if (reader != null) reader.close();
            if (writer != null) writer.close();

        }

    }

    /**
     * Create a fasta index without any prompting.  Fasta files whose line lengths are not
     * uniform cannot be indexed in place, so a normalized copy {@code <name>.regularized.fa}
     * (80 bases per line) is written next to the original and indexed instead.
     *
     * @param fastaPath  path to the fasta file
     * @param indexPath  desired index path (normally {@code fastaPath + ".fai"})
     * @return a 2 element array {fastaPath, indexPath} actually used.  On total failure the
     * original paths are returned and the caller will fail as before.
     */
    public static String[] ensureIndexed(String fastaPath, String indexPath) {

        File indexFile = new File(indexPath);

        try {
            createIndexFile(fastaPath, indexPath, true);
            if (indexFile.isFile() && indexFile.length() > 0) {
                return new String[]{fastaPath, indexPath};
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            boolean lineLengthProblem = msg.contains("uneven line length") || msg.contains("blank line");
            deleteIndexIfEmpty(indexFile);
            if (!lineLengthProblem) {
                log.error("Could not index fasta " + fastaPath + ": " + e.getMessage(), e);
                return new String[]{fastaPath, indexPath};
            }
        }
        deleteIndexIfEmpty(indexFile);

        File input = new File(fastaPath);
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File regularized = new File(input.getAbsoluteFile().getParentFile(), base + ".regularized.fa");

        try {
            log.info("Fasta line lengths are uneven, writing normalized copy " + regularized.getAbsolutePath());
            regularizeFastaFile(input, regularized);
            String regularizedIndex = regularized.getAbsolutePath() + ".fai";
            createIndexFile(regularized.getAbsolutePath(), regularizedIndex, true);
            File regIdx = new File(regularizedIndex);
            if (regIdx.isFile() && regIdx.length() > 0) {
                return new String[]{regularized.getAbsolutePath(), regularizedIndex};
            }
            log.error("Could not index normalized fasta " + regularized.getAbsolutePath());
        } catch (Exception e) {
            log.error("Could not create a fasta index for " + fastaPath, e);
            if (Globals.isTesting()) throw new RuntimeException(e);
        }

        return new String[]{fastaPath, indexPath};
    }

    private static void deleteIndexIfEmpty(File indexFile) {
        try {
            if (indexFile.exists()) indexFile.delete();
        } catch (Exception e) {
            // ignore
        }
    }

    static void writeLine(Writer writer, String contig, long size, long location, int basesPerLine, int bytesPerLine) throws IOException {
        String delim = "\t";
        String line = contig + delim + size + delim + location + delim + basesPerLine + delim + bytesPerLine;
        writer.write(line);
        //We infer the newline character based on bytesPerLine - basesPerLine
        //Fasta file may not have been created on this platform, want to keep the index and fasta file consistent
        String newline = "\n";
        if (bytesPerLine - basesPerLine == 2) {
            newline = "\r\n";
        }
        writer.write(newline);
    }

    public static void regularizeFastaFile(File inputFile, File outputFile) throws IOException {

        int basesPerLine = 80;

        BufferedReader br = null;
        PrintWriter pw = null;

        try {
            br = new BufferedReader(new FileReader(inputFile));
            pw = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)));
            String nextLine;
            int count = 0;
            while ((nextLine = br.readLine()) != null) {
                if (nextLine.startsWith(">")) {
                    if (count != 0) {
                        pw.println();
                    }
                    pw.println(nextLine);
                    count = 0;
                } else {
                    char[] characters = nextLine.toCharArray();
                    for (int i = 0; i < characters.length; i++) {
                        pw.print(characters[i]);
                        count++;
                        if (count == basesPerLine) {
                            pw.println();
                            count = 0;
                        }
                    }
                }
            }
        } finally {
            if (br != null) br.close();
            if (pw != null) pw.close();
        }
    }

    public static boolean isFastaPath(String absolutePath) {
        String pth = absolutePath.toLowerCase();
        return pth.endsWith(".fa") || pth.endsWith(".fasta") || pth.endsWith(".fna");
    }
}
