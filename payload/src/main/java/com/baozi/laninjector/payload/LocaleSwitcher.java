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
        forceUpdateResources(activity, locale);
        forceUpdateResources(activity.getApplicationContext(), locale);

        // Flutter apps: recreate() would restart the Flutter engine and show splash again.
        // Just updating Resources is enough since Flutter renders its own UI.
        if (isFlutterActivity(activity)) {
            Log.d(TAG, "Flutter app detected, skipping recreate() to avoid splash restart");
        } else {
            Log.d(TAG, "Locale set to " + locale + ", calling recreate()");
            activity.recreate();
        }
    }

    /** Check if the activity is a Flutter activity by walking the class hierarchy */
    private static boolean isFlutterActivity(Activity activity) {
        Class<?> clazz = activity.getClass();
        while (clazz != null && clazz != Object.class) {
            if (clazz.getName().equals("io.flutter.embedding.android.FlutterActivity")
                    || clazz.getName().equals("io.flutter.app.FlutterActivity")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
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
