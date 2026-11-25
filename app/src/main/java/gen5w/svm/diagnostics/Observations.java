package gen5w.svm.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Observations {
    enum Evidence {
        UNKNOWN("Unknown"),
        INTERFACE("Interface detected"),
        SERVICE("Service active"),
        FRAMES("Frame activity observed"),
        NO_ACTIVITY("No activity observed"),
        INACCESSIBLE("Not accessible"),
        UNSUPPORTED("Unsupported");

        final String label;
        Evidence(String label) { this.label = label; }
    }

    static JSONObject json(Object... pairs) {
        JSONObject output = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) {
                output.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
            }
            return output;
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static JSONObject observation(String collector, String type, String source, Object value,
                                  Evidence evidence, String confidence, String status) {
        return json("collectorId", collector, "observationType", type, "evidenceSource", source,
                "observedValue", value, "evidence", evidence.label, "confidence", confidence,
                "status", status);
    }

    static String list(JSONArray items) {
        StringBuilder result = new StringBuilder();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                if (i > 0) result.append(", ");
                result.append(items.optString(i));
            }
        }
        return result.length() == 0 ? "None reported" : result.toString();
    }

    private Observations() {}
}
