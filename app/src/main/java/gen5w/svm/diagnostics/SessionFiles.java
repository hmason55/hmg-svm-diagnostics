package gen5w.svm.diagnostics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SessionFiles {
    static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] MEMBERS = {
        "metadata.json", "inventory.json", "samples.jsonl", "camera-events.jsonl", "errors.jsonl",
        "summary.txt", "summary.json"
    };
    final File directory;
    final String id;
    private long used;

    static SessionFiles create(File root, long wallMs) throws IOException {
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory()) {
            throw new IOException("session_root_unavailable");
        }
        String name = "session-" + wallMs + "-" + UUID.randomUUID().toString().substring(0, 8);
        File directory = new File(root, name);
        if (!directory.mkdir()) throw new IOException("session_directory_unavailable");
        return new SessionFiles(directory);
    }

    SessionFiles(File directory) {
        if (!directory.getName().matches("session-\\d{1,16}-[0-9a-f]{8}")) {
            throw new IllegalArgumentException("Invalid session directory");
        }
        this.directory = directory;
        id = directory.getName();
        for (String member : MEMBERS) used += new File(directory, member).length();
    }

    static List<SessionFiles> list(File root) {
        File[] entries = root.listFiles();
        List<SessionFiles> sessions = new ArrayList<SessionFiles>();
        if (entries != null) {
            Arrays.sort(entries, Collections.reverseOrder());
            for (File entry : entries) {
                if (entry.isDirectory() && entry.getName().matches("session-\\d{1,16}-[0-9a-f]{8}")) {
                    sessions.add(new SessionFiles(entry));
                }
            }
        }
        return sessions;
    }

    boolean complete() {
        JSONObject summary = readObject("summary.json");
        return id.equals(summary.optString("sessionId")) && summary.optBoolean("completed")
                && new File(directory, "summary.txt").isFile();
    }

    long used() { return used; }

    void atomic(String member, String contents) throws IOException {
        File target = member(member);
        byte[] bytes = contents.getBytes(UTF8);
        if (bytes.length > SessionPolicy.MAX_BYTES - used) throw new IOException("session_storage_limit");
        File temporary = new File(directory, member + ".tmp");
        FileOutputStream stream = new FileOutputStream(temporary);
        try {
            stream.write(bytes);
            stream.getFD().sync();
        } finally {
            stream.close();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("atomic_commit_failed");
        }
        used += bytes.length;
    }

    void append(String member, JSONArray observations) throws IOException {
        if (observations.length() == 0) return;
        File target = member(member);
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        for (int i = 0; i < observations.length(); i++) {
            byte[] line = (observations.optJSONObject(i).toString() + "\n").getBytes(UTF8);
            batch.write(line, 0, line.length);
        }
        if (!SessionPolicy.permitsAppend(used, batch.size())) throw new IOException("session_storage_limit");
        FileOutputStream stream = new FileOutputStream(target, true);
        try {
            batch.writeTo(stream);
            used += batch.size();
        } finally {
            stream.close();
        }
    }

    JSONObject readObject(String member) {
        try {
            return new JSONObject(readSmall(member));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    String readSmall(String member) {
        File file = member(member);
        if (!file.isFile() || file.length() > 512 * 1024) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            FileInputStream stream = new FileInputStream(file);
            try {
                copy(stream, output);
            } finally {
                stream.close();
            }
            return output.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    JSONArray recover(String member, int maximumRows) {
        JSONArray rows = new JSONArray();
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    new FileInputStream(member(member)), UTF8));
            try {
                String line;
                while (rows.length() < maximumRows && (line = input.readLine()) != null) {
                    try {
                        rows.put(new JSONObject(line));
                    } catch (JSONException ignored) {
                        // A killed writer may leave a partial final record.
                    }
                }
            } finally {
                input.close();
            }
        } catch (IOException ignored) {
            // Missing logs are normal before the first successful sample.
        }
        return rows;
    }

    void zip(File destination) throws IOException {
        if (!complete()) throw new IOException("session_incomplete");
        ZipOutputStream archive = new ZipOutputStream(new FileOutputStream(destination));
        try {
            for (String name : MEMBERS) {
                File file = member(name);
                if (!file.isFile()) continue;
                archive.putNextEntry(new ZipEntry(name));
                FileInputStream input = new FileInputStream(file);
                try {
                    copy(input, archive);
                } finally {
                    input.close();
                }
                archive.closeEntry();
            }
        } finally {
            archive.close();
        }
    }

    boolean delete() {
        boolean ok = true;
        for (String name : MEMBERS) {
            File file = member(name);
            if (file.exists()) ok &= file.delete();
            File temporary = new File(directory, name + ".tmp");
            if (temporary.exists()) ok &= temporary.delete();
        }
        return ok && directory.delete();
    }

    private File member(String name) {
        if (!Arrays.asList(MEMBERS).contains(name)) throw new IllegalArgumentException("Invalid session member");
        return new File(directory, name);
    }

    private static void copy(FileInputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
            output.write(buffer, 0, count);
        }
    }
}
