package org.broad.igv.update;

import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.prefs.IGVPreferences;
import org.broad.igv.prefs.PreferencesManager;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.HttpUtils;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks for a newer myigv release on GitHub.
 *
 * <p>All network work happens on a daemon thread: the startup check must never delay or block
 * the UI, and it fails silently (offline, proxy problems, rate limits) because a background
 * convenience check must not get in the user's way.  The manual check from the Help menu does
 * report the outcome.</p>
 */
public class UpdateChecker {

    private static final Logger log = LogManager.getLogger(UpdateChecker.class);

    /** Default release feed. */
    public static final String DEFAULT_FEED = "https://api.github.com/repos/junjunlab/myigv/releases/latest";

    /** System property used by QA to point the updater at a local feed. */
    public static final String FEED_PROPERTY = "myigv.update.feed";

    static final String PREF_LAST_CHECK = "myigv.update.lastCheck";
    static final String PREF_SKIPPED_VERSION = "myigv.update.skippedVersion";
    static final String PREF_AUTO_CHECK = "myigv.update.auto";

    /** Automatic checks are throttled to one per day. */
    private static final long CHECK_INTERVAL_SECONDS = 24L * 60L * 60L;
    private static final int TIMEOUT_MS = 10_000;

    /** True once the user has been prompted in this session ("Later" must not nag). */
    private static volatile boolean promptedThisSession = false;

    /** Guards against overlapping checks (repeated "Check for updates…" clicks). */
    private static volatile boolean checking = false;

    private UpdateChecker() {
        // Utility class
    }

    /**
     * Fetch the latest release description.
     *
     * @return the release, or {@code null} on any failure (never throws)
     */
    public static UpdateInfo fetchLatest() {
        String feed = System.getProperty(FEED_PROPERTY);
        String url = feed == null || feed.isBlank() ? DEFAULT_FEED : feed.trim();
        try {
            String json = readContents(url);
            if (json == null) {
                return null;
            }
            UpdateInfo info = UpdateInfo.fromJson(json);
            if (info == null) {
                log.warn("Update feed did not contain a usable release: " + url);
            }
            return info;
        } catch (Throwable t) {
            // Automatic checking is best effort; log and move on.
            log.warn("Update check failed (" + url + "): " + t.getMessage());
            return null;
        }
    }

