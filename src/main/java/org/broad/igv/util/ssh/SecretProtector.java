package org.broad.igv.util.ssh;

import com.sun.jna.platform.win32.Crypt32Util;
import org.broad.igv.logging.LogManager;
import org.broad.igv.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Wraps saved passwords with the operating system's user-bound secret store.
 * <p>
 * Windows uses DPAPI: the ciphertext can only be decrypted by the same Windows user on the
 * same machine, and it travels inside {@code ssh-servers.json}. macOS uses the login keychain
 * through {@code /usr/bin/security}; the JSON then only holds a {@code keychain} marker and
 * the password itself never leaves the keychain. Other platforms report
 * {@link #isAvailable()} false, so only the non-sensitive profile fields are remembered.
 */
public final class SecretProtector {

    private static final Logger log = LogManager.getLogger(SecretProtector.class);

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");

    private static final String SECURITY = "/usr/bin/security";
    private static final String SERVICE = "myigv-ssh";

    /** Marker stored in the JSON on macOS; the password lives in the keychain instead. */
    private static final String KEYCHAIN_MARK = "keychain";

    private SecretProtector() {
    }

    public static boolean isAvailable() {
        return WINDOWS || MAC;
    }

    /**
     * @param key stable identity of the profile, {@code user@host:port}; used as the keychain
     *            account so several servers can be stored side by side
     * @return DPAPI base64 on Windows, {@code keychain} on macOS, null when unsupported
     */
    public static String protect(String key, String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        if (WINDOWS) {
            try {
                byte[] encrypted = Crypt32Util.cryptProtectData(plain.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(encrypted);
            } catch (Throwable t) {
                log.error("DPAPI encryption failed", t);
                return null;
            }
        }
        if (MAC) {
            return keychainStore(key, plain) ? KEYCHAIN_MARK : null;
        }
        return null;
    }

    /** Returns the plaintext, or null when the blob is missing or cannot be unlocked. */
    public static String unprotect(String key, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        if (WINDOWS) {
            try {
                byte[] decrypted = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encoded));
                return new String(decrypted, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                log.error("DPAPI decryption failed", t);
                return null;
            }
        }
        if (MAC) {
            return KEYCHAIN_MARK.equals(encoded) ? keychainFind(key) : null;
        }
        return null;
    }

    private static boolean keychainStore(String key, String plain) {
        // Replace any previous entry for this server; a failed delete is expected on first use.
        run(List.of(SECURITY, "delete-generic-password", "-a", key, "-s", SERVICE));
        // The password is passed as an argument, which is briefly visible to the same user's
        // process list; "security" offers no stdin form. Acceptable for a desktop tool, and it
        // never reaches the JSON file.
        return run(List.of(SECURITY, "add-generic-password",
                "-a", key, "-s", SERVICE, "-w", plain)) != null;
    }

    private static String keychainFind(String key) {
        return run(List.of(SECURITY, "find-generic-password",
                "-a", key, "-s", SERVICE, "-w"));
    }

    private static String run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            log.error("security command failed: " + String.join(" ", command.subList(0, 3)), e);
            return null;
        }
    }
}
