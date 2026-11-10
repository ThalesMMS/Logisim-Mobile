package dev.logisim.mobile.documents;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Xml;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;

/** Reads the active accessibility tree without waiting for UI idle or starting an activity. */
public final class UiSnapshotInstrumentation extends Instrumentation {
    public static final String NONCE = "snapshotNonce";
    public static final String XML_BASE64 = "snapshotXmlBase64";
    public static final String UPTIME_MILLIS = "snapshotUptimeMillis";
    public static final String ROTATION = "snapshotRotation";
    public static final String ROOT_ATTEMPTS = "snapshotRootAttempts";
    public static final String ROOT_WAIT_MILLIS = "snapshotRootWaitMillis";
    public static final String EXPECTED_PACKAGE = "snapshotExpectedPackage";
    public static final String ROOT_SOURCE = "snapshotRootSource";
    public static final String ROOT_WINDOW_ID = "snapshotRootWindowId";
    public static final String CAPTURE_ATTEMPTS = "snapshotCaptureAttempts";
    public static final String CAPTURE_STATE = "snapshotCaptureState";
    public static final String CAPTURE_ELAPSED_MILLIS = "snapshotCaptureElapsedMillis";
    public static final String CAPTURE_OVERRUN_MILLIS = "snapshotCaptureOverrunMillis";
    public static final String INCOMPLETE_TREE_ATTEMPTS = "snapshotIncompleteTreeAttempts";
    public static final String TRAVERSAL_NODE_COUNT = "snapshotTraversalNodeCount";
    public static final String ERROR = "snapshotError";

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
            String nonce = arguments.getString(NONCE);
            if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{16,128}")) {
                throw new IllegalArgumentException("snapshotNonce must contain 16 to 128 ASCII letters, digits, underscores or hyphens");
            }
            result.putString(NONCE, nonce);

            String testPackage = getContext().getPackageName();
            if (!testPackage.endsWith(".e2e.test")
                    || !testPackage.equals(getTargetContext().getPackageName())) {
                throw new SecurityException("Snapshot instrumentation must target its own .e2e.test package");
            }
            String expectedPackage = arguments.getString(EXPECTED_PACKAGE);
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
            result.putString(EXPECTED_PACKAGE, expectedPackage);

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

            result.putAll(capture(automation, getContext(), expectedPackage, nonce));
            resultCode = result.containsKey(ERROR) ? Activity.RESULT_CANCELED : Activity.RESULT_OK;
        } catch (Exception error) {
            result.putString(ERROR, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        finish(resultCode, result);
    }

    static Bundle capture(UiAutomation automation, Context context, String expectedPackage, String nonce) {
        Bundle result = new Bundle();
        result.putString(NONCE, nonce);
        result.putString(EXPECTED_PACKAGE, expectedPackage);
        result.putInt(ROOT_ATTEMPTS, 0);
        result.putLong(ROOT_WAIT_MILLIS, 0);
        SnapshotCaptureRetry.Result<Bundle> capture = SnapshotCaptureRetry.run(
                new SnapshotCaptureRetry.Clock() {
                    @Override public long uptimeMillis() { return SystemClock.uptimeMillis(); }
                    @Override public void sleep(long millis) { SystemClock.sleep(millis); }
                }, deadline -> captureAttempt(automation, context, expectedPackage, nonce, result, deadline));
        if (capture.value != null) {
            result.putAll(capture.value);
        }
        result.putInt(CAPTURE_ATTEMPTS, capture.attempts);
        result.putString(CAPTURE_STATE, capture.state.name());
        result.putLong(CAPTURE_ELAPSED_MILLIS, capture.elapsedMillis);
        result.putLong(CAPTURE_OVERRUN_MILLIS, capture.overrunMillis);
        result.putInt(INCOMPLETE_TREE_ATTEMPTS, capture.incompleteAttempts);
        result.putInt("snapshotCaptureAttemptRecordsDropped", capture.droppedRecords);
        try {
            JSONArray records = new JSONArray();
            for (SnapshotCaptureRetry.Record record : capture.records) {
                JSONObject entry = new JSONObject().put("attempt", record.attempt)
                        .put("startedUptimeMillis", record.startedUptimeMillis)
                        .put("elapsedMillis", record.elapsedMillis).put("state", record.state.name());
                if (record.error != null) {
                    entry.put("error", record.error.getClass().getSimpleName() + ": " + record.error.getMessage());
                }
                if (record.error instanceof SnapshotCaptureRetry.IncompleteTreeException) {
                    SnapshotCaptureRetry.IncompleteTreeException incomplete =
                            (SnapshotCaptureRetry.IncompleteTreeException) record.error;
                    entry.put("parentPath", incomplete.parentPath).put("childIndex", incomplete.childIndex)
                            .put("parentChildCount", incomplete.parentChildCount).put("windowId", incomplete.windowId)
                            .put("nodeCount", incomplete.nodeCount);
                    result.putString("snapshotLastIncompleteParentPath", incomplete.parentPath);
                    result.putInt("snapshotLastIncompleteChildIndex", incomplete.childIndex);
                    result.putInt("snapshotLastIncompleteParentChildCount", incomplete.parentChildCount);
                    result.putInt("snapshotLastIncompleteWindowId", incomplete.windowId);
                    result.putInt("snapshotLastIncompleteNodeCount", incomplete.nodeCount);
                }
                records.put(entry);
            }
            result.putString("snapshotCaptureAttemptRecordsBase64", Base64.encodeToString(
                    records.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (org.json.JSONException error) {
            result.putString("snapshotCaptureDiagnosticsError", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        if (capture.error != null) {
            result.putString(ERROR, capture.error.getClass().getSimpleName() + ": " + capture.error.getMessage());
        }
        return result;
    }

    private static Bundle captureAttempt(UiAutomation automation, Context context, String expectedPackage,
            String nonce, Bundle result, long rootDeadline) throws Exception {
        AccessibilityNodeInfo root = null;
        TreeProgress progress = new TreeProgress();
        Bundle completed = new Bundle();
        try {
            long rootStarted = SystemClock.uptimeMillis();
            result.putString(ROOT_SOURCE, "unavailable");
            result.putInt(ROOT_WINDOW_ID, -1);
            try {
                do {
                    result.putInt(ROOT_ATTEMPTS, result.getInt(ROOT_ATTEMPTS) + 1);
                    root = automation.getRootInActiveWindow();
                    if (root != null) {
                        result.putString(ROOT_SOURCE, "active-window");
                        result.putInt(ROOT_WINDOW_ID, root.getWindowId());
                        break;
                    }
                    if (SystemClock.uptimeMillis() < rootDeadline) {
                        root = windowRoot(automation, expectedPackage, rootDeadline, result);
                    }
                    if (root != null) {
                        break;
                    }
                    long remaining = rootDeadline - SystemClock.uptimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    SystemClock.sleep(Math.min(25, remaining));
                } while (SystemClock.uptimeMillis() < rootDeadline);
            } finally {
                result.putLong(ROOT_WAIT_MILLIS, result.getLong(ROOT_WAIT_MILLIS)
                        + SystemClock.uptimeMillis() - rootStarted);
            }
            if (root == null) {
                throw new SnapshotCaptureRetry.RootUnavailableException(expectedPackage);
            }
            long uptimeMillis = SystemClock.uptimeMillis();
            int rotation = rotationFor(context, root);
            StringWriter output = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(output);
            xml.startDocument("UTF-8", true);
            xml.startTag("", "hierarchy");
            xml.attribute("", "rotation", Integer.toString(rotation));
            xml.attribute("", "snapshot-nonce", nonce);
            xml.attribute("", "uptime-millis", Long.toString(uptimeMillis));
            writeNode(xml, root, 0, "0", progress);
            xml.endTag("", "hierarchy");
            xml.endDocument();
            completed.putString(XML_BASE64, Base64.encodeToString(
                    output.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            completed.putLong(UPTIME_MILLIS, uptimeMillis);
            completed.putInt(ROTATION, rotation);
        } finally {
            result.putInt(TRAVERSAL_NODE_COUNT, progress.nodeCount);
            if (root != null) {
                root.recycle();
            }
        }
        return completed;
    }

    private static AccessibilityNodeInfo windowRoot(UiAutomation automation, String expectedPackage,
            long deadline, Bundle result) {
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        try {
            for (int priority = 0; priority < 2; priority++) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                            || (priority == 0 ? !window.isActive() : window.isActive() || !window.isFocused())) {
                        continue;
                    }
                    if (SystemClock.uptimeMillis() >= deadline) {
                        return null;
                    }
                    result.putInt(ROOT_ATTEMPTS, result.getInt(ROOT_ATTEMPTS) + 1);
                    AccessibilityNodeInfo candidate = window.getRoot();
                    if (candidate == null) {
                        continue;
                    }
                    boolean accepted = false;
                    try {
                        CharSequence packageName = candidate.getPackageName();
                        accepted = packageName != null && expectedPackage.contentEquals(packageName)
                                && candidate.getWindowId() == window.getId();
                        if (accepted) {
                            result.putString(ROOT_SOURCE, priority == 0 ? "window-active" : "window-focused");
                            result.putInt(ROOT_WINDOW_ID, window.getId());
                            return candidate;
                        }
                    } finally {
                        if (!accepted) {
                            candidate.recycle();
                        }
                    }
                }
            }
            return null;
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                window.recycle();
            }
        }
    }

    private static int rotationFor(Context context, AccessibilityNodeInfo root) {
        int displayId = Display.DEFAULT_DISPLAY;
        AccessibilityWindowInfo window = root.getWindow();
        if (window != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayId = window.getDisplayId();
            }
            window.recycle();
        }
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(displayId);
        if (display == null) {
            throw new IllegalStateException("Active window display is unavailable: " + displayId);
        }
        return display.getRotation();
    }

    private static final class TreeProgress {
        int nodeCount;
    }

    private static void writeNode(XmlSerializer xml, AccessibilityNodeInfo node, int index,
            String path, TreeProgress progress) throws IOException {
        progress.nodeCount++;
        xml.startTag("", "node");
        xml.attribute("", "index", Integer.toString(index));
        attribute(xml, "text", node.getText());
        attribute(xml, "resource-id", node.getViewIdResourceName());
        attribute(xml, "class", node.getClassName());
        attribute(xml, "package", node.getPackageName());
        attribute(xml, "content-desc", node.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attribute(xml, "state-desc", node.getStateDescription());
        }
        xml.attribute("", "checkable", Boolean.toString(node.isCheckable()));
        xml.attribute("", "checked", Boolean.toString(node.isChecked()));
        xml.attribute("", "clickable", Boolean.toString(node.isClickable()));
        xml.attribute("", "enabled", Boolean.toString(node.isEnabled()));
        xml.attribute("", "focusable", Boolean.toString(node.isFocusable()));
        xml.attribute("", "focused", Boolean.toString(node.isFocused()));
        xml.attribute("", "accessibility-focused", Boolean.toString(node.isAccessibilityFocused()));
        xml.attribute("", "scrollable", Boolean.toString(node.isScrollable()));
        xml.attribute("", "long-clickable", Boolean.toString(node.isLongClickable()));
        xml.attribute("", "password", Boolean.toString(node.isPassword()));
        xml.attribute("", "selected", Boolean.toString(node.isSelected()));
        xml.attribute("", "visible-to-user", Boolean.toString(node.isVisibleToUser()));
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.attribute("", "bounds", bounds.toShortString());

        int count = node.getChildCount();
        for (int childIndex = 0; childIndex < count; childIndex++) {
            AccessibilityNodeInfo child = node.getChild(childIndex);
            if (child == null) {
                throw new SnapshotCaptureRetry.IncompleteTreeException(
                        path, childIndex, count, node.getWindowId(), progress.nodeCount);
            }
            try {
                writeNode(xml, child, childIndex, path + "/" + childIndex, progress);
            } finally {
                child.recycle();
            }
        }
        xml.endTag("", "node");
    }

    private static void attribute(XmlSerializer xml, String name, CharSequence value) throws IOException {
        String text = value == null ? "" : value.toString();
        StringBuilder clean = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (codePoint == 9 || codePoint == 10 || codePoint == 13
                    || codePoint >= 0x20 && codePoint <= 0xd7ff
                    || codePoint >= 0xe000 && codePoint <= 0xfffd
                    || codePoint >= 0x10000 && codePoint <= 0x10ffff) {
                clean.appendCodePoint(codePoint);
            } else {
                clean.append('\ufffd');
            }
            offset += Character.charCount(codePoint);
        }
        xml.attribute("", name, clean.toString());
    }
}
