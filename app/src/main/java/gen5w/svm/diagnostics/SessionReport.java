package gen5w.svm.diagnostics;

import static gen5w.svm.diagnostics.Observations.json;
import java.util.Date;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

final class SessionReport {
    static final String[] STAGES = {
        "Camera hardware powered",
        "Physical camera transmitting video",
        "SVM controller receiving video",
        "SVM controller processing video",
        "SVM controller transmitting output to Gen5W",
        "Gen5W receiving SVM output",
        "Factory UI displaying SVM output"
    };
    final JSONArray changes = new JSONArray();
    final JSONObject eventCounts = new JSONObject();
    int acceptedEvents;
    int sampleCount;
    int errorCount;

    void change(long elapsed, String source, String value) {
        if (changes.length() < 80) changes.put(json("elapsedMs", elapsed, "source", source, "value", value));
    }

    void observeEvent(JSONObject event) {
        String source = event.optString("evidenceSource");
        Inventory.put(eventCounts, source, eventCounts.optInt(source) + 1);
        acceptedEvents++;
    }

    void finish(SessionFiles files, JSONObject inventory, String reason, long wallStarted,
                long elapsed, long cpu, int dropped) throws java.io.IOException {
        JSONObject stages = new JSONObject();
        for (String stage : STAGES) Inventory.put(stages, stage, Observations.Evidence.UNKNOWN.label);
        JSONObject summary = json("sessionId", files.id, "completed", true, "stopReason", reason,
                "wallStartMs", wallStarted, "elapsedMs", elapsed, "sampleCount", sampleCount,
                "storedBytesBeforeSummary", files.used(), "collectorThreadCpuMs", cpu,
                "cameraEventCount", acceptedEvents, "droppedCameraEvents", dropped,
                "capabilityIssueCount", errorCount, "changes", changes, "eventSources", eventCounts,
                "cameraPipelineStages", stages, "frameObservation", "unsupported",
                "speedSource", "unknown", "physicalValidation", "unverified");
        StringBuilder report = new StringBuilder("SVM Diagnostics — experimental\n\n");
        report.append(files.id).append("\nStarted: ").append(new Date(wallStarted))
                .append(" (wall timestamp ").append(wallStarted).append(" ms)\n")
                .append("Stopped: ").append(reason).append("\n")
                .append("Elapsed: ").append(elapsed / 1000).append(" s (monotonic)\n")
                .append("Samples: ").append(sampleCount).append("\n")
                .append("Collector worker CPU: ").append(cpu).append(" ms")
                .append(" (excludes child commands and event reader)\n")
                .append("Stored bytes before summary: ").append(files.used()).append("\n\n");
        JSONArray devices = inventory.optJSONArray("devices");
        report.append("Detected video interfaces\n");
        if (devices == null || devices.length() == 0) report.append("None reported\n");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            report.append(devices.optJSONObject(i).optString("path")).append('\n');
        }
        report.append("\nBinder registrations: ").append(Observations.list(inventory.optJSONArray("services")))
                .append("\nRegistration alone provides no evidence of camera use.\n\n")
                .append("Changes in path visibility or Binder registration\n");
        if (changes.length() == 0) report.append("No changes recorded in sampled metadata.\n");
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            report.append(change.optLong("elapsedMs")).append(" ms  ")
                    .append(change.optString("source")).append("  ").append(change.optString("value")).append('\n');
        }

        report.append("\nSVM/AVM, rear-view, BVM and related log hints\n")
                .append(acceptedEvents).append(" retained, ").append(dropped).append(" dropped at the queue limit.\n");
        for (Iterator<?> tags = eventCounts.keys(); tags.hasNext();) {
            String tag = (String) tags.next();
            report.append(tag).append(": ").append(eventCounts.optInt(tag)).append('\n');
        }
        report.append("Event categories and times are in camera-events.jsonl. Log hints cannot establish activity.\n")
                .append("\nFrame counters: Unsupported on this build. Front, rear, left and right cameras: Unknown.\n")
                .append("Speed: Unknown. Correlate wallStartMs and sample times with externally measured speed")
                .append(" or phase annotations. No speed-correlated transitions were computed.\n\n")
                .append("Pipeline evidence\n");
        for (String stage : STAGES) report.append(stage).append(": Unknown\n");
        report.append("\nCapability and permission issues");
        JSONArray issues = files.recover("errors.jsonl", 96);
        if (issues.length() == 0) report.append("\nNone recorded.");
        for (int i = 0; i < issues.length(); i++) {
            JSONObject issue = issues.optJSONObject(i);
            report.append('\n').append(issue.optString("evidenceSource")).append(" — ")
                    .append(issue.optString("status"));
        }
        report.append("\n\nOpen questions\n")
                .append("Does Gen5W continue to receive SVM output after the factory view closes? ")
                .append("Does rear-view driving mode change that output? ")
                .append("Can a documented passive observer distinguish the individual cameras?\n");
        files.atomic("summary.txt", report.toString());
        files.atomic("summary.json", summary.toString());
    }
}
