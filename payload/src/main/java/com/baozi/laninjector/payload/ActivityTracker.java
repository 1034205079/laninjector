package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.lang.ref.WeakReference;

public class ActivityTracker implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "LanInjector";

    public interface OnActivityChangedListener {
        void onActivityChanged(Activity activity);
    }

    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private OnActivityChangedListener listener;

    public Activity getCurrentActivity() {
        return currentActivity.get();
    }

    public void setOnActivityChangedListener(OnActivityChangedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        Activity prev = currentActivity.get();
        currentActivity = new WeakReference<>(activity);
        if (prev != activity && listener != null) {
            Log.d(TAG, "Activity changed: " + activity.getClass().getSimpleName());
            listener.onActivityChanged(activity);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) {
            currentActivity = new WeakReference<>(null);
        }
    }
}
