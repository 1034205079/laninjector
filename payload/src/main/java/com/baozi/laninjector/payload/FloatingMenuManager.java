package com.baozi.laninjector.payload;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.List;

/**
 * Manages the floating language menu using WindowManager.
 * Uses TYPE_APPLICATION_PANEL to display above Dialogs.
 * No SYSTEM_ALERT_WINDOW permission needed.
 */
public class FloatingMenuManager {

    private static final String TAG = "LanInjector";

    private enum State {
        IDLE,
        PANEL_OPEN,
        CYCLING
    }

    private final String[] locales;
    private final ActivityTracker activityTracker;
    private final Handler handler;

    private FloatingBallView ballView;
    private LanguagePanelView panelView;
    private FloatingBallView stopButton;

    private State state = State.IDLE;
    private List<String> selectedLocales;
    private int currentIndex = 0;
    private boolean pendingOverlayUpgrade = false;

    private static final int BALL_WIDTH_DP = 80;
    private static final int BALL_HEIGHT_DP = 56;

    // Ball position (in pixels)
    private int ballX;
    private int ballY;

    public FloatingMenuManager(Activity activity, String[] locales, ActivityTracker tracker) {
        this.locales = locales;
        this.activityTracker = tracker;
        this.handler = new Handler(Looper.getMainLooper());
        this.ballX = dpToPx(activity, 16);
        this.ballY = dpToPx(activity, 200);

        // Re-attach ball when activity changes (e.g. after recreate)
        tracker.setOnActivityChangedListener(newActivity -> {
            handler.post(() -> reattachBall(newActivity));
        });

        // Hide/show when app goes to background/foreground
        tracker.setOnForegroundChangeListener(foreground -> {
            handler.post(() -> {
                if (foreground) {
                    setAllViewsVisibility(View.VISIBLE);
                } else {
                    setAllViewsVisibility(View.GONE);
                }
            });
        });

        // Listen for ALL activity resumes to detect overlay permission grant
        activity.getApplication().registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                if (pendingOverlayUpgrade && ballView != null) {
                    pendingOverlayUpgrade = false;
                    Log.d(TAG, "Overlay permission may have been granted, rebuilding ball");
                    handler.post(() -> reattachBall(a));
                }
            }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    public void show() {
        Activity activity = activityTracker.getCurrentActivity();
        if (activity == null) {
            Log.w(TAG, "No current activity, cannot show ball");
            return;
        }

        // Request overlay permission if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            Log.d(TAG, "Overlay permission not granted, requesting...");
            pendingOverlayUpgrade = true;
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open overlay permission settings", e);
            }
        }

        Log.d(TAG, "Showing floating ball (WindowManager panel)");
        showBall(activity);
    }

    private void showBall(Activity activity) {
        if (ballView != null && ballView.getParent() != null) return;

        int ballW = dpToPx(activity, BALL_WIDTH_DP);
        int ballH = dpToPx(activity, BALL_HEIGHT_DP);

        ballView = new FloatingBallView(activity);
        ballView.setDisplayText("Start");
        ballView.setShowArrow(false);
        ballView.setClickCallback(this::onBallClicked);

        ballView.setDragListener((dx, dy) -> {
            ballX += (int) dx;
            ballY += (int) dy;
            updateViewPosition(ballView, ballX, ballY);
        });

        addToWindow(activity, ballView, ballW, ballH, ballX, ballY);
    }

    private void reattachBall(Activity activity) {
        reattachBall(activity, 0);
    }

    private void reattachBall(Activity activity, int retryCount) {
        // Remove old views
        removeFromWindow(ballView);
        removeFromWindow(panelView);
        panelView = null;
        removeFromWindow(stopButton);
        stopButton = null;

        // If panel was open, reset to idle since the panel is gone
        if (state == State.PANEL_OPEN) {
            state = State.IDLE;
        }

        // Check if window is ready
        try {
            activity.getWindow().getDecorView().getWindowToken();
        } catch (Exception e) {
            if (retryCount < 3) {
                Log.d(TAG, "Window not ready, retry " + (retryCount + 1));
                handler.postDelayed(() -> reattachBall(activity, retryCount + 1), 200);
            } else {
                Log.w(TAG, "Failed to reattach ball after retries");
            }
            return;
        }

        // Recreate ball with new activity context
        String currentText = ballView != null ? ballView.getDisplayText() : "Start";
        int currentColor = ballView != null ? ballView.getBallColor() : 0xFF6200EE;
        boolean currentArrow = state == State.CYCLING;

        int ballW = dpToPx(activity, BALL_WIDTH_DP);
        int ballH = dpToPx(activity, BALL_HEIGHT_DP);
        ballView = new FloatingBallView(activity);
        ballView.setDisplayText(currentText);
        ballView.setBallColor(currentColor);
        ballView.setShowArrow(currentArrow);
        ballView.setClickCallback(this::onBallClicked);
        ballView.setDragListener((dx, dy) -> {
            ballX += (int) dx;
            ballY += (int) dy;
            updateViewPosition(ballView, ballX, ballY);
        });

        addToWindow(activity, ballView, ballW, ballH, ballX, ballY);

        // Re-add stop button if cycling
        if (state == State.CYCLING) {
            showStopButton();
        }

        Log.d(TAG, "Ball reattached to: " + activity.getClass().getSimpleName());
    }

    private void onBallClicked() {
        Log.d(TAG, "Ball clicked, state=" + state);
        switch (state) {
            case IDLE:
                showPanel();
                break;
            case CYCLING:
                switchToNext();
                break;
            default:
                break;
        }
    }

    private void showPanel() {
        Activity activity = activityTracker.getCurrentActivity();
        if (activity == null || panelView != null) return;
        Log.d(TAG, "Showing language panel");
        state = State.PANEL_OPEN;

        panelView = new LanguagePanelView(activity, locales);
        panelView.setStartCallback(selected -> {
            hidePanel();
            startCycling(selected);
        });

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int panelWidth = Math.min(dpToPx(activity, 300), dm.widthPixels - dpToPx(activity, 32));
        int panelHeight = Math.min(dpToPx(activity, 400), dm.heightPixels - dpToPx(activity, 100));

        // Position panel below the ball, centered horizontally
        int panelX = (dm.widthPixels - panelWidth) / 2;
        int ballBottom = ballY + dpToPx(activity, BALL_HEIGHT_DP) + dpToPx(activity, 12);
        int panelY = Math.min(ballBottom, dm.heightPixels - panelHeight - dpToPx(activity, 16));
        addToWindow(activity, panelView, panelWidth, panelHeight, panelX, panelY);

        ballView.setDisplayText("Close");
        ballView.setShowArrow(false);
        ballView.setBallColor(0xFFF44336);
        ballView.setClickCallback(() -> {
            hidePanel();
            resetToIdle();
        });
    }

    private void hidePanel() {
        removeFromWindow(panelView);
        panelView = null;
    }

    private void startCycling(List<String> selected) {
        Log.d(TAG, "Start cycling, selected " + selected.size() + " locales: " + selected);
        this.selectedLocales = selected;
        this.currentIndex = 0;
        state = State.CYCLING;
        switchToCurrentLocale();
    }

    private void switchToCurrentLocale() {
        String locale = selectedLocales.get(currentIndex);
        Log.d(TAG, "Switching to locale: " + locale + " (" + (currentIndex + 1) + "/" + selectedLocales.size() + ")");

        String displayName = LocaleUtils.formatForDisplay(locale);
        ballView.setDisplayText(displayName);
        ballView.setShowArrow(true);
        ballView.setBallColor(0xFF03DAC5);
        ballView.setClickCallback(this::onBallClicked);

        showStopButton();

        Activity activity = activityTracker.getCurrentActivity();
        if (activity != null) {
            LocaleSwitcher.switchLocale(activity, locale);
        }
    }

    private void switchToNext() {
        currentIndex = (currentIndex + 1) % selectedLocales.size();
        switchToCurrentLocale();
    }

    private void resetToIdle() {
        Log.d(TAG, "Reset to idle");
        state = State.IDLE;
        hideStopButton();
        ballView.setDisplayText("Start");
        ballView.setShowArrow(false);
        ballView.setBallColor(0xFF6200EE);
        ballView.setClickCallback(this::onBallClicked);
    }

    private void showStopButton() {
        Activity activity = activityTracker.getCurrentActivity();
        if (activity == null) return;
        if (stopButton != null && stopButton.getParent() != null) return;

        stopButton = new FloatingBallView(activity);
        stopButton.setDisplayText("X");
        stopButton.setShowArrow(false);
        stopButton.setBallColor(0xAAF44336);
        stopButton.setClickCallback(() -> {
            hideStopButton();
            resetToIdle();
        });

        // Small circle at top-right corner of the ball
        int size = dpToPx(activity, 22);
        int stopX = ballX + dpToPx(activity, BALL_WIDTH_DP) - size / 2;
        int stopY = ballY - size / 2;
        addToWindow(activity, stopButton, size, size, stopX, stopY);
    }

    private void hideStopButton() {
        removeFromWindow(stopButton);
        stopButton = null;
    }

    // ===== WindowManager helpers =====

    private void addToWindow(Activity activity, View view, int width, int height, int x, int y) {
        // DecorView may not have a window token yet during onActivityResumed,
        // so post to ensure the window is fully attached first
        View decorView = activity.getWindow().getDecorView();
        decorView.post(() -> addToWindowNow(activity, view, width, height, x, y));
    }

    private void addToWindowNow(Activity activity, View view, int width, int height, int x, int y) {
        if (view.getParent() != null) return;

        // Strategy 1: Try TYPE_APPLICATION_OVERLAY first (above Dialogs)
        // Don't check canDrawOverlays() - MIUI and some ROMs return false even when granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = x;
                params.y = y;
                activity.getWindowManager().addView(view, params);
                Log.d(TAG, "Using TYPE_APPLICATION_OVERLAY (above Dialogs)");
                return;
            } catch (Exception e) {
                Log.d(TAG, "TYPE_APPLICATION_OVERLAY failed: " + e.getMessage());
            }
        }

        // Strategy 2: TYPE_APPLICATION_PANEL with activity token
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = x;
            params.y = y;
            params.token = activity.getWindow().getDecorView().getWindowToken();

            if (params.token != null) {
                activity.getWindowManager().addView(view, params);
                Log.d(TAG, "Using TYPE_APPLICATION_PANEL");
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "TYPE_APPLICATION_PANEL failed: " + e.getMessage());
        }

        // Strategy 3: Fallback to content view
        Log.d(TAG, "Falling back to content view");
        addToContentView(activity, view, width, height, x, y);
    }

    /** Fallback: add to Activity's content FrameLayout (won't show above Dialogs) */
    private void addToContentView(Activity activity, View view, int width, int height, int x, int y) {
        try {
            if (view.getParent() != null) return;
            android.widget.FrameLayout content =
                    (android.widget.FrameLayout) activity.findViewById(android.R.id.content);
            if (content == null) return;

            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(width, height);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = x;
            lp.topMargin = y;
            content.addView(view, lp);
        } catch (Exception e) {
            Log.e(TAG, "Fallback addToContentView also failed", e);
        }
    }

    private void updateViewPosition(View view, int x, int y) {
        if (view == null || view.getParent() == null) return;
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
            params.x = x;
            params.y = y;
            Activity activity = activityTracker.getCurrentActivity();
            if (activity != null) {
                activity.getWindowManager().updateViewLayout(view, params);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update view position", e);
        }
    }

    private void removeFromWindow(View view) {
        if (view == null || view.getParent() == null) return;
        try {
            Activity activity = activityTracker.getCurrentActivity();
            if (activity != null) {
                activity.getWindowManager().removeView(view);
            } else {
                // Fallback: remove from parent directly
                ((ViewGroup) view.getParent()).removeView(view);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove view from window", e);
        }
    }

    private void setAllViewsVisibility(int visibility) {
        if (ballView != null) ballView.setVisibility(visibility);
        if (panelView != null) panelView.setVisibility(visibility);
        if (stopButton != null) stopButton.setVisibility(visibility);
    }

    private int dpToPx(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density);
    }
}