    /** Convert a {@code file:} URL to a File, tolerating unencoded spaces in the path. */
    private static File toFile(URL url) throws IOException {
        try {
            return new File(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new File(url.getPath());
        }
    }

    private static String readContents(String urlString) throws IOException {        URL url = new URL(urlString);
        if ("file".equalsIgnoreCase(url.getProtocol())) {
            File file = toFile(url);
            if (!file.isFile()) {
                throw new IOException("Update feed not found: " + file);
            }
            return java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }

        // Reuse IGV's proxy configuration (manual proxy prefs + system proxy), but with our own
        // short timeouts: the defaults are minutes long, which is far too long for a startup check.
        HttpURLConnection connection = HttpUtils.getInstance().openProxiedConnection(url);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "myIGV-Updater");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " from " + urlString);
            }
            try (InputStream stream = connection.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Silent, throttled check performed once the main window is up.
     *
     * @param owner window the prompt is parented to (may be {@code null})
     */
    public static void checkOnStartup(final Frame owner) {

        if (!isAutomaticCheckEnabled()) {
            log.info("Automatic update check is disabled in preferences");
            return;
        }
        if (isWithinThrottleWindow()) {
            log.info("Skipping automatic update check, last check was less than 24h ago");
            return;
        }
        if (!beginCheck()) {
            // A manual check is already running; it will report the result.
            return;
        }

        Thread thread = new Thread(() -> {
            // True once the prompt has been handed to the EDT: the check then owns the flag until
            // the user closes the dialog, so a manual click while it is open cannot stack a
            // second modal dialog on top of it.
            boolean dialogOwnsFlag = false;
            try {
                UpdateInfo info = fetchLatest();
                if (info == null) {
                    // Do not consume the 24h window on a failed check: the next launch retries.
                    return;
                }
                // Record the successful attempt off the EDT, so a flaky network (or a hung
                // proxy) cannot leave a preference write blocking the UI.
                recordCheckTime();

                final String current = AppVersion.current();
                if (!AppVersion.isNewer(info.version, current)) {
                    log.info("myigv " + current + " is up to date (latest " + info.version + ")");
                    return;
                }
                if (info.version.equals(getSkippedVersion())) {
                    log.info("Update to " + info.version + " was skipped by the user");
                    return;
                }
                if (promptedThisSession) {
                    return;
                }
                promptedThisSession = true;
                log.info("New myigv version available: " + info.version + " (current " + current + ")");
                final UpdateInfo dialogInfo = info;
                dialogOwnsFlag = true;
                SwingUtilities.invokeLater(() -> {
                    try {
                        new UpdateDialog(owner, dialogInfo).setVisible(true);
                    } catch (Throwable t) {
                        log.error("Error showing the update dialog", t);
                    } finally {
                        checking = false;
                    }
                });
            } catch (Throwable t) {
                log.warn("Automatic update check failed: " + t.getMessage());
            } finally {
                if (!dialogOwnsFlag) {
                    checking = false;
                }
            }
        }, "myigv-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Manual check from the Help menu: ignores the throttle and the skip list, and always
     * reports the outcome.
     *
     * @param owner window the dialogs are parented to (may be {@code null})
     */
    public static void checkManually(final Frame owner) {

        if (!beginCheck()) {
            // Re-entrant click (or a startup check still running): do not stack dialogs.
            log.info("An update check is already in progress");
            return;
        }

        Thread thread = new Thread(() -> {
            UpdateInfo info = null;
            String current = AppVersion.current();
            try {
                info = fetchLatest();
                recordCheckTime();
            } catch (Throwable t) {
                log.warn("Manual update check failed: " + t.getMessage());
            } finally {
                final UpdateInfo result = info;
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (result == null) {
                            // MessageUtils renders HTML and does not honour "\n": use <br>.
                            MessageUtils.showMessage("无法检查更新。<br>请确认网络连接后重试，"
                                    + "或直接访问 https://github.com/junjunlab/myigv/releases 手动下载。");
                        } else if (!AppVersion.isNewer(result.version, current)) {
                            MessageUtils.showMessage("当前已是最新版本 " + current);
                        } else {
                            promptedThisSession = true;
                            new UpdateDialog(owner, result).setVisible(true);
                        }
                    } finally {
                        // Released only after the dialog is gone, so a second click while the
                        // modal prompt is open cannot open another one.
                        checking = false;
                    }
                });
            }
        }, "myigv-update-manual-check");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Report an update that failed last time.  The helper script leaves
     * {@value UpdateInstaller#FAILURE_MARKER_NAME} in the installation directory when a
     * replacement could not be completed; without this the user would silently keep running a
     * partially updated installation.
     *
     * <p>Must be called on the EDT; the dialog is modal and the marker is removed once the user
     * acknowledges it.</p>
     *
     * @param owner window the dialog is parented to (may be {@code null})
     */
    public static void checkPendingFailure(final Frame owner) {

        for (File marker : pendingFailureMarkers()) {
            if (marker.isFile()) {
                reportPendingFailure(marker);
                return;
            }
        }
    }

    /**
     * Places a failure report can be left: the installation directory first, then the updater's
     * work directory (used when the installation directory is not writable).
     */
    private static List<File> pendingFailureMarkers() {
        List<File> markers = new ArrayList<>();
        try {
            File installDir = UpdateInstaller.resolveInstallDir();
            if (installDir != null) {
                markers.add(new File(installDir, UpdateInstaller.FAILURE_MARKER_NAME));
            }
        } catch (Throwable t) {
            log.warn("Cannot resolve the installation directory: " + t.getMessage());
        }
        markers.add(UpdateInstaller.pendingMarkerFile());
        return markers;
    }

    private static void reportPendingFailure(final File marker) {

        final String report;
        try {
            report = java.nio.file.Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            log.warn("Cannot read the pending update failure report: " + t.getMessage());
            return;
        }

        log.warn("Previous update did not complete: " + marker.getAbsolutePath());
        String details = report.isEmpty() ? "(no details recorded)" : report;
        // MessageUtils renders the text as HTML, so newlines have to be <br> tags.
        String html = "<b>上次更新未完成，已回退到 " + htmlEscape(AppVersion.current()) + "。</b><hr>"
                + htmlEscape(details).replace("\r\n", "\n").replace("\n", "<br>")
                + "<hr>详细信息见：<br>" + htmlEscape(marker.getAbsolutePath());
        MessageUtils.showMessage(html);
        acknowledge(marker);
    }

    /**
     * Remove the report once the user has seen it.  If it cannot be deleted (read-only file,
     * locked directory) it is renamed instead, so the same failure is not reported on every
     * single launch - the file is still there for support, just not matched any more.
     */
    private static void acknowledge(File marker) {
        try {
            java.nio.file.Files.deleteIfExists(marker.toPath());
            return;
        } catch (Throwable t) {
            log.warn("Cannot delete " + marker + ": " + t.getMessage());
        }
        File acked = new File(marker.getParentFile(), marker.getName() + UpdateInstaller.ACK_MARKER_SUFFIX);
        try {
            java.nio.file.Files.deleteIfExists(acked.toPath());
            java.nio.file.Files.move(marker.toPath(), acked.toPath());
            log.info("Update report renamed to " + acked.getName() + " so it is reported only once");
        } catch (Throwable t) {
            log.warn("Cannot acknowledge " + marker + ": " + t.getMessage());
        }
    }

    /** Minimal escaping for text embedded in an HTML message. */
    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Serialise update checks: at most one runs at a time, so repeated menu clicks and a
     * startup check cannot stack dialogs or duplicate network work.
     *
     * @return true when the caller now owns the check and must clear the flag when done
     */
    private static synchronized boolean beginCheck() {
        if (checking) {
            return false;
        }
        checking = true;
        return true;
    }

    /** The user pressed "Skip this version": remember it so we stop asking. */
    static void skipVersion(String version) {
        if (version == null || version.isBlank()) {
            return;
        }
        try {
            preferences().put(PREF_SKIPPED_VERSION, version.trim());
        } catch (Throwable t) {
            log.warn("Could not save the skipped version: " + t.getMessage());
        }
    }

    /** The user pressed "Later": nothing is persisted, the session flag is enough. */
    static void postpone() {
        promptedThisSession = true;
    }

    private static String getSkippedVersion() {
        try {
            String skipped = preferences().get(PREF_SKIPPED_VERSION, "");
            return skipped == null ? "" : skipped.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isAutomaticCheckEnabled() {
        try {
            String value = preferences().get(PREF_AUTO_CHECK, "true");
            return value == null || !"false".equalsIgnoreCase(value.trim());
        } catch (Throwable t) {
            // Preferences not initialised yet (e.g. very early startup): assume enabled.
            return true;
        }
    }

    private static boolean isWithinThrottleWindow() {
        try {
            String value = preferences().get(PREF_LAST_CHECK, null);
            if (value == null || value.isBlank()) {
                return false;
            }
            long last = Long.parseLong(value.trim());
            long now = System.currentTimeMillis() / 1000L;
            long elapsed = now - last;
            return elapsed >= 0 && elapsed < CHECK_INTERVAL_SECONDS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void recordCheckTime() {
        try {
            preferences().put(PREF_LAST_CHECK, String.valueOf(System.currentTimeMillis() / 1000L));
        } catch (Throwable t) {
            log.warn("Could not record the update check time: " + t.getMessage());
        }
    }

    private static IGVPreferences preferences() {
        return PreferencesManager.getPreferences();
    }
}
