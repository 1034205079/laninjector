package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class ActivityTracker implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "LanInjector";

    public interface OnActivityChangedListener {
        void onActivityChanged(Activity activity);
    }

    public interface OnForegroundChangeListener {
        void onForegroundChanged(boolean foreground);
    }

    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private OnActivityChangedListener listener;
    private OnForegroundChangeListener foregroundListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isForeground = false;
    private Runnable backgroundCheck;
    private final Set<Activity> visibleActivities = Collections.newSetFromMap(new WeakHashMap<>());

    public Activity getCurrentActivity() {
        return currentActivity.get();
    }

    public boolean isForeground() {
        return isForeground;
    }

    public void setOnActivityChangedListener(OnActivityChangedListener listener) {
        this.listener = listener;
    }

    public void setOnForegroundChangeListener(OnForegroundChangeListener listener) {
        this.foregroundListener = listener;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        visibleActivities.add(activity);
        // Cancel any pending background check
        if (backgroundCheck != null) {
            handler.removeCallbacks(backgroundCheck);
            backgroundCheck = null;
        }

        Activity prev = currentActivity.get();
        currentActivity = new WeakReference<>(activity);

        if (!isForeground) {
            isForeground = true;
            Log.d(TAG, "App moved to foreground");
            if (foregroundListener != null) {
                foregroundListener.onForegroundChanged(true);
            }
        }

        if (listener != null) {
            if (prev != activity) Log.d(TAG, "Activity changed: " + activity.getClass().getSimpleName());
            listener.onActivityChanged(activity);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // A focusable overlay can pause the Activity while the app stays visible.
    }

    @Override
    public void onActivityStopped(Activity activity) {
        visibleActivities.remove(activity);
        if (!visibleActivities.isEmpty()) return;
        if (activity.isChangingConfigurations()) return;
        if (backgroundCheck != null) handler.removeCallbacks(backgroundCheck);
        // Delay the background check — if another activity resumes within 300ms,
        // we're just transitioning between activities, not going to background
        backgroundCheck = () -> {
            backgroundCheck = null;
            if (!visibleActivities.isEmpty()) return;
            isForeground = false;
            Log.d(TAG, "App moved to background");
            if (foregroundListener != null) {
                foregroundListener.onForegroundChanged(false);
            }
        };
        handler.postDelayed(backgroundCheck, 300);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {
        visibleActivities.add(activity);
        if (backgroundCheck != null) {
            handler.removeCallbacks(backgroundCheck);
            backgroundCheck = null;
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        visibleActivities.remove(activity);
        Activity current = currentActivity.get();
        if (current == activity) {
            currentActivity = new WeakReference<>(null);
        }
    }
}
