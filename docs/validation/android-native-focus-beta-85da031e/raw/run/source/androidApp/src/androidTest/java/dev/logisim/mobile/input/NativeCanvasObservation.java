package dev.logisim.mobile.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Values captured from one complete accessibility tree, without inferred component bounds. */
public final class NativeCanvasObservation {
    private NativeCanvasObservation() {}

    public static final class Point {
        public final double x;
        public final double y;
        public Point(double x, double y) { this.x = x; this.y = y; }
    }

    public static final class Bounds {
        public final int left, top, right, bottom;
        public Bounds(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        boolean positive() { return right > left && bottom > top; }
        Point center() { return new Point((left + (double) right) / 2, (top + (double) bottom) / 2); }
        boolean contains(Point point) {
            return point.x >= left && point.x < right && point.y >= top && point.y < bottom;
        }
        boolean contains(Bounds other) {
            return other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom;
        }
    }

    public static final class Node {
        public final String resourceId;
        public final int windowId;
        public final boolean visible, enabled;
        public final Bounds bounds;
        public Node(String resourceId, int windowId, boolean visible, boolean enabled, Bounds bounds) {
            this.resourceId = resourceId; this.windowId = windowId;
            this.visible = visible; this.enabled = enabled; this.bounds = bounds;
        }
    }

    public static final class Snapshot {
        public final int windowId;
        public final boolean complete;
        public final List<Node> nodes;
        public Snapshot(int windowId, boolean complete, List<Node> nodes) {
            this.windowId = windowId; this.complete = complete;
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        }
    }

    public static final class Frame {
        public final int windowId;
        public final Bounds canvas, inputBounds, outputBounds;
        public final Point input, output;
        private Frame(int windowId, Bounds canvas, Bounds inputBounds, Bounds outputBounds) {
            this.windowId = windowId; this.canvas = canvas;
            this.inputBounds = inputBounds; this.outputBounds = outputBounds;
            this.input = inputBounds.center(); this.output = outputBounds.center();
        }
    }

    public static final class Baseline {
        public final Frame frame;
        public final Bounds targetBounds;
        public final Point target;
        private Baseline(Frame frame, Bounds targetBounds) {
            this.frame = frame; this.targetBounds = targetBounds; this.target = targetBounds.center();
        }
    }

    public static Frame viewport(Snapshot observed) {
        if (!observed.complete) return null;
        Node canvas = unique(observed, "editor-canvas");
        Node input = unique(observed, "canvas-component:mi-input");
        Node output = unique(observed, "canvas-component:mi-output");
        if (canvas == null || input == null || output == null) return null;
        Frame frame = new Frame(observed.windowId, canvas.bounds, input.bounds, output.bounds);
        if (!frame.canvas.contains(frame.input) || !frame.canvas.contains(frame.output) || same(frame.input, frame.output)) return null;
        return frame;
    }

    public static Baseline capture(Snapshot observed, String targetTag) {
        Frame frame = viewport(observed);
        Node target = unique(observed, targetTag);
        if (frame == null || target == null || !frame.canvas.contains(target.bounds.center())) return null;
        return new Baseline(frame, target.bounds);
    }

    public static Point reuse(Baseline before, Snapshot observed) {
        Frame after = viewport(observed);
        if (after == null || before.frame.windowId != after.windowId ||
                !same(before.frame.canvas, after.canvas) || !same(before.frame.input, after.input) ||
                !same(before.frame.output, after.output) || !after.canvas.contains(before.target)) return null;
        return before.target;
    }

    public static Point translate(Baseline before, Snapshot observed, double worldDx, double worldDy, double pixelsPerWorld) {
        Point origin = reuse(before, observed);
        if (origin == null || !Double.isFinite(worldDx) || !Double.isFinite(worldDy) ||
                !Double.isFinite(pixelsPerWorld) || pixelsPerWorld <= 0) return null;
        if (worldDx == 0 && worldDy == 0) return origin;
        Point translated = new Point(origin.x + worldDx * pixelsPerWorld, origin.y + worldDy * pixelsPerWorld);
        return Double.isFinite(translated.x) && Double.isFinite(translated.y) &&
                before.frame.canvas.contains(translated) ? translated : null;
    }

    /** Opt-in for a verified label-only edit; strict movement/cancellation guards stay unchanged. */
    public static Point focusAfterLabelClear(Baseline before, Snapshot observed) {
        Frame after = viewport(observed);
        Frame prior = before.frame;
        if (after == null || prior.windowId != after.windowId || !same(prior.canvas, after.canvas) ||
                !sameSize(prior.inputBounds, after.inputBounds) || !sameSize(prior.outputBounds, after.outputBounds) ||
                !prior.canvas.contains(prior.inputBounds) || !prior.canvas.contains(prior.outputBounds) ||
                !after.canvas.contains(after.inputBounds) || !after.canvas.contains(after.outputBounds)) return null;
        double dx = after.input.x - prior.input.x;
        double dy = after.input.y - prior.input.y;
        if (dx != after.output.x - prior.output.x || dy != after.output.y - prior.output.y) return null;
        if (dx == 0 && dy == 0) return before.target;
        Point translated = new Point(before.target.x + dx, before.target.y + dy);
        return Double.isFinite(translated.x) && Double.isFinite(translated.y) &&
                after.canvas.contains(translated) ? translated : null;
    }

    public static boolean editorReturned(Snapshot observed, int expectedWindowId) {
        return observed.complete && observed.windowId == expectedWindowId &&
                unique(observed, "editor-canvas") != null && unique(observed, "editor-project-toolbar") != null;
    }

    public static Point undoFocus(Snapshot observed, boolean selectActive) {
        Frame frame = selectActive ? viewport(observed) : null;
        return frame == null ? null : frame.input;
    }

    private static Node unique(Snapshot observed, String tag) {
        Node found = null;
        for (Node node : observed.nodes) {
            if (!node.visible || !node.enabled || !matches(node.resourceId, tag)) continue;
            if (found != null) return null;
            found = node;
        }
        return found != null && found.windowId == observed.windowId && found.bounds.positive() ? found : null;
    }

    private static boolean matches(String resourceId, String tag) {
        return tag.equals(resourceId) || (resourceId != null && resourceId.endsWith(":id/" + tag));
    }

    private static boolean same(Point a, Point b) { return a.x == b.x && a.y == b.y; }
    private static boolean same(Bounds a, Bounds b) {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }
    private static boolean sameSize(Bounds a, Bounds b) {
        return (long) a.right - a.left == (long) b.right - b.left &&
                (long) a.bottom - a.top == (long) b.bottom - b.top;
    }
}
