package org.broad.igv.ui;

import org.broad.igv.Globals;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * Branding and version information for myigv, with links to the repository, releases and issue
 * tracker. Replaces the stock IGV about box, which only printed a version string.
 */
public class AboutDialog extends JDialog {

    private static final Logger log = LogManager.getLogger(AboutDialog.class);

    private static final String GITHUB = "https://github.com/junjunlab/myigv";
    private static final String RELEASES = GITHUB + "/releases";
    private static final String README = GITHUB + "/blob/main/README.md";
    private static final String ISSUES = GITHUB + "/issues";
    private static final String UPSTREAM = "https://igv.org";

    private static final String BASE_IGV_VERSION = "2.19.8";
    private static final String FALLBACK_VERSION = "1.0.3";

    public AboutDialog(Frame parent, boolean modal) {
        super(parent, "About myigv", modal);
        buildUi();
        pack();
        setResizable(false);
        setLocationRelativeTo(parent);
    }

    private void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createEmptyBorder(18, 24, 14, 24));

        JLabel title = new JLabel("myigv");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(6));

        content.add(line("An enhanced IGV fork: offline annotations, multi-gene panels,", 12, Color.DARK_GRAY));
        content.add(line("Hi-C matrices, TAD overlays and BEDPE arcs.", 12, Color.DARK_GRAY));
        content.add(Box.createVerticalStrut(14));

        content.add(line("Version " + displayVersion() + "  ·  based on IGV " + BASE_IGV_VERSION, 12, Color.BLACK));
        content.add(line("Java " + System.getProperty("java.version")
                + "  ·  " + System.getProperty("os.name"), 11, Color.GRAY));
        content.add(Box.createVerticalStrut(16));

        content.add(link("GitHub repository", GITHUB));
        content.add(link("Releases / downloads", RELEASES));
        content.add(link("Documentation (README)", README));
        content.add(link("Report an issue", ISSUES));
        content.add(link("Upstream IGV", UPSTREAM));
        content.add(Box.createVerticalStrut(16));

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel();
        buttons.setBackground(Color.WHITE);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(close);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        content.add(buttons);

        setContentPane(content);
    }

    private static JLabel line(String text) {
        return line(text, 12, Color.BLACK);
    }

    private static JLabel line(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, size));
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel link(String text, String url) {
        JLabel label = new JLabel("<html><u>" + text + "</u></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12));
        label.setForeground(new Color(0x1A5FB4));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setToolTipText(url);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open(url);
            }
        });
        return label;
    }

    private static void open(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            log.error("Cannot open " + url, e);
        }
    }

    /** Builds from an explicit -Pversion when packaged, otherwise the release we shipped. */
    private static String displayVersion() {
        String version = Globals.VERSION;
        if (version == null || version.isBlank()
                || "user".equalsIgnoreCase(version) || version.contains("not_set")) {
            return FALLBACK_VERSION;
        }
        return version;
    }

    /**
     * The version this build reports, for code outside the dialog (the updater compares against
     * it).  Single source of truth: whatever the About box shows is what the updater compares.
     */
    public static String appVersion() {
        return displayVersion();
    }
}
