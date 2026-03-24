package com.baozi.laninjector.payload;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

public class LocaleSwitcher {

    private static final String TAG = "LanInjector";

    @SuppressWarnings("deprecation")
    public static void switchLocale(Activity activity, String localeCode) {
        Log.d(TAG, "switchLocale: " + localeCode + " on " + activity.getClass().getSimpleName());

        String languageTag = localeCode.replace("-r", "-");
        Locale locale = LocaleUtils.parseLocaleQualifier(localeCode);

        // Strategy 1: Android 13+ framework LocaleManager (not affected by obfuscation)
        if (Build.VERSION.SDK_INT >= 33) {
            if (tryFrameworkLocaleManager(activity, languageTag)) {
                Log.d(TAG, "Locale switched via framework LocaleManager: " + languageTag);
                return;
            }
        }

        // Strategy 2: Write AppCompat's internal SharedPreferences + updateConfiguration
        tryWriteAppCompatLocalePrefs(activity, languageTag);

        // Strategy 3: Try finding obfuscated AppCompatDelegate via Activity's getDelegate()
        tryAppCompatDelegateViaActivity(activity, languageTag);

        // Strategy 4: Manual updateConfiguration + recreate (always runs as final step)
        Locale.setDefault(locale);

        Resources resources = activity.getResources();
        Configuration config = new Configuration(resources.getConfiguration());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(locale));
            activity.getApplicationContext().getResources()
                    .updateConfiguration(config, resources.getDisplayMetrics());
        }

        config.setLocale(locale);
        resources.updateConfiguration(config, resources.getDisplayMetrics());

        Resources appResources = activity.getApplicationContext().getResources();
        Configuration appConfig = new Configuration(appResources.getConfiguration());
        appConfig.setLocale(locale);
        appResources.updateConfiguration(appConfig, appResources.getDisplayMetrics());

        Log.d(TAG, "Locale set to " + locale + ", calling recreate()");
        activity.recreate();
    }

    /**
     * Android 13+ (API 33): Use framework LocaleManager directly.
     * Not affected by code obfuscation since it's a system API.
     */
    private static boolean tryFrameworkLocaleManager(Activity activity, String languageTag) {
        try {
            Object localeManager = activity.getSystemService(
                    (Class<?>) Class.forName("android.app.LocaleManager"));
            if (localeManager == null) return false;

            // LocaleList.forLanguageTags(languageTag)
            LocaleList localeList = LocaleList.forLanguageTags(languageTag);

            // localeManager.setApplicationLocales(localeList)
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

    /**
     * Write locale to AppCompat's internal SharedPreferences.
     * Scans shared_prefs directory to find actual locale preference files,
     * plus writes to known AppCompat preference names.
     */
    private static void tryWriteAppCompatLocalePrefs(Context context, String languageTag) {
        try {
            // Known AppCompat preference file/key combinations across versions
            String[] knownPrefNames = {
                    "androidx.appcompat.app.AppCompatDelegate",
                    "AppCompatDelegate.locales",
                    "app_compat_locales",
                    "_app_locales_prefs",
            };
            String[] knownKeys = {
                    "app_locales",
                    "app_locale",
                    "locales",
                    "app_locales_key",
            };

            // Write to all known locations
            for (String prefName : knownPrefNames) {
                SharedPreferences prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : knownKeys) {
                    editor.putString(key, languageTag);
                }
                editor.apply();
            }
            Log.d(TAG, "Wrote known AppCompat locale prefs: " + languageTag);

            // Scan shared_prefs directory for locale-related files and keys
            java.io.File prefsDir = new java.io.File(context.getApplicationInfo().dataDir, "shared_prefs");
            if (prefsDir.exists() && prefsDir.isDirectory()) {
                java.io.File[] files = prefsDir.listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        String fileName = file.getName();
                        if (!fileName.endsWith(".xml")) continue;

                        String prefName = fileName.replace(".xml", "");
                        // Check if this prefs file contains locale-related keys
                        SharedPreferences prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                        java.util.Map<String, ?> allEntries = prefs.getAll();

                        for (java.util.Map.Entry<String, ?> entry : allEntries.entrySet()) {
                            String key = entry.getKey().toLowerCase();
                            if (key.contains("locale") || key.contains("language") || key.contains("lang")) {
                                Object value = entry.getValue();
                                if (value instanceof String) {
                                    String oldVal = (String) value;
                                    Log.d(TAG, "Found locale pref: " + prefName + "/" + entry.getKey() + " = " + oldVal);
                                    prefs.edit().putString(entry.getKey(), languageTag).apply();
                                    Log.d(TAG, "Updated to: " + languageTag);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Write AppCompat prefs failed: " + e.getMessage());
        }
    }

    /**
     * Try to call setApplicationLocales via Activity.getDelegate() to find the
     * potentially obfuscated AppCompatDelegate class at runtime.
     */
    private static boolean tryAppCompatDelegateViaActivity(Activity activity, String languageTag) {
        try {
            // AppCompatActivity.getDelegate() returns AppCompatDelegate instance
            Method getDelegate = activity.getClass().getMethod("getDelegate");
            Object delegate = getDelegate.invoke(activity);
            if (delegate == null) return false;

            // Get the actual (possibly obfuscated) AppCompatDelegate class
            Class<?> delegateClass = delegate.getClass();
            // Walk up to find the class that has setApplicationLocales
            Class<?> current = delegateClass;
            Method setLocalesMethod = null;

            while (current != null && current != Object.class) {
                for (Method m : current.getDeclaredMethods()) {
                    // Look for a static method that takes one parameter and the parameter
                    // type name contains "Locale" or "List"
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getParameterTypes().length == 1
                            && m.getName().equals("setApplicationLocales")) {
                        setLocalesMethod = m;
                        break;
                    }
                }
                if (setLocalesMethod != null) break;
                current = current.getSuperclass();
            }

            if (setLocalesMethod != null) {
                // Found the method, now we need to create the LocaleListCompat parameter
                Class<?> paramType = setLocalesMethod.getParameterTypes()[0];
                // Try to find forLanguageTags on the parameter type
                Method forTags = paramType.getMethod("forLanguageTags", String.class);
                Object localeList = forTags.invoke(null, languageTag);
                setLocalesMethod.invoke(null, localeList);
                Log.d(TAG, "AppCompat delegate locale switch succeeded: " + languageTag);
                return true;
            }

            Log.d(TAG, "setApplicationLocales not found on delegate class hierarchy");
            return false;
        } catch (Exception e) {
            Log.d(TAG, "AppCompat delegate approach failed: " + e.getMessage());
            return false;
        }
    }
}
