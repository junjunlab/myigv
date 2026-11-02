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

package org.broad.igv.ui.action;

import junit.framework.AssertionFailedError;
import org.broad.igv.AbstractHeadlessTest;
import org.broad.igv.feature.genome.ChromAlias;
import org.broad.igv.feature.genome.ChromAliasSource;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.util.TestUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;


/**
 * User: jacob
 * Date: 2011/12/19
 */
public class SearchCommandTest extends AbstractHeadlessTest {

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Test
    public void testSingleLOCUSs() throws Exception {

        int min = 1;
        int max = 22;
        String[] chrs = new String[max - min + 1];
        String[] nums = new String[max - min + 1];
        String chr;

        for (int cn = min; cn <= max; cn++) {
            chr = "chr" + cn;
            chrs[cn - min] = chr;
            nums[cn - min] = "" + cn;
        }
        tstFeatureTypes(chrs, SearchCommand.ResultType.LOCUS);
        tstFeatureTypes(nums, SearchCommand.ResultType.LOCUS);
    }

    @Test
    public void testChromoWithColon() throws Exception {


        String [] chrNames = {"chr10", "chr1", "chr20"};
        String [][] aliases = {{"abc:123", "abc:456", "abc:789"}, {"xy:12"}, {"aaa:bbb"}};
        List<List<String>> synonymsList = new ArrayList<>();
        synonymsList.add(Arrays.asList("chr10", "abc:123", "abc:456", "abc:789"));
        synonymsList.add(Arrays.asList("chr1", "xy:12"));
        synonymsList.add(Arrays.asList("chr20", "aaa:bbb"));
        genome.addChrAliases(synonymsList);


        SearchCommand cmd;
        for (int i = 0; i < chrNames.length; i++) {

            String chr = chrNames[i];
            String [] synonyms = aliases[i];

            for (String searchStr : synonyms) {
                cmd = new SearchCommand(null, searchStr, genome);
                List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
                assertEquals(1, results.size());
                assertEquals(SearchCommand.ResultType.LOCUS, results.get(0).type);
                assertEquals(chr, results.get(0).chr);
            }
        }

        String[] queries = new String[]{"X:100-1000", "Y:100-1000", "Y:12", "1\t 100", "X\t50", "X\t50\t500"};
        tstFeatureTypes(queries, SearchCommand.ResultType.LOCUS);

        genome = TestUtils.loadGenome();
    }

    @Test
    public void testSingleFeatures() throws Exception {
        String[] features = {"EGFR", "ABO", "BRCA1", "egFr"};
        tstFeatureTypes(features, SearchCommand.ResultType.FEATURE);
    }

    /**
     * Test that mutations are parsed correctly. They get returned
     * as type LOCUS.
     *
     * @throws Exception
     */
    @Test
    @Ignore("Fails unless tests are run in separate JVMs")
    public void testFeatureMuts() throws Exception {
        String[] features = {"EGFR:M1I", "EGFR:G5R", "egfr:g5r", "egfr:r2*"};
        tstFeatureTypes(features, SearchCommand.ResultType.LOCUS);

        String egfr_seq = "atgcgaccctccgggacggccggggcagcgctcctggcgctgctggctgcgctc";
        String[] targets = new String[]{"A", "G", "C", "T", "a", "g", "c", "t"};
        String mut_let, tar_let;
        for (int let = 0; let < egfr_seq.length(); let++) {
            mut_let = egfr_seq.substring(let, let + 1);
            if (Math.random() > 0.5) {
                mut_let = mut_let.toUpperCase();
            }
            tar_let = targets[let % targets.length];
            String[] features2 = {"EGFR:" + (let + 1) + mut_let + ">" + tar_let};
            tstFeatureTypes(features2, SearchCommand.ResultType.LOCUS);
        }
    }

    /*
    Run a bunch of queries, test that they all return the same type
     */
    private void tstFeatureTypes(String[] queries, SearchCommand.ResultType type) {
        SearchCommand cmd;
        for (String f : queries) {
            cmd = new SearchCommand(null, f, genome);
            List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
            try {
                assertTrue(containsType(results, type));
            } catch (AssertionFailedError e) {
                System.out.println(f + " :" + results.get(0).getMessage());
                throw e;
            }
        }
    }

