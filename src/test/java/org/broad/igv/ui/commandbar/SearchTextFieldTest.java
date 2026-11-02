package org.broad.igv.ui.commandbar;

import org.junit.Test;

import javax.swing.*;

import static org.junit.Assert.*;

/**
 * Tests for the "resolved gene name" state of the search field: while it is set the command bar
 * must not replace the text with the current locus coordinates.
 */
public class SearchTextFieldTest {

    @Test
    public void testResolvedGeneTextState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SearchTextField field = new SearchTextField();
            assertFalse(field.isShowingResolvedGenes());

            field.setResolvedGeneText("SOX1,SOX2");
            assertEquals("SOX1,SOX2", field.getText());
            assertTrue(field.isShowingResolvedGenes());

            // A subsequent programmatic coordinate fill would be suppressed by the flag; once the
            // user edits the text the flag drops and coordinates can be shown again.
            try {
                field.getDocument().insertString(field.getDocument().getLength(), ",SOX3", null);
            } catch (Exception e) {
                fail(e.getMessage());
            }
            assertFalse(field.isShowingResolvedGenes());
            assertEquals("SOX1,SOX2,SOX3", field.getText());
        });
    }

    @Test
    public void testClearResolvedGeneState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SearchTextField field = new SearchTextField();
            field.setResolvedGeneText("TP53");
            assertTrue(field.isShowingResolvedGenes());

            field.clearResolvedGeneState();
            assertFalse(field.isShowingResolvedGenes());
        });
    }

    @Test
    public void testNullTextIgnored() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SearchTextField field = new SearchTextField();
            field.setText("chr1:100-200");
            field.setResolvedGeneText(null);
            assertEquals("chr1:100-200", field.getText());
            assertFalse(field.isShowingResolvedGenes());
        });
    }
}
