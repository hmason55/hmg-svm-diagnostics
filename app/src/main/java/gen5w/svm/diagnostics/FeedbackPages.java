package gen5w.svm.diagnostics;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Builds a frozen, photo-friendly view of the filtered diagnostic records. */
final class FeedbackPages {
    private static final int ROWS_PER_PAGE = 4;
    private static final int MAX_DETAIL_ROWS = 12;

    static final class Page {
        final String title;
        final String body;

        Page(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    static List<Page> build(JSONObject snapshot) {
        List<Page> pages = new ArrayList<Page>();
        JSONObject inventory = object(snapshot.optJSONObject("inventory"));
        JSONObject props = object(inventory.optJSONObject("properties"));
        JSONObject summary = object(snapshot.optJSONObject("summary"));
        JSONObject sample = object(snapshot.optJSONObject("sample"));
        JSONObject values = object(sample.optJSONObject("observedValue"));
        boolean saved = snapshot.optBoolean("saved");
        boolean completed = summary.optBoolean("completed");
        long start = summary.optLong("wallStartMs", snapshot.optLong("wallStartMs"));
        long elapsed = summary.optLong("elapsedMs", snapshot.optLong("elapsedMs", -1));

        pages.add(new Page("System", "Firmware build  " + props.optString("ro.build.version.incremental", "Unknown")
                + "\nAndroid  " + snapshot.optString("android") + "  ·  " + snapshot.optString("abi")
                + "\nInternal free space  " + snapshot.optString("storage")
                + "\nLast scan  " + timestamp(inventory.optLong("wallTimeMs"))));
        pages.add(new Page("Session", "Status  " + snapshot.optString("state")
                + (summary.has("stopReason") ? " · " + summary.optString("stopReason").replace('_', ' ') : "")
                + "\nSamples  " + summary.optInt("sampleCount", snapshot.optInt("sampleCount"))
                + (elapsed >= 0 ? "  ·  " + elapsed / 1000 + " s elapsed" : "")
                + "\nStarted  " + timestamp(start) + "\nLatest sample  " + timestamp(sample.optLong("wallTimeMs"))));

        String registration = values.optString("mediaCameraRegistration", "Not sampled");
        pages.add(new Page("Camera evidence",
                "SVM pipeline  Unknown  ·  frame observer unsupported"
                + "\nFront / rear / left / right  Unknown"
                + "\nStandard camera Binder registration  " + registration
                + "\nSpeed  Unknown  ·  log hints  "
                + (completed ? String.valueOf(summary.optInt("cameraEventCount"))
                        + " retained / " + summary.optInt("droppedCameraEvents") + " dropped"
                        : values.has("cameraLogHints") ? values.optInt("cameraLogHints") + " in latest sample"
                        : saved ? "see saved records" : "not sampled")));

        Set<String> issueRows = new LinkedHashSet<String>();
        addIssues(issueRows, inventory.optJSONArray("problems"));
        addIssues(issueRows, snapshot.optJSONArray("problems"));
        addPages(pages, "Collector feedback", issueRows,
                inventory.length() == 0 ? "No scan recorded" : "No capability issues recorded");

        Set<String> interfaces = new LinkedHashSet<String>();
        JSONArray devices = inventory.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null) interfaces.add(device.optString("path"));
        }
        addPages(pages, "Video interfaces", interfaces, emptyInventory(inventory, "interfacesEnabled"));
        Set<String> services = new LinkedHashSet<String>();
        JSONArray registrations = inventory.optJSONArray("services");
        if (registrations != null) for (int i = 0; i < registrations.length(); i++) {
            services.add(registrations.optString(i));
        }
        addPages(pages, "Binder registrations", services, emptyInventory(inventory, "serviceDiscoveryEnabled"));

        Set<String> observations = new LinkedHashSet<String>();
        JSONArray changes = summary.optJSONArray("changes");
        if (changes != null) for (int i = Math.max(0, changes.length() - 4); i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            if (change != null) observations.add(change.optLong("elapsedMs") / 1000 + " s  "
                    + change.optString("source") + "  ·  " + change.optString("value"));
        }
        JSONArray hints = snapshot.optJSONArray("hints");
        if (hints != null) for (int i = Math.max(0, hints.length() - 4); i < hints.length(); i++) {
            JSONObject hint = hints.optJSONObject(i);
            if (hint != null) observations.add(hint.optLong("elapsedMs") / 1000 + " s  "
                    + hint.optString("evidenceSource") + "  ·  " + hint.optString("observedValue"));
        }
        if (saved) addPages(pages, "Recorded changes and hints", observations, "No changes or log hints recorded");
        return pages;
    }

    private static JSONObject object(JSONObject value) {
        return value == null ? new JSONObject() : value;
    }

    private static String emptyInventory(JSONObject inventory, String enabledKey) {
        if (inventory.length() == 0) return "No scan recorded";
        return inventory.optBoolean(enabledKey) ? "None reported" : "Collector disabled";
    }

    private static void addIssues(Set<String> rows, JSONArray issues) {
        if (issues == null) return;
        for (int i = 0; i < issues.length(); i++) {
            JSONObject issue = issues.optJSONObject(i);
            if (issue == null || issue.optString("status").isEmpty()) continue;
            rows.add(issue.optString("evidenceSource") + "  ·  "
                    + issue.optString("status").replace('_', ' '));
        }
    }

    private static void addPages(List<Page> pages, String title, Set<String> rows, String empty) {
        if (rows.isEmpty()) {
            pages.add(new Page(title, empty));
            return;
        }
        StringBuilder body = new StringBuilder();
        int count = 0;
        for (String row : rows) {
            if (count == MAX_DETAIL_ROWS) break;
            if (body.length() > 0) body.append('\n');
            body.append(row);
            count++;
            if (count % ROWS_PER_PAGE == 0 || count == rows.size()) {
                if (count == MAX_DETAIL_ROWS && rows.size() > count) {
                    body.append('\n').append(rows.size() - count).append(" more in session data");
                }
                pages.add(new Page(title, body.toString()));
                body.setLength(0);
            }
        }
    }

    static String timestamp(long milliseconds) {
        return milliseconds > 0
                ? new SimpleDateFormat("MMM d, HH:mm:ss Z", Locale.US).format(new Date(milliseconds))
                : "Unknown";
    }

    private FeedbackPages() {}
}
