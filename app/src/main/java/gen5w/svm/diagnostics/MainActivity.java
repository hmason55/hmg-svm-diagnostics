package gen5w.svm.diagnostics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler();
    private TextView dashboard;
    private CheckBox system;
    private CheckBox interfaces;
    private CheckBox services;
    private CheckBox logs;
    private Button scan;
    private Button start;
    private Button stop;
    private Button export;
    private Button delete;
    private AlertDialog exportDialog;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateDashboard();
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int margin = Math.round(20 * getResources().getDisplayMetrics().density);
        panel.setPadding(margin, margin, margin, margin);
        scroll.addView(panel);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("SVM Diagnostics");
        title.setTextSize(28);
        panel.addView(title);
        TextView guidance = new TextView(this);
        guidance.setText("Experimental · Configure and use controls while parked.");
        guidance.setTextSize(16);
        panel.addView(guidance);
        dashboard = new TextView(this);
        dashboard.setTextSize(17);
        dashboard.setPadding(0, margin, 0, margin);
        panel.addView(dashboard);

        system = option(panel, SessionService.SYSTEM, "Scan system properties and driver metadata");
        interfaces = option(panel, SessionService.INTERFACES, "Discover camera and video interfaces");
        services = option(panel, SessionService.SERVICES, "Discover camera-related Binder registrations");
        logs = option(panel, SessionService.LOGS, "Collect filtered camera log hints");

        LinearLayout actions = new LinearLayout(this);
        panel.addView(actions);
        scan = button(actions, "Scan System", new Runnable() {
            @Override public void run() { request(SessionService.SCAN); }
        });
        start = button(actions, "Start Session", new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Start diagnostic session")
                        .setMessage("Confirm you’re parked. Sampling runs every 2 seconds for up to 15 minutes.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Start", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                request(SessionService.START);
                            }
                        }).show();
            }
        });
        stop = button(actions, "Stop Session", new Runnable() {
            @Override public void run() { if (SessionService.busy) request(SessionService.STOP); }
        });

        LinearLayout sessions = new LinearLayout(this);
        panel.addView(sessions);
        button(sessions, "View Summary", new Runnable() {
            @Override public void run() { chooseSession("View summary", false, 0); }
        });
        export = button(sessions, "Export Session", new Runnable() {
            @Override public void run() { chooseSession("Export completed session", true, 1); }
        });
        delete = button(sessions, "Delete Session", new Runnable() {
            @Override public void run() { chooseSession("Delete session", false, 2); }
        });
    }

    private CheckBox option(LinearLayout panel, String key, String label) {
        CheckBox option = new CheckBox(this);
        option.setText(label);
        option.setChecked(getPreferences(0).getBoolean(key, true));
        panel.addView(option);
        return option;
    }

    private Button button(LinearLayout row, String name, final Runnable callback) {
        Button result = new Button(this);
        result.setText(name);
        row.addView(result, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        result.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { callback.run(); }
        });
        return result;
    }

    private void request(String action) {
        getPreferences(0).edit().putBoolean(SessionService.SYSTEM, system.isChecked())
                .putBoolean(SessionService.INTERFACES, interfaces.isChecked())
                .putBoolean(SessionService.SERVICES, services.isChecked())
                .putBoolean(SessionService.LOGS, logs.isChecked()).apply();
        Intent intent = new Intent(this, SessionService.class).setAction(action)
                .putExtra(SessionService.SYSTEM, system.isChecked())
                .putExtra(SessionService.INTERFACES, interfaces.isChecked())
                .putExtra(SessionService.SERVICES, services.isChecked())
                .putExtra(SessionService.LOGS, logs.isChecked());
        startService(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        refresh.run();
    }

    @Override protected void onStop() {
        ui.removeCallbacks(refresh);
        if (exportDialog != null) exportDialog.dismiss();
        super.onStop();
    }

    private void updateDashboard() {
        JSONObject inventory = SessionService.inventory;
        JSONObject props = inventory == null ? new JSONObject() : inventory.optJSONObject("properties");
        if (props == null) props = new JSONObject();
        JSONArray devices = inventory == null ? null : inventory.optJSONArray("devices");
        JSONArray names = new JSONArray();
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            names.put(devices.optJSONObject(i).optString("path"));
        }
        JSONArray foundServices = inventory == null ? null : inventory.optJSONArray("services");
        boolean busy = SessionService.busy;
        long begin = SessionService.started;
        long seconds = busy && begin != 0 ? (SystemClock.elapsedRealtime() - begin) / 1000 : 0;
        dashboard.setText("Firmware build  " + props.optString("ro.build.version.incremental", Build.VERSION.INCREMENTAL)
                + "\nAndroid  " + Build.VERSION.RELEASE + "  ·  API " + Build.VERSION.SDK_INT
                + "  ·  " + Build.CPU_ABI
                + "\nAvailable internal storage  " + String.format(Locale.US, "%.1f MB",
                        getFilesDir().getUsableSpace() / 1000000.0)
                + "\n\nSession  " + SessionService.state
                + (busy && begin != 0 ? "\nElapsed  " + seconds + " s" : "")
                + "\nSamples collected  " + SessionService.sampleCount
                + "\n\nVideo interfaces  " + (inventory == null ? "Unknown"
                        : !inventory.optBoolean("interfacesEnabled") ? "Disabled"
                        : (names.length() > 0 ? "Interface detected · " : "") + Observations.list(names))
                + "\nCamera-related Binder registrations  " + (inventory == null ? "Unknown"
                        : !inventory.optBoolean("serviceDiscoveryEnabled") ? "Disabled" : Observations.list(foundServices))
                + "\nSVM pipeline  Unknown  ·  frame observer unsupported"
                + "\nFront  Unknown  ·  Rear  Unknown  ·  Left  Unknown  ·  Right  Unknown");
        start.setEnabled(!busy);
        scan.setEnabled(!busy);
        system.setEnabled(!busy);
        interfaces.setEnabled(!busy);
        services.setEnabled(!busy);
        logs.setEnabled(!busy);
        stop.setEnabled(busy && begin != 0);
        export.setEnabled(!busy && exportDialog == null);
        delete.setEnabled(!busy && exportDialog == null);
    }

    private void chooseSession(String title, boolean completedOnly, final int operation) {
        final List<SessionFiles> matching = new ArrayList<SessionFiles>();
        List<String> labels = new ArrayList<String>();
        for (SessionFiles files : SessionFiles.list(SessionService.root(this))) {
            if (SessionService.busy && files.id.equals(SessionService.sessionId)) continue;
            boolean complete = files.complete();
            if (completedOnly && !complete) continue;
            matching.add(files);
            labels.add(files.id + (complete ? "" : " · interrupted"));
        }
        if (matching.isEmpty()) {
            message(completedOnly ? "No completed sessions available" : "No saved sessions available");
            return;
        }
        new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Cancel", null)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        SessionFiles files = matching.get(which);
                        if (operation == 0) view(files);
                        else if (operation == 1) chooseAddress(files);
                        else confirmDelete(files);
                    }
                }).show();
    }

    private void view(SessionFiles files) {
        String text = files.readSmall("summary.txt");
        if (!files.complete()) {
            JSONObject metadata = files.readObject("metadata.json");
            JSONArray rows = files.recover("samples.jsonl", 500);
            JSONObject last = rows.optJSONObject(rows.length() - 1);
            text = files.id + "\nInterrupted session\n\nReadable samples: " + rows.length()
                    + "\nStart timestamp: " + metadata.optString("wallStartMs", "Unknown")
                    + "\nLast sample elapsed: " + (last == null ? "Unknown" : last.optLong("elapsedMs") + " ms")
                    + "\nCamera and speed evidence: Unknown";
        }
        new AlertDialog.Builder(this).setTitle("Session summary").setMessage(text)
                .setPositiveButton("Close", null).show();
    }

    private void confirmDelete(final SessionFiles files) {
        new AlertDialog.Builder(this).setTitle("Delete session?")
                .setMessage(files.id).setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        message(files.delete() ? "Session deleted" : "Unable to delete session");
                    }
                }).show();
    }

    private void chooseAddress(final SessionFiles files) {
        final List<InetAddress> addresses = new ArrayList<InetAddress>();
        List<String> labels = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface link = interfaces.nextElement();
                if (!link.isUp() || link.isLoopback()) continue;
                Enumeration<InetAddress> values = link.getInetAddresses();
                while (values.hasMoreElements()) {
                    InetAddress address = values.nextElement();
                    if (address instanceof Inet4Address && (address.isSiteLocalAddress() || address.isLinkLocalAddress())) {
                        addresses.add(address);
                        labels.add(link.getName() + " · " + address.getHostAddress());
                    }
                }
            }
        } catch (IOException e) {
            message("Unable to inspect network interfaces");
            return;
        }
        if (addresses.isEmpty()) {
            message("No local IPv4 address available. The session is saved.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Select a local network address")
                .setNegativeButton("Cancel", null)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        openExport(files, addresses.get(which));
                    }
                }).show();
    }

    private void openExport(final SessionFiles files, final InetAddress address) {
        final TextView contents = new TextView(this);
        contents.setPadding(24, 20, 24, 20);
        contents.setTextSize(17);
        contents.setTextIsSelectable(true);
        contents.setText("Preparing archive…");
        final ExportTask task = new ExportTask(files, address, contents);
        exportDialog = new AlertDialog.Builder(this).setTitle("Wireless export")
                .setView(contents).setNegativeButton("Close", null).create();
        exportDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                task.close();
                exportDialog = null;
            }
        });
        exportDialog.show();
        exportDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        task.thread.start();
    }

    private final class ExportTask implements Runnable {
        final SessionFiles files;
        final InetAddress address;
        final TextView text;
        final Thread thread = new Thread(this, "session-export");
        private volatile boolean closed;
        private ExportServer server;

        ExportTask(SessionFiles files, InetAddress address, TextView text) {
            this.files = files;
            this.address = address;
            this.text = text;
        }

        @Override public void run() {
            File archive = null;
            try {
                if (getCacheDir().getUsableSpace() < files.used() + SessionPolicy.MIN_FREE_BYTES) {
                    show("Insufficient storage to prepare the archive. The session is saved.");
                    return;
                }
                File[] stale = getCacheDir().listFiles();
                if (stale != null) for (File old : stale) {
                    if (old.getName().startsWith("svm-export-") && old.isFile()) old.delete();
                }
                archive = File.createTempFile("svm-export-", ".zip", getCacheDir());
                files.zip(archive);
                synchronized (this) {
                    if (closed) return;
                    server = new ExportServer(address, archive, new ExportServer.Clock() {
                        @Override public long now() { return SystemClock.elapsedRealtime(); }
                    }, ExportServer.LIFETIME_MS);
                }
                show("Open this URL on another device on the same trusted network within five minutes."
                        + " The download uses local HTTP.\n\n" + server.url());
                server.serve();
                show("Export ended. The session is saved.");
            } catch (IOException e) {
                show("Export unavailable. The session is saved.");
            } finally {
                if (archive != null) archive.delete();
            }
        }

        private void show(final String value) {
            ui.post(new Runnable() {
                @Override public void run() { if (!closed) text.setText(value); }
            });
        }

        synchronized void close() {
            closed = true;
            thread.interrupt();
            if (server != null) server.close();
        }
    }

    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
