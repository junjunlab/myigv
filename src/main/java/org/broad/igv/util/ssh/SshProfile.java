package org.broad.igv.util.ssh;

/**
 * One remembered SSH server. Only {@code secret} is sensitive; it holds DPAPI ciphertext
 * produced by {@link SecretProtector}, never a plain password.
 */
public class SshProfile {
    public String host;
    public int port = 22;
    public String user;
    public String keyPath;
    public String secret;
    public long lastUsed;
}
