package gen5w.svm.diagnostics;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void enforcesCancellationDurationAndSizeLimits() {
        long free = SessionPolicy.MIN_FREE_BYTES;
        assertNull(SessionPolicy.stopReason(false, SessionPolicy.DURATION_MS - 1, 0, free));
        assertEquals("duration_limit", SessionPolicy.stopReason(false, SessionPolicy.DURATION_MS, 0, free));
        assertEquals("stopped_by_user", SessionPolicy.stopReason(true, 10, 0, free));
        assertEquals("low_available_storage", SessionPolicy.stopReason(false, 10, 0, free - 1));
        long maximum = SessionPolicy.MAX_BYTES - SessionPolicy.FINALIZATION_BYTES;
        assertTrue(SessionPolicy.permitsAppend(maximum - 32, 32));
        assertFalse(SessionPolicy.permitsAppend(maximum - 32, 33));
        assertEquals("session_storage_limit", SessionPolicy.stopReason(false, 10, maximum, free));
    }

    @Test public void reopensInterruptedSessionsAndSkipsPartialRecord() throws Exception {
        File root = temporary.newFolder();
        SessionFiles files = SessionFiles.create(root, 1000);
        files.atomic("metadata.json", "{\"wallStartMs\":1000}");
        files.atomic("summary.txt", "Killed just before commit marker");
        Files.write(new File(files.directory, "samples.jsonl").toPath(), (
                "{\"elapsedMs\":2000}\n{\"elapsedMs\":4000}\n{\"elapsedMs\":").getBytes("UTF-8"));
        SessionFiles reopened = SessionFiles.list(root).get(0);
        assertFalse(reopened.complete());
        assertEquals(2, reopened.recover("samples.jsonl", 100).length());
        assertEquals(4000, reopened.recover("samples.jsonl", 100).getJSONObject(1).getLong("elapsedMs"));
        try {
            reopened.zip(new File(root, "partial.zip"));
            fail("Interrupted sessions must not be exported");
        } catch (IOException expected) {
            assertEquals("session_incomplete", expected.getMessage());
        }
        assertTrue(reopened.delete());
    }

    @Test public void completeArchiveContainsOnlySessionData() throws Exception {
        SessionFiles files = SessionFiles.create(temporary.newFolder(), 1001);
        files.atomic("metadata.json", "{}");
        files.atomic("inventory.json", "{}");
        files.append("samples.jsonl", new JSONArray().put(Observations.json("elapsedMs", 2000)));
        Files.write(new File(files.directory, "unexpected.txt").toPath(), new byte[] {1});
        new SessionReport().finish(files, Observations.json(), "stopped_by_user", 1001, 2000, 2, 0);
        File archive = new File(temporary.newFolder(), "export.zip");
        files.zip(archive);
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        }
        assertEquals(5, names.size());
        assertTrue(names.contains("summary.txt"));
        assertTrue(names.contains("summary.json"));
        assertTrue(names.contains("samples.jsonl"));
        assertFalse(names.contains("unexpected.txt"));
        assertFalse(files.delete());
    }

    private static String request(URL url, String method, String target) throws Exception {
        try (Socket client = new Socket(url.getHost(), url.getPort())) {
            client.setSoTimeout(3000);
            client.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes("US-ASCII"));
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] block = new byte[512];
            int count;
            while ((count = client.getInputStream().read(block)) != -1) response.write(block, 0, count);
            return response.toString("US-ASCII");
        }
    }

    @Test public void exportAuthenticatesOnePathAndExpires() throws Exception {
        File fakeArchive = temporary.newFile();
        Files.write(fakeArchive.toPath(), "archive-body".getBytes("US-ASCII"));
        final AtomicLong time = new AtomicLong();
        try (final ExportServer server = new ExportServer(InetAddress.getByName("127.0.0.1"),
                fakeArchive, new ExportServer.Clock() {
                    @Override public long now() { return time.get(); }
                }, 15000)) {
            Thread worker = new Thread(new Runnable() {
                @Override public void run() { server.serve(); }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                URL url = new URL(server.url());
                String path = url.getPath();
                assertTrue(path.matches("/[0-9a-f]{32}/session\\.zip"));
                assertTrue(request(url, "GET", path).endsWith("archive-body"));
                for (String denied : new String[] {"/abc/session.zip", path + "?token=x", path + "/../",
                        path.replace("session.zip", "../inventory.json"), path.replace("session.zip", "%2e%2e/file")}) {
                    assertTrue(request(url, "GET", denied).startsWith("HTTP/1.1 404"));
                }
                assertTrue(request(url, "POST", path).startsWith("HTTP/1.1 404"));
                assertTrue(server.accepts(path));
                time.set(15000);
                assertFalse(server.accepts(path));
            } finally {
                server.close();
                worker.join(3000);
                assertFalse(worker.isAlive());
            }
        }
    }
}
