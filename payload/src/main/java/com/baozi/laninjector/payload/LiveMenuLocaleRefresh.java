package com.baozi.laninjector.payload;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Keep MenuItem titles in sync, so selection cannot restore the original labels. */
final class LiveMenuLocaleRefresh {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final Map<MenuItem, Saved> savedTitles = new WeakHashMap<>();
    private static final Map<Class<?>, List<Method>> getters = new WeakHashMap<>();
    private final Map<Resources, Map<Integer, Integer>> titleResources = new IdentityHashMap<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final Set<Menu> seenMenus = Collections.newSetFromMap(new IdentityHashMap<>());

    void capture(View view) {
        // Search by return type as well as name: R8 may rename getMenu().
        List<Method> methods = getters.get(view.getClass());
        if (methods == null) {
            methods = new ArrayList<>();
            for (Method method : view.getClass().getMethods()) {
                if (method.getParameterTypes().length == 0 && Menu.class.isAssignableFrom(method.getReturnType())) {
                    methods.add(method);
                }
            }
            getters.put(view.getClass(), methods);
        }
        for (Method method : methods) {
            try {
                method.setAccessible(true);
                Menu menu = (Menu) method.invoke(view);
                if (menu != null) capture(menu, view.getResources());
            } catch (Exception e) {
                Log.d("LanInjector", "Cannot access navigation menu on " + view.getClass().getName());
            }
        }
    }

    private void capture(Menu menu, Resources resources) {
        if (!seenMenus.add(menu)) return;
        Map<Integer, Integer> ids = titleResources.get(resources);
        if (ids == null) {
            ids = readTitleResources(resources);
            titleResources.put(resources, ids);
        }
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Integer titleId = ids.get(item.getItemId());
            if (titleId != null && titleId != 0) {
                try {
                    Saved prior = savedTitles.get(item);
                    CharSequence current = item.getTitle();
                    if (TextUtils.equals(current, resources.getText(titleId))
                            || (prior != null && prior.id == titleId
                                && (TextUtils.equals(current, prior.original) || TextUtils.equals(current, prior.translated)))) {
                        bindings.add(new Binding(item, resources, titleId, current == null ? "" : current.toString()));
                    }
                } catch (Resources.NotFoundException ignored) { }
            }
            if (item.hasSubMenu()) capture(item.getSubMenu(), resources);
        }
    }

    private Map<Integer, Integer> readTitleResources(Resources resources) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int menuId : ResourceStringIndex.menus()) {
            try (XmlResourceParser parser = resources.getXml(menuId)) {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
                    int itemId = parser.getAttributeResourceValue(ANDROID, "id", 0);
                    int titleId = parser.getAttributeResourceValue(ANDROID, "title", 0);
                    if (itemId == 0 || titleId == 0) continue;
                    Integer previous = result.get(itemId);
                    // Shared IDs with different titles cannot be resolved safely.
                    result.put(itemId, previous == null || previous == titleId ? titleId : 0);
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    int refresh(Map<Resources, Resources> translations) {
        return refresh(translations, true);
    }

    int refresh(Map<Resources, Resources> translations, boolean log) {
        int changed = 0;
        for (Binding binding : bindings) {
            Saved existing = savedTitles.get(binding.item);
            if (!TextUtils.equals(binding.item.getTitle(), binding.previous)
                    && (existing == null || !TextUtils.equals(binding.item.getTitle(), existing.original))) continue;
            Resources localized = translations.get(binding.resources);
            if (localized == null) localized = binding.resources;
            try {
                CharSequence translated = localized.getText(binding.id);
                Saved prior = savedTitles.get(binding.item);
                savedTitles.put(binding.item, new Saved(binding.id,
                        prior == null ? binding.previous : prior.original, translated.toString()));
                if (!TextUtils.equals(binding.item.getTitle(), translated)) {
                    binding.item.setTitle(translated);
                    changed++;
                }
            } catch (Exception e) {
                Log.w("LanInjector", "Navigation title refresh failed", e);
            }
        }
        if (log) Log.d("LanInjector", "Menu refresh: " + changed + " titles changed, " + bindings.size() + " bindings");
        return changed;
    }

    private static final class Binding {
        final MenuItem item;
        final Resources resources;
        final int id;
        final String previous;
        Binding(MenuItem item, Resources resources, int id, String previous) {
            this.item = item; this.resources = resources; this.id = id; this.previous = previous;
        }
    }

    private static final class Saved {
        final int id;
        final String original;
        final String translated;
        Saved(int id, String original, String translated) {
            this.id = id; this.original = original; this.translated = translated;
        }
    }
}
