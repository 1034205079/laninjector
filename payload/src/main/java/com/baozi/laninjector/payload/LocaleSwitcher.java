package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

public class LocaleSwitcher {

    private static final String TAG = "LanInjector";
    private static final String PREFS_NAME = "laninjector_locale";
    private static final String KEY_LOCALE = "target_locale";

    // Registered once to intercept every Activity creation
    private static boolean overrideRegistered = false;

    @SuppressWarnings("deprecation")
    public static void switchLocale(Activity activity, String localeCode) {
        Log.d(TAG, "switchLocale: " + localeCode + " on " + activity.getClass().getSimpleName());

        String languageTag = localeCode.replace("-r", "-");
        Locale locale = LocaleUtils.parseLocaleQualifier(localeCode);

        // Strategy 1: Android 13+ framework LocaleManager (helps apps that respect system locale)
        if (Build.VERSION.SDK_INT >= 33) {
            if (tryFrameworkLocaleManager(activity, languageTag)) {
                Log.d(TAG, "LocaleManager set: " + languageTag);
            }
            // Don't return — also apply Strategy 2 for apps that ignore LocaleManager
        }

        // Strategy 2: Direct Resources override + lifecycle callback + recreate
        // Works for apps with their own language management that ignores LocaleManager
        saveTargetLocale(activity, languageTag);
        registerLocaleOverride(activity.getApplication(), locale);

        // Apply to current process immediately
        Locale.setDefault(locale);

        // Update resources
        forceUpdateResources(activity, locale);
        forceUpdateResources(activity.getApplicationContext(), locale);

        if (isFlutterActivity(activity)) {
            // Flutter apps: recreate() would restart the Flutter engine and show splash.
            // Instead, dispatch onConfigurationChanged() so Flutter's engine picks up the
            // new locale and sends it to Dart via localizationChannel.
            Log.d(TAG, "Flutter app detected, dispatching onConfigurationChanged instead of recreate()");
            notifyFlutterConfigChanged(activity, locale);
        } else {
            Log.d(TAG, "Locale set to " + locale + ", calling recreate()");
            activity.recreate();
        }
    }