    private boolean containsType(List<SearchCommand.SearchResult> results, SearchCommand.ResultType check) {
        boolean contains = false;
        for (SearchCommand.SearchResult result : results) {
            contains |= result.type == check;
        }
        return contains;
    }

    @Test
    public void testMultiFeatures() throws Exception {
        tstMultiFeatures(" ");
        tstMultiFeatures("  ");
        tstMultiFeatures("   ");
        tstMultiFeatures("    ");
        tstMultiFeatures("\t");
        tstMultiFeatures("\r\n"); //This should never happen
    }

    public void tstMultiFeatures(String delim) throws Exception {

        //Not sure if this is correct behavior or not
        String[] tokens = {"EgfR", "ABO", "BRCA1", "chr1:1-100"};
        SearchCommand.ResultType[] types = new SearchCommand.ResultType[]{
                SearchCommand.ResultType.FEATURE,
                SearchCommand.ResultType.FEATURE,
                SearchCommand.ResultType.FEATURE,
                SearchCommand.ResultType.LOCUS,
                SearchCommand.ResultType.LOCUS,
                SearchCommand.ResultType.LOCUS
        };
        String searchStr = tokens[0];
        for (int ii = 1; ii < tokens.length; ii++) {
            searchStr += delim + tokens[ii];
        }
        SearchCommand cmd;
        cmd = new SearchCommand(null, searchStr, genome);
        List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);

