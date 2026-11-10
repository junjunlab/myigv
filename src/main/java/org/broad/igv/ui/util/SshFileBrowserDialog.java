package org.broad.igv.ui.util;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import org.broad.igv.util.ssh.SecretProtector;
import org.broad.igv.util.ssh.SshCredentialStore;
import org.broad.igv.util.ssh.SshProfile;
import org.broad.igv.util.ssh.SshSessionManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists a directory on the connected server and returns the selected file paths as
 * {@code /abs/path} strings. Directory contents are fetched on a background thread; the
 * table only shows track-like files unless "Show all files" is ticked.
 * <p>
 * The account can be switched from the toolbar without closing the window. Switching only
 * changes which server this browser lists: sessions already used by loaded tracks stay open
 * so those tracks keep reading.
 */
public class SshFileBrowserDialog extends JDialog {

    private static final String[] TRACK_SUFFIXES = {
            ".bam", ".bai", ".cram", ".crai", ".bw", ".bigwig", ".bedgraph", ".vcf",
            ".vcf.gz", ".gz", ".hic", ".cool", ".mcool", ".h5", ".bed", ".bedpe", ".gtf",
            ".gff", ".gff3", ".fa", ".fasta", ".fna", ".tdf", ".wig", ".seg", ".narrowpeak",
            ".broadpeak", ".csv", ".tsv", ".txt"
    };

    private String host;
    private int port;
    private String user;

    private final JTextField pathField = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Name", "Size", "Modified"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JCheckBox allFiles = new JCheckBox("Show all files");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton accountsButton = new JButton("Accounts...");
    private final JButton openButton = new JButton("Open");
    private final JButton upButton = new JButton("Up");
    private final JButton cancelButton = new JButton("Cancel");

    private ChannelSftp channel;
    private String currentPath = ".";
    private List<ChannelSftp.LsEntry> lastEntries = new ArrayList<>();
    private final List<String> selectedPaths = new ArrayList<>();

    /** Directory listings, keyed by canonical path, so going back/up is instant. */
    private final Map<String, List<ChannelSftp.LsEntry>> dirCache = new HashMap<>();

    public SshFileBrowserDialog(Frame owner, String host, int port, String user) {
        super(owner, titleFor(user, host), true);
        this.host = host;
        this.port = port;
        this.user = user;
        buildUi();
        loadDirectory(".");
        pack();
        setMinimumSize(new Dimension(760, 460));
        setLocationRelativeTo(owner);
    }

