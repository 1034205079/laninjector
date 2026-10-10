package com.baozi.laninjector.payload;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.LocaleList;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;
import android.content.Context;
import android.content.res.TypedArray;
import com.baozi.laninjector.payload.ResourceLabelCatalogue.Candidate;

/** Refresh resource-backed labels without inflating or replacing any window. */
final class LiveWindowLocaleRefresh {
    private static final String TAG = "LanInjector";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final List<View> roots = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final Map<Resources, Map<Integer, Map<Integer, int[]>>> layouts = new HashMap<>();
    private final List<TextView> textViews = new ArrayList<>();
    private final Set<TextView> boundText = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TextView> boundHint = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<TextView, Resolved> resolvedText = new WeakHashMap<>();
    private static final Map<TextView, Resolved> resolvedHint = new WeakHashMap<>();
    private final Map<Resources, Resources> translations = new IdentityHashMap<>();
    private final LiveMenuLocaleRefresh menus = new LiveMenuLocaleRefresh();
    private final LivePropertyLocaleRefresh properties = new LivePropertyLocaleRefresh();
    private final Map<Resources, ResourceLabelCatalogue> catalogues = new IdentityHashMap<>();

    static LiveWindowLocaleRefresh capture(Activity activity) {
        long started = android.os.SystemClock.elapsedRealtime();
        LiveWindowLocaleRefresh refresh = new LiveWindowLocaleRefresh();
        if (Build.VERSION.SDK_INT >= 29) {
            refresh.roots.addAll(WindowInspector.getGlobalWindowViews());
        }
        View decor = activity.getWindow().getDecorView();
        if (!refresh.roots.contains(decor)) refresh.roots.add(decor);
        for (View root : refresh.roots) refresh.prepareCatalogues(root);
        LiveRebindGuard.addOriginals(refresh.roots, refresh.catalogues);
        refresh.properties.useCatalogues(refresh.catalogues);
        long catalogued = android.os.SystemClock.elapsedRealtime();
        for (View root : refresh.roots) {
            LayoutStructureBindings.capture(root);
            refresh.captureView(root);
        }
        long captured = android.os.SystemClock.elapsedRealtime();
        refresh.captureDynamicText();
        Log.d(TAG, "LocalePerf capture: catalogueMs=" + (catalogued - started)
                + " viewsMs=" + (captured - catalogued) + " textMatchMs="
                + (android.os.SystemClock.elapsedRealtime() - captured) + " textViews=" + refresh.textViews.size());
        return refresh;
    }

