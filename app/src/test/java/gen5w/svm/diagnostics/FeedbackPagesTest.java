package gen5w.svm.diagnostics;

import static gen5w.svm.diagnostics.Observations.json;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.junit.Test;

public class FeedbackPagesTest {
    @Test public void displaysSavedEvidenceWithoutRawPayloads() {
        List<FeedbackPages.Page> pages = FeedbackPages.build(json("saved", true, "state", "Completed",
                "android", "4.4.2", "abi", "armeabi-v7a", "storage", "130 MB",
                "summary", json("completed", true, "sampleCount", 17, "elapsedMs", 32000,
                        "cameraEventCount", 4, "droppedCameraEvents", 1),
                "inventory", json("properties", json("ro.build.version.incremental", "260507"),
                        "devices", new JSONArray().put(json("path", "/dev/video0")),
                        "services", new JSONArray().put("media.camera")),
                "problems", new JSONArray().put(json("evidenceSource", "logcat",
                        "status", "PERMISSION_DENIED", "raw", "personal-output")),
                "hints", new JSONArray().put(json("evidenceSource", "logcat/SVM",
                        "observedValue", "frame or buffer mention", "elapsedMs", 2000,
                        "raw", "personal-output"))));
        String text = content(pages);
        assertEquals("System", pages.get(0).title);
        assertTrue(text.contains("260507"));
        assertTrue(text.contains("17  ·  32 s elapsed"));
        assertTrue(text.contains("SVM pipeline  Unknown"));
        assertTrue(text.contains("4 retained / 1 dropped"));
        assertTrue(text.contains("/dev/video0"));
        assertTrue(text.contains("logcat  ·  PERMISSION DENIED"));
        assertTrue(text.contains("2 s  logcat/SVM  ·  frame or buffer mention"));
        assertFalse(text.contains("personal-output"));
    }

    @Test public void keepsLongInventoryBoundedAndLabelsUnscannedData() {
        JSONArray devices = new JSONArray();
        for (int i = 0; i < 18; i++) devices.put(json("path", "/dev/video" + i));
        List<FeedbackPages.Page> longInventory = FeedbackPages.build(json("inventory",
                json("devices", devices, "interfacesEnabled", true, "serviceDiscoveryEnabled", false)));
        int interfacePages = 0;
        for (FeedbackPages.Page page : longInventory) {
            if ("Video interfaces".equals(page.title)) interfacePages++;
        }
        assertEquals(3, interfacePages);
        assertTrue(content(longInventory).contains("6 more in session data"));
        assertTrue(content(longInventory).contains("Collector disabled"));
        assertTrue(content(FeedbackPages.build(json())).contains("No scan recorded"));
    }

    private String content(List<FeedbackPages.Page> pages) {
        StringBuilder text = new StringBuilder();
        for (FeedbackPages.Page page : pages) text.append(page.body).append('\n');
        return text.toString();
    }
}
