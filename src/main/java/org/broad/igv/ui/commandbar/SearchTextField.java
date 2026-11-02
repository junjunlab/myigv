package org.broad.igv.ui.commandbar;

import com.jidesoft.hints.ListDataIntelliHints;
import org.broad.igv.feature.FeatureDB;
import org.broad.igv.feature.GeneSearchIndex;
import org.broad.igv.feature.GeneSearchSources;
import org.broad.igv.feature.IGVNamedFeature;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.ui.action.SearchCommand;
import org.broad.igv.ui.panel.FrameManager;
import org.broad.igv.util.LongRunningTask;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.util.List;

/**
 * Created by jrobinso on 7/6/17.
 */
public class SearchTextField extends JTextField {

    static Logger log = LogManager.getLogger(SearchTextField.class);

    /**
     * Maximum number of completion suggestions offered for a user supplied GTF/GFF annotation.
     */
    static int HINT_LIMIT = 50;

    /**
     * True while the field displays the canonical name(s) of genes resolved by the last search.
     * In this state the command bar must not replace the text with the current locus coordinates,
     * so the field stays usable as a gene list editor.
     */
    private volatile boolean showingResolvedGenes = false;

    /**
     * Guards programmatic text updates so they are not mistaken for user edits.
     */
    private boolean updatingText = false;

    public SearchTextField() {

        setToolTipText("Enter one or more genes/loci, separated by commas or spaces, e.g. EGFR, TP53, chr1:100,000-200,000. " +
                "Use the annotation dropdown to search genes from an imported GTF/GFF file.");

        addActionListener(actionevent -> {
            searchByLocus(getText());
        });

        // Any edit by the user ends "resolved gene" display mode
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                userEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                userEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                userEdited();
            }

            private void userEdited() {
                if (!updatingText) {
                    showingResolvedGenes = false;
                }
            }
        });

        new SearchHints(this);  // This has the side-effect, apparently, of enabling hints
    }

    /**
     * @return true if the text currently shows gene names resolved by a search (as opposed to a
     * locus string the user typed, or coordinates filled in by the command bar).
     */
    public boolean isShowingResolvedGenes() {
        return showingResolvedGenes;
    }

    public void clearResolvedGeneState() {
        showingResolvedGenes = false;
    }

    /**
     * Replace the contents with the canonical name(s) of the genes that were just resolved, e.g.
     * "sox1" -> "SOX1".  The caret is moved to the end so more genes can be appended immediately.
     */
    public void setResolvedGeneText(String text) {
        if (text == null || text.isEmpty()) return;

        Runnable task = () -> {
            updatingText = true;
            try {
                setText(text);
                showingResolvedGenes = true;
                if (hasFocus()) {
                    setCaretPosition(text.length());
                }
            } finally {
                updatingText = false;
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    public void searchByLocus(final String searchText) {
        showingResolvedGenes = false;
        SearchCommand command = new SearchCommand(FrameManager.getDefaultFrame(), searchText);
        command.setGeneNameConsumer(this::setResolvedGeneText);
        LongRunningTask.submit(command);
    }

    private class SearchHints extends ListDataIntelliHints<String> {

        public SearchHints(JTextComponent jTextComponent) {
            super(jTextComponent, new String[]{});
        }

        @Override
        public void acceptHint(Object context) {
            String selected = (String) context;
            String current = getText();
            // For comma / semicolon separated gene lists, replace only the last
            // fragment and keep the list intact.  Do not search yet -- let the
            // user press Enter to run the whole multi-gene search.
            if (current.contains(",") || current.contains(";")) {
                int idx = Math.max(current.lastIndexOf(','), current.lastIndexOf(';'));
                String newText = current.substring(0, idx + 1) + selected;
                setText(newText);
                setCaretPosition(newText.length());
                return;
            }
            super.acceptHint(context);
            searchByLocus(selected);
        }

        @Override
        public boolean updateHints(Object context) {
            String text = (String) context;
            // For comma / semicolon separated lists, suggest based on the last fragment
            String[] parts = text.split("[,;]");
            String last = parts[parts.length - 1].trim();
            if (last.length() <= 1) {
                return false;
            }

            // A GTF/GFF annotation may have been selected in the command bar -- if so, gene
            // names come from that file rather than the genome's built-in annotation.
            GeneSearchIndex geneIndex = GeneSearchSources.getSelected();
            if (geneIndex != null) {
                if (!geneIndex.isReady()) {
                    geneIndex.buildAsync();
                    return false;
                }
                List<IGVNamedFeature> hits = geneIndex.prefix(last, HINT_LIMIT);
                if (!hits.isEmpty()) {
                    Object[] list = SearchCommand.getSelectionList(SearchCommand.getResults(hits), false);
                    this.setListData(list);
                    return true;
                }
                return false;
            }

            //TODO Uncomment to use comprehensive feature search, note that it should support partial matches
            //List<IGVNamedFeature> features = SearchCommand.comprehensiveFeatureSearch(last);
            List<IGVNamedFeature> features = FeatureDB.getFeaturesList(last, SearchCommand.SEARCH_LIMIT);
            final List<SearchCommand.SearchResult> results = SearchCommand.getResults(features);
            Object[] list = SearchCommand.getSelectionList(results, false);
            if (list.length >= 1) {
                this.setListData(list);
                return true;
            }
            return false;
        }
    }


}