    private void prepareCatalogues(View view) {
        if (view instanceof FloatingBallView || view instanceof LanguagePanelView) return;
        if (!catalogues.containsKey(view.getResources())) {
            catalogues.put(view.getResources(), new ResourceLabelCatalogue(view.getResources()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) prepareCatalogues(group.getChildAt(i));
        }
    }

    private void captureView(View view) {
        if (view instanceof FloatingBallView || view instanceof LanguagePanelView) return;
        menus.capture(view);
        properties.capture(view);
        if (view instanceof TextView) textViews.add((TextView) view);
        if (view instanceof TextView) {
            int[] structural = LayoutStructureBindings.get(view);
            if (structural != null) {
                TextView text = (TextView) view;
                if (!(text instanceof EditText)) captureBinding(text, structural[0], false);
                captureBinding(text, structural[1], true);
            }
        }
        // User input and labels changed by app logic must remain untouched.
        if (Build.VERSION.SDK_INT >= 29 && view instanceof TextView) {
            TextView text = (TextView) view;
            int layout = view.getSourceLayoutResId();
            if (layout != 0 && view.getId() != View.NO_ID) {
                int[] ids = readLayout(view.getContext(), layout).get(view.getId());
                if (ids != null) {
                    if (!(text instanceof EditText)) captureBinding(text, ids[0], false);
                    captureBinding(text, ids[1], true);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) captureView(group.getChildAt(i));
        }
    }

    private void captureBinding(TextView view, int id, boolean hint) {
        if (id == 0 || (hint ? boundHint : boundText).contains(view)) return;
        try {
            CharSequence current = hint ? view.getHint() : view.getText();
            if (TextUtils.equals(current, view.getResources().getText(id))) {
                bindings.add(new Binding(view, id, hint, current));
                if (!hint) boundText.add(view);
                else boundHint.add(view);
            }
        } catch (Resources.NotFoundException ignored) { }
    }

    private void captureDynamicText() {
        Map<Resources, List<Pending>> wanted = new IdentityHashMap<>();
        for (TextView view : textViews) {
            if (!(view instanceof EditText) && !boundText.contains(view)) addPending(wanted, view, false);
            if (!boundHint.contains(view)) addPending(wanted, view, true);
        }
        for (Map.Entry<Resources, List<Pending>> group : wanted.entrySet()) {
            ResourceLabelCatalogue catalogue = catalogues.get(group.getKey());
            for (Pending pending : group.getValue()) {
                pending.candidates.addAll(catalogue.match(pending.current));
                int[] exact = LayoutStructureBindings.get(pending.view);
                int resource = exact == null ? 0 : exact[pending.hint ? 1 : 0];
                if (resource != 0) {
                    List<Candidate> narrowed = new ArrayList<>();
                    for (Candidate candidate : pending.candidates) if (candidate.id == resource) narrowed.add(candidate);
                    if (!narrowed.isEmpty()) { pending.candidates.clear(); pending.candidates.addAll(narrowed); }
                }
            }
            for (Pending pending : group.getValue()) {
                if (!pending.candidates.isEmpty()) {
                    bindings.add(new Binding(pending.view, pending.candidates.toArray(new Candidate[0]),
                            pending.hint, pending.current));
                }
            }
        }
    }

    private void addPending(Map<Resources, List<Pending>> wanted, TextView view, boolean hint) {
        CharSequence current = hint ? view.getHint() : view.getText();
        if (TextUtils.isEmpty(current)) return;
        Resolved prior = (hint ? resolvedHint : resolvedText).get(view);
        if (prior != null && TextUtils.equals(prior.text, current)) {
            bindings.add(new Binding(view, prior.candidates, hint, current));
            return;
        }
        wanted.computeIfAbsent(view.getResources(), key -> new ArrayList<>()).add(new Pending(view, hint, current));
    }

    private Map<Integer, int[]> readLayout(Context context, int id) {
        Resources resources = context.getResources();
        Map<Integer, Map<Integer, int[]>> cache = layouts.get(resources);
        if (cache == null) {
            cache = new HashMap<>();
            layouts.put(resources, cache);
        }
        if (cache.containsKey(id)) return cache.get(id);
        Map<Integer, int[]> result = new HashMap<>();
        try (XmlResourceParser parser = resources.getLayout(id)) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                int viewId = parser.getAttributeResourceValue(ANDROID, "id", 0);
                if (viewId != 0) {
                    String tag = parser.getName();
                    int defaultStyle = tag.endsWith("EditText") ? android.R.attr.editTextStyle
                            : tag.endsWith("ToggleButton") ? android.R.attr.buttonStyleToggle
                            : tag.endsWith("Switch") ? android.R.attr.switchStyle
                            : tag.endsWith("CheckBox") ? android.R.attr.checkboxStyle
                            : tag.endsWith("RadioButton") ? android.R.attr.radioButtonStyle
                            : tag.endsWith("Button") ? android.R.attr.buttonStyle : android.R.attr.textViewStyle;
                    TypedArray attributes = context.obtainStyledAttributes(parser,
                            new int[] {android.R.attr.text, android.R.attr.hint}, defaultStyle, 0);
                    try {
                        result.put(viewId, new int[] {attributes.getResourceId(0, 0), attributes.getResourceId(1, 0)});
                    } finally { attributes.recycle(); }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Cannot read label bindings from layout: " + id);
        }
        cache.put(id, result);
        return result;
    }

    @SuppressWarnings("deprecation")
    void updateResources(Locale locale) {
        Set<Resources> updated = Collections.newSetFromMap(new IdentityHashMap<>());
        for (View root : roots) updateResources(root, locale, updated);
    }

    @SuppressWarnings("deprecation")
    private void updateResources(View view, Locale locale, Set<Resources> updated) {
        if (view instanceof FloatingBallView || view instanceof LanguagePanelView) return;
        Resources resources = view.getResources();
        if (updated.add(resources)) {
            Configuration config = new Configuration(resources.getConfiguration());
            config.setLocale(locale);
            config.setLocales(new LocaleList(locale));
            translations.put(resources, view.getContext().createConfigurationContext(config).getResources());
            resources.updateConfiguration(config, resources.getDisplayMetrics());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                updateResources(group.getChildAt(i), locale, updated);
            }
        }
    }

    void dispatchConfiguration(Configuration config) {
        for (View root : roots) {
            try {
                root.dispatchConfigurationChanged(config);
                root.requestLayout();
                root.invalidate();
            } catch (Exception e) {
                Log.w(TAG, "Window configuration refresh failed", e);
            }
        }
    }

    int refreshText() {
        int changed = menus.refresh(translations) + properties.refresh(translations);
        int unchanged = 0;
        int ambiguous = 0;
        for (Binding binding : bindings) {
            if (!binding.view.isAttachedToWindow()) continue;
            CharSequence current = binding.hint ? binding.view.getHint() : binding.view.getText();
            if (!TextUtils.equals(current, binding.previous)) continue;
            try {
                Resources resources = translations.get(binding.view.getResources());
                if (resources == null) resources = binding.view.getResources();
                CharSequence translated = null;
                boolean conflict = false;
                for (Candidate source : binding.candidates) {
                    CharSequence candidate = source.translate(resources);
                    if (candidate == null) { conflict = true; break; }
                    if (translated != null && !TextUtils.equals(translated, candidate)) {
                        conflict = true;
                        break;
                    }
                    translated = candidate;
                }
                if (conflict) { ambiguous++; continue; }
                if (translated == null) continue;
                (binding.hint ? resolvedHint : resolvedText).put(binding.view,
                        new Resolved(binding.candidates, translated.toString()));
                if (TextUtils.equals(current, translated)) { unchanged++; continue; }
                if (binding.hint) binding.view.setHint(translated);
                else binding.view.setText(translated);
                changed++;
            } catch (Resources.NotFoundException ignored) { }
        }
        Log.d(TAG, "Live window refresh: " + roots.size() + " windows, " + changed
                + " labels actually changed, " + unchanged + " unchanged, " + ambiguous
                + " ambiguous; " + textViews.size() + " text views, " + ResourceStringIndex.ids().size() + " indexed strings");
        LiveRebindGuard.install(roots, catalogues, translations, menus, properties);
        return changed;
    }

    private static final class Binding {
        final TextView view;
        final Candidate[] candidates;
        final boolean hint;
        final CharSequence previous;

        Binding(TextView view, int id, boolean hint, CharSequence previous) {
            this(view, new Candidate[] {new Candidate(id, null)}, hint, previous);
        }

        Binding(TextView view, Candidate[] candidates, boolean hint, CharSequence previous) {
            this.view = view;
            this.candidates = candidates;
            this.hint = hint;
            this.previous = previous;
        }
    }

    private static final class Resolved {
        final Candidate[] candidates;
        final String text;
        Resolved(Candidate[] candidates, String text) { this.candidates = candidates; this.text = text; }
    }

    private static final class Pending {
        final TextView view;
        final boolean hint;
        final CharSequence current;
        final List<Candidate> candidates = new ArrayList<>();
        Pending(TextView view, boolean hint, CharSequence current) {
            this.view = view; this.hint = hint; this.current = current;
        }
    }
}
