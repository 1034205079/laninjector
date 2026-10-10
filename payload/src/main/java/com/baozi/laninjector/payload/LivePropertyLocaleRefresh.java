package com.baozi.laninjector.payload;

import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import android.widget.ToggleButton;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Refresh accessibility and public widget title models without replacing widgets. */
final class LivePropertyLocaleRefresh {
    private enum Property {
        DESCRIPTION, TOOLTIP, PANE, STATE, ON, OFF, TITLE, SUBTITLE, NAVIGATION, TAB
    }

    private static final Map<Object, Map<Property, Saved>> saved = new WeakHashMap<>();
    private final Map<Resources, ResourceLabelCatalogue> catalogues = new IdentityHashMap<>();
    private final List<Binding> bindings = new ArrayList<>();

    void useCatalogues(Map<Resources, ResourceLabelCatalogue> shared) { catalogues.putAll(shared); }

    void capture(View view) {
        capture(view, view, Property.DESCRIPTION);
        if (Build.VERSION.SDK_INT >= 26) capture(view, view, Property.TOOLTIP);
        if (Build.VERSION.SDK_INT >= 28) capture(view, view, Property.PANE);
        if (Build.VERSION.SDK_INT >= 30) capture(view, view, Property.STATE);
        if (view instanceof Switch || view instanceof ToggleButton) {
            capture(view, view, Property.ON);
            capture(view, view, Property.OFF);
        }
        if (named(view, "android.widget.Toolbar") || named(view, "androidx.appcompat.widget.Toolbar")) {
            capture(view, view, Property.TITLE);
            capture(view, view, Property.SUBTITLE);
            capture(view, view, Property.NAVIGATION);
        } else if (named(view, "com.google.android.material.appbar.CollapsingToolbarLayout")) {
            capture(view, view, Property.TITLE);
        } else if (named(view, "com.google.android.material.tabs.TabLayout")) {
            try {
                int count = (Integer) view.getClass().getMethod("getTabCount").invoke(view);
                Method getTab = view.getClass().getMethod("getTabAt", int.class);
                for (int i = 0; i < count; i++) {
                    Object tab = getTab.invoke(view, i);
                    if (tab != null) capture(view, tab, Property.TAB);
                }
            } catch (Exception e) { Log.d("LanInjector", "TabLayout text API unavailable"); }
        }
    }

    private boolean named(View view, String name) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private void capture(View owner, Object target, Property property) {
        try {
            CharSequence current = read(target, property);
            if (TextUtils.isEmpty(current)) return;
            Map<Property, Saved> prior = saved.get(target);
            Saved resolved = prior == null ? null : prior.get(property);
            List<ResourceLabelCatalogue.Candidate> candidates;
            if (resolved != null && (TextUtils.equals(resolved.translated, current)
                    || TextUtils.equals(resolved.original, current))) {
                candidates = resolved.candidates;
            } else {
                ResourceLabelCatalogue catalogue = catalogues.get(owner.getResources());
                if (catalogue == null) {
                    catalogue = new ResourceLabelCatalogue(owner.getResources());
                    catalogues.put(owner.getResources(), catalogue);
                }
                candidates = catalogue.match(current);
            }
            if (!candidates.isEmpty()) bindings.add(new Binding(owner, target, property, candidates, current.toString()));
        } catch (Exception ignored) { }
    }

    int refresh(Map<Resources, Resources> translations) {
        return refresh(translations, true);
    }

    int refresh(Map<Resources, Resources> translations, boolean log) {
        int changed = 0;
        for (Binding binding : bindings) {
            if (!binding.owner.isAttachedToWindow()) continue;
            try {
                Map<Property, Saved> savedRecord = saved.get(binding.target);
                Saved existing = savedRecord == null ? null : savedRecord.get(binding.property);
                CharSequence current = read(binding.target, binding.property);
                if (!TextUtils.equals(current, binding.previous)
                        && (existing == null || !TextUtils.equals(current, existing.original))) continue;
                Resources resources = translations.get(binding.owner.getResources());
                if (resources == null) resources = binding.owner.getResources();
                CharSequence translated = null;
                boolean conflict = false;
                for (ResourceLabelCatalogue.Candidate candidate : binding.candidates) {
                    CharSequence next = candidate.translate(resources);
                    if (next == null || (translated != null && !TextUtils.equals(translated, next))) {
                        conflict = true; break;
                    }
                    translated = next;
                }
                if (conflict || translated == null) continue;
                Map<Property, Saved> record = saved.get(binding.target);
                if (record == null) { record = new EnumMap<>(Property.class); saved.put(binding.target, record); }
                Saved prior = record.get(binding.property);
                record.put(binding.property, new Saved(binding.candidates,
                        prior == null ? binding.previous : prior.original, translated.toString()));
                if (!TextUtils.equals(translated, current)) {
                    write(binding.target, binding.property, translated);
                    changed++;
                }
            } catch (Exception e) { Log.d("LanInjector", "Widget property refresh skipped: " + binding.property); }
        }
        if (log) Log.d("LanInjector", "Widget property refresh: " + changed + " changed, " + bindings.size() + " bindings");
        return changed;
    }

    private CharSequence read(Object target, Property property) throws Exception {
        switch (property) {
            case DESCRIPTION: return ((View) target).getContentDescription();
            case TOOLTIP: return ((View) target).getTooltipText();
            case PANE: return ((View) target).getAccessibilityPaneTitle();
            case STATE: return ((View) target).getStateDescription();
            case ON: return target instanceof Switch ? ((Switch) target).getTextOn() : ((ToggleButton) target).getTextOn();
            case OFF: return target instanceof Switch ? ((Switch) target).getTextOff() : ((ToggleButton) target).getTextOff();
            default: return (CharSequence) target.getClass().getMethod(getter(property)).invoke(target);
        }
    }

    private void write(Object target, Property property, CharSequence text) throws Exception {
        switch (property) {
            case DESCRIPTION: ((View) target).setContentDescription(text); break;
            case TOOLTIP: ((View) target).setTooltipText(text); break;
            case PANE: ((View) target).setAccessibilityPaneTitle(text); break;
            case STATE: ((View) target).setStateDescription(text); break;
            case ON:
                if (target instanceof Switch) ((Switch) target).setTextOn(text); else ((ToggleButton) target).setTextOn(text);
                break;
            case OFF:
                if (target instanceof Switch) ((Switch) target).setTextOff(text); else ((ToggleButton) target).setTextOff(text);
                break;
            default: target.getClass().getMethod("set" + getter(property).substring(3), CharSequence.class).invoke(target, text);
        }
    }

    private String getter(Property property) {
        switch (property) {
            case TITLE: return "getTitle";
            case SUBTITLE: return "getSubtitle";
            case NAVIGATION: return "getNavigationContentDescription";
            default: return "getText";
        }
    }

    private static final class Binding {
        final View owner;
        final Object target;
        final Property property;
        final List<ResourceLabelCatalogue.Candidate> candidates;
        final String previous;
        Binding(View owner, Object target, Property property, List<ResourceLabelCatalogue.Candidate> candidates, String previous) {
            this.owner = owner; this.target = target; this.property = property; this.candidates = candidates; this.previous = previous;
        }
    }

    private static final class Saved {
        final List<ResourceLabelCatalogue.Candidate> candidates;
        final String original;
        final String translated;
        Saved(List<ResourceLabelCatalogue.Candidate> candidates, String original, String translated) {
            this.candidates = candidates; this.original = original; this.translated = translated;
        }
    }
}
