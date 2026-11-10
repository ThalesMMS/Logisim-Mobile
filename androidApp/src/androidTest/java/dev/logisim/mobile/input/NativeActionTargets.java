package dev.logisim.mobile.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves one labelled action inside an already-scoped accessibility subtree. */
public final class NativeActionTargets {
    private NativeActionTargets() {}

    public static final class Node {
        public final int parent;
        public final String text;
        public final String description;
        public final boolean visible;
        public final boolean enabled;
        public final boolean clickable;
        public final boolean selected;
        public final boolean checkable;
        public final boolean checked;

        public Node(int parent, String text, String description, boolean visible,
                    boolean enabled, boolean clickable) {
            this(parent, text, description, visible, enabled, clickable, false, false, false);
        }

        public Node(int parent, String text, String description, boolean visible,
                    boolean enabled, boolean clickable, boolean selected, boolean checkable,
                    boolean checked) {
            this.parent = parent;
            this.text = text;
            this.description = description;
            this.visible = visible;
            this.enabled = enabled;
            this.clickable = clickable;
            this.selected = selected;
            this.checkable = checkable;
            this.checked = checked;
        }
    }

    public enum Selection { UNKNOWN, INACTIVE, ACTIVE, AMBIGUOUS }

    public static final class Action {
        public final int clickTarget;
        public final Selection selection;
        public final List<Integer> semanticNodes;

        private Action(int clickTarget, Selection selection, List<Integer> semanticNodes) {
            this.clickTarget = clickTarget;
            this.selection = selection;
            this.semanticNodes = Collections.unmodifiableList(new ArrayList<>(semanticNodes));
        }
    }

    public static int resolve(List<Node> nodes, Set<String> names) {
        Action action = resolveAction(nodes, names);
        return action == null ? -1 : action.clickTarget;
    }

    public static Action resolveAction(List<Node> nodes, Set<String> names) {
        Set<Integer> targets = new LinkedHashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            if (node.parent >= index || node.parent < -1 || (index > 0 && node.parent < 0)) {
                throw new IllegalArgumentException("Action nodes must be one scoped preorder tree");
            }
            if (!node.visible || !node.enabled || !matches(node, names)) continue;
            int target = owner(nodes, index);
            if (target >= 0) targets.add(target);
        }
        if (targets.size() != 1) return null;
        int target = targets.iterator().next();
        List<Integer> semantics = new ArrayList<>();
        Selection selection = Selection.UNKNOWN;
        for (int index = target; index < nodes.size(); index++) {
            // A nested clickable control owns its own state, even inside this subtree.
            if (owner(nodes, index) != target) continue;
            Node node = nodes.get(index);
            Selection current = selection(node);
            if (current == Selection.UNKNOWN) continue;
            semantics.add(index);
            if (current == Selection.AMBIGUOUS ||
                    (selection != Selection.UNKNOWN && selection != current)) {
                selection = Selection.AMBIGUOUS;
            } else if (selection != Selection.AMBIGUOUS) selection = current;
        }
        return new Action(target, selection, semantics);
    }

    private static int owner(List<Node> nodes, int index) {
        int ancestor = index;
        while (ancestor >= 0) {
            Node node = nodes.get(ancestor);
            if (!node.visible || !node.enabled) return -1;
            if (node.clickable) return ancestor;
            ancestor = node.parent;
        }
        return -1;
    }

    private static Selection selection(Node node) {
        // Android Compose maps Selected to selected for Tab and checked for other roles.
        // A default false selected flag carries no evidence that Selected was present.
        if (node.selected && node.checkable && !node.checked) return Selection.AMBIGUOUS;
        if (node.selected) return Selection.ACTIVE;
        if (node.checkable) return node.checked ? Selection.ACTIVE : Selection.INACTIVE;
        return Selection.UNKNOWN;
    }

    private static boolean matches(Node node, Set<String> names) {
        if (names.contains(node.text) || names.contains(node.description)) return true;
        if (node.description == null) return false;
        int separator = node.description.indexOf(", ");
        return separator >= 0 && names.contains(node.description.substring(0, separator));
    }
}