    /** Check if the activity is a Flutter activity by walking the class hierarchy */
    private static boolean isFlutterActivity(Activity activity) {
        Class<?> clazz = activity.getClass();
        while (clazz != null && clazz != Object.class) {
            String name = clazz.getName();
            if (name.equals("io.flutter.embedding.android.FlutterActivity")
                    || name.equals("io.flutter.embedding.android.FlutterFragmentActivity")
                    || name.equals("io.flutter.app.FlutterActivity")
                    || name.equals("io.flutter.app.FlutterFragmentActivity")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * Notify Flutter engine of locale change via reflection.
     * Method/field names may be obfuscated by R8/ProGuard, so we search by TYPE
     * rather than by name. Class names like FlutterEngine are preserved.
     *
     * Chain: Activity → (find FlutterEngine by type) → (find method accepting Configuration)
     * Falls back to onConfigurationChanged() if reflection fails.
     */
    private static void notifyFlutterConfigChanged(Activity activity, Locale locale) {
        Configuration newConfig = new Configuration(activity.getResources().getConfiguration());
        newConfig.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            newConfig.setLocales(new LocaleList(locale));
        }

        // Try 1: Find FlutterEngine via reflection (handles obfuscated method names)
        try {
            Object engine = findFlutterEngine(activity);
            if (engine != null) {
                Log.d(TAG, "Found FlutterEngine: " + engine.getClass().getName());

                // Find and call sendLocalesToFlutter(Configuration) by parameter type
                if (callSendLocalesToFlutter(engine, newConfig)) {
                    Log.d(TAG, "Sent locale to Flutter via engine: " + locale);
                    return;
                }
            } else {
                Log.d(TAG, "FlutterEngine not found via reflection");
            }
        } catch (Exception e) {
            Log.d(TAG, "Flutter engine reflection failed: " + e.getMessage());
        }

        // Try 2: Fallback to onConfigurationChanged (may work if app declared configChanges)
        try {
            activity.onConfigurationChanged(newConfig);
            Log.d(TAG, "Fallback: dispatched onConfigurationChanged with locale: " + locale);
        } catch (Exception e) {
            Log.e(TAG, "onConfigurationChanged fallback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Find FlutterEngine instance from the activity.
     * Since method names may be obfuscated, we search by return type first,
     * then fall back to searching fields by type.
     */
    private static Object findFlutterEngine(Activity activity) {
        String engineClassName = "io.flutter.embedding.engine.FlutterEngine";

        // Strategy A: Find a no-arg method that returns FlutterEngine
        for (Method m : activity.getClass().getMethods()) {
            if (m.getParameterTypes().length == 0
                    && m.getReturnType().getName().equals(engineClassName)) {
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(activity);
                    if (result != null) {
                        Log.d(TAG, "Found engine via method: " + m.getName() + "()");
                        return result;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Method " + m.getName() + " failed: " + e.getMessage());
                }
            }
        }

        // Strategy B: Walk fields of activity (and its superclasses) for FlutterEngine
        // FlutterActivity stores engine in a delegate, so also check 1 level deep
        Class<?> clazz = activity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(activity);
                    if (val != null && val.getClass().getName().equals(engineClassName)) {
                        Log.d(TAG, "Found engine in field: " + clazz.getSimpleName() + "." + f.getName());
                        return val;
                    }
                    // Check one level deeper (delegate object may hold the engine)
                    if (val != null) {
                        for (Field f2 : val.getClass().getDeclaredFields()) {
                            try {
                                f2.setAccessible(true);
                                Object val2 = f2.get(val);
                                if (val2 != null && val2.getClass().getName().equals(engineClassName)) {
                                    Log.d(TAG, "Found engine in field: " + f.getName() + "." + f2.getName());
                                    return val2;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
            clazz = clazz.getSuperclass();
        }

        return null;
    }

    /**
     * Find and call sendLocalesToFlutter(Configuration) on the engine's localization plugin.
     * Searches the engine's fields for an object that has a method accepting Configuration.
     */
    private static boolean callSendLocalesToFlutter(Object engine, Configuration config) {
        // First: try methods directly on the engine that accept Configuration
        for (Method m : engine.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0] == Configuration.class) {
                try {
                    m.setAccessible(true);
                    m.invoke(engine, config);
                    Log.d(TAG, "Called engine." + m.getName() + "(config)");
                    return true;
                } catch (Exception e) {
                    Log.d(TAG, "engine." + m.getName() + " failed: " + e.getMessage());
                }
            }
        }

        // Second: search engine's fields for plugin objects that accept Configuration
        for (Field f : engine.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object plugin = f.get(engine);
                if (plugin == null) continue;
                for (Method m : plugin.getClass().getMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == Configuration.class) {
                        try {
                            m.setAccessible(true);
                            m.invoke(plugin, config);
                            Log.d(TAG, "Called " + f.getName() + "." + m.getName() + "(config)");
                            return true;
                        } catch (Exception e) {
                            Log.d(TAG, f.getName() + "." + m.getName() + " failed: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        Log.d(TAG, "No sendLocalesToFlutter method found on engine or its plugins");
        return false;
    }



    /**
     * Register a one-time ActivityLifecycleCallbacks that overrides the locale
     * on EVERY Activity creation, BEFORE the app's own onCreate/setContentView.
     */
    private static void registerLocaleOverride(Application app, Locale locale) {
        if (overrideRegistered) return;
        overrideRegistered = true;

        if (Build.VERSION.SDK_INT >= 29) {
            // API 29+: onActivityPreCreated fires BEFORE onCreate
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                    Locale target = getTargetLocale(activity);
                    if (target != null) {
                        Locale.setDefault(target);
                        forceUpdateResources(activity, target);
                        forceUpdateResources(activity.getApplicationContext(), target);
                        Log.d(TAG, "PreCreated locale override: " + target + " on " + activity.getClass().getSimpleName());
                    }
                }
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            Log.d(TAG, "Registered onActivityPreCreated locale override (API 29+)");
        } else {
            // API < 29: Use onActivityCreated (after onCreate, less ideal but still useful)
            // Also try reflection to modify base context
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    Locale target = getTargetLocale(activity);
                    if (target != null) {
                        Locale.setDefault(target);
                        forceUpdateResources(activity, target);
                        forceUpdateResources(activity.getApplicationContext(), target);
                        tryOverrideBaseContext(activity, target);
                        Log.d(TAG, "Created locale override: " + target + " on " + activity.getClass().getSimpleName());
                    }
                }
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            Log.d(TAG, "Registered onActivityCreated locale override (API < 29)");
        }
    }

    /** Save the target locale so it persists across Activity recreations */
    private static void saveTargetLocale(Context context, String languageTag) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LOCALE, languageTag).apply();
    }

    /** Read the saved target locale */
    private static Locale getTargetLocale(Context context) {
        String tag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LOCALE, null);
        if (tag == null || tag.isEmpty()) return null;
        return Locale.forLanguageTag(tag);
    }

    @SuppressWarnings("deprecation")
    private static void forceUpdateResources(Context context, Locale locale) {
        try {
            Resources resources = context.getResources();
            Configuration config = new Configuration(resources.getConfiguration());
            config.setLocale(locale);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(new LocaleList(locale));
            }
            resources.updateConfiguration(config, resources.getDisplayMetrics());
        } catch (Exception e) {
            Log.d(TAG, "forceUpdateResources failed: " + e.getMessage());
        }
    }

    /**
     * On API < 29, try reflection to modify the Activity's base context configuration.
     * This counters the app's own attachBaseContext locale override.
     */
    private static void tryOverrideBaseContext(Activity activity, Locale locale) {
        try {
            // Get ContextWrapper.mBase (the base context)
            Field mBase = findField(activity.getClass(), "mBase");
            if (mBase == null) return;
            mBase.setAccessible(true);
            Object baseContext = mBase.get(activity);
            if (baseContext == null) return;

            // Update the base context's resources configuration
            Resources baseResources = ((Context) baseContext).getResources();
            Configuration config = new Configuration(baseResources.getConfiguration());
            config.setLocale(locale);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(new LocaleList(locale));
            }
            baseResources.updateConfiguration(config, baseResources.getDisplayMetrics());
            Log.d(TAG, "Base context locale overridden via reflection");
        } catch (Exception e) {
            Log.d(TAG, "Base context override failed: " + e.getMessage());
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    // ===== Strategy implementations =====

    private static boolean tryFrameworkLocaleManager(Activity activity, String languageTag) {
        try {
            Object localeManager = activity.getSystemService(
                    (Class<?>) Class.forName("android.app.LocaleManager"));
            if (localeManager == null) return false;

            LocaleList localeList = LocaleList.forLanguageTags(languageTag);
            Method setLocales = localeManager.getClass().getMethod(
                    "setApplicationLocales", LocaleList.class);
            setLocales.invoke(localeManager, localeList);

            Log.d(TAG, "Framework LocaleManager: set " + languageTag);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Framework LocaleManager failed: " + e.getMessage());
            return false;
        }
    }
}
