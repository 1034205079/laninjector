package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
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
     * Entry point called from PayloadProvider (ContentProvider).
     * No Activity available yet — registers callbacks and waits for first Activity.
     */
    public static void initFromProvider(Context context) {
        Log.d(TAG, "PayloadInit.initFromProvider() called, initialized=" + initialized);
        if (initialized) return;
        initialized = true;

        final String[] locales = loadLocales(context);
        if (locales == null || locales.length == 0) {
            Log.w(TAG, "No locales found in " + LOCALES_FILE);
            return;
        }

        Log.d(TAG, "Loaded " + locales.length + " locales from " + LOCALES_FILE);

        final Application app = (Application) context.getApplicationContext();
        final ActivityTracker tracker = new ActivityTracker();
        app.registerActivityLifecycleCallbacks(tracker);
        Log.d(TAG, "ActivityTracker registered, waiting for first Activity...");

        // Use a separate lifecycle callback to detect first Activity resume,
        // then create FloatingMenuManager (which sets its own listener on tracker)
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private boolean ballCreated = false;

            @Override
            public void onActivityResumed(Activity activity) {
                if (!ballCreated) {
                    ballCreated = true;
                    // Seed the tracker with the first activity
                    tracker.onActivityResumed(activity);
                    Log.d(TAG, "First Activity resumed: " + activity.getClass().getSimpleName());
                    FloatingMenuManager manager = new FloatingMenuManager(activity, locales, tracker);
                    manager.show();
                    Log.d(TAG, "FloatingMenuManager shown");
                    // Unregister this one-shot callback
                    app.unregisterActivityLifecycleCallbacks(this);
                }
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static String[] loadLocales(Context context) {
        try {
            InputStream is = context.getAssets().open(LOCALES_FILE);
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
