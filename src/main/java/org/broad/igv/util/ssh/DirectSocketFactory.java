package org.broad.igv.util.ssh;

import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/**
 * Creates SSH sockets that bypass the JVM's proxy configuration.
 * <p>
 * myigv starts with {@code -Djava.net.useSystemProxies=true} so IGV can download genomes and
 * annotations through the user's HTTP proxy. That flag also makes plain {@link Socket}
 * construction consult the system {@code ProxySelector}, so when a proxy/VPN is enabled JSch
 * tries to push the SSH session through an HTTP/SOCKS proxy which does not relay SSH to a
 * custom port (e.g. 31631). The server then drops the connection during the handshake with
 * {@code "connection is closed by foreign host"}.
 * <p>
 * {@link Proxy#NO_PROXY} forces a direct TCP connection. It bypasses the JVM proxy selector
 * only — it does not bypass routing, so a VPN/TUN adapter is still used when the target needs
 * it. Set {@code -Dmyigv.ssh.useSystemProxy=true} to opt back into proxied SSH connections.
 */
public class DirectSocketFactory implements SocketFactory {

    private final int timeoutMs;

    public DirectSocketFactory(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the connect failure is what matters
            }
            throw e;
        }
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }
}
