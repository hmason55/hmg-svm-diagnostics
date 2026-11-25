package gen5w.svm.diagnostics;

import static gen5w.svm.diagnostics.Observations.observation;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

final class CameraEvents implements AutoCloseable {
    private static final String[] TAGS = {
        "RearCamera", "SVM", "AVM", "BVM", "CameraService", "Telechips", "VIOC", "V4L2",
        "VideoInput", "CameraInput", "RVM"
    };
    private static final Pattern LINE = Pattern.compile(
            "^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+\\d+\\s+\\d+\\s+[VDIWEFAS]\\s+"
            + "(" + joinTags() + ")\\s*:\\s*(.*)$");
    private static final int LINE_LIMIT = 8192;

    private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<JSONObject>(128);
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile Commands.Status status = Commands.Status.OK;
    private volatile boolean closed;
    private Process process;
    private Thread reader;

    private static String joinTags() {
        StringBuilder result = new StringBuilder();
        for (String tag : TAGS) {
            if (result.length() > 0) result.append('|');
            result.append(tag);
        }
        return result.toString();
    }

    void start(final long startedAt) {
        List<String> args = new ArrayList<String>();
        args.add("/system/bin/logcat");
        args.add("-v");
        args.add("threadtime");
        for (String tag : TAGS) args.add(tag + ":V");
        args.add("*:S");
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
            status = message.contains("permission denied") || message.contains("error=13")
                    ? Commands.Status.PERMISSION_DENIED : Commands.Status.UNSUPPORTED;
            return;
        } catch (SecurityException e) {
            status = Commands.Status.PERMISSION_DENIED;
            return;
        }
        final Process logcat = process;
        reader = new Thread(new Runnable() {
            @Override public void run() {
                read(logcat, startedAt);
            }
        }, "camera-event-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void read(Process logcat, long startedAt) {
        StringBuilder line = new StringBuilder();
        boolean oversized = false;
        try {
            InputStream input = logcat.getInputStream();
            byte[] block = new byte[2048];
            int count;
            while (!closed && (count = input.read(block)) != -1) {
                for (int i = 0; i < count; i++) {
                    int character = block[i] & 255;
                    if (character == '\n') {
                        if (!oversized) accept(line.toString(), startedAt);
                        line.setLength(0);
                        oversized = false;
                    } else if (line.length() < LINE_LIMIT) {
                        line.append((char) character);
                    } else {
                        oversized = true;
                    }
                }
            }
        } catch (IOException ignored) {
            // Process exit and capability failures use fixed statuses.
        } finally {
            if (!closed && status == Commands.Status.OK) status = Commands.Status.FAILED;
            logcat.destroy();
        }
    }

    private void accept(String line, long startedAt) {
        if (!LINE.matcher(line).matches()) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("unable to open log device") || lower.startsWith("logcat:")) {
                status = lower.contains("permission denied") || lower.contains("operation not permitted")
                        ? Commands.Status.PERMISSION_DENIED : Commands.Status.UNSUPPORTED;
            }
            return;
        }
        JSONObject event = classify(line, startedAt, System.currentTimeMillis());
        if (event != null && !queue.offer(event)) dropped.incrementAndGet();
    }

    static JSONObject classify(String line, long startedAt, long receivedAt) {
        Matcher match = LINE.matcher(line);
        if (!match.matches()) return null;
        long eventTime = parseTime(match.group(1), receivedAt);
        if (eventTime < startedAt || eventTime > receivedAt) return null;
        String tag = match.group(2);
        String body = match.group(3).toLowerCase(Locale.US);
        String category = "message";
        if (body.matches(".*\\b(error|fail(?:ed|ure)?|timeout|denied)\\b.*")) category = "error hint";
        else if (body.matches(".*\\b(start(?:ed|ing)?|open(?:ed|ing)?|enable[ds]?)\\b.*")) category = "start or open hint";
        else if (body.matches(".*\\b(stop(?:ped|ping)?|clos(?:e|ed|ing)|disable[ds]?)\\b.*")) category = "stop or close hint";
        else if (body.matches(".*\\b(frame|frames|buffer|buffers)\\b.*")) category = "frame or buffer mention";
        JSONObject event = observation("camera-log", "event", "logcat/" + tag,
                category, Observations.Evidence.UNKNOWN, "unverified log hint", "OK");
        Inventory.put(event, "eventWallTimeMs", eventTime);
        return event;
    }

    private static long parseTime(String time, long receivedAt) {
        Calendar year = Calendar.getInstance();
        year.setTimeInMillis(receivedAt);
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        parser.setLenient(false);
        try {
            long result = parser.parse(year.get(Calendar.YEAR) + "-" + time).getTime();
            if (result > receivedAt + 86400000L) {
                result = parser.parse((year.get(Calendar.YEAR) - 1) + "-" + time).getTime();
            }
            return result;
        } catch (ParseException e) {
            return -1;
        }
    }

    List<JSONObject> drain() {
        List<JSONObject> events = new ArrayList<JSONObject>();
        queue.drainTo(events);
        return events;
    }

    Commands.Status status() { return status; }

    int dropped() { return dropped.get(); }

    @Override public void close() {
        closed = true;
        if (process != null) process.destroy();
        if (reader != null) reader.interrupt();
    }
}