        for (int ii = 0; ii < tokens.length; ii++) {
            SearchCommand.SearchResult result = results.get(ii);
            try {
                assertEquals(types[ii], result.type);
                assertEquals(tokens[ii].toLowerCase(), result.getShortName().toLowerCase());
            } catch (AssertionFailedError e) {
                System.out.println(searchStr + " :" + result.getMessage());
                throw e;
            }
        }
    }

    @Test
    public void testMultiLOCUSs() throws Exception {
        String[] tokens = {"chr1", "chr5", "4", "12", "X", "Y"};
        String searchStr = tokens[0];
        for (int ii = 1; ii < tokens.length; ii++) {
            searchStr += " " + tokens[ii];
        }

        SearchCommand cmd;
        cmd = new SearchCommand(null, searchStr, genome);
        List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);

        for (int ii = 0; ii < tokens.length; ii++) {
            SearchCommand.SearchResult result = results.get(ii);

            assertEquals(SearchCommand.ResultType.LOCUS, result.type);
            assertTrue(result.getLocus().contains(tokens[ii]));
        }
    }

    /**
     * myigv enhancement: comma / semicolon delimited gene lists should be split
     * into separate feature/locus tokens, e.g. "EGFR,ABO,BRCA1".
     */
    @Test
    public void testCommaDelimitedMultiFeatures() throws Exception {
        String[] cases = {"EGFR,ABO,BRCA1", "EGFR;ABO;BRCA1", "EGFR, ABO , BRCA1"};
        for (String searchStr : cases) {
            SearchCommand cmd = new SearchCommand(null, searchStr, genome);
            List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
            assertEquals("Expected 3 features for: " + searchStr, 3, results.size());
            for (SearchCommand.SearchResult r : results) {
                assertEquals(SearchCommand.ResultType.FEATURE, r.type);
            }
        }
    }

    /**
     * Locus coordinates with commas as thousands separators must NOT be split.
     */
    @Test
    public void testColonPreventsCommaSplit() throws Exception {
        String searchStr = "chr1:1,000,000-2,000,000";
        SearchCommand cmd = new SearchCommand(null, searchStr, genome);
        List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
        assertEquals(1, results.size());
        assertEquals(SearchCommand.ResultType.LOCUS, results.get(0).type);
    }



    /**
     * myigv enhancement: when a GTF/GFF annotation is selected as the search source, gene names
     * are resolved from that file instead of the genome's built-in annotation.
     */
    @Test
    public void testSearchFromSelectedGtfSource() throws Exception {
        String gtf =
                "chr1\ttest\tgene\t1001\t2000\t.\t+\t.\tgene_id \"G1\"; gene_name \"MYGENE1\";\n" +
                "chr1\ttest\tgene\t3001\t4000\t.\t+\t.\tgene_id \"G2\"; gene_name \"MYGENE2\";\n";

        java.io.File file = java.nio.file.Files.createTempFile("myigv_search", ".gtf").toFile();
        file.deleteOnExit();
        java.nio.file.Files.write(file.toPath(), gtf.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        org.broad.igv.feature.GeneSearchIndex index =
                org.broad.igv.feature.GeneSearchSources.getIndex(file.getAbsolutePath(), file.getName());
        index.build();
        org.broad.igv.feature.GeneSearchSources.setSelected(index);

        try {
            // Exact symbol from the GTF
            SearchCommand cmd = new SearchCommand(null, "MYGENE1", genome);
            List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
            assertEquals(1, results.size());
            assertEquals(SearchCommand.ResultType.FEATURE, results.get(0).type);
            assertEquals("MYGENE1", results.get(0).getFeature().getName());
            assertEquals(1000, results.get(0).getStart());

            // Prefix match, several genes -> gene list mode
            cmd = new SearchCommand(null, "MYGENE", genome);
            results = cmd.runSearch(cmd.searchString);
            assertEquals(2, results.size());

            // Multi-gene comma search still works against the selected source
            cmd = new SearchCommand(null, "MYGENE1,MYGENE2", genome);
            results = cmd.runSearch(cmd.searchString);
            assertEquals(2, results.size());

            // Genes that only exist in the genome annotation are not resolved from this file
            cmd = new SearchCommand(null, "EGFR", genome);
            results = cmd.runSearch(cmd.searchString);
            assertEquals(1, results.size());
            assertEquals(SearchCommand.ResultType.ERROR, results.get(0).type);
            assertNotNull(results.get(0).getMessage());

            // Locus strings still work while a GTF source is selected
            cmd = new SearchCommand(null, "chr1:100-200", genome);
            results = cmd.runSearch(cmd.searchString);
            assertEquals(1, results.size());
            assertEquals(SearchCommand.ResultType.LOCUS, results.get(0).type);
        } finally {
            org.broad.igv.feature.GeneSearchSources.clear();
        }
    }

    /**
     * myigv: after a gene search the search box shows the canonical gene name(s), not coordinates.
     */
    @Test
    public void testResolvedGeneTextUsesCanonicalNames() throws Exception {
        SearchCommand cmd = new SearchCommand(null, "egfr", genome);
        List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
        assertEquals("EGFR", SearchCommand.resolvedGeneTextFor(results));

        // Several genes (comma list) resolve to a comma separated canonical list
        cmd = new SearchCommand(null, "egfr,abo", genome);
        results = cmd.runSearch(cmd.searchString);
        assertEquals("EGFR,ABO", SearchCommand.resolvedGeneTextFor(results));
    }

    /**
     * Coordinate searches must not have their text rewritten.
     */
    @Test
    public void testResolvedGeneTextNullForLocus() throws Exception {
        SearchCommand cmd = new SearchCommand(null, "chr1:1,000,000-2,000,000", genome);
        List<SearchCommand.SearchResult> results = cmd.runSearch(cmd.searchString);
        assertNull(SearchCommand.resolvedGeneTextFor(results));

        cmd = new SearchCommand(null, "chr1", genome);
        results = cmd.runSearch(cmd.searchString);
        assertNull(SearchCommand.resolvedGeneTextFor(results));
    }

    /**
     * Saving because we might want these test cases, but don't
     * want to test checkTokenType specifically
     */
    @Deprecated
    public void testTokenChecking() {
        String[] chromos = {"chr3", "chr20", "chrX", "chrY"};
        SearchCommand cmd = new SearchCommand(null, "", genome);
        for (String chr : chromos) {
            assertEquals(SearchCommand.ResultType.LOCUS, cmd.checkTokenType(chr));
        }

        String[] starts = {"39,239,480", "958392", "0,4829,44", "5"};
        String[] ends = {"40,321,111", "5", "48153181,813156", ""};
        for (int ii = 0; ii < starts.length; ii++) {
            String tstr = chromos[ii] + ":" + starts[ii] + "-" + ends[ii];
            assertEquals(SearchCommand.ResultType.LOCUS, cmd.checkTokenType(tstr));
            tstr = chromos[ii] + "\t" + starts[ii] + "  " + ends[ii];
            assertEquals(SearchCommand.ResultType.LOCUS, cmd.checkTokenType(tstr));
        }

        String[] errors = {"egfr:1-100", "   ", "chr1\t1\t100\tchr2"};
        for (String s : errors) {
            //System.out.println(s);
            assertEquals(SearchCommand.ResultType.ERROR, cmd.checkTokenType(s));
        }
    }
}


