package org.broad.igv.local;

import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves genome and annotation resources from a local resource pack shipped with myigv, so that genome
 * descriptions and annotation tracks do not have to be downloaded each time the application starts.
 * <p>
 * The pack is a directory containing a "manifest.tsv" file with one entry per bundled resource, holding the
 * original URL, a tab, and the path of the local copy relative to the pack root. Only URLs listed in the
 * manifest are served locally; everything else, in particular the reference sequence (fasta / twoBit),
 * continues to be fetched over the network exactly as before.
 * <p>
 * Lookups happen at the I/O boundary (ParsingUtils, HttpUtils, IGVSeekableStreamFactory) rather than by
 * rewriting resource paths, so track and session files keep recording the original URLs and stay portable.
 */
public class LocalResourcePack {

    private static Logger log = LogManager.getLogger(LocalResourcePack.class);

    /** System property naming the pack directory, overrides all other locations. */
    public static final String PACK_PROPERTY = "myigv.pack";

    /** Environment variable naming the pack directory, used when the system property is not set. */
    public static final String PACK_ENV = "MYIGV_PACK";

    private static final String MANIFEST_NAME = "manifest.tsv";

    private static volatile LocalResourcePack instance;
    private static volatile boolean initialized = false;

    private final File root;

    /** Original URL to local file. */
    private final Map<String, File> entries;

    private LocalResourcePack(File root, Map<String, File> entries) {
        this.root = root;
        this.entries = entries;
    }

    /**
     * Return the pack, or null if none is installed.  A missing pack is not an error: the application then behaves
     * exactly as stock IGV and resolves everything over the network.
     */
    public static LocalResourcePack getInstance() {
        if (!initialized) {
            synchronized (LocalResourcePack.class) {
                if (!initialized) {
                    instance = load();
                    initialized = true;
                }
            }
        }
        return instance;
    }

    public static boolean isInstalled() {
        LocalResourcePack pack = getInstance();
        return pack != null && !pack.entries.isEmpty();
    }

    /**
     * Return the local copy of the resource identified by the URL, or null if it is not bundled.
     */
    public static File lookup(String url) {
        LocalResourcePack pack = getInstance();
        return pack == null ? null : pack.lookupEntry(url);
    }

    /**
     * Return a local path for the resource if it is bundled, otherwise the supplied path unchanged.  The result is
     * suitable for any code that distinguishes remote from local resources by URL scheme.
     */
    public static String mapPath(String path) {
        if (path == null) {
            return null;
        }
        File local = lookup(path);
        return local == null ? path : local.getAbsolutePath();
    }

    /**
     * Return the size of the bundled copy, or -1 if the resource is not bundled.
     */
    public static long size(String url) {
        File local = lookup(url);
        return local == null ? -1 : local.length();
    }

    private File lookupEntry(String url) {
        if (url == null) {
            return null;
        }
        File file = entries.get(url.trim());
        if (file == null) {
            file = entries.get(normalize(url));
        }
        return file;
    }

    private static LocalResourcePack load() {

        File root = findPackRoot();
        if (root == null) {
            return null;
        }

        File manifest = new File(root, MANIFEST_NAME);
        if (!manifest.isFile()) {
            log.info("Local resource pack directory found without a manifest, ignoring: " + root.getAbsolutePath());
            return null;
        }

        Map<String, File> entries = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 1) {
                    continue;
                }
                String url = line.substring(0, tab).trim();
                String relative = line.substring(tab + 1).trim();
                File file = new File(root, relative);
                if (!file.isFile()) {
                    log.warn("Manifest entry has no local file, ignoring: " + url);
                    continue;
                }
                entries.put(url, file);
                entries.putIfAbsent(normalize(url), file);
            }
        } catch (IOException e) {
            log.error("Error reading local resource pack manifest: " + manifest.getAbsolutePath(), e);
            return null;
        }

        log.info("Local resource pack: " + root.getAbsolutePath() + "  (" + entries.size() + " entries)");
        return new LocalResourcePack(root, Collections.unmodifiableMap(entries));
    }

    /**
     * Locate the pack directory.  Order: system property, environment variable, "pack" beside the jar or its
     * parent, then "myigv/pack" under the user home.
     */
    private static File findPackRoot() {

        String configured = System.getProperty(PACK_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(PACK_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            File dir = new File(configured);
            return dir.isDirectory() ? dir : null;
        }

        File jarDir = getJarDirectory();
        if (jarDir != null) {
            // Handle the layout of both the plain distribution and a jpackage app-image / installed app:
            //   plain dist:     <dist>/lib/myigv.jar           + <dist>/pack
            //   jpackage:       <app>/mods/myigv.jar           + <app>/pack, <root>/pack or <root>/app-content/pack
            final File appDir = jarDir.getParentFile();          // <dist> or <app>
            final File rootDir = appDir != null ? appDir.getParentFile() : null;  // <root> (jpackage)

            final File[] candidates = {
                    new File(jarDir, "pack"),                                  // pack beside the jars
                    appDir != null ? new File(appDir, "pack") : null,          // <dist>/pack or <app>/pack
                    rootDir != null ? new File(rootDir, "pack") : null,        // <root>/pack
                    rootDir != null ? new File(rootDir, "app-content/pack") : null, // jpackage --app-content
                    appDir != null ? new File(appDir, "app-content/pack") : null    // macOS variant
            };
            for (File dir : candidates) {
                if (dir != null && dir.isDirectory()) {
                    return dir;
                }
            }
        }

        File userDir = new File(new File(System.getProperty("user.home"), "myigv"), "pack");
        return userDir.isDirectory() ? userDir : null;
    }

    private static File getJarDirectory() {
        try {
            URL location = LocalResourcePack.class.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(location.toURI());
            return file.isFile() ? file.getParentFile() : file;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Loose key for a URL: scheme and host lower case, default ports removed, and trailing slash trimmed, so that
     * minor spelling differences between a bundled URL and the one in use still match.
     */
    private static String normalize(String url) {
        String key = url.trim();
        try {
            URL parsed = new URL(key);
            String protocol = parsed.getProtocol().toLowerCase(Locale.ROOT);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            int port = parsed.getPort();
            boolean defaultPort = port == -1 || (port == 80 && "http".equals(protocol)) || (port == 443 && "https".equals(protocol));
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            String query = parsed.getQuery();
            key = protocol + "://" + host + (defaultPort ? "" : ":" + port) + path + (query == null ? "" : "?" + query);
        } catch (Exception e) {
            // Not a URL, fall through with the trimmed string.
        }
        while (key.length() > 1 && key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }
}
