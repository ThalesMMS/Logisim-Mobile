package dev.logisim.mobile.documents;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityWindowInfo;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Compares up to four snapshot acquisitions without reconnecting UiAutomation. */
public final class UiSnapshotSeriesInstrumentation extends Instrumentation {
    public static final String QUERY_COUNT = "snapshotQueryCount";
    public static final String QUERY_INDEX = "snapshotQueryIndex";
    public static final String BASE_NONCE = "snapshotBaseNonce";
    public static final String HELPER_PID = "snapshotHelperPid";
    public static final String CONNECTION_UPTIME_MILLIS = "snapshotConnectionUptimeMillis";
    public static final String WINDOWS_BASE64 = "snapshotWindowsBase64";
    public static final String WINDOWS_ERROR = "snapshotWindowsError";

    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            String nonce = arguments.getString(UiSnapshotInstrumentation.NONCE);
            if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,126}")) {
                throw new IllegalArgumentException("snapshotNonce must contain 16 to 126 ASCII letters, digits, underscores or hyphens");
            }
            int queryCount = Integer.parseInt(arguments.getString(QUERY_COUNT, "4"));
            if (queryCount < 1 || queryCount > 4) {
                throw new IllegalArgumentException("snapshotQueryCount must be between 1 and 4");
            }
            result.putString(BASE_NONCE, nonce);
            result.putString(UiSnapshotInstrumentation.NONCE, nonce);
            result.putInt(QUERY_COUNT, queryCount);

            String testPackage = getContext().getPackageName();
            if (!testPackage.endsWith(".e2e.test")
                    || !testPackage.equals(getTargetContext().getPackageName())) {
                throw new SecurityException("Snapshot instrumentation must target its own .e2e.test package");
            }
            String expectedPackage = arguments.getString(UiSnapshotInstrumentation.EXPECTED_PACKAGE);
            if (expectedPackage == null) {
                String helperSuffix = ".snapshot.e2e.test";
                if (!testPackage.endsWith(helperSuffix)) {
                    throw new IllegalArgumentException("snapshotExpectedPackage is required for a helper without the .snapshot.e2e.test suffix");
                }
                expectedPackage = testPackage.substring(0, testPackage.length() - helperSuffix.length()) + ".e2e";
            }
            if (!expectedPackage.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) {
                throw new IllegalArgumentException("snapshotExpectedPackage must be an Android package name");
            }
            result.putString(UiSnapshotInstrumentation.EXPECTED_PACKAGE, expectedPackage);

            long connectionUptimeMillis = SystemClock.uptimeMillis();
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            if (automation == null) {
                throw new IllegalStateException("UiAutomation did not connect");
            }
            AccessibilityServiceInfo service = automation.getServiceInfo();
            if (service == null) {
                throw new IllegalStateException("UiAutomation returned no accessibility service info");
            }
            service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(service);
            result.putInt(HELPER_PID, Process.myPid());
            result.putLong(CONNECTION_UPTIME_MILLIS, connectionUptimeMillis);

            int failures = 0;
            for (int index = 0; index < queryCount; index++) {
                Bundle sample = UiSnapshotInstrumentation.capture(
                        automation, getContext(), expectedPackage, nonce + "-" + index);
                sample.putString(BASE_NONCE, nonce);
                sample.putInt(QUERY_INDEX, index);
                sample.putInt(QUERY_COUNT, queryCount);
                sample.putInt(HELPER_PID, Process.myPid());
                sample.putLong(CONNECTION_UPTIME_MILLIS, connectionUptimeMillis);
                boolean failed = sample.containsKey(UiSnapshotInstrumentation.ERROR);
                if (failed) {
                    failures++;
                }
                sendStatus(failed ? Activity.RESULT_CANCELED : Activity.RESULT_OK, sample);
            }
            result.putInt("snapshotQueryFailures", failures);
            result.putInt("snapshotQuerySuccesses", queryCount - failures);
            try {
                result.putString(WINDOWS_BASE64, windowCensus(automation));
            } catch (Exception error) {
                result.putString(WINDOWS_ERROR, error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            resultCode = failures == 0 && !result.containsKey(WINDOWS_ERROR)
                    ? Activity.RESULT_OK : Activity.RESULT_CANCELED;
        } catch (Exception error) {
            result.putString(UiSnapshotInstrumentation.ERROR,
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        finish(resultCode, result);
    }

    private static String windowCensus(UiAutomation automation) throws JSONException {
        JSONArray census = new JSONArray();
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                census.put(new JSONObject()
                        .put("id", window.getId())
                        .put("type", window.getType())
                        .put("active", window.isActive())
                        .put("focused", window.isFocused()));
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                window.recycle();
            }
        }
        return Base64.encodeToString(census.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
