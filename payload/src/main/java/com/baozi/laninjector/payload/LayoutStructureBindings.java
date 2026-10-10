package com.baozi.laninjector.payload;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.view.View;
import android.view.ViewGroup;
import org.xmlpull.v1.XmlPullParser;
import java.util.*;

/** Locate anonymous labels by a complete layout subtree with explicit ID anchors. */
final class LayoutStructureBindings {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    private static final Map<Resources, Map<String, List<Node>>> cache = new WeakHashMap<>();
    private static final Map<View, int[]> known = new WeakHashMap<>();
    private static final Map<View, Snapshot> snapshots = new WeakHashMap<>();

    private static final class Snapshot {
        final List<java.lang.ref.WeakReference<View>> views = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        final List<Integer> childCounts = new ArrayList<>();
        Object index;
    }

    private static void snapshot(View view, Snapshot result) {
        if (view instanceof FloatingBallView || view instanceof LanguagePanelView) return;
        result.views.add(new java.lang.ref.WeakReference<>(view)); result.ids.add(view.getId());
        result.childCounts.add(view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) snapshot(group.getChildAt(i), result);
        }
    }

    private static boolean same(Snapshot first, Snapshot second) {
        if (first == null || first.index != second.index || first.views.size() != second.views.size()) return false;
        for (int i = 0; i < first.views.size(); i++) {
            if (first.views.get(i).get() != second.views.get(i).get() || !first.ids.get(i).equals(second.ids.get(i))
                    || !first.childCounts.get(i).equals(second.childCounts.get(i))) return false;
        }
        return true;
    }

    static int[] get(View view) { return known.get(view); }

    static void capture(View root) {
        long start = android.os.SystemClock.elapsedRealtime();
        Snapshot current = new Snapshot(); snapshot(root, current);
        if (current.views.isEmpty()) return;
        Map<String, List<Node>> nodes = cache.get(root.getResources());
        int parsed = 0;
        if (nodes == null || (nodes.isEmpty() && !ResourceStringIndex.layouts().isEmpty())) {
            nodes = new HashMap<>();
            for (int id : ResourceStringIndex.layouts()) {
                try {
                    Node node = parse(root.getContext(), id); parsed++;
                    if (node != null && node.anchors >= 2) nodes.computeIfAbsent(node.tag, key -> new ArrayList<>()).add(node);
                } catch (Exception ignored) { }
            }
            cache.put(root.getResources(), nodes);
        }
        long ready = android.os.SystemClock.elapsedRealtime();
        current.index = nodes;
        if (same(snapshots.get(root), current)) {
            android.util.Log.d("LanInjector", "LocalePerf layout: reused=true matchMs="
                    + (android.os.SystemClock.elapsedRealtime() - ready) + " views=" + current.views.size() + " candidates=0");
            return;
        }
        int[] counts = new int[2];
        visit(root, nodes, counts);
        snapshots.put(root, current);
        android.util.Log.d("LanInjector", "LocalePerf layout: parseMs=" + (ready - start)
                + " matchMs=" + (android.os.SystemClock.elapsedRealtime() - ready)
                + " parsed=" + parsed + " views=" + counts[0] + " candidates=" + counts[1]);
    }

    private static void visit(View view, Map<String, List<Node>> nodes, int[] counts) {
        if (view instanceof FloatingBallView || view instanceof LanguagePanelView) return;
        counts[0]++;
        Set<Node> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> cls = view.getClass(); cls != null; cls = cls.getSuperclass()) {
            List<Node> full = nodes.get(cls.getName()), simple = nodes.get(cls.getSimpleName());
            if (full != null) candidates.addAll(full);
            if (simple != null) candidates.addAll(simple);
        }
        for (Node node : candidates) {
            if (node.id != 0 && node.id != view.getId()) continue;
            counts[1]++;
            // Validate first; failed comparisons allocate no per-label binding maps.
            if (!match(view, node, null)) continue;
            Map<View, int[]> found = new IdentityHashMap<>();
            match(view, node, found);
            for (Map.Entry<View, int[]> entry : found.entrySet()) {
                int[] prior = known.get(entry.getKey()), next = entry.getValue();
                if (prior == null) known.put(entry.getKey(), next.clone());
                else for (int i = 0; i < 2; i++) if (prior[i] != next[i]) prior[i] = 0;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i), nodes, counts);
        }
    }

    private static boolean match(View view, Node node, Map<View, int[]> result) {
        if (node.id != 0 && view.getId() != node.id) return false;
        boolean type = false;
        for (Class<?> cls = view.getClass(); cls != null; cls = cls.getSuperclass()) {
            if (cls.getName().equals(node.tag) || cls.getSimpleName().equals(node.tag)) { type = true; break; }
        }
        if (!type) return false;
        int count = view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0;
        // RecyclerView and other adapter containers add children after inflation.
        // Empty XML containers provide no label bindings to infer from those children.
        if (!node.children.isEmpty() && count != node.children.size()) return false;
        for (int i = 0; i < node.children.size(); i++) {
            if (!match(((ViewGroup) view).getChildAt(i), node.children.get(i), result)) return false;
        }
        if (result != null && (node.labels[0] != 0 || node.labels[1] != 0)) result.put(view, node.labels);
        return true;
    }

    private static Node parse(Context context, int id) throws Exception {
        Node root = null;
        Deque<Node> stack = new ArrayDeque<>();
        try (XmlResourceParser parser = context.getResources().getLayout(id)) {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    Node node = new Node(); node.tag = parser.getName();
                    if (node.tag.equals("include") || node.tag.equals("merge") || node.tag.equals("fragment")) return null;
                    node.id = parser.getAttributeResourceValue(NS, "id", 0);
                    TypedArray attrs = context.obtainStyledAttributes(parser,
                            new int[]{android.R.attr.text, android.R.attr.hint});
                    try { node.labels = new int[]{attrs.getResourceId(0, 0), attrs.getResourceId(1, 0)}; }
                    finally { attrs.recycle(); }
                    if (stack.isEmpty()) root = node; else stack.peek().children.add(node);
                    stack.push(node);
                } else if (event == XmlPullParser.END_TAG && !stack.isEmpty()) {
                    Node node = stack.pop(); node.anchors += node.id == 0 ? 0 : 1;
                    if (!stack.isEmpty()) stack.peek().anchors += node.anchors;
                }
            }
        }
        return root;
    }

    private static final class Node {
        String tag; int id; int anchors; int[] labels;
        final List<Node> children = new ArrayList<>();
    }
}
