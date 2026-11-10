package dev.logisim.mobile.input;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public-SDK input injection confined to the isolated editor test package. */
public final class NativeMixedInput {
    private static final long FRAME_MS = 16;
    private static final long MAX_GESTURE_MS = 5000;

    public enum Tool {
        TOUCH(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER),
        STYLUS(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS),
        MOUSE(InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE);

        final int source;
        final int pointerType;

        Tool(int source, int pointerType) {
            this.source = source;
            this.pointerType = pointerType;
        }
    }

    private final UiAutomation automation;
    private final JSONArray events;
    private final String targetPackage;
    private long sequence;
    private long lastEventTime;

    public NativeMixedInput(Instrumentation instrumentation, JSONArray events) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        this.events = Objects.requireNonNull(events, "events");
        targetPackage = instrumentation.getTargetContext().getPackageName();
        if (!targetPackage.endsWith(".e2e")) {
            throw new SecurityException("Input injection requires an isolated .e2e target: " + targetPackage);
        }
        automation = instrumentation.getUiAutomation();
        record(json("kind", "input-session", "targetPackage", targetPackage,
                "apiLevel", Build.VERSION.SDK_INT, "syntheticEvents", true));
    }

    public void tap(Tool tool, float x, float y) {
        contact("tap", tool, x, y, x, y, FRAME_MS, false, false,
                tool == Tool.MOUSE ? MotionEvent.BUTTON_PRIMARY : 0, null);
    }

    public void drag(Tool tool, float fromX, float fromY, float toX, float toY,
                     long durationMs, boolean cancel) {
        contact("drag", tool, fromX, fromY, toX, toY, durationMs, cancel, true,
                tool == Tool.MOUSE ? MotionEvent.BUTTON_PRIMARY : 0, null);
    }

    public void dragInterrupted(Tool tool, float fromX, float fromY, float toX, float toY,
                                long durationMs, Runnable whilePressed) {
        contact("drag-interrupted", tool, fromX, fromY, toX, toY, durationMs, false, true,
                tool == Tool.MOUSE ? MotionEvent.BUTTON_PRIMARY : 0,
                Objects.requireNonNull(whilePressed, "whilePressed"));
    }

    public void hold(Tool tool, float x, float y, Runnable whilePressed) {
        contact("hold", tool, x, y, x, y, FRAME_MS, false, false,
                tool == Tool.MOUSE ? MotionEvent.BUTTON_PRIMARY : 0,
                Objects.requireNonNull(whilePressed, "whilePressed"));
    }

    public void secondaryTap(float x, float y) {
        contact("secondary-tap", Tool.MOUSE, x, y, x, y, FRAME_MS,
                false, false, MotionEvent.BUTTON_SECONDARY, null);
    }

    public void modifiedMouseTap(float x, float y, int button, int modifiers) {
        if (button != MotionEvent.BUTTON_PRIMARY && button != MotionEvent.BUTTON_SECONDARY &&
                button != MotionEvent.BUTTON_TERTIARY) {
            throw new IllegalArgumentException("A single mouse button is required");
        }
        contact("modified-mouse-tap", Tool.MOUSE, x, y, x, y, FRAME_MS,
                false, false, button, null, modifiers);
    }

    public void scroll(float x, float y, float horizontal, float vertical, boolean ctrl) {
        finite(x, y, horizontal, vertical);
        long operation = begin("scroll");
        Gesture gesture = new Gesture(operation, "scroll", Tool.MOUSE, 1,
                ctrl ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0);
        gesture.position(0, x, y, 0);
        gesture.coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal);
        gesture.coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);
        gesture.emit(MotionEvent.ACTION_SCROLL, 1, 0, false);
    }

    public void key(int keyCode) {
        validateKey(keyCode);
        KeySequence keys = new KeySequence(begin("key"), "key");
        try {
            keys.press(keyCode);
            nextFrame();
            keys.release(keyCode, false);
        } finally {
            keys.releaseRemaining();
        }
    }

    public void chordCtrl(int keyCode) {
        validateKey(keyCode);
        KeySequence keys = new KeySequence(begin("ctrl-chord"), "ctrl-chord");
        try {
            keys.press(KeyEvent.KEYCODE_CTRL_LEFT);
            keys.press(keyCode);
            nextFrame();
            keys.release(keyCode, false);
            keys.release(KeyEvent.KEYCODE_CTRL_LEFT, false);
        } finally {
            keys.releaseRemaining();
        }
    }

    public void pinch(float centerX, float centerY, float startSpan, float endSpan,
                      long durationMs) {
        finite(centerX, centerY, startSpan, endSpan);
        if (startSpan <= 0 || endSpan <= 0) throw new IllegalArgumentException("Pinch spans must be positive");
        int steps = steps(durationMs);
        Gesture gesture = new Gesture(begin("pinch"), "pinch", Tool.TOUCH, 2, 0);
        gesture.position(0, centerX - startSpan / 2, centerY, 1);
        gesture.position(1, centerX + startSpan / 2, centerY, 1);
        try {
            gesture.emit(MotionEvent.ACTION_DOWN, 1, 0, false);
            gesture.emit(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0, false);
            for (int i = 1; i <= steps; i++) {
                nextFrame();
                float span = startSpan + (endSpan - startSpan) * i / steps;
                gesture.position(0, centerX - span / 2, centerY, 1);
                gesture.position(1, centerX + span / 2, centerY, 1);
                gesture.emit(MotionEvent.ACTION_MOVE, 2, 0, false);
            }
            gesture.coords[1].pressure = 0;
            gesture.emit(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0, false);
            gesture.coords[0].pressure = 0;
            gesture.emit(MotionEvent.ACTION_UP, 1, 0, false);
        } finally {
            gesture.cancelIfActive();
        }
    }

    private void contact(String name, Tool tool, float fromX, float fromY,
                         float toX, float toY, long durationMs, boolean cancel,
                         boolean move, int pressedButtons, Runnable whilePressed) {
        contact(name, tool, fromX, fromY, toX, toY, durationMs, cancel, move, pressedButtons, whilePressed, 0);
    }

    private void contact(String name, Tool tool, float fromX, float fromY,
                         float toX, float toY, long durationMs, boolean cancel,
                         boolean move, int pressedButtons, Runnable whilePressed, int modifiers) {
        Objects.requireNonNull(tool, "tool");
        finite(fromX, fromY, toX, toY);
        int steps = steps(durationMs);
        Gesture gesture = new Gesture(begin(name), name, tool, 1, modifiers);
        gesture.position(0, fromX, fromY, 1);
        try {
            gesture.emit(MotionEvent.ACTION_DOWN, 1, pressedButtons, false);
            for (int i = 1; i <= steps; i++) {
                nextFrame();
                if (move) {
                    gesture.position(0, fromX + (toX - fromX) * i / steps,
                            fromY + (toY - fromY) * i / steps, 1);
                    gesture.emit(MotionEvent.ACTION_MOVE, 1, pressedButtons, false);
                }
            }
            if (whilePressed != null) {
                whilePressed.run();
                verifyTarget(name, gesture.operation, "after-callback");
            }
            gesture.coords[0].pressure = 0;
            gesture.emit(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, 1, 0, false);
        } finally {
            gesture.cancelIfActive();
        }
    }

    private final class Gesture {
        final long operation;
        final String name;
        final Tool tool;
        final int metaState;
        final long downTime = eventTime();
        final MotionEvent.PointerProperties[] properties;
        final MotionEvent.PointerCoords[] coords;
        int activePointers;

        Gesture(long operation, String name, Tool tool, int capacity, int metaState) {
            this.operation = operation;
            this.name = name;
            this.tool = tool;
            this.metaState = metaState;
            properties = new MotionEvent.PointerProperties[capacity];
            coords = new MotionEvent.PointerCoords[capacity];
            for (int i = 0; i < capacity; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = tool.pointerType;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].size = 1;
            }
        }

        void position(int index, float x, float y, float pressure) {
            finite(x, y, pressure);
            coords[index].x = x;
            coords[index].y = y;
            coords[index].pressure = pressure;
        }

        void emit(int action, int count, int buttons, boolean cleanup) {
            MotionEvent event = MotionEvent.obtain(downTime, eventTime(), action, count,
                    properties, coords, metaState, buttons, 1, 1, 0, 0, tool.source, 0);
            try {
                JSONArray pointers = new JSONArray();
                for (int i = 0; i < event.getPointerCount(); i++) {
                    pointers.put(json("id", event.getPointerId(i), "toolType", event.getToolType(i),
                            "x", event.getX(i), "y", event.getY(i), "pressure", event.getPressure(i),
                            "horizontalScroll", event.getAxisValue(MotionEvent.AXIS_HSCROLL, i),
                            "verticalScroll", event.getAxisValue(MotionEvent.AXIS_VSCROLL, i)));
                }
                inject(event, json("kind", "motion", "operation", name, "operationId", operation,
                        "tool", tool.name(), "source", event.getSource(), "action", MotionEvent.actionToString(action),
                        "actionMasked", event.getActionMasked(), "actionIndex", event.getActionIndex(),
                        "downTimeMs", event.getDownTime(), "eventTimeMs", event.getEventTime(),
                        "buttons", event.getButtonState(), "metaState", event.getMetaState(),
                        "pointers", pointers, "accepted", JSONObject.NULL, "status", "attempt", "cleanup", cleanup));
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN: activePointers = count; break;
                    case MotionEvent.ACTION_POINTER_UP: activePointers = count - 1; break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: activePointers = 0; break;
                    default: break;
                }
            } finally {
                event.recycle();
            }
        }

        void cancelIfActive() {
            if (activePointers == 0) return;
            for (int i = 0; i < activePointers; i++) coords[i].pressure = 0;
            try { emit(MotionEvent.ACTION_CANCEL, activePointers, 0, true); }
            catch (RuntimeException ignored) { /* Preserve the original rejected-event failure. */ }
        }
    }

    private final class KeySequence {
        final long operation;
        final String name;
        final Map<Integer, Long> pressed = new LinkedHashMap<>();

        KeySequence(long operation, String name) { this.operation = operation; this.name = name; }

        void press(int code) {
            long time = eventTime();
            emit(KeyEvent.ACTION_DOWN, code, time, metaState(code, true), false);
            pressed.put(code, time);
        }

        void release(int code, boolean cleanup) {
            Long down = pressed.get(code);
            if (down == null) return;
            emit(KeyEvent.ACTION_UP, code, down, metaState(code, false), cleanup);
            pressed.remove(code);
        }

        int metaState(int changedCode, boolean down) {
            boolean control = pressed.containsKey(KeyEvent.KEYCODE_CTRL_LEFT);
            if (changedCode == KeyEvent.KEYCODE_CTRL_LEFT) control = down;
            return control ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0;
        }

        void emit(int action, int code, long down, int meta, boolean cleanup) {
            KeyEvent event = new KeyEvent(down, eventTime(), action, code, 0, meta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    cleanup ? KeyEvent.FLAG_CANCELED : 0, InputDevice.SOURCE_KEYBOARD);
            inject(event, json("kind", "key", "operation", name, "operationId", operation,
                    "source", event.getSource(), "action", action == KeyEvent.ACTION_DOWN ? "ACTION_DOWN" : "ACTION_UP",
                    "keyCode", code, "key", KeyEvent.keyCodeToString(code), "downTimeMs", event.getDownTime(),
                    "eventTimeMs", event.getEventTime(), "metaState", meta,
                    "accepted", JSONObject.NULL, "status", "attempt", "cleanup", cleanup));
        }

        void releaseRemaining() {
            List<Integer> codes = new ArrayList<>(pressed.keySet());
            for (int i = codes.size() - 1; i >= 0; i--) {
                try { release(codes.get(i), true); }
                catch (RuntimeException ignored) { /* Preserve the original rejected-event failure. */ }
            }
        }
    }

    private long begin(String operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Input injection must run off the target main thread");
        }
        long id = ++sequence;
        verifyTarget(operation, id, "before");
        return id;
    }

    private void verifyTarget(String operation, long id, String phase) {
        String activePackage = packageOf(automation.getRootInActiveWindow());
        boolean allowed = targetPackage.equals(activePackage);
        String focusedPackage = null;
        if (!allowed) {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                try {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                        focusedPackage = packageOf(window.getRoot());
                        if (targetPackage.equals(focusedPackage)) allowed = true;
                    }
                } finally { window.recycle(); }
            }
        }
        record(json("kind", "foreground-guard", "operation", operation, "operationId", id, "phase", phase,
                "targetPackage", targetPackage, "activePackage", activePackage == null ? JSONObject.NULL : activePackage,
                "focusedPackage", focusedPackage == null ? JSONObject.NULL : focusedPackage, "accepted", allowed));
        if (!allowed) throw new SecurityException("Input target is not foreground: " + targetPackage);
    }

    private static String packageOf(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try { return node.getPackageName() == null ? null : node.getPackageName().toString(); }
        finally { node.recycle(); }
    }

    private void nextFrame() {
        long remaining = lastEventTime + FRAME_MS - SystemClock.uptimeMillis();
        if (remaining > 0) SystemClock.sleep(remaining);
    }

    private long eventTime() {
        long now = SystemClock.uptimeMillis();
        if (now <= lastEventTime) {
            SystemClock.sleep(lastEventTime + 1 - now);
            now = SystemClock.uptimeMillis();
        }
        lastEventTime = now;
        return now;
    }

    private static int steps(long durationMs) {
        if (durationMs < FRAME_MS || durationMs > MAX_GESTURE_MS) {
            throw new IllegalArgumentException("Gesture duration must be between 16 and 5000 ms");
        }
        return (int) ((durationMs + FRAME_MS - 1) / FRAME_MS);
    }

    private static void finite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Input coordinates must be finite");
    }

    private static void validateKey(int code) {
        if (code <= KeyEvent.KEYCODE_UNKNOWN || code > KeyEvent.getMaxKeyCode()
                || KeyEvent.isModifierKey(code) || new KeyEvent(KeyEvent.ACTION_DOWN, code).isSystem()) {
            throw new IllegalArgumentException("System/navigation keys are outside the mixed-input helper");
        }
    }

    private void inject(InputEvent event, JSONObject receipt) {
        record(receipt);
        try {
            boolean accepted = automation.injectInputEvent(event, true);
            receipt.put("accepted", accepted).put("status", accepted ? "accepted" : "rejected");
            if (!accepted) throw new IllegalStateException("Input injection rejected: "
                    + receipt.optString("operation") + " " + receipt.optString("action"));
        } catch (JSONException error) {
            throw new IllegalArgumentException("Cannot encode input acceptance", error);
        } catch (RuntimeException error) {
            try {
                if (!"rejected".equals(receipt.optString("status"))) receipt.put("status", "exception");
                receipt.put("exception", error.getClass().getName()).put("message", error.getMessage());
            } catch (JSONException encodingFailure) { error.addSuppressed(encodingFailure); }
            throw error;
        }
    }

    private void record(JSONObject event) { synchronized (events) { events.put(event); } }

    private static JSONObject json(Object... fields) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        } catch (JSONException error) { throw new IllegalArgumentException("Cannot encode input receipt", error); }
        return result;
    }
}
