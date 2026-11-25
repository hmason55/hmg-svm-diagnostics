package gen5w.svm.diagnostics;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InventoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    static String fixture(String name) {
        InputStream input = InventoryTest.class.getResourceAsStream("/fixtures/" + name);
        try (Scanner scanner = new Scanner(input, "UTF-8").useDelimiter("\\A")) {
            return scanner.next();
        }
    }

    @Test public void cameraServicesKeepOnlyRelevantValidNames() {
        Set<String> names = Inventory.cameraServices(fixture("service-list.txt"));
        assertEquals(3, names.size());
        assertTrue(names.contains("media.camera"));
        assertTrue(names.contains("com.hkmc.micom.pgs"));
        assertTrue(names.contains("vendor.svm"));
        assertTrue(Inventory.cameraServices("invalid malformed: account@home").isEmpty());
    }

    @Test public void missingNodesAndCommandFailuresDegradeIndependently() throws Exception {
        File root = temporary.newFolder();
        File proc = new File(root, "proc");
        assertTrue(proc.mkdir());
        Files.write(new File(proc, "cpuinfo").toPath(),
                ("Hardware : Telechips TCC897x\nSerial : 0000000091837723\n").getBytes("UTF-8"));
        Files.write(new File(proc, "pmap").toPath(),
                "0x94020000 0x01000000 rearcamera\n".getBytes("UTF-8"));
        Inventory inventory = new Inventory(new Inventory.Source(root), new Commands.Runner() {
            @Override public Commands.Result run(String... arguments) {
                return new Commands.Result(arguments[0].endsWith("service") ? Commands.Status.PERMISSION_DENIED
                        : Commands.Status.UNSUPPORTED, "");
            }
        });
        JSONObject scanned = inventory.scan(true, true, true, 1234);
        assertEquals(0, scanned.getJSONArray("devices").length());
        assertEquals(0, scanned.getJSONArray("services").length());
        assertEquals(1234, scanned.getLong("freeBytes"));
        String output = scanned.toString();
        assertTrue(output.contains("Telechips TCC897x"));
        assertTrue(output.contains("PERMISSION_DENIED"));
        assertTrue(output.contains("UNSUPPORTED"));
        assertFalse(output.contains("91837723"));
        assertEquals("rearcamera", scanned.getJSONObject("proc").getJSONArray("reservedRegions").getString(0));
        assertFalse(output.contains("94020000"));
        assertFalse(output.contains("Frame activity observed"));
    }

    @Test public void detectsInterfaceMetadataWithoutConsumingContents() throws Exception {
        File root = temporary.newFolder();
        File dev = new File(root, "dev");
        assertTrue(dev.mkdir());
        Files.write(new File(dev, "video0").toPath(), "out of scope bytes".getBytes("UTF-8"));
        JSONObject value = new Inventory(new Inventory.Source(root),
                new Commands.Runner() {
                    @Override public Commands.Result run(String... arguments) {
                        return new Commands.Result(Commands.Status.OK, "bad service inventory");
                    }
                })
                .scan(true, true, true, 1234);
        assertEquals("/dev/video0", value.getJSONArray("devices").getJSONObject(0).getString("path"));
        assertTrue(value.toString().contains("MALFORMED"));
        assertFalse(value.toString().contains("out of scope bytes"));
    }

    @Test public void logFixtureExportsCategoriesAndNeverRawMessageOrFrameClaims() throws Exception {
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        long start = time.parse("2026-11-24 15:04:05.000").getTime();
        long now = time.parse("2026-11-24 15:04:08.100").getTime();
        JSONArray output = new JSONArray();
        for (String line : fixture("camera-log.txt").split("\\r?\\n")) {
            JSONObject event = CameraEvents.classify(line, start, now);
            if (event != null) output.put(event);
        }
        assertEquals(4, output.length());
        assertEquals("start or open hint", output.getJSONObject(0).getString("observedValue"));
        assertEquals("frame or buffer mention", output.getJSONObject(1).getString("observedValue"));
        assertEquals("Unknown", output.getJSONObject(1).getString("evidence"));
        assertEquals("stop or close hint", output.getJSONObject(2).getString("observedValue"));
        for (String field : new String[] {"KM8K", "example.test", "312555", "37.42", "frame count 500",
                "Frame activity observed", "No activity observed"}) {
            assertFalse(field, output.toString().contains(field));
        }
        assertNull(CameraEvents.classify("11-24 15:04:04.001 101 202 I RearCamera: old", start, now));
        assertNull(CameraEvents.classify("totally malformed", start, now));
    }

    @Test public void commandStatusesAndTimeout() {
        assertEquals(Commands.Status.UNSUPPORTED, Commands.Result.from(127, "", false).status);
        assertEquals(Commands.Status.PERMISSION_DENIED,
                Commands.Result.from(0, "Permission Denial: log access", false).status);
        assertEquals(Commands.Status.TRUNCATED, Commands.Result.from(0, "", true).status);
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.US).contains("win");
        Commands.Result timeout = windows ? new Commands.Local(40).run("ping.exe", "-n", "10", "127.0.0.1")
                : new Commands.Local(40).run("/bin/sleep", "10");
        assertEquals(Commands.Status.TIMEOUT, timeout.status);
    }
}
