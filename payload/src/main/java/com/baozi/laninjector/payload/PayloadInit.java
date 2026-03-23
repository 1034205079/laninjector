package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Application;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class PayloadInit {

    private static final String TAG = "LanInjector";
    private static final String LOCALES_FILE = "laninjector_locales.txt";
    private static boolean initialized = false;

    /**
     * Entry point called from injected code. Takes only Activity (no extra registers needed).
     * Reads locale list from assets/laninjector_locales.txt at runtime.
     */
    public static void init(Activity activity) {
        Log.d(TAG, "PayloadInit.init() called, initialized=" + initialized);
        if (initialized) return;
        initialized = true;

        String[] locales = loadLocales(activity);
        if (locales == null || locales.length == 0) {
            Log.w(TAG, "No locales found in " + LOCALES_FILE);
            return;
        }

        Log.d(TAG, "Loaded " + locales.length + " locales from " + LOCALES_FILE);

        Application app = activity.getApplication();
        ActivityTracker tracker = new ActivityTracker();
        app.registerActivityLifecycleCallbacks(tracker);
        Log.d(TAG, "ActivityTracker registered");

        tracker.onActivityResumed(activity);

        FloatingMenuManager manager = new FloatingMenuManager(activity, locales, tracker);
        manager.show();
        Log.d(TAG, "FloatingMenuManager shown");
    }

    private static String[] loadLocales(Activity activity) {
        try {
            InputStream is = activity.getAssets().open(LOCALES_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            List<String> locales = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    locales.add(line);
                }
            }
            reader.close();
            return locales.toArray(new String[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load locales file", e);
            return null;
        }
    }
}
