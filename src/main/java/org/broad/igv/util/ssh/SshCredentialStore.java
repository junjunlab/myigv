package org.broad.igv.util.ssh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.broad.igv.DirectoryManager;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers SSH connection profiles in {@code ssh-servers.json} inside the myigv data
 * directory. Passwords go through {@link SecretProtector} before they are written, so the file
 * never contains a plaintext credential; on platforms without an OS key store only the
 * non-sensitive fields (host, port, user, key path) are kept.
 */
public final class SshCredentialStore {

    private static final Logger log = LogManager.getLogger(SshCredentialStore.class);

    private static final String FILE_NAME = "ssh-servers.json";
    private static final int MAX_ENTRIES = 20;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class Wrapper {
        List<SshProfile> servers = new ArrayList<>();
    }

    private SshCredentialStore() {
    }

    public static synchronized List<SshProfile> load() {
        File file = file();
        if (!file.isFile()) {
            return new ArrayList<>();
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Wrapper wrapper = GSON.fromJson(json, Wrapper.class);
            if (wrapper == null || wrapper.servers == null) {
                return new ArrayList<>();
            }
            wrapper.servers.removeIf(p -> p == null || p.host == null || p.user == null);
            wrapper.servers.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
            return wrapper.servers;
        } catch (Exception e) {
            log.error("Cannot read " + file, e);
            return new ArrayList<>();
        }
    }

    /**
     * Adds or replaces the profile for host/port/user.
     *
     * @param password plain password to remember, or null to store the profile without one
     */
    public static synchronized boolean save(SshProfile profile, String password) {
        if (profile == null || profile.host == null || profile.user == null) {
            return false;
        }
        profile.lastUsed = System.currentTimeMillis();
        profile.secret = SecretProtector.protect(keyOf(profile), password);

        List<SshProfile> all = load();
        all.removeIf(p -> sameServer(p, profile));
        all.add(0, profile);
        if (all.size() > MAX_ENTRIES) {
            all = new ArrayList<>(all.subList(0, MAX_ENTRIES));
        }

        Wrapper wrapper = new Wrapper();
        wrapper.servers = all;
        try {
            File file = file();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            log.error("Cannot write " + file(), e);
            return false;
        }
    }

    public static synchronized SshProfile mostRecent() {
        List<SshProfile> all = load();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Stable identity of a profile, used as the keychain account and for de-duplication. */
    public static String keyOf(SshProfile profile) {
        return profile.user + "@" + profile.host + ":" + profile.port;
    }

    private static boolean sameServer(SshProfile a, SshProfile b) {
        return a.port == b.port && eq(a.host, b.host) && eq(a.user, b.user);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static File file() {
        try {
            return new File(DirectoryManager.getIgvDirectory(), FILE_NAME);
        } catch (Exception e) {
            return new File(System.getProperty("user.home"), "." + FILE_NAME);
        }
    }
}
