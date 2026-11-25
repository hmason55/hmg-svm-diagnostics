package gen5w.svm.diagnostics;

import static gen5w.svm.diagnostics.Observations.json;
import static gen5w.svm.diagnostics.Observations.observation;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Debug;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SessionService extends Service {
    static final String SCAN = "scan";
    static final String START = "start";
    static final String STOP = "stop";
    static final String SYSTEM = "system";
    static final String INTERFACES = "interfaces";
    static final String SERVICES = "services";
    static final String LOGS = "logs";
    static volatile JSONObject inventory;
    static volatile JSONObject latestSample;
    static volatile long wallStartMs;
    static volatile String state = "Idle";
    static volatile String sessionId;
    static volatile long started;
    static volatile int sampleCount;
    static volatile boolean busy;
    private volatile Thread worker;
    private volatile boolean cancel;
    private volatile boolean destroyed;

    static File root(Context context) { return new File(context.getFilesDir(), "sessions"); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (STOP.equals(action)) {
            cancel = true;
            Thread thread = worker;
            if (thread != null) thread.interrupt();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (busy) return START_NOT_STICKY;
        if (!SCAN.equals(action) && !START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final boolean collect = START.equals(action);
        final boolean system = intent.getBooleanExtra(SYSTEM, true);
        final boolean interfaces = intent.getBooleanExtra(INTERFACES, true);
        final boolean services = intent.getBooleanExtra(SERVICES, true);
        final boolean logs = intent.getBooleanExtra(LOGS, true);
        busy = true;
        cancel = false;
        state = collect ? "Starting session" : "Scanning system";
        sessionId = null;
        started = 0;
        sampleCount = 0;
        latestSample = null;
        wallStartMs = 0;
        PendingIntent dashboard = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("SVM Diagnostics")
                .setContentText(collect ? "Collecting diagnostic metadata" : "Scanning system metadata")
                .setContentIntent(dashboard).setOngoing(true).build());
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (collect) runSession(system, interfaces, services, logs);
                    else {
                        inventory = scan(system, interfaces, services);
                        state = cancel ? "Scan cancelled" : "Scan complete";
                    }
                } catch (Exception e) {
                    state = collect ? "Session interrupted — partial records retained" : "Scan failed";
                } finally {
                    finishWork();
                }
            }
        }, "diagnostic-session");
        worker.start();
        return START_NOT_STICKY;
    }

    private void finishWork() {
        // Serialize service shutdown with commands delivered on the main thread.
        new android.os.Handler(getMainLooper()).post(new Runnable() {
            @Override public void run() {
                worker = null;
                busy = false;
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private JSONObject scan(boolean system, boolean interfaces, boolean services) {
        return new Inventory(new Inventory.Source(new File("/")), new Commands.Local(450))
                .scan(system, interfaces, services, getFilesDir().getUsableSpace());
    }

    private void runSession(boolean system, boolean interfaces, boolean services, boolean logs) throws Exception {
        if (getFilesDir().getUsableSpace() < SessionPolicy.MIN_FREE_BYTES) {
            state = "Insufficient internal storage";
            return;
        }
        started = SystemClock.elapsedRealtime();
        long wallStarted = System.currentTimeMillis();
        wallStartMs = wallStarted;
        long cpuStart = Debug.threadCpuTimeNanos();
        SessionFiles files = SessionFiles.create(root(this), wallStarted);
        sessionId = files.id;
        files.atomic("metadata.json", json("schema", 1, "sessionId", sessionId, "wallStartMs", wallStarted,
                "bootElapsedStartMs", started, "sampleIntervalMs", SessionPolicy.INTERVAL_MS,
                "durationLimitMs", SessionPolicy.DURATION_MS, "storageLimitBytes", SessionPolicy.MAX_BYTES,
                "systemEnabled", system, "interfacesEnabled", interfaces, "serviceDiscoveryEnabled", services,
                "cameraLogEnabled", logs, "speedSource", "unknown").toString());

        CameraEvents events = new CameraEvents();
        SessionReport report = new SessionReport();
        if (logs && !cancel) events.start(wallStarted);
        JSONObject snapshot;
        String reason;
        try {
            snapshot = scan(system, interfaces, services);
            inventory = snapshot;
            files.atomic("inventory.json", snapshot.toString());
            JSONArray errors = snapshot.optJSONArray("problems");
            if (errors == null) errors = new JSONArray();
            Inventory.addProblem(errors, "frames", "SVM frame counters", Commands.Status.UNSUPPORTED);
            Inventory.addProblem(errors, "speed", "standalone vehicle speed API", Commands.Status.UNSUPPORTED);
            for (int i = 0; i < errors.length(); i++) stamp(errors.optJSONObject(i));
            files.append("errors.jsonl", errors);
            report.errorCount += errors.length();
            state = "Collecting";
            reason = sample(files, snapshot, events, logs, report);
        } finally {
            events.close();
        }
        Thread.interrupted();
        if (destroyed) return;
        report.finish(files, snapshot, reason, wallStarted, elapsed(),
                Math.max(0, (Debug.threadCpuTimeNanos() - cpuStart) / 1000000L), events.dropped());
        started = 0;
        state = "Session complete — " + reason.replace('_', ' ');
    }

    private String sample(SessionFiles files, JSONObject snapshot, CameraEvents events, boolean logs,
                          SessionReport report) throws IOException {
        List<File> videoPaths = new ArrayList<File>();
        JSONArray devices = snapshot.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            String path = devices.optJSONObject(i).optString("path");
            if (path.startsWith("/sys/class/video4linux/") || path.matches("/dev/video[0-9]+")) {
                videoPaths.add(new File(path));
            }
        }
        boolean cameraDiscovered = false;
        JSONArray services = snapshot.optJSONArray("services");
        if (services != null) for (int i = 0; i < services.length(); i++) {
            cameraDiscovered |= "media.camera".equals(services.optString(i));
        }
        boolean[] visible = new boolean[videoPaths.size()];
        java.util.Arrays.fill(visible, true);
        String binder = cameraDiscovered ? "registered" : "not sampled";
        String lastBinderError = "";
        Commands.Status lastLogStatus = Commands.Status.OK;
        Commands.Runner runner = new Commands.Local(450);
        long nextTick = SystemClock.elapsedRealtime();
        long nextBinderCheck = nextTick;
        while (true) {
            String reason = SessionPolicy.stopReason(cancel || destroyed, elapsed(), files.used(),
                    getFilesDir().getUsableSpace());
            if (reason != null) return reason;

            JSONArray missing = new JSONArray();
            for (int i = 0; i < videoPaths.size(); i++) {
                File path = videoPaths.get(i);
                boolean now = path.exists();
                if (now != visible[i]) report.change(elapsed(), path.toString(), now ? "visible" : "not visible");
                visible[i] = now;
                if (!now) missing.put(path.toString());
            }

            JSONArray errors = new JSONArray();
            if (cameraDiscovered && SystemClock.elapsedRealtime() >= nextBinderCheck) {
                nextBinderCheck = SystemClock.elapsedRealtime() + 10000;
                String previous = binder;
                Commands.Result check = runner.run("/system/bin/service", "check", "media.camera");
                String value = check.output.trim();
                if (check.status == Commands.Status.CANCELLED) return "stopped_by_user";
                if (check.status == Commands.Status.OK && value.equals("Service media.camera: found")) {
                    binder = "registered";
                    lastBinderError = "";
                } else if (check.status == Commands.Status.OK && value.equals("Service media.camera: not found")) {
                    binder = "not registered";
                    lastBinderError = "";
                } else {
                    Commands.Status failure = check.status == Commands.Status.OK ? Commands.Status.MALFORMED : check.status;
                    binder = "unavailable";
                    if (!lastBinderError.equals(failure.name())) {
                        Inventory.addProblem(errors, "binder", "service check media.camera", failure);
                    }
                    lastBinderError = failure.name();
                }
                if (!previous.equals(binder)) report.change(elapsed(), "media.camera registration", binder);
            }

            JSONArray retained = new JSONArray();
            if (logs) {
                Commands.Status status = events.status();
                if (status != lastLogStatus) Inventory.addProblem(errors, "camera-log", "logcat", status);
                lastLogStatus = status;
                for (JSONObject event : events.drain()) {
                    retained.put(stamp(event));
                    report.observeEvent(event);
                }
            }
            for (int i = 0; i < errors.length(); i++) stamp(errors.optJSONObject(i));
            JSONObject row = stamp(observation("metadata", "sample", "path visibility / Binder registration",
                    json("visibleVideoPaths", videoPaths.size() - missing.length(), "notVisiblePaths", missing,
                            "mediaCameraRegistration", binder, "cameraLogHints", retained.length(),
                            "availableStorageBytes", getFilesDir().getUsableSpace()),
                    Observations.Evidence.UNKNOWN, "metadata only", "OK"));
            try {
                files.append("errors.jsonl", errors);
                files.append("camera-events.jsonl", retained);
                files.append("samples.jsonl", new JSONArray().put(row));
            } catch (IOException e) {
                if ("session_storage_limit".equals(e.getMessage())) return "session_storage_limit";
                throw e;
            }
            report.errorCount += errors.length();
            latestSample = row;
            sampleCount = ++report.sampleCount;
            nextTick = Math.max(nextTick + SessionPolicy.INTERVAL_MS, SystemClock.elapsedRealtime());
            long wait = Math.min(nextTick - SystemClock.elapsedRealtime(), SessionPolicy.DURATION_MS - elapsed());
            if (wait <= 0) continue;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return "stopped_by_user";
            }
        }
    }

    private static long elapsed() { return Math.max(0, SystemClock.elapsedRealtime() - started); }

    private static JSONObject stamp(JSONObject row) {
        Inventory.put(row, "sessionId", sessionId);
        Inventory.put(row, "wallTimeMs", System.currentTimeMillis());
        Inventory.put(row, "elapsedMs", elapsed());
        Inventory.put(row, "speedSource", "unknown");
        Inventory.put(row, "speedKmh", JSONObject.NULL);
        return row;
    }

    @Override public void onDestroy() {
        destroyed = true;
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }
}
