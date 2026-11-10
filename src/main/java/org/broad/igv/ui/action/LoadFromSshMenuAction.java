package org.broad.igv.ui.action;

import com.jcraft.jsch.Session;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.ui.util.SshConnectionDialog;
import org.broad.igv.ui.util.SshFileBrowserDialog;
import org.broad.igv.util.ResourceLocator;
import org.broad.igv.util.ssh.SecretProtector;
import org.broad.igv.util.ssh.SshCredentialStore;
import org.broad.igv.util.ssh.SshProfile;
import org.broad.igv.util.ssh.SshSessionManager;

import javax.swing.SwingWorker;
import java.awt.Cursor;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the remote file browser and loads the selected files as
 * {@code sftp://user@host:port/path} resources.
 * <p>
 * The connection dialog is only a fallback: an already live session goes straight to the file
 * list, and a saved profile is reconnected in the background, so adding a second track from the
 * same server is one menu click.
 */
public class LoadFromSshMenuAction extends MenuAction {

    public static final String LOAD_FROM_SSH = "Load from SSH Server...";

    private final IGV igv;

    public LoadFromSshMenuAction(String label, int mnemonic, IGV igv) {
        super(label, null, mnemonic);
        this.igv = igv;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Session live = SshSessionManager.mostRecentLive();
        if (live != null) {
            // Already connected in this session: skip straight to the file list.
            browse(live.getHost(), live.getPort(), live.getUserName());
            return;
        }

        SshProfile saved = SshCredentialStore.mostRecent();
        if (saved != null && canReconnect(saved)) {
            reconnectAndBrowse(saved);
        } else {
            connectThenBrowse();
        }
    }

    private static boolean canReconnect(SshProfile profile) {
        if (profile.keyPath != null && !profile.keyPath.isBlank()) {
            return true;
        }
        return SecretProtector.unprotect(SshCredentialStore.keyOf(profile), profile.secret) != null;
    }

    /** Reuses the saved profile so a restarted app does not ask for the password again. */
    private void reconnectAndBrowse(SshProfile profile) {
        final Frame frame = igv.getMainFrame();
        final Cursor previous = frame.getCursor();
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                String password = SecretProtector.unprotect(
                        SshCredentialStore.keyOf(profile), profile.secret);
                Session session = SshSessionManager.connect(profile.host, profile.port, profile.user,
                        password == null ? null : password.toCharArray(),
                        profile.keyPath, null, 8_000);
                SshSessionManager.probeSftp(session);
                return null;
            }

            @Override
            protected void done() {
                frame.setCursor(previous);
                try {
                    get();
                    browse(profile.host, profile.port, profile.user);
                } catch (Exception ex) {
                    // Fall back to the dialog; it pre-fills the saved values.
                    connectThenBrowse();
                }
            }
        }.execute();
    }

    private void connectThenBrowse() {
        SshConnectionDialog connection = new SshConnectionDialog(igv.getMainFrame());
        connection.setVisible(true);
        if (connection.isConnected()) {
            browse(connection.getHost(), connection.getPort(), connection.getUser());
        }
    }

    private void browse(String host, int port, String user) {
        SshFileBrowserDialog browser = new SshFileBrowserDialog(igv.getMainFrame(), host, port, user);
        browser.setVisible(true);

        List<String> paths = browser.getSelectedPaths();
        if (paths.isEmpty()) {
            return;
        }

        List<ResourceLocator> locators = new ArrayList<>();
        for (String path : paths) {
            try {
                locators.add(new ResourceLocator(toSftpUrl(user, host, port, path)));
            } catch (Exception ex) {
                MessageUtils.showMessage("Cannot build URL for " + path + ": " + ex.getMessage());
            }
        }
        if (!locators.isEmpty()) {
            igv.addToRecentUrls(locators);
            igv.loadTracks(locators);
        }
    }

    private static String toSftpUrl(String user, String host, int port, String path) throws Exception {
        // The URI constructor encodes spaces and other characters in the path.
        return new URI("sftp", user, host, port, path, null, null).toString();
    }
}
