package org.broad.igv.util.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns SSH connections to user supplied servers.
 * <p>
 * One {@link Session} per user@host:port is kept alive so the connection dialog, the future
 * remote file browser and the track readers reuse the same transport instead of
 * re-authenticating for every file. Sessions are never written to disk.
 */
public final class SshSessionManager {

    private static final Logger log = LogManager.getLogger(SshSessionManager.class);

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Last successful use per server, so the menu can reuse the newest live connection. */
    private static final Map<String, Long> LAST_USED = new ConcurrentHashMap<>();

    private SshSessionManager() {
    }

    public static String key(String host, int port, String user) {
        return user + "@" + host + ":" + port;
    }

    /**
     * Connects and keeps the session. The caller owns the returned session only in the sense
     * of using it; closing is handled by {@link #disconnect(String, int, String)} or
     * {@link #closeAll()}.
     *
     * @param keyPath   optional private key file; when blank the password is used
     * @param passphrase optional passphrase for an encrypted private key
     */
    public static Session connect(String host, int port, String user,
                                  char[] password, String keyPath, char[] passphrase,
                                  int timeoutMs) throws JSchException {

        JSch jsch = new JSch();
        if (keyPath != null && !keyPath.isBlank()) {
            if (passphrase != null && passphrase.length > 0) {
                jsch.addIdentity(keyPath, new String(passphrase));
            } else {
                jsch.addIdentity(keyPath);
            }
        }

        Session session = jsch.getSession(user, host, port);
        if (password != null && password.length > 0) {
            session.setPassword(new String(password));
        }

        // Accept the host key on first use. A fingerprint prompt belongs with the remote file
        // browser; until then this is the only way to connect to a server we have never seen.
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setServerAliveInterval(30_000);
        session.setServerAliveCountMax(3);

        // -Djava.net.useSystemProxies=true (needed for IGV's HTTP resources) otherwise makes
        // JSch tunnel SSH through the desktop HTTP/SOCKS proxy, which drops the handshake on
        // non-standard ports. Always connect directly unless explicitly opted out.
        if (!Boolean.getBoolean("myigv.ssh.useSystemProxy")) {
            session.setSocketFactory(new DirectSocketFactory(timeoutMs));
            log.info("SSH using direct connection (system proxy bypassed) for "
                    + key(host, port, user));
        }

        session.connect(timeoutMs);
        Session previous = SESSIONS.put(key(host, port, user), session);
        LAST_USED.put(key(host, port, user), System.currentTimeMillis());
        if (previous != null && previous != session) {
            previous.disconnect();
        }
        log.info("SSH connected to " + key(host, port, user)
                + " (" + session.getServerVersion() + ")");
        return session;
    }

    /**
     * Opens an SFTP channel once and returns its working directory, proving that the server
     * actually has the subsystem enabled (SSH alone is not enough for file access).
     */
    public static String probeSftp(Session session) throws JSchException {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10_000);
            return sftp.pwd();
        } catch (Exception e) {
            throw new JSchException("SFTP subsystem unavailable: " + e.getMessage(), e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /** Existing live session for this server, or {@code null}. */
    public static Session get(String host, int port, String user) {
        Session session = SESSIONS.get(key(host, port, user));
        if (session != null && !session.isConnected()) {
            SESSIONS.remove(key(host, port, user));
            return null;
        }
        return session;
    }

    /**
     * Most recently used session that is still alive, or {@code null}. Used to skip the
     * connection dialog when the user opens a second file from the same server.
     */
    public static Session mostRecentLive() {
        Session best = null;
        long bestTime = -1;
        for (Map.Entry<String, Long> entry : LAST_USED.entrySet()) {
            Session session = SESSIONS.get(entry.getKey());
            if (session == null || !session.isConnected()) {
                continue;
            }
            long used = entry.getValue();
            if (used > bestTime) {
                best = session;
                bestTime = used;
            }
        }
        return best;
    }

    public static void disconnect(String host, int port, String user) {
        Session session = SESSIONS.remove(key(host, port, user));
        LAST_USED.remove(key(host, port, user));
        if (session != null) {
            session.disconnect();
        }
    }

    public static void closeAll() {
        for (String k : SESSIONS.keySet()) {
            Session session = SESSIONS.remove(k);
            LAST_USED.remove(k);
            if (session != null) {
                session.disconnect();
            }
        }
    }
}
