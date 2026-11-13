package org.broad.igv.update;

import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;

/**
 * Small prompt shown when a newer release is found.
 *
 * <p>Three choices: update now, remind me later (this session only) or skip this version
 * permanently.  The window is intentionally compact and is only ever created on the EDT.</p>
 */
public class UpdateDialog extends JDialog {

    private static final Logger log = LogManager.getLogger(UpdateDialog.class);

    /** Release notes are summarised, not reproduced: at most this many lines. */
    private static final int MAX_NOTE_LINES = 8;
    /** Longer individual lines are clipped with an ellipsis. */
    private static final int MAX_NOTE_LINE_LENGTH = 78;

    private final transient UpdateInfo info;

    public UpdateDialog(Frame owner, UpdateInfo info) {
        super(owner, "发现新版本", true);
        this.info = info;
        buildUi();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void buildUi() {

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createEmptyBorder(16, 22, 12, 22));

        JLabel title = new JLabel("发现新版本");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(10));

        content.add(line("当前版本 " + AppVersion.current()));
        content.add(Box.createVerticalStrut(2));
        content.add(line("最新版本 " + info.version));

        String notes = summariseNotes(info.releaseNotes);
        if (notes != null) {
            content.add(Box.createVerticalStrut(10));
            JTextArea area = new JTextArea(notes);
            area.setEditable(false);
            area.setLineWrap(false);
            area.setOpaque(false);
            area.setFont(area.getFont().deriveFont(Font.PLAIN, 11f));
            area.setForeground(new Color(0x333333));
            area.setBorder(null);
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setPreferredSize(new Dimension(scroll.getPreferredSize().width,
                    Math.min(area.getPreferredSize().height + 4, 170)));
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(scroll);
        }

        content.add(Box.createVerticalStrut(14));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBackground(Color.WHITE);

        JButton skip = new JButton("跳过此版本");
        skip.addActionListener(e -> {
            UpdateChecker.skipVersion(info.version);
            dispose();
        });

        JButton later = new JButton("稍后");
        later.addActionListener(e -> {
            UpdateChecker.postpone();
            dispose();
        });

        JButton update = new JButton("立即更新");
        update.addActionListener(e -> {
            dispose();
            try {
                UpdateInstaller.start(info, getOwner() instanceof Frame ? (Frame) getOwner() : null);
            } catch (Throwable t) {
                log.error("Cannot start the update", t);
            }
        });

        buttons.add(Box.createHorizontalGlue());
        buttons.add(skip);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(later);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(update);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(buttons);

        getRootPane().setDefaultButton(update);
        setContentPane(content);
    }

    private static JLabel line(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setForeground(Color.BLACK);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /**
     * Keep the first {@link #MAX_NOTE_LINES} non-empty lines of the release notes, clipping
     * long lines.  Returns {@code null} when there is nothing worth showing.
     */
    public static String summariseNotes(String releaseNotes) {
        if (releaseNotes == null || releaseNotes.isBlank()) {
            return null;
        }
        StringBuilder summary = new StringBuilder();
        int used = 0;
        boolean truncated = false;
        for (String rawLine : releaseNotes.split("\r?\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            line = line.replace("**", "").replace("###", "").strip();
            if (line.isEmpty()) {
                continue;
            }
            if (used >= MAX_NOTE_LINES) {
                truncated = true;
                break;
            }
            if (used > 0) {
                summary.append('\n');
            }
            summary.append(clip(line));
            used++;
        }
        if (used == 0) {
            return null;
        }
        if (truncated) {
            // Stay within the line budget: mark the truncation on the last line instead of
            // adding a ninth one.
            summary.append(" …");
        }
        return summary.toString();
    }

    private static String clip(String line) {
        if (line.length() <= MAX_NOTE_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_NOTE_LINE_LENGTH - 1) + "…";
    }
}
