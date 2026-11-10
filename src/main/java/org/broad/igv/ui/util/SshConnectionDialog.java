package org.broad.igv.ui.util;

import com.jcraft.jsch.Session;
import org.broad.igv.util.ssh.SecretProtector;
import org.broad.igv.util.ssh.SshCredentialStore;
import org.broad.igv.util.ssh.SshProfile;
import org.broad.igv.util.ssh.SshSessionManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects SSH connection details and proves the server is usable before anything else is
 * built on top of it: TCP + authentication, then an SFTP channel, because SSH alone does not
 * guarantee file access.
 * <p>
 * Profiles are remembered in {@code myigv_data/ssh-servers.json}. The password is encrypted
 * with DPAPI on Windows (bound to the current user); elsewhere only host/user/key are stored.
 */
public class SshConnectionDialog extends JDialog {

    private final JTextField hostField = new JTextField(22);
    private final JTextField portField = new JTextField("22", 5);
    private final JTextField userField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JTextField keyField = new JTextField(22);
    private final JPasswordField passphraseField = new JPasswordField(18);

    private final JComboBox<String> savedBox = new JComboBox<>();
    private final List<SshProfile> savedProfiles = new ArrayList<>();
    private final JCheckBox remember = new JCheckBox("Remember password", true);

    private final JLabel statusLabel = new JLabel(" ");
    private final JButton testButton = new JButton("Test connection");
    private final JButton closeButton = new JButton("Close");

    private boolean connected;
    private String connectedServer;
    private String connectedHost;
    private int connectedPort;
    private String connectedUser;

    public SshConnectionDialog(Frame owner) {
        super(owner, "SSH Connection", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUi();
        loadSaved();
        pack();
        setMinimumSize(new Dimension(540, getHeight()));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addLabeled(form, c, row++, "Saved", savedBox);
        addLabeled(form, c, row++, "Host", hostField);
        addLabeled(form, c, row++, "Port", portField);
        addLabeled(form, c, row++, "User", userField);
        addLabeled(form, c, row++, "Password", passwordField);

        JPanel keyPanel = new JPanel(new BorderLayout(6, 0));
        keyPanel.add(keyField, BorderLayout.CENTER);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> chooseKeyFile());
        keyPanel.add(browse, BorderLayout.EAST);
        addLabeled(form, c, row++, "Private key (optional)", keyPanel);

        addLabeled(form, c, row++, "Passphrase (optional)", passphraseField);

        if (!SecretProtector.isAvailable()) {
            remember.setSelected(false);
            remember.setEnabled(false);
            remember.setToolTipText("Encrypted password storage needs Windows DPAPI; "
                    + "host, user and key path are still remembered.");
        }
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(remember, c);

        JLabel hint = new JLabel("<html><body style='width: 440px; color: #666666'>"
                + "Leave the private key empty to log in with a password. Profiles are saved in "
                + "myigv_data/ssh-servers.json. On Windows the password is encrypted with DPAPI; "
                + "on macOS it is kept in your login keychain. Copying the file to another "
                + "machine does not transfer the password."
                + "</body></html>");
        c.gridy = row++;
        form.add(hint, c);

        c.gridy = row++;
        c.insets = new Insets(10, 4, 4, 4);
        statusLabel.setVerticalAlignment(SwingConstants.TOP);
        form.add(statusLabel, c);
        c.gridwidth = 1;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(testButton);
        buttons.add(closeButton);

        testButton.addActionListener(e -> {
            if (connected) {
                dispose();
            } else {
                doConnect();
            }
        });
        closeButton.addActionListener(e -> dispose());

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
    }

    private void loadSaved() {
        savedProfiles.addAll(SshCredentialStore.load());
        if (savedProfiles.isEmpty()) {
            savedBox.addItem("(no saved servers)");
            savedBox.setEnabled(false);
            return;
        }
        for (SshProfile profile : savedProfiles) {
            savedBox.addItem(profile.user + "@" + profile.host + ":" + profile.port);
        }
        savedBox.addActionListener(e -> {
            int index = savedBox.getSelectedIndex();
            if (index >= 0 && index < savedProfiles.size()) {
                fillFrom(savedProfiles.get(index));
            }
        });
        fillFrom(savedProfiles.get(0));
    }

    private void fillFrom(SshProfile profile) {
        hostField.setText(profile.host);
        portField.setText(Integer.toString(profile.port));
        userField.setText(profile.user);
        keyField.setText(profile.keyPath == null ? "" : profile.keyPath);
        String password = SecretProtector.unprotect(SshCredentialStore.keyOf(profile), profile.secret);
        passwordField.setText(password == null ? "" : password);
        remember.setSelected(password != null && !password.isEmpty());
    }

    private static void addLabeled(JPanel form, GridBagConstraints c, int row,
                                   String label, JComponent field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        c.weightx = 0;
    }

    private void chooseKeyFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select private key");
        File home = new File(System.getProperty("user.home"), ".ssh");
        chooser.setCurrentDirectory(home.isDirectory() ? home : new File(System.getProperty("user.home")));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            keyField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void doConnect() {
        final String host = hostField.getText().trim();
        final String user = userField.getText().trim();
        final String portText = portField.getText().trim();

        if (host.isEmpty() || user.isEmpty()) {
            setStatus("Host and user are required.", false);
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            setStatus("Port must be a number.", false);
            return;
        }

        final char[] password = passwordField.getPassword();
        final char[] passphrase = passphraseField.getPassword();
        final String keyPath = keyField.getText().trim();

        testButton.setEnabled(false);
        setStatus("Connecting to " + host + ":" + port + " ...", null);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                Session session = SshSessionManager.connect(host, port, user, password,
                        keyPath, passphrase, 12_000);
                String dir = SshSessionManager.probeSftp(session);
                return session.getServerVersion() + ", SFTP available, home directory " + dir;
            }

            @Override
            protected void done() {
                testButton.setEnabled(true);
                try {
                    String info = get();
                    connected = true;
                    connectedServer = SshSessionManager.key(host, port, user);
                    connectedHost = host;
                    connectedPort = port;
                    connectedUser = user;
                    boolean saved = rememberProfile(host, port, user, keyPath, password);
                    setStatus(saved ? "Connected: " + info
                            : "Connected: " + info + " (profile could not be saved - see log)", saved);
                    testButton.setText("Done");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    String message = cause.getMessage();
                    if (message == null || message.isBlank()) {
                        message = cause.getClass().getSimpleName();
                    }
                    setStatus("Connection failed: " + message, false);
                }
            }
        }.execute();
    }

    private boolean rememberProfile(String host, int port, String user,
                                    String keyPath, char[] password) {
        SshProfile profile = new SshProfile();
        profile.host = host;
        profile.port = port;
        profile.user = user;
        profile.keyPath = keyPath;
        return SshCredentialStore.save(profile, remember.isSelected() ? new String(password) : null);
    }

    private void setStatus(String message, Boolean ok) {
        String color = ok == null ? "#555555" : (ok ? "#1a7f37" : "#b3261e");
        statusLabel.setText("<html><body style='width: 440px; color: " + color + "'>"
                + escapeHtml(message) + "</body></html>");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** True when a connection was established and verified in this dialog. */
    public boolean isConnected() {
        return connected;
    }

    /** user@host:port of the verified connection, or {@code null}. */
    public String getConnectedServer() {
        return connectedServer;
    }

    public String getHost() {
        return connectedHost;
    }

    public int getPort() {
        return connectedPort;
    }

    public String getUser() {
        return connectedUser;
    }
}
