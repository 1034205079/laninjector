package com.baozi.laninjector.payload;

import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Repair cached resource labels when existing windows rebind or recycle their views. */
final class LiveRebindGuard implements ViewTreeObserver.OnGlobalLayoutListener {
    private enum Slot { TEXT, HINT, DESCRIPTION, TOOLTIP }
    private static final Map<View, WeakReference<LiveRebindGuard>> guards = new WeakHashMap<>();
    private final List<WeakReference<View>> roots = new ArrayList<>();
    private final Map<Resources, ResourceLabelCatalogue> sources = new IdentityHashMap<>();
    private final Map<Resources, Resources> translations;
    private final Map<View, Map<Slot, String>> rendered = new WeakHashMap<>();
    private final LiveMenuLocaleRefresh menus;
    private final LivePropertyLocaleRefresh properties;
    private boolean active = true;
    private boolean repairing;

    static void addOriginals(List<View> roots, Map<Resources, ResourceLabelCatalogue> catalogues) {
        for (View root : roots) {
            WeakReference<LiveRebindGuard> reference = guards.get(root);
            LiveRebindGuard guard = reference == null ? null : reference.get();
            if (guard == null) continue;
            for (Map.Entry<Resources, ResourceLabelCatalogue> entry : guard.sources.entrySet()) {
                ResourceLabelCatalogue current = catalogues.get(entry.getKey());
                if (current != null) current.addOriginalSnapshot(entry.getValue());
            }
        }
    }

    static void install(List<View> views, Map<Resources, ResourceLabelCatalogue> sources,
                        Map<Resources, Resources> translations, LiveMenuLocaleRefresh menus,
                        LivePropertyLocaleRefresh properties) {
        LiveRebindGuard guard = new LiveRebindGuard(sources, translations, menus, properties);
        for (View root : views) {
            WeakReference<LiveRebindGuard> reference = guards.get(root);
            LiveRebindGuard old = reference == null ? null : reference.get();
            if (old != null) {
                // Keep original resource snapshots: adapters may still hold those strings.
                for (Map.Entry<Resources, ResourceLabelCatalogue> entry : old.sources.entrySet()) {
                    ResourceLabelCatalogue current = guard.sources.get(entry.getKey());
                    if (current == null) guard.sources.put(entry.getKey(), entry.getValue());
                    else current.addOriginalSnapshot(entry.getValue());
                }
                old.stop();
            }
            guard.roots.add(new WeakReference<>(root));
            guard.walk(root, true);
            root.getViewTreeObserver().addOnGlobalLayoutListener(guard);
            guards.put(root, new WeakReference<>(guard));
        }
    }

    private LiveRebindGuard(Map<Resources, ResourceLabelCatalogue> sources,
                            Map<Resources, Resources> translations, LiveMenuLocaleRefresh menus,
                            LivePropertyLocaleRefresh properties) {
        this.sources.putAll(sources);
        this.translations = new IdentityHashMap<>(translations);
        this.menus = menus;
        this.properties = properties;
    }

    private void stop() {
        active = false;
        for (WeakReference<View> reference : roots) {
            View root = reference.get();
            if (root != null && root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        }
    }

    @Override public void onGlobalLayout() {
        if (!active || repairing) return;
        repairing = true;
        try {
            menus.refresh(translations, false);
            properties.refresh(translations, false);
            for (WeakReference<View> reference : roots) {
                View root = reference.get();
                if (root != null && root.isAttachedToWindow()) walk(root, false);
            }
        } catch (Exception e) { Log.w("LanInjector", "Resource rebind repair skipped", e); }
        finally { repairing = false; }
    }

    private void walk(View view, boolean seed) {
        if (view instanceof FloatingBallView || view instanceof LanguagePanelView) return;
        if (view instanceof TextView) {
            if (!(view instanceof EditText)) repair(view, Slot.TEXT, seed);
            repair(view, Slot.HINT, seed);
        }
        repair(view, Slot.DESCRIPTION, seed);
        if (Build.VERSION.SDK_INT >= 26) repair(view, Slot.TOOLTIP, seed);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), seed);
        }
    }

    private void repair(View view, Slot slot, boolean seed) {
        CharSequence current = read(view, slot);
        if (TextUtils.isEmpty(current)) return;
        Map<Slot, String> record = rendered.get(view);
        if (record == null) { record = new EnumMap<>(Slot.class); rendered.put(view, record); }
        if (seed || TextUtils.equals(record.get(slot), current)) {
            record.put(slot, current.toString());
            return;
        }
        ResourceLabelCatalogue catalogue = sources.get(view.getResources());
        Resources localized = translations.get(view.getResources());
        if (catalogue == null || localized == null) return;
        CharSequence translated = null;
        boolean conflict = false;
        int[] binding = LayoutStructureBindings.get(view);
        int resource = binding == null ? 0 : slot == Slot.TEXT ? binding[0] : slot == Slot.HINT ? binding[1] : 0;
        List<ResourceLabelCatalogue.Candidate> candidates = catalogue.match(current);
        if (resource != 0) {
            List<ResourceLabelCatalogue.Candidate> narrowed = new ArrayList<>();
            for (ResourceLabelCatalogue.Candidate candidate : candidates) if (candidate.id == resource) narrowed.add(candidate);
            if (!narrowed.isEmpty()) candidates = narrowed;
        }
        for (ResourceLabelCatalogue.Candidate candidate : candidates) {
            try {
                CharSequence next = candidate.translate(localized);
                if (next == null || (translated != null && !TextUtils.equals(translated, next))) {
                    conflict = true; break;
                }
                translated = next;
            } catch (Resources.NotFoundException ignored) { }
        }
        if (!conflict && translated != null && !TextUtils.equals(current, translated)) {
            write(view, slot, translated);
            record.put(slot, translated.toString());
        } else record.put(slot, current.toString());
    }

    private CharSequence read(View view, Slot slot) {
        switch (slot) {
            case TEXT: return ((TextView) view).getText();
            case HINT: return ((TextView) view).getHint();
            case TOOLTIP: return view.getTooltipText();
            default: return view.getContentDescription();
        }
    }

    private void write(View view, Slot slot, CharSequence value) {
        switch (slot) {
            case TEXT: ((TextView) view).setText(value); break;
            case HINT: ((TextView) view).setHint(value); break;
            case TOOLTIP: view.setTooltipText(value); break;
            default: view.setContentDescription(value);
        }
    }
}
