package org.broad.igv.update;

import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.HttpUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a new myigv build and replaces the current installation in place.
 *
 * <p>Windows cannot overwrite a running executable or the jars the JVM has open, so the actual
 * file replacement is delegated to a generated batch script ({@code apply.bat}) which waits for
 * this process to exit, copies the new files over the installation and restarts the app.</p>
 *
 * <p>Two layouts are supported:</p>
 * <ul>
 *   <li><b>jpackage</b> install: {@code myigv.exe} + {@code app\} + {@code runtime\} (from the
 *       official installer).  The app-image archive is copied over {@code app\} and
 *       {@code runtime\}.</li>
 *   <li><b>portable dist</b> (the {@code createDist} folder): {@code myigv.jar} + dependency jars
 *       in {@code lib\}.  The jars from the archive are copied into {@code lib\}, with the
 *       application jar renamed to {@code myigv.jar} because that is the name the launcher
 *       scripts put on the module path.</li>
 * </ul>
 *
 * <p>macOS is intentionally not auto-replaced in this version: the app bundle is signed and the
 * whole bundle has to be swapped atomically, which is the installer's (dmg) job.  The downloaded
 * dmg is simply opened for the user.</p>
 */
public class UpdateInstaller {

    private static final Logger log = LogManager.getLogger(UpdateInstaller.class);

    /** QA switch: download and validate but do not touch the installation or exit. */
    public static final String DRY_RUN_PROPERTY = "myigv.update.dryRun";

    /** Name of the generated helper script, inside the per-version work directory. */
    public static final String APPLY_SCRIPT_NAME = "apply.bat";

    /**
     * Marker written into the installation directory when an update does not complete.  The next
     * launch reports it to the user (see UpdateChecker.checkPendingFailure).
     */
    public static final String FAILURE_MARKER_NAME = "update-failed.txt";

    /**
     * Fallback marker in the work directory, used when the installation directory is not
     * writable.  Without it a failed update would be silent again.
     */
    public static final String PENDING_MARKER_NAME = "pending.txt";

    /** Suffix applied to the marker once the user has been told, so it is reported only once. */
    public static final String ACK_MARKER_SUFFIX = ".ack";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /**
     * How many one-second polls the helper does before giving up on this JVM (300 = 5 minutes).
     * On timeout the helper aborts: it must never touch the installation while the JVM is alive.
     */
    private static final int MAX_WAIT_ITERATIONS = 300;
    /** Hard limit for the JVM to get out of the way before it is halted. */
    private static final int EXIT_WATCHDOG_MS = 5000;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /** Where the installation lives, which decides how the payload is applied. */
    enum Layout {JPACKAGE, PORTABLE, UNKNOWN}

    private UpdateInstaller() {
        // Utility class
    }

    public static boolean isDryRun() {
        return Boolean.parseBoolean(System.getProperty(DRY_RUN_PROPERTY, "false"));
    }

