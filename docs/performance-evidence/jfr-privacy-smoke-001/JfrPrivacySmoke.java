import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;

/** One startup recording, one data-free marker, then local decoding after stop. */
public class JfrPrivacySmoke {
    @Name("dev.logisim.PrivacySmokeMarker")
    static class Marker extends Event { }

    static final List<String> FORBIDDEN_EVENTS = List.of(
        "jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.JVMInformation");
    static final Map<String, String> SENTINELS = Map.of(
        "GITHUB_TOKEN", "FAKE_LOGISIM_PRIVACY_GITHUB_20261105",
        "AWS_SECRET_ACCESS_KEY", "FAKE_LOGISIM_PRIVACY_AWS_20261105",
        "UNEXPECTED_ENVIRONMENT", "FAKE_LOGISIM_PRIVACY_UNKNOWN_20261105",
        "JAVA_TOOL_OPTIONS", "-Dlogisim.privacy.tool=FAKE_LOGISIM_PRIVACY_TOOL_20261105",
        "_JAVA_OPTIONS", "-Dlogisim.privacy.private=FAKE_LOGISIM_PRIVACY_PRIVATE_20261105",
        "JDK_JAVA_OPTIONS", "-Dlogisim.privacy.jdk=FAKE_LOGISIM_PRIVACY_JDK_20261105");

    static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static boolean contains(byte[] bytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer: for (int index = 0; index <= bytes.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (bytes[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    static void appendMap(StringBuilder json, String name, Map<String, ?> values) {
        json.append(",\n  ").append(quoted(name)).append(": {");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\n').append("    ").append(quoted(entry.getKey())).append(": ").append(entry.getValue());
        }
        json.append("\n  }");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected recording and receipt paths");
        Path recordingPath = Path.of(args[0]);
        Path receiptPath = Path.of(args[1]);
        List<Recording> candidates = FlightRecorder.getFlightRecorder().getRecordings().stream()
            .filter(recording -> recording.getName().equals("logisim-privacy-smoke")
                && recording.getState() == RecordingState.RUNNING).toList();
        if (candidates.size() != 1) throw new IllegalStateException("Expected exactly one running startup recording");
        Recording recording = candidates.get(0);
        Map<String, Boolean> disabledSettings = new TreeMap<>();
        FORBIDDEN_EVENTS.forEach(name -> disabledSettings.put(name,
            "false".equals(recording.getSettings().get(name + "#enabled"))));
        Map<String, Boolean> absentEnvironment = new TreeMap<>();
        SENTINELS.keySet().forEach(name -> absentEnvironment.put(name, System.getenv(name) == null));
        boolean absentOptionProperties = List.of("logisim.privacy.tool", "logisim.privacy.private", "logisim.privacy.jdk")
            .stream().allMatch(name -> System.getProperty(name) == null);
        try {
            recording.enable(Marker.class).withoutStackTrace().withThreshold(Duration.ZERO);
            new Marker().commit();
            recording.stop();
            recording.dump(recordingPath);
        } finally {
            recording.close();
        }
        Map<String, Long> counts = new TreeMap<>();
        try (RecordingFile file = new RecordingFile(recordingPath)) {
            while (file.hasMoreEvents()) {
                String name = file.readEvent().getEventType().getName();
                counts.merge(name, 1L, Long::sum);
            }
        }
        Map<String, Long> forbiddenCounts = new TreeMap<>();
        FORBIDDEN_EVENTS.forEach(name -> forbiddenCounts.put(name, counts.getOrDefault(name, 0L)));
        byte[] bytes = Files.readAllBytes(recordingPath);
        boolean sentinelBytesAbsent = SENTINELS.values().stream().noneMatch(value ->
            contains(bytes, value.startsWith("-D") ? value.substring(value.indexOf('=') + 1) : value));
        boolean markerPresent = counts.getOrDefault("dev.logisim.PrivacySmokeMarker", 0L) == 1L;
        boolean passed = markerPresent && sentinelBytesAbsent && absentOptionProperties
            && disabledSettings.values().stream().allMatch(Boolean::booleanValue)
            && absentEnvironment.values().stream().allMatch(Boolean::booleanValue)
            && forbiddenCounts.values().stream().allMatch(count -> count == 0L);
        StringBuilder json = new StringBuilder("{\n  \"status\": " + quoted(passed ? "PASS" : "FAIL"));
        json.append(",\n  \"java_version\": ").append(quoted(Runtime.version().toString()));
        json.append(",\n  \"recording_closed_before_decode\": true");
        json.append(",\n  \"marker_count\": ").append(counts.getOrDefault("dev.logisim.PrivacySmokeMarker", 0L));
        json.append(",\n  \"sentinel_bytes_absent\": ").append(sentinelBytesAbsent);
        json.append(",\n  \"option_properties_absent\": ").append(absentOptionProperties);
        appendMap(json, "disabled_settings", disabledSettings);
        appendMap(json, "absent_environment", absentEnvironment);
        appendMap(json, "forbidden_event_counts", forbiddenCounts);
        appendMap(json, "event_counts", counts);
        json.append("\n}\n");
        Files.writeString(receiptPath, json);
        if (!passed) throw new IllegalStateException("Privacy smoke failed; see boolean/count receipt");
    }
}