    private static String titleFor(String user, String host) {
        return "Remote files - " + user + "@" + host;
    }

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        top.add(new JLabel("Path"), BorderLayout.WEST);
        top.add(pathField, BorderLayout.CENTER);

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        topRight.add(accountsButton);
        topRight.add(upButton);
        top.add(topRight, BorderLayout.EAST);
        pathField.addActionListener(e -> loadDirectory(pathField.getText().trim()));

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(20);
        table.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String name = value == null ? "" : value.toString();
                setIcon(TrackFileIcons.forEntry(name, name.endsWith("/")));
                setText(name);
                setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
                return this;
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(440);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.getSelectedRow();
                    if (viewRow < 0) {
                        return;
                    }
                    int row = table.convertRowIndexToModel(viewRow);
                    String name = String.valueOf(model.getValueAt(row, 0));
                    if (name.endsWith("/")) {
                        loadDirectory(join(currentPath, name.substring(0, name.length() - 1)));
                    } else {
                        openSelected();
                    }
                }
            }
        });

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(allFiles);
        left.add(statusLabel);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(openButton);
        right.add(cancelButton);
        bottom.add(left, BorderLayout.CENTER);
        bottom.add(right, BorderLayout.EAST);

        allFiles.addActionListener(e -> fillTable());
        accountsButton.addActionListener(e -> showAccountMenu());
        upButton.addActionListener(e -> loadDirectory(parentOf(currentPath)));
        openButton.addActionListener(e -> openSelected());
        cancelButton.addActionListener(e -> dispose());

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
    }

    private void showAccountMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<SshProfile> profiles = SshCredentialStore.load();
        String current = SshSessionManager.key(host, port, user);

        for (SshProfile profile : profiles) {
            String key = SshSessionManager.key(profile.host, profile.port, profile.user);
            boolean isCurrent = key.equals(current);
            JMenuItem item = new JMenuItem(profile.user + "@" + profile.host + ":" + profile.port
                    + (isCurrent ? "   (current)" : ""));
            item.setEnabled(!isCurrent);
            item.addActionListener(e -> switchTo(profile));
            menu.add(item);
        }
        if (!profiles.isEmpty()) {
            menu.addSeparator();
        }
        JMenuItem other = new JMenuItem("Other server...");
        other.addActionListener(e -> openOtherServer());
        menu.add(other);

        menu.show(accountsButton, 0, accountsButton.getHeight());
    }

    private void switchTo(SshProfile profile) {
        if (profile.host.equals(host) && profile.port == port && profile.user.equals(user)) {
            return;
        }
        // Reuse a session that is already open for that account, e.g. an earlier import.
        Session existing = SshSessionManager.get(profile.host, profile.port, profile.user);
        if (existing != null && existing.isConnected()) {
            applyAccount(profile);
            return;
        }

        String password = SecretProtector.unprotect(SshCredentialStore.keyOf(profile), profile.secret);
        if (password == null && (profile.keyPath == null || profile.keyPath.isBlank())) {
            openOtherServer();
            return;
        }

        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText("Connecting to " + profile.user + "@" + profile.host + " ...");
        accountsButton.setEnabled(false);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                Session session = SshSessionManager.connect(profile.host, profile.port, profile.user,
                        password == null ? null : password.toCharArray(),
                        profile.keyPath, null, 8_000);
                SshSessionManager.probeSftp(session);
                return null;
            }

            @Override
            protected void done() {
                accountsButton.setEnabled(true);
                try {
                    get();
                    applyAccount(profile);
                } catch (Exception ex) {
                    statusLabel.setForeground(new Color(0xB3, 0x26, 0x1E));
                    statusLabel.setText("Switch failed: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void applyAccount(SshProfile profile) {
        host = profile.host;
        port = profile.port;
        user = profile.user;
        resetChannel();
        dirCache.clear();
        setTitle(titleFor(user, host));
        currentPath = ".";
        lastEntries = new ArrayList<>();
        loadDirectory(".");
    }

    private void openOtherServer() {
        Window owner = getOwner();
        SshConnectionDialog dialog = new SshConnectionDialog(owner instanceof Frame ? (Frame) owner : null);
        dialog.setVisible(true);
        if (!dialog.isConnected()) {
            return;
        }
        SshProfile profile = new SshProfile();
        profile.host = dialog.getHost();
        profile.port = dialog.getPort();
        profile.user = dialog.getUser();
        applyAccount(profile);
    }

    private void loadDirectory(String path) {
        final String target = (path == null || path.isBlank()) ? "." : path;

        // Serve a previously listed directory straight from the cache: no round trip, no wait.
        List<ChannelSftp.LsEntry> cached = dirCache.get(target);
        if (cached != null) {
            currentPath = target;
            pathField.setText(target);
            lastEntries = cached;
            fillTable();
            return;
        }

        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText("Loading " + target + " ...");
        openButton.setEnabled(false);

        new SwingWorker<Object, Void>() {
            private String canonical;
            private List<ChannelSftp.LsEntry> entries = new ArrayList<>();
            private Exception failure;

            @Override
            protected Object doInBackground() {
                try {
                    ChannelSftp ch = channel();
                    ch.cd(target);
                    canonical = ch.pwd();
                    for (Object o : ch.ls(".")) {
                        entries.add((ChannelSftp.LsEntry) o);
                    }
                } catch (Exception e) {
                    failure = e;
                }
                return null;
            }

            @Override
            protected void done() {
                openButton.setEnabled(true);
                if (failure != null) {
                    statusLabel.setForeground(new Color(0xB3, 0x26, 0x1E));
                    statusLabel.setText("Cannot list " + target + ": " + failure.getMessage());
                    return;
                }
                currentPath = canonical;
                pathField.setText(canonical);
                lastEntries = entries;
                dirCache.put(canonical, entries);
                if (!target.equals(canonical)) {
                    dirCache.put(target, entries);
                }
                fillTable();
            }
        }.execute();
    }

    private void fillTable() {
        List<ChannelSftp.LsEntry> visible = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : lastEntries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            if (entry.getAttrs().isDir() || allFiles.isSelected() || isTrackFile(name)) {
                visible.add(entry);
            }
        }
        visible.sort(Comparator
                .comparing((ChannelSftp.LsEntry e) -> !e.getAttrs().isDir())
                .thenComparing(e -> e.getFilename().toLowerCase(Locale.ROOT)));

        model.setRowCount(0);
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (ChannelSftp.LsEntry entry : visible) {
            SftpATTRS attrs = entry.getAttrs();
            String name = entry.getFilename() + (attrs.isDir() ? "/" : "");
            String size = attrs.isDir() ? "-" : humanSize(attrs.getSize());
            String modified = date.format(new Date(attrs.getMTime() * 1000L));
            model.addRow(new Object[]{name, size, modified});
        }
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText(visible.size() + " entries"
                + (allFiles.isSelected() ? "" : " (tracks only)"));
    }

    private void openSelected() {
        selectedPaths.clear();
        for (int viewRow : table.getSelectedRows()) {
            int row = table.convertRowIndexToModel(viewRow);
            String name = String.valueOf(model.getValueAt(row, 0));
            if (name.endsWith("/")) {
                continue;
            }
            selectedPaths.add(join(currentPath, name));
        }
        if (selectedPaths.isEmpty()) {
            statusLabel.setForeground(new Color(0xB3, 0x26, 0x1E));
            statusLabel.setText("Select at least one file.");
            return;
        }
        dispose();
    }

    private ChannelSftp channel() throws Exception {
        if (channel == null || !channel.isConnected()) {
            Session session = SshSessionManager.get(host, port, user);
            if (session == null) {
                throw new IOException("Not connected to " + SshSessionManager.key(host, port, user));
            }
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(10_000);
        }
        return channel;
    }

    private void resetChannel() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static boolean isTrackFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : TRACK_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String join(String dir, String name) {
        if (dir == null || dir.isEmpty()) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String parentOf(String path) {
        if (path == null || "/".equals(path) || path.isEmpty()) {
            return "/";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash <= 0 ? "/" : trimmed.substring(0, slash);
    }

    /** Absolute remote paths of the files the user chose, empty when cancelled. */
    public List<String> getSelectedPaths() {
        return selectedPaths;
    }

    @Override
    public void dispose() {
        resetChannel();
        super.dispose();
    }
}
