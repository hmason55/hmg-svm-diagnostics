package gen5w.svm.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

final class ExportServer implements AutoCloseable {
    static final long LIFETIME_MS = 5 * 60 * 1000;
    private static final int MAX_REQUEST = 8192;
    private static final long CLIENT_HEADER_MS = 3000;

    interface Clock { long now(); }

    private final ServerSocket listener;
    private final File archive;
    private final Clock clock;
    private final Timer timer;
    private final long expires;
    private final String path;
    private Socket active;
    private boolean closed;

    ExportServer(InetAddress address, File archive, Clock clock, long lifetimeMs) throws IOException {
        this.archive = archive;
        this.clock = clock;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder key = new StringBuilder(random.length * 2);
        for (byte value : random) key.append(String.format(Locale.US, "%02x", value & 255));
        path = "/" + key + "/session.zip";
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(address, 0), 2);
            listener.setSoTimeout(500);
        } catch (IOException e) {
            listener.close();
            throw e;
        }
        expires = clock.now() + lifetimeMs;
        timer = new Timer("export-expiry", true);
        timer.schedule(new TimerTask() {
            @Override public void run() { close(); }
        }, lifetimeMs);
    }

    String url() {
        return "http://" + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort() + path;
    }

    boolean accepts(String requestedPath) {
        return clock.now() < expires && MessageDigest.isEqual(
                path.getBytes(SessionFiles.UTF8), requestedPath.getBytes(SessionFiles.UTF8));
    }

    void serve() {
        try {
            while (!listener.isClosed() && clock.now() < expires) {
                Socket client;
                try {
                    client = listener.accept();
                } catch (SocketTimeoutException idle) {
                    continue;
                }
                try {
                    synchronized (this) {
                        if (closed) return;
                        active = client;
                    }
                    respond(client);
                } catch (IOException ignored) {
                    // A slow or disconnected client cannot extend the export window.
                } finally {
                    client.close();
                    synchronized (this) { active = null; }
                }
            }
        } catch (IOException ignored) {
            // Closing the listener also releases a blocked accept.
        } finally {
            close();
        }
    }

    private void respond(Socket client) throws IOException {
        String request = readRequest(client);
        OutputStream output = client.getOutputStream();
        if (request == null || !request.matches("GET /[^ ]+ HTTP/1\\.[01]")
                || !accepts(request.split(" ", 3)[1])) {
            output.write(("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(SessionFiles.UTF8));
            return;
        }
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename=\"svm-diagnostics-session.zip\"\r\n"
                + "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
                + "X-Content-Type-Options: nosniff\r\nConnection: close\r\nContent-Length: "
                + archive.length() + "\r\n\r\n").getBytes(SessionFiles.UTF8));

        FileInputStream input = new FileInputStream(archive);
        try {
            byte[] block = new byte[8192];
            int count;
            while (clock.now() < expires && (count = input.read(block)) != -1) {
                output.write(block, 0, count);
            }
            output.flush();
        } finally {
            input.close();
        }
    }

    private String readRequest(Socket client) throws IOException {
        long deadline = Math.min(expires, clock.now() + CLIENT_HEADER_MS);
        InputStream input = client.getInputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        String first = null;
        for (int i = 0; i < MAX_REQUEST; i++) {
            long timeLeft = deadline - clock.now();
            if (timeLeft <= 0) return null;
            client.setSoTimeout((int) timeLeft);
            int value = input.read();
            if (value == -1) return null;
            if (value == '\n') {
                String parsed = line.toString("US-ASCII");
                if (!parsed.endsWith("\r")) return null;
                parsed = parsed.substring(0, parsed.length() - 1);
                if (first == null) first = parsed;
                else if (parsed.isEmpty()) return first;
                line.reset();
            } else {
                if (value != '\r' && value != '\t' && (value < 32 || value > 126)) return null;
                line.write(value);
            }
        }
        return null;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        timer.cancel();
        try { listener.close(); } catch (IOException ignored) {}
        if (active != null) {
            try { active.close(); } catch (IOException ignored) {}
        }
    }
}