    /**
     * Entry point: download the payload and hand the file replacement to the platform helper.
     *
     * @param info  release to install
     * @param owner window used to parent the progress/error dialogs
     */
    public static void start(final UpdateInfo info, final Frame owner) {

        if (info == null) {
            return;
        }

        if (isDryRun()) {
            // Exercise the whole pipeline without replacing anything.
            runDryRun(info);
            return;
        }

        if (UpdateInfo.isMac()) {
            startMac(info, owner);
            return;
        }

        final UpdateInfo.Asset asset = info.pickPayload();
        if (asset == null) {
            MessageUtils.showMessage("该版本没有可用于本平台的安装包。<br>请访问 "
                    + (info.htmlUrl == null || info.htmlUrl.isBlank()
                    ? "https://github.com/junjunlab/myigv/releases" : info.htmlUrl)
                    + " 手动下载。");
            return;
        }

        final File installDir = resolveInstallDir();
        final Layout layout = detectLayout(installDir);
        if (installDir == null || layout == Layout.UNKNOWN || !installDir.canWrite()) {
            log.warn("Cannot determine a writable installation directory (candidate: " + installDir + ")");
            openReleasePage(info, owner, installDir);
            return;
        }

        final UpdateProgressDialog dialog = new UpdateProgressDialog(owner, info);

        Thread worker = new Thread(() -> {
            try {
                File workDir = updateDirectory(info.version);
                File downloaded = download(asset, workDir, percent -> SwingUtilities.invokeLater(
                        () -> dialog.setPercent(percent)));
                final String script = buildApplyScript(info, asset, downloaded, installDir, layout, workDir);
                final File scriptFile = writeApplyScript(workDir, script);
                log.info("Update helper script written to " + scriptFile.getAbsolutePath()
                        + System.lineSeparator() + script);
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    launchHelper(scriptFile, workDir);
                });
            } catch (Throwable t) {
                log.error("Update failed: " + t.getMessage(), t);
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    MessageUtils.showErrorMessage("更新失败：" + t.getMessage()
                            + "<br><br>请稍后重试，或访问 https://github.com/junjunlab/myigv/releases 手动下载。", t
                            instanceof Exception ? (Exception) t : new Exception(t));
                });
            }
        }, "myigv-update-download");
        worker.setDaemon(true);
        worker.start();

        // Modal: the download thread dismisses it when it is done.  Must be shown on the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.setVisible(true);
        } else {
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        }
    }

    /**
     * Dry run used by QA: download, validate, print the generated script, change nothing.
     *
     * @param info release to "install"
     * @return the generated batch script, or {@code null} when the payload is unusable
     */
    public static String runDryRun(UpdateInfo info) {

        if (info == null) {
            System.out.println("[dryRun] no update information");
            return null;
        }
        UpdateInfo.Asset asset = info.pickPayload();
        if (asset == null) {
            System.out.println("[dryRun] release " + info.version + " has no payload for this platform");
            return null;
        }

        File installDir = resolveInstallDir();
        Layout layout = detectLayout(installDir);

        try {
            File workDir = updateDirectory(info.version);
            System.out.println("[dryRun] release      : " + info.version + " (tag " + info.tagName + ")");
            System.out.println("[dryRun] current      : " + AppVersion.current());
            System.out.println("[dryRun] newer?       : " + AppVersion.isNewer(info.version, AppVersion.current()));
            System.out.println("[dryRun] payload      : " + asset);
            System.out.println("[dryRun] work dir     : " + workDir.getAbsolutePath());
            System.out.println("[dryRun] install dir  : " + (installDir == null ? "<null>" : installDir.getAbsolutePath())
                    + "  layout=" + layout);

            File downloaded = download(asset, workDir, percent -> { });
            System.out.println("[dryRun] downloaded   : " + downloaded.getAbsolutePath()
                    + " (" + downloaded.length() + " bytes, size check " + (asset.size > 0 ? "OK" : "skipped") + ")");

            String script = buildApplyScript(info, asset, downloaded, installDir, layout, workDir);
            File scriptFile = writeApplyScript(workDir, script);
            System.out.println("[dryRun] script file  : " + scriptFile.getAbsolutePath());
            System.out.println("----- BEGIN " + APPLY_SCRIPT_NAME + " -----");
            System.out.print(script);
            System.out.println("----- END " + APPLY_SCRIPT_NAME + " -----");
            System.out.println("[dryRun] dry run only: helper NOT started, application NOT closed");
            return script;
        } catch (Throwable t) {
            System.out.println("[dryRun] FAILED: " + t.getMessage());
            log.error("Dry run failed", t);
            return null;
        }
    }

    /**
     * macOS: the bundle cannot be swapped safely while running, so hand the dmg to the user.
     */
    private static void startMac(final UpdateInfo info, final Frame owner) {

        UpdateInfo.Asset dmg = info.pickBySuffix(".dmg");
        if (dmg == null) {
            openReleasePage(info, owner, null);
            return;
        }

        final UpdateInfo.Asset asset = dmg;
        final UpdateProgressDialog dialog = new UpdateProgressDialog(owner, info);
        Thread worker = new Thread(() -> {
            try {
                File workDir = updateDirectory(info.version);
                File downloaded = download(asset, workDir, percent -> SwingUtilities.invokeLater(
                        () -> dialog.setPercent(percent)));
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    try {
                        // Platform limitation: replacing /Applications/myigv.app from inside the
                        // running bundle would break the code signature and the running JVM, so
                        // the user finishes the job by dragging the app across in Finder.
                        Desktop.getDesktop().open(downloaded);
                        MessageUtils.showMessage("更新包已下载并打开：<br>" + downloaded.getAbsolutePath()
                                + "<br><br>请把 myigv 拖到 Applications 覆盖旧版本，然后重新启动。");
                    } catch (Throwable t) {
                        log.error("Cannot open the downloaded dmg", t);
                        MessageUtils.showMessage("更新包已下载到：<br>" + downloaded.getAbsolutePath()
                                + "<br><br>请手动打开并安装。");
                    }
                });
            } catch (Throwable t) {
                log.error("Update download failed", t);
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    MessageUtils.showErrorMessage("更新失败：" + t.getMessage(), new Exception(t));
                });
            }
        }, "myigv-update-download");
        worker.setDaemon(true);
        worker.start();

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.setVisible(true);
        } else {
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        }
    }

    // ------------------------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------------------------

    /**
     * Download one asset into {@code targetDir}, reporting progress as a percentage.
     *
     * @param asset      asset to fetch
     * @param targetDir  directory to write into
     * @param onProgress receives 0..100 (may receive negative values when the size is unknown)
     * @return the downloaded file
     * @throws IOException on a network error or when the size does not match the release metadata
     */
    static File download(UpdateInfo.Asset asset, File targetDir, IntConsumer onProgress) throws IOException {

        Files.createDirectories(targetDir.toPath());
        File target = new File(targetDir, sanitizeFileName(asset.name));
        long expected = asset.size;

        URL url = new URL(asset.url);
        if ("file".equalsIgnoreCase(url.getProtocol())) {
            try (InputStream in = url.openStream();
                 OutputStream out = new FileOutputStream(target)) {
                copyWithProgress(in, out, expected, onProgress);
            }
        } else {
            HttpURLConnection connection = HttpUtils.getInstance().openProxiedConnection(url);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "myIGV-Updater");
            try {
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("下载失败，HTTP " + code + "：" + asset.url);
                }
                long declared = expected > 0 ? expected : connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream();
                     OutputStream out = new FileOutputStream(target)) {
                    copyWithProgress(in, out, declared, onProgress);
                }
            } finally {
                connection.disconnect();
            }
        }

        long actual = target.length();
        if (expected > 0 && actual != expected) {
            // Never apply a truncated download.
            if (!target.delete()) {
                log.warn("Could not delete the corrupt download " + target);
            }
            throw new IOException("下载文件大小不一致（期望 " + expected + " 字节，实际 " + actual + " 字节）");
        }
        return target;
    }

    private static void copyWithProgress(InputStream in, OutputStream out, long total,
                                         IntConsumer onProgress) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long read = 0;
        int lastPercent = -1;
        int count;
        while ((count = in.read(buffer)) > 0) {
            out.write(buffer, 0, count);
            read += count;
            if (total > 0) {
                int percent = (int) Math.min(100L, read * 100L / total);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    onProgress.accept(percent);
                }
            }
        }
        out.flush();
        onProgress.accept(100);
    }

    // ------------------------------------------------------------------------------------
    // Where are we installed?
    // ------------------------------------------------------------------------------------

    /**
     * Locate the installation root.
     *
     * @return the directory holding the executable / {@code lib} folder, or {@code null}
     */
    public static File resolveInstallDir() {

        // 1. Set by the jpackage launcher: <install>/myigv.exe
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            File launcher = new File(appPath);
            File dir = launcher.getParentFile();
            if (dir != null && dir.isDirectory()) {
                return dir;
            }
        }

        // 2. Walk up from the jar / classes directory until we see the app-image or dist layout.
        try {
            URL location = UpdateInstaller.class.getProtectionDomain().getCodeSource().getLocation();
            File start = new File(location.toURI());
            File dir = start.isDirectory() ? start : start.getParentFile();
            for (int depth = 0; depth < 6 && dir != null; depth++) {
                if (new File(dir, "app").isDirectory() || new File(dir, "lib").isDirectory()) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        } catch (URISyntaxException | RuntimeException e) {
            log.warn("Cannot derive the installation directory from the code source: " + e.getMessage());
        }

        // 3. Last resort: the working directory.
        String userDir = System.getProperty("user.dir");
        return userDir == null ? null : new File(userDir);
    }

    static Layout detectLayout(File installDir) {
        if (installDir == null || !installDir.isDirectory()) {
            return Layout.UNKNOWN;
        }
        if (new File(installDir, "app").isDirectory()) {
            return Layout.JPACKAGE;
        }
        if (new File(installDir, "lib").isDirectory()) {
            return Layout.PORTABLE;
        }
        return Layout.UNKNOWN;
    }

    static File updateDirectory(String version) {
        File base = new File(System.getProperty("java.io.tmpdir", "."), "myigv-update");
        File dir = new File(base, sanitizeFileName(version == null || version.isBlank() ? "latest" : version));
        dir.mkdirs();
        return dir;
    }

    /**
     * Fallback failure report used when the installation directory is not writable.  It lives in
     * the updater's work directory so a failed update can never be silently unnoticed.
     */
    public static File pendingMarkerFile() {
        return new File(new File(System.getProperty("java.io.tmpdir", "."), "myigv-update"),
                PENDING_MARKER_NAME);
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name == null ? "" : name.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isEmpty() ? "myigv-update.bin" : cleaned;
    }

    // ------------------------------------------------------------------------------------
    // Script generation
    // ------------------------------------------------------------------------------------

    /** Everything we need to know about the downloaded zip, learned by reading its entries. */
    static final class ArchiveLayout {
        /** Entry prefix holding the app-image folder, e.g. {@code "myigv/"} (may be empty). */
        String root = "";
        /** Folder inside {@code app/} that holds the jars: {@code "app/mods"} or {@code "app"}. */
        String jarDir = "app";
        /** Name of the application jar. */
        String appJar = "";
        /**
         * Path (relative to the app-image root) of the offline annotation pack, or an empty
         * string when the archive has none.  jpackage keeps {@code --app-content} verbatim, so
         * this is normally {@code "app-content/pack"}.
         */
        String packRel = "";
        boolean hasApp;
        boolean hasRuntime;

        @Override
        public String toString() {
            return "ArchiveLayout{root='" + root + "', jarDir='" + jarDir + "', appJar='" + appJar
                    + "', packRel='" + packRel + "', app=" + hasApp + ", runtime=" + hasRuntime + "}";
        }
    }

    /**
     * Build the batch script that replaces the installation after this process exits.
     *
     * @return script text (CRLF line endings, no non-ASCII literals)
     */
    static String buildApplyScript(UpdateInfo info, UpdateInfo.Asset asset, File downloaded,
                                   File installDir, Layout layout, File workDir) throws IOException {

        String lowerName = asset.name.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".exe")) {
            return buildExeScript(downloaded, workDir);
        }
        if (!lowerName.endsWith(".zip")) {
            throw new IOException("不支持的更新包格式：" + asset.name);
        }

        ArchiveLayout archive = inspectArchive(downloaded);
        if (archive == null || !archive.hasApp) {
            throw new IOException("更新包内容无法识别（未找到 app 目录）：" + asset.name);
        }

        String install = installDir.getAbsolutePath();
        String stage = new File(workDir, "stage").getAbsolutePath();
        String appRoot = joinWindows(stage, archive.root + "app");
        String jarSource = joinWindows(stage, archive.root + archive.jarDir);

        // Directories that are swapped, as {live path, backup name, moved flag}.
        //
        // INVARIANT enforced by the generated script: when any failure path finishes, every live
        // directory is either the complete previous version or the complete new version - never a
        // half-copied mix, and never deleted merely because a stale backup could not be cleared.
        // That is why each target carries its own MOVED_* flag (only targets this run actually
        // moved aside may be restored) and why the restore copies instead of renaming.
        List<String[]> swapTargets = new ArrayList<>();
        if (layout == Layout.PORTABLE) {
            swapTargets.add(new String[]{joinWindows(install, "lib"), "lib.old", "MOVED_LIB"});
            if (!archive.packRel.isEmpty()) {
                swapTargets.add(new String[]{joinWindows(install, "pack"), "pack.old", "MOVED_PACK"});
            }
        } else {
            swapTargets.add(new String[]{joinWindows(install, "app"), "app.old", "MOVED_APP"});
            if (archive.hasRuntime) {
                swapTargets.add(new String[]{joinWindows(install, "runtime"), "runtime.old", "MOVED_RUNTIME"});
            }
            if (!archive.packRel.isEmpty() && !archive.packRel.startsWith("app/")) {
                swapTargets.add(new String[]{joinWindows(install, archive.packRel.replace('/', '\\')),
                        "pack.old", "MOVED_PACK"});
            }
        }

        StringBuilder script = new StringBuilder(8192);
        script.append("@echo off").append("\r\n");
        script.append(":: Generated by myigv ").append(AppVersion.current())
                .append(" to install ").append(info.version).append("\r\n");
        script.append(":: layout=").append(layout).append(" archive=").append(asset.name).append("\r\n");
        script.append("setlocal").append("\r\n");
        script.append("set \"OLDPID=").append(ProcessHandle.current().pid()).append("\"\r\n");
        script.append("set \"OLDVER=").append(AppVersion.current()).append("\"\r\n");
        script.append("set \"NEWVER=").append(info.version).append("\"\r\n");
        script.append("set \"ZIPFILE=").append(downloaded.getAbsolutePath()).append("\"\r\n");
        script.append("set \"STAGE=").append(stage).append("\"\r\n");
        script.append("set \"INSTALL=").append(install).append("\"\r\n");
        script.append("set \"APPROOT=").append(appRoot).append("\"\r\n");
        script.append("set \"JARSRC=").append(jarSource).append("\"\r\n");
        script.append("set \"APPJAR=").append(archive.appJar).append("\"\r\n");
        script.append("set \"MARKER=%INSTALL%\\").append(FAILURE_MARKER_NAME).append("\"\r\n");
        script.append("set \"PENDING=%TEMP%\\myigv-update\\").append(PENDING_MARKER_NAME).append("\"\r\n");
        script.append("set \"ERRLOG=%TEMP%\\myigv-update\\apply-error.log\"").append("\r\n");
        script.append("set \"REASON=\"").append("\r\n");
        script.append("set \"RESTOREFAILED=\"").append("\r\n");
        script.append("set \"TOUCHED=\"").append("\r\n");
        script.append("set \"MANUAL=\"").append("\r\n");
        script.append("set /a TRIES=0").append("\r\n");
        script.append("\r\n");

        // --- 1. wait for this process to exit (files are locked while it runs) ---
        appendWaitLoop(script);
        script.append("\r\n");

        // --- 2. unpack the archive (PowerShell is present on every supported Windows) ---
        script.append("if exist \"%STAGE%\" rmdir /S /Q \"%STAGE%\" >nul 2>&1").append("\r\n");
        script.append("powershell -NoProfile -ExecutionPolicy Bypass -Command \"Expand-Archive -LiteralPath '%ZIPFILE%' -DestinationPath '%STAGE%' -Force\"").append("\r\n");
        script.append("if exist \"%APPROOT%\" goto backup").append("\r\n");
        script.append("set \"REASON=the downloaded archive could not be extracted\"").append("\r\n");
        script.append("goto failed").append("\r\n");
        script.append("\r\n");

        // --- 3. move the live directories aside (the transactional part starts here) ---
        script.append(":backup").append("\r\n");
        for (int i = 0; i < swapTargets.size(); i++) {
            String[] target = swapTargets.get(i);
            script.append("call :backupOne \"").append(target[0]).append("\" \"").append(target[1])
                    .append("\" \"").append(target[2]).append("\"").append("\r\n");
            script.append("if not errorlevel 1 goto backupNext").append(i + 1).append("\r\n");
            script.append("set \"REASON=cannot move ").append(fileName(target[0]))
                    .append(" aside - a file is locked or access is denied\"").append("\r\n");
            // Only targets that were really moved get restored, so rolling back here cannot
            // touch a live directory that this run never modified.
            script.append("goto rollback").append("\r\n");
            script.append(":backupNext").append(i + 1).append("\r\n");
        }
        script.append("\r\n");

        // --- 4. copy the new content into the (now empty) target paths ---
        script.append(":copy").append("\r\n");
        if (layout == Layout.PORTABLE) {
            script.append(":: portable dist: rebuild lib from the backup, then drop in the new jars\r\n");
            script.append("robocopy \"%INSTALL%\\").append(swapTargets.get(0)[1])
                    .append("\" \"%INSTALL%\\lib\" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
            script.append("if errorlevel 8 goto copyFailed").append("\r\n");
            script.append("robocopy \"%JARSRC%\" \"%INSTALL%\\lib\" *.jar /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
            script.append("if errorlevel 8 goto copyFailed").append("\r\n");
            script.append("if not exist \"%JARSRC%\\%APPJAR%\" goto copyFailed").append("\r\n");
            script.append("copy /Y \"%JARSRC%\\%APPJAR%\" \"%INSTALL%\\lib\\myigv.jar\" >nul").append("\r\n");
            script.append("if errorlevel 1 goto copyFailed").append("\r\n");
            if (!archive.packRel.isEmpty()) {
                script.append("robocopy \"").append(joinWindows(stage, archive.root + archive.packRel))
                        .append("\" \"%INSTALL%\\pack\" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
                script.append("if errorlevel 8 goto copyFailed").append("\r\n");
            }
        } else {
            script.append("robocopy \"%APPROOT%\" \"%INSTALL%\\app\" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
            script.append("if errorlevel 8 goto copyFailed").append("\r\n");
            if (archive.hasRuntime) {
                script.append("robocopy \"").append(joinWindows(stage, archive.root + "runtime"))
                        .append("\" \"%INSTALL%\\runtime\" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
                script.append("if errorlevel 8 goto copyFailed").append("\r\n");
            }
            // The offline annotation pack lives outside app\ (jpackage --app-content).
            if (!archive.packRel.isEmpty() && !archive.packRel.startsWith("app/")) {
                script.append("robocopy \"").append(joinWindows(stage, archive.root + archive.packRel))
                        .append("\" \"%INSTALL%\\").append(archive.packRel.replace('/', '\\'))
                        .append("\" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
                script.append("if errorlevel 8 goto copyFailed").append("\r\n");
            }
        }
        script.append("\r\n");

        // --- 5. verify before declaring success ---
        script.append("dir /b \"").append(verifyJarGlob(layout, archive)).append("\" >nul 2>&1").append("\r\n");
        script.append("if errorlevel 1 goto verifyFailed").append("\r\n");
        script.append("goto success").append("\r\n");
        script.append("\r\n");

        // --- 6. success: drop the backups, clear the marker, start the new version ---
        script.append(":success").append("\r\n");
        script.append("call :purge \"%MARKER%\"").append("\r\n");
        for (String[] target : swapTargets) {
            script.append("if defined ").append(target[2]).append(" call :removeOne \"")
                    .append(target[0]).append("\" \"").append(target[1]).append("\"")
                    .append("\r\n");
        }
        script.append("echo [%DATE% %TIME%] myigv %OLDVER% installed version %NEWVER%>>\"%ERRLOG%\"").append("\r\n");
        script.append("goto restart").append("\r\n");
        script.append("\r\n");

        script.append(":copyFailed").append("\r\n");
        script.append("set \"REASON=copying the new files failed - a file is locked or access is denied\"").append("\r\n");
        script.append("goto rollback").append("\r\n");
        script.append(":verifyFailed").append("\r\n");
        script.append("set \"REASON=the new files are incomplete after copying\"").append("\r\n");
        script.append("goto rollback").append("\r\n");
        script.append("\r\n");

        // --- 7. rollback: restore only the directories this run moved aside ---
        script.append(":rollback").append("\r\n");
        script.append("echo [%DATE% %TIME%] rollback: %REASON%>>\"%ERRLOG%\"").append("\r\n");
        for (int i = 0; i < swapTargets.size(); i++) {
            String[] target = swapTargets.get(i);
            script.append("if not defined ").append(target[2]).append(" goto rollbackNext").append(i + 1).append("\r\n");
            script.append("call :restoreOne \"").append(target[0]).append("\" \"").append(target[1]).append("\"").append("\r\n");
            script.append("if not errorlevel 1 goto rollbackNext").append(i + 1).append("\r\n");
            script.append("set \"RESTOREFAILED=1\"").append("\r\n");
            script.append("echo [%DATE% %TIME%] rollback failed for ").append(target[0]).append(">>\"%ERRLOG%\"").append("\r\n");
            script.append(":rollbackNext").append(i + 1).append("\r\n");
        }
        script.append("call :writeMarker").append("\r\n");
        script.append("goto restart").append("\r\n");
        script.append("\r\n");

        // --- 8. pre-backup failures (extract / wait timeout): nothing to restore ---
        script.append(":waitTimeout").append("\r\n");
        script.append("echo [%DATE% %TIME%] timed out waiting for pid %OLDPID%>>\"%ERRLOG%\"").append("\r\n");
        script.append("set \"REASON=timed out waiting for pid %OLDPID%, the installation was not touched\"").append("\r\n");
        script.append("goto failed").append("\r\n");
        script.append(":failed").append("\r\n");
        script.append("echo [%DATE% %TIME%] update aborted: %REASON%>>\"%ERRLOG%\"").append("\r\n");
        script.append("call :writeMarker").append("\r\n");
        script.append("goto restart").append("\r\n");
        script.append("\r\n");

        // --- 9. restart whatever is on disk now, then clean up ---
        // A missing / incomplete application directory cannot be started: starting it would only
        // produce a crash loop, so the user is asked to restore the backup by hand instead.
        script.append(":restart").append("\r\n");
        script.append("if defined MANUAL goto noRestart").append("\r\n");
        script.append("if not exist \"").append(verifyJarGlob(layout, archive)).append("\" goto noRestart").append("\r\n");
        script.append("if not exist \"%INSTALL%\\myigv.exe\" goto restartPortable").append("\r\n");
        script.append("start \"\" \"%INSTALL%\\myigv.exe\"").append("\r\n");
        script.append("goto cleanup").append("\r\n");
        script.append(":restartPortable").append("\r\n");
        script.append("if not exist \"%INSTALL%\\myigv.bat\" goto cleanup").append("\r\n");
        script.append("start \"\" \"%INSTALL%\\myigv.bat\"").append("\r\n");
        script.append("goto cleanup").append("\r\n");
        script.append(":noRestart").append("\r\n");
        script.append("echo [%DATE% %TIME%] not restarting: the application directory is missing or incomplete>>\"%ERRLOG%\"").append("\r\n");
        script.append("echo myigv update needs manual action, see %MARKER%>>\"%ERRLOG%\"").append("\r\n");
        script.append("goto cleanup").append("\r\n");
        script.append("\r\n");
        script.append(":cleanup").append("\r\n");
        script.append("call :purge \"%STAGE%\"").append("\r\n");
        script.append("call :purge \"%ZIPFILE%\"").append("\r\n");
        script.append(":: terminate this script, then delete it (avoids the 'batch file not found' noise)\r\n");
        script.append("(goto) 2>nul & del \"%~f0\" 2>nul <nul").append("\r\n");
        script.append("\r\n");

        // --- subroutines (only reachable through "call") ---
        script.append(":backupOne").append("\r\n");
        script.append(":: %1 = live directory, %2 = backup name, %3 = flag set once the move happened").append("\r\n");
        script.append("if not exist \"%~1\" exit /b 0").append("\r\n");
        script.append(":: a stale backup from an earlier run would block the rename\r\n");
        script.append("if exist \"%~dp1%~2\" call :purge \"%~dp1%~2\"").append("\r\n");
        script.append("if exist \"%~dp1%~2\" exit /b 1").append("\r\n");
        script.append("ren \"%~1\" \"%~2\"").append("\r\n");
        script.append("if errorlevel 1 exit /b 1").append("\r\n");
        script.append("set \"%~3=1\"").append("\r\n");
        script.append("set \"TOUCHED=1\"").append("\r\n");
        script.append("exit /b 0").append("\r\n");
        script.append("\r\n");
        script.append(":restoreOne").append("\r\n");
        script.append(":: %1 = live directory, %2 = backup name holding the previous content").append("\r\n");
        script.append(":: Assembled into \"<dir>.restore\" and swapped in afterwards: the backup is never").append("\r\n");
        script.append(":: consumed, so a failed restore leaves it complete for manual recovery.").append("\r\n");
        script.append("if not exist \"%~dp1%~2\" exit /b 0").append("\r\n");
        script.append("call :purge \"%~1.restore\"").append("\r\n");
        script.append("robocopy \"%~dp1%~2\" \"%~1.restore\" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP").append("\r\n");
        script.append("if errorlevel 8 goto restoreFailed").append("\r\n");
        script.append("if exist \"%~1\" call :purge \"%~1\"").append("\r\n");
        script.append("if exist \"%~1\" goto restoreFailed").append("\r\n");
        script.append("ren \"%~1.restore\" \"%~nx1\"").append("\r\n");
        script.append("if errorlevel 1 goto restoreFailed").append("\r\n");
        script.append("exit /b 0").append("\r\n");
        script.append(":restoreFailed").append("\r\n");
        script.append("call :purge \"%~1.restore\"").append("\r\n");
        script.append("exit /b 1").append("\r\n");
        script.append("\r\n");
        script.append(":removeOne").append("\r\n");
        script.append(":: %1 = directory, %2 = backup name to delete after a successful update").append("\r\n");
        script.append("if not exist \"%~dp1%~2\" exit /b 0").append("\r\n");
        script.append("call :purge \"%~dp1%~2\"").append("\r\n");
        script.append("exit /b 0").append("\r\n");
        script.append("\r\n");
        script.append(":purge").append("\r\n");
        script.append(":: %1 = file or directory to remove; never prompts (stdin is redirected) and never").append("\r\n");
        script.append(":: fails the caller, so a stuck cleanup cannot hang the helper.").append("\r\n");
        script.append("if not exist \"%~1\" exit /b 0").append("\r\n");
        script.append("rmdir /S /Q \"%~1\" 2>nul <nul").append("\r\n");
        script.append("if exist \"%~1\" del /F /Q \"%~1\" 2>nul <nul").append("\r\n");
        script.append("exit /b 0").append("\r\n");
        script.append("\r\n");
        script.append(":writeMarker").append("\r\n");
        script.append(":: user visible failure report, picked up by the next launch.  Falls back to the").append("\r\n");
        script.append(":: work directory when the installation directory is not writable.").append("\r\n");
        script.append("set \"MANUAL=\"").append("\r\n");
        script.append("if not exist \"").append(verifyJarGlob(layout, archive)).append("\" set \"MANUAL=1\"").append("\r\n");
        script.append("call :writeMarkerTo \"%MARKER%\"").append("\r\n");
        script.append("if exist \"%MARKER%\" goto markerDone").append("\r\n");
        script.append("call :writeMarkerTo \"%PENDING%\"").append("\r\n");
        script.append("if exist \"%PENDING%\" echo [%DATE% %TIME%] marker fallback written to %PENDING%>>\"%ERRLOG%\"").append("\r\n");
        script.append(":markerDone").append("\r\n");
        script.append("exit /b 0").append("\r\n");
        script.append("\r\n");
        script.append(":writeMarkerTo").append("\r\n");
        script.append(":: %1 = marker file to create").append("\r\n");
        script.append("if exist \"%~dp1\" goto markerDir").append("\r\n");
        script.append("mkdir \"%~dp1\" >nul 2>&1 <nul").append("\r\n");
        script.append(":markerDir").append("\r\n");
        script.append("call :purge \"%~1\"").append("\r\n");
        script.append("echo myigv update did not complete.>\"%~1\" 2>nul").append("\r\n");
        script.append("echo.>>\"%~1\" 2>nul").append("\r\n");
        script.append("echo version: %OLDVER% -^> %NEWVER%>>\"%~1\" 2>nul").append("\r\n");
        script.append("echo time: %DATE% %TIME%>>\"%~1\" 2>nul").append("\r\n");
        script.append("echo reason: %REASON%>>\"%~1\" 2>nul").append("\r\n");
        script.append("echo install: %INSTALL%>>\"%~1\" 2>nul").append("\r\n");
        // Exactly one status line: rollback failure dominates, then "we moved and restored",
        // then "we never touched anything".  (Previously two contradictory lines could appear.)
        script.append("if defined RESTOREFAILED echo status: rollback incomplete - the previous version could not be restored>>\"%~1\" 2>nul").append("\r\n");
        script.append("if not defined RESTOREFAILED if defined TOUCHED echo status: the previous version was restored from the backup copy>>\"%~1\" 2>nul").append("\r\n");
        script.append("if not defined TOUCHED echo status: no files were changed - the previous version is still installed>>\"%~1\" 2>nul").append("\r\n");
        for (String[] target : swapTargets) {
            String kept = backupPath(target[0], target[1]);
            script.append("if exist \"").append(kept).append("\" echo backup kept: ")
                    .append(kept).append(">>\"%~1\" 2>nul").append("\r\n");
        }
        String appBackup = backupPath(swapTargets.get(0)[0], swapTargets.get(0)[1]);
        script.append("if defined MANUAL echo manual action required: rename ").append(appBackup)
                .append(" to ").append(fileName(swapTargets.get(0)[0]))
                .append(" and start myigv again>>\"%~1\" 2>nul").append("\r\n");
        script.append("if exist \"%~1\" exit /b 0").append("\r\n");
        script.append("exit /b 1").append("\r\n");
        return script.toString();
    }

    /** Full path of the backup directory: the parent of {@code target} plus {@code backupName}. */
    private static String backupPath(String target, String backupName) {
        int slash = Math.max(target.lastIndexOf('\\'), target.lastIndexOf('/'));
        return slash >= 0 ? target.substring(0, slash + 1) + backupName : backupName;
    }

    /** Glob used to confirm that the application jars really landed in the installation. */
    private static String verifyJarGlob(Layout layout, ArchiveLayout archive) {
        if (layout == Layout.PORTABLE) {
            return "%INSTALL%\\lib\\*.jar";
        }
        String sub = archive.jarDir.equals("app") ? "" : archive.jarDir.substring("app/".length()) + "\\";
        return "%INSTALL%\\app\\" + sub + "*.jar";
    }

    /** Last path element, used in user facing messages written by the helper script. */
    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Append the "wait until the JVM has exited" loop.
     *
     * <p>Deliberately avoids {@code find.exe}: a Git/MSYS installation on PATH can shadow it.
     * The PID from {@code tasklist /FO CSV} is compared explicitly instead.</p>
     *
     * <p>On timeout the loop jumps to {@code :waitTimeout} in the caller: the installation must
     * never be touched while the application is still running.</p>
     */
    private static void appendWaitLoop(StringBuilder script) {
        script.append(":wait").append("\r\n");
        script.append("set /a TRIES+=1").append("\r\n");
        script.append("if %TRIES% GEQ ").append(MAX_WAIT_ITERATIONS).append(" goto waitTimeout").append("\r\n");
        script.append("set \"ALIVE=\"").append("\r\n");
        script.append("for /f \"tokens=1,2 delims=,\" %%A in ('%SystemRoot%\\System32\\tasklist.exe /FI \"PID eq %OLDPID%\" /NH /FO CSV 2^>nul') do if \"%%~B\"==\"%OLDPID%\" set \"ALIVE=1\"").append("\r\n");
        script.append("if not defined ALIVE goto ready").append("\r\n");
        script.append("timeout /t 1 /nobreak >nul 2>&1").append("\r\n");
        script.append("if errorlevel 1 ping -n 2 127.0.0.1 >nul").append("\r\n");
        script.append("goto wait").append("\r\n");
        script.append(":ready").append("\r\n");
    }

    /** Fallback path: run the Windows installer and let the user confirm the target directory. */
    private static String buildExeScript(File installer, File workDir) {
        StringBuilder script = new StringBuilder(2048);
        script.append("@echo off").append("\r\n");
        script.append(":: Generated by myigv ").append(AppVersion.current())
                .append(" - running the downloaded installer").append("\r\n");
        script.append("setlocal").append("\r\n");
        script.append("set \"OLDPID=").append(ProcessHandle.current().pid()).append("\"\r\n");
        script.append("set \"ERRLOG=%TEMP%\\myigv-update\\apply-error.log\"").append("\r\n");
        script.append("set /a TRIES=0").append("\r\n");
        appendWaitLoop(script);
        script.append("start \"\" \"").append(installer.getAbsolutePath()).append("\"").append("\r\n");
        script.append("goto done").append("\r\n");
        script.append(":waitTimeout").append("\r\n");
        // Running an installer over a live installation would fail half way; do not even try.
        script.append("echo [%DATE% %TIME%] timed out waiting for pid %OLDPID%, installer not started>>\"%ERRLOG%\"").append("\r\n");
        script.append(":done").append("\r\n");
        script.append("(goto) 2>nul & del \"%~f0\" 2>nul <nul").append("\r\n");
        return script.toString();
    }

    private static File writeApplyScript(File workDir, String script) throws IOException {
        Files.createDirectories(workDir.toPath());
        File scriptFile = new File(workDir, APPLY_SCRIPT_NAME);
        // cmd.exe reads batch files with the OEM code page, which is what native.encoding reports
        // (JDK 18+ defaults file.encoding to UTF-8, which would garble non-ASCII install paths).
        Charset charset = nativeCharset();
        Files.write(scriptFile.toPath(), script.getBytes(charset));
        return scriptFile;
    }

    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null && !name.isBlank()) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException e) {
                log.warn("Unsupported native encoding " + name + ", falling back to the default one");
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * Read the archive's entry names to learn the folder layout and the application jar name.
     *
     * <p>A {@code jpackage --type app-image} build looks like this (verified against a real
     * 1.0.4 app-image):</p>
     * <pre>
     *   myigv/myigv.exe
     *   myigv/app/myigv.cfg
     *   myigv/app/mods/*.jar        &lt;- modular app, jars live in mods\
     *   myigv/runtime/...
     *   myigv/app-content/pack/...  &lt;- offline annotation pack
     * </pre>
     * A non-modular app keeps its jars directly in {@code app/}.
     */
    static ArchiveLayout inspectArchive(File zipFile) {
        ArchiveLayout layout = new ArchiveLayout();
        List<String> jarsInApp = new ArrayList<>();
        List<String> jarsInMods = new ArrayList<>();
        List<String> cfgFiles = new ArrayList<>();
        String root = null;
        boolean hasPackAtRoot = false;
        boolean hasPackInApp = false;
        boolean hasPackInAppContent = false;

        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                if (root == null) {
                    root = rootPrefix(name);
                }
                if (!name.startsWith(root)) {
                    continue;
                }
                String relative = name.substring(root.length());

                if (relative.startsWith("app/mods/")) {
                    layout.hasApp = true;
                    layout.jarDir = "app/mods";
                    String file = relative.substring("app/mods/".length());
                    if (file.indexOf('/') < 0 && file.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        jarsInMods.add(file);
                    }
                } else if (relative.startsWith("app/")) {
                    layout.hasApp = true;
                    String file = relative.substring("app/".length());
                    if (file.indexOf('/') < 0) {
                        String lower = file.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".jar")) {
                            jarsInApp.add(file);
                        } else if (lower.endsWith(".cfg")) {
                            cfgFiles.add(file);
                        }
                    }
                    if (relative.startsWith("app/pack/")) {
                        hasPackInApp = true;
                    }
                } else if (relative.startsWith("runtime/")) {
                    layout.hasRuntime = true;
                } else if (relative.startsWith("pack/")) {
                    hasPackAtRoot = true;
                } else if (relative.startsWith("app-content/pack/")) {
                    hasPackInAppContent = true;
                }
            }

            layout.root = root == null ? "" : root;

            // The launcher config names the application jar explicitly (non-modular builds).
            for (String cfg : cfgFiles) {
                String mainJar = readMainJarFromConfig(zip, layout.root + "app/" + cfg);
                if (mainJar != null && jarsInApp.contains(mainJar)) {
                    layout.appJar = mainJar;
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot inspect the update archive " + zipFile + ": " + e.getMessage());
            return null;
        }

        if (layout.appJar.isEmpty()) {
            layout.appJar = guessAppJar(jarsInMods.isEmpty() ? jarsInApp : jarsInMods);
        }
        if (hasPackInAppContent) {
            layout.packRel = "app-content/pack";
        } else if (hasPackInApp) {
            layout.packRel = "app/pack";
        } else if (hasPackAtRoot) {
            layout.packRel = "pack";
        }
        return layout;
    }

    /**
     * The archive holds a single top-level folder (normally {@code myigv/}); unfold zips that
     * put the app-image content at the root.
     */
    private static String rootPrefix(String firstEntryName) {
        int slash = firstEntryName.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String first = firstEntryName.substring(0, slash);
        String lower = first.toLowerCase(Locale.ROOT);
        if (lower.equals("app") || lower.equals("app-content") || lower.equals("runtime")
                || lower.equals("pack") || lower.endsWith(".exe")) {
            return "";
        }
        return first + "/";
    }

    /** Parse {@code app.classpath=} / {@code app.mainjar=} from a jpackage launcher config. */
    private static String readMainJarFromConfig(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            String candidate = null;
            for (String line : text.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("app.mainjar=")) {
                    candidate = jarName(trimmed.substring("app.mainjar=".length()));
                } else if (trimmed.startsWith("app.classpath=")) {
                    // jpackage lists the application jar last; dependency jars come first.
                    for (String token : trimmed.substring("app.classpath=".length()).split("[;:]")) {
                        String jar = jarName(token);
                        if (jar != null) {
                            candidate = jar;
                        }
                    }
                }
                if (candidate != null) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read " + entryName + ": " + e.getMessage());
        }
        return null;
    }

    private static String jarName(String token) {
        String value = token.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value.toLowerCase(Locale.ROOT).endsWith(".jar") ? value : null;
    }

    /** Best effort when there is no launcher config to read. */
    private static String guessAppJar(List<String> appJars) {
        for (String preferred : new String[]{"myigv.jar", "org.igv.jar", "IGV.jar"}) {
            for (String jar : appJars) {
                if (jar.equalsIgnoreCase(preferred)) {
                    return jar;
                }
            }
        }
        for (String jar : appJars) {
            if (jar.toLowerCase(Locale.ROOT).contains("igv")) {
                return jar;
            }
        }
        return appJars.isEmpty() ? "" : appJars.get(0);
    }

    private static String joinWindows(String base, String child) {
        String cleaned = child.replace('/', '\\');
        if (base.endsWith("\\")) {
            return base + cleaned;
        }
        return base + "\\" + cleaned;
    }

    // ------------------------------------------------------------------------------------
    // Launching the helper / errors
    // ------------------------------------------------------------------------------------

    /**
     * Start the batch helper detached from this JVM, then close the application so the helper can
     * replace the files.
     */
    private static void launchHelper(File scriptFile, File workDir) {
        try {
            new ProcessBuilder("cmd", "/c", "start", "/b", "", scriptFile.getAbsolutePath())
                    .directory(workDir)
                    .start();
        } catch (IOException e) {
            log.error("Cannot start the update helper", e);
            MessageUtils.showErrorMessage("无法启动更新程序：" + e.getMessage()
                    + "<br><br>请访问 https://github.com/junjunlab/myigv/releases 手动下载。", e);
            return;
        }

        // The watchdog MUST be armed before the window is closed.  Closing the main frame runs
        // System.exit -> the shutdown hooks (session autosave, prefs flush), which can block for
        // an unbounded time; dispatchEvent only returns after that finished, so a watchdog
        // started afterwards would never get to run.
        armExitGuardAndClose();
    }

    /**
     * Arm the hard exit guard, then close the main window.
     *
     * <p>Public only so the exit-race probe can exercise it against a slow shutdown hook; it is
     * not part of the updater API.</p>
     */
    public static void armExitGuardAndClose() {

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(EXIT_WATCHDOG_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            appendTempLog("watchdog: forced halt after " + (EXIT_WATCHDOG_MS / 1000) + "s");
            Runtime.getRuntime().halt(0);
        }, "myigv-update-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        SwingUtilities.invokeLater(() -> {
            try {
                if (IGV.hasInstance()) {
                    Frame frame = IGV.getInstance().getMainFrame();
                    if (frame instanceof Window) {
                        ((Window) frame).dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                    }
                }
            } catch (Throwable t) {
                log.warn("Could not close the main window cleanly: " + t.getMessage());
            }
            // If we get here the close did not terminate the JVM; the watchdog is the backstop.
        });
    }

    /**
     * Append one line to {@code %TEMP%\myigv-update\apply-error.log}, the same log the helper
     * script writes to, so a support case can see the whole hand-over in one place.
     */
    static void appendTempLog(String message) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir", "."), "myigv-update");
            Files.createDirectories(dir.toPath());
            File logFile = new File(dir, "apply-error.log");
            String line = "[" + LocalDateTime.now().withNano(0) + "] " + message + System.lineSeparator();
            Files.writeString(logFile.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            log.warn("Cannot write the update log: " + t.getMessage());
        }
    }

    private static void openReleasePage(UpdateInfo info, Frame owner, File installDir) {
        String url = info != null && info.htmlUrl != null && !info.htmlUrl.isBlank()
                ? info.htmlUrl : "https://github.com/junjunlab/myigv/releases";
        String reason = installDir == null
                ? "无法定位 myIGV 的安装目录。"
                : "无法写入安装目录 " + installDir.getAbsolutePath() + "（可能需要管理员权限）。";
        MessageUtils.showMessage(reason + "<br>请你手动下载并安装新版本：" + url);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URL(url).toURI());
            }
        } catch (Exception e) {
            log.warn("Cannot open " + url + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------
    // Progress dialog
    // ------------------------------------------------------------------------------------

    /** Minimal modal progress window: a label and a bar, no buttons. */
    private static class UpdateProgressDialog extends JDialog {

        private final JProgressBar bar;

        UpdateProgressDialog(Frame owner, UpdateInfo info) {
            super(owner, "正在下载 myIGV " + (info == null ? "" : info.version) + " 更新", true);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(Color.WHITE);
            content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

            JLabel label = new JLabel("正在下载更新，请稍候…");
            label.setFont(label.getFont().deriveFont(12f));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(label);
            content.add(Box.createVerticalStrut(10));

            bar = new JProgressBar(0, 100);
            bar.setValue(0);
            bar.setIndeterminate(true);
            bar.setStringPainted(false);
            bar.setAlignmentX(Component.LEFT_ALIGNMENT);
            bar.setMaximumSize(new Dimension(320, 22));
            bar.setPreferredSize(new Dimension(320, 22));
            content.add(bar);

            setContentPane(content);
            pack();
            setResizable(false);
            setLocationRelativeTo(owner);
        }

        void setPercent(int percent) {
            if (percent < 0) {
                return;
            }
            if (bar.isIndeterminate()) {
                bar.setIndeterminate(false);
            }
            bar.setValue(Math.max(0, Math.min(100, percent)));
        }
    }
}
