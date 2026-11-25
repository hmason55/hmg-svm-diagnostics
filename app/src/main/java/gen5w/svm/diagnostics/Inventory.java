package gen5w.svm.diagnostics;

import static gen5w.svm.diagnostics.Observations.json;
import static gen5w.svm.diagnostics.Observations.observation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class Inventory {
    private static final Pattern DEVICE = Pattern.compile(
            "video[0-9]{1,4}|v4l-subdev[0-9]{1,4}|admem|vioc(?:_[a-z0-9_]{1,32})?"
            + "|tcc_[a-z0-9_]*(?:camera|vin|vout|vpu|vioc|video)[a-z0-9_]*|mali[0-9]*");
    private static final Pattern LIBRARY = Pattern.compile(
            "(?:lib(?:camera|rearcamera|svm|avm|v4l|vioc|GLES_mali|Mali|tcc[^./]*"
            + "(?:camera|video|vpu))|camera\\.)[A-Za-z0-9_.-]{0,50}\\.so");
    private static final Pattern SERVICE = Pattern.compile(
            "^\\s*\\d+\\s+([A-Za-z0-9_.-]{1,96}):\\s*\\[([A-Za-z0-9_.$-]{0,150})\\]\\s*$");
    private static final Pattern CAMERA_NAME = Pattern.compile(
            "(?:^|[._$-])(?:svm|avm|bvm|pgs|vin|vioc)(?:$|[._$-])"
            + "|rear.?camera|camera|around.?view|surround.?view|parking.?ecu|video.?input|v4l2|telechips");
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z0-9_. +()-]{1,120}");
    private static final Pattern IDENTIFIER = Pattern.compile("(?i)(?:[A-HJ-NPR-Z0-9]{17}|[0-9a-f]{12,})");
    private static final Pattern PMAP = Pattern.compile("\\s*(?:0x)?[0-9a-fA-F]+\\s+(?:0x)?[0-9a-fA-F]+\\s+([A-Za-z0-9_.-]{1,64})\\s*");
    private static final Pattern DASHED = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final String[] PROPERTIES = {
        "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.incremental",
        "ro.product.cpu.abi", "ro.board.platform", "ro.hardware"
    };
    private static final String[] DIRECTORIES = {
        "/dev", "/sys/class/video4linux", "/system/lib", "/system/lib/hw", "/system/vendor/lib",
        "/system/vendor/lib/hw", "/vendor/lib", "/vendor/lib/hw"
    };

    static final class Source {
        final File root;

        Source(File root) { this.root = root; }

        File file(String absolutePath) { return new File(root, absolutePath.substring(1)); }

        Commands.Result read(String path) {
            File target = file(path);
            // Only callers with fixed procfs paths may read contents.
            try {
                FileInputStream input = new FileInputStream(target);
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] block = new byte[2048];
                    int count;
                    while ((count = input.read(block)) != -1) {
                        if (count > Commands.LIMIT - output.size()) {
                            return new Commands.Result(Commands.Status.TRUNCATED, "");
                        }
                        output.write(block, 0, count);
                    }
                    return Commands.Result.from(0, output.toString("UTF-8"), false);
                } finally {
                    input.close();
                }
            } catch (IOException e) {
                String message = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
                boolean denied = message.contains("permission denied")
                        || message.contains("operation not permitted") || target.exists();
                return new Commands.Result(denied ? Commands.Status.PERMISSION_DENIED
                        : Commands.Status.UNSUPPORTED, "");
            } catch (SecurityException e) {
                return new Commands.Result(Commands.Status.PERMISSION_DENIED, "");
            }
        }
    }

    private final Source source;
    private final Commands.Runner runner;

    Inventory(Source source, Commands.Runner runner) {
        this.source = source;
        this.runner = runner;
    }

    JSONObject scan(boolean systemEnabled, boolean interfacesEnabled, boolean servicesEnabled, long freeBytes) {
        JSONArray problems = new JSONArray();
        JSONObject properties = new JSONObject();
        if (systemEnabled) for (String key : PROPERTIES) {
            Commands.Result result = runner.run("/system/bin/getprop", key);
            String value = result.output.trim();
            if (result.status != Commands.Status.OK) addProblem(problems, "system", key, result.status);
            else if (PROPERTY.matcher(value).matches() && !sensitiveIdentifier(value)) put(properties, key, value);
        }

        JSONObject proc = new JSONObject();
        if (systemEnabled) {
            proc(problems, proc, "/proc/cpuinfo", "cpu");
            proc(problems, proc, "/proc/meminfo", "memory");
            proc(problems, proc, "/proc/modules", "modules");
            proc(problems, proc, "/proc/pmap", "reservedRegions");
        }

        JSONArray devices = new JSONArray();
        JSONArray libraries = new JSONArray();
        if (interfacesEnabled) for (String directory : DIRECTORIES) {
            boolean videoDirectory = directory.startsWith("/dev") || directory.startsWith("/sys/");
            File parent = source.file(directory);
            String[] names = parent.list();
            if (names == null) {
                addProblem(problems, "interfaces", directory, parent.exists()
                        ? Commands.Status.PERMISSION_DENIED : Commands.Status.UNSUPPORTED);
                continue;
            }
            Set<String> accepted = new TreeSet<String>();
            for (String name : names) {
                if ((videoDirectory ? DEVICE : LIBRARY).matcher(name).matches() && name.length() <= 64) {
                    accepted.add(name);
                }
            }
            for (String name : accepted) {
                if (videoDirectory && devices.length() < 128) {
                    devices.put(json("path", directory + "/" + name,
                            "readableMetadata", new File(parent, name).canRead()));
                } else if (!videoDirectory && libraries.length() < 128) {
                    libraries.put(directory + "/" + name);
                }
            }
        }

        JSONArray services = new JSONArray();
        if (servicesEnabled) {
            Commands.Result result = runner.run("/system/bin/service", "list");
            if (result.status != Commands.Status.OK) addProblem(problems, "binder", "service list", result.status);
            else if (!result.output.matches("(?s)^\\s*Found \\d+ services?:.*")) {
                addProblem(problems, "binder", "service list", Commands.Status.MALFORMED);
            } else {
                for (String name : cameraServices(result.output)) services.put(name);
            }
        }
        return json("wallTimeMs", System.currentTimeMillis(), "freeBytes", freeBytes,
                "properties", properties, "proc", proc, "devices", devices, "libraries", libraries,
                "services", services, "serviceDiscoveryEnabled", servicesEnabled,
                "systemEnabled", systemEnabled, "interfacesEnabled", interfacesEnabled, "problems", problems);
    }

    private void proc(JSONArray problems, JSONObject output, String path, String key) {
        Commands.Result result = source.read(path);
        if (result.status != Commands.Status.OK) {
            addProblem(problems, "system", path, result.status);
            return;
        }
        JSONArray values = new JSONArray();
        Set<String> accepted = new TreeSet<String>();
        for (String line : result.output.split("\\r?\\n")) {
            if (key.equals("cpu") && line.matches("(?:processor|Processor|model name|Hardware|CPU architecture|CPU implementer|CPU part)\\s*:\\s*[A-Za-z0-9 ._()+-]{1,100}")) {
                if (!sensitiveIdentifier(line)) accepted.add(line.trim().replaceAll("\\s+", " "));
            } else if (key.equals("memory") && line.matches("(?:MemTotal|MemFree|MemAvailable|Buffers|Cached)\\s*:\\s*\\d{1,12}\\s+kB")) {
                accepted.add(line.trim().replaceAll("\\s+", " "));
            } else if (key.equals("reservedRegions")) {
                Matcher region = PMAP.matcher(line);
                if (region.matches() && relevantDriver(region.group(1))) accepted.add(region.group(1));
            } else if (key.equals("modules")) {
                String word = line.trim().split("\\s+", 2)[0];
                if (DASHED.matcher(word).matches() && relevantDriver(word)) accepted.add(word);
            }
        }
        for (String value : accepted) if (values.length() < 128) values.put(value);
        put(output, key, values);
    }

    static Set<String> cameraServices(String content) {
        Set<String> services = new TreeSet<String>();
        for (String line : content.split("\\r?\\n")) {
            Matcher parsed = SERVICE.matcher(line);
            if (parsed.matches() && !sensitiveIdentifier(parsed.group(1))
                    && CAMERA_NAME.matcher((parsed.group(1) + " " + parsed.group(2))
                    .toLowerCase(Locale.US)).find()) {
                if (services.size() < 64) services.add(parsed.group(1));
            }
        }
        return services;
    }

    static boolean relevantDriver(String name) {
        String lower = name.toLowerCase(Locale.US);
        return !sensitiveIdentifier(name) && (CAMERA_NAME.matcher(lower).find()
                || lower.matches("mali.*|ump|fb[0-9]*|jpeg|vpu|video.*|tcc.*|v4l.*|vin.*|vioc.*"));
    }

    static boolean sensitiveIdentifier(String value) {
        return IDENTIFIER.matcher(value).find()
                || value.toLowerCase(Locale.US).matches(".*(?:serial|account|vehicle.?id|(?:^|[._-])vin[0-9]{5,}).*");
    }

    static void addProblem(JSONArray problems, String collector, String source, Commands.Status status) {
        Observations.Evidence evidence = status == Commands.Status.UNSUPPORTED
                ? Observations.Evidence.UNSUPPORTED : status == Commands.Status.PERMISSION_DENIED
                ? Observations.Evidence.INACCESSIBLE : Observations.Evidence.UNKNOWN;
        problems.put(observation(collector, "capability", source, null, evidence, "none", status.name()));
    }

    static void put(JSONObject object, String name, Object value) {
        try {
            object.put(name, value);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
