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

    private static final int BALL_SIZE_DP = 56;

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

        int ballSize = dpToPx(activity, BALL_SIZE_DP);

        ballView = new FloatingBallView(activity);
        ballView.setDisplayText("L");
        ballView.setClickCallback(this::onBallClicked);

        ballView.setDragListener((dx, dy) -> {
            ballX += (int) dx;
            ballY += (int) dy;
            updateViewPosition(ballView, ballX, ballY);
        });

        addToWindow(activity, ballView, ballSize, ballSize, ballX, ballY);
        Log.d(TAG, "Ball attached to activity: " + activity.getClass().getSimpleName());
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
        String currentText = ballView != null ? ballView.getDisplayText() : "L";
        int currentColor = ballView != null ? ballView.getBallColor() : 0xFF6200EE;

        int ballSize = dpToPx(activity, BALL_SIZE_DP);
        ballView = new FloatingBallView(activity);
        ballView.setDisplayText(currentText);
        ballView.setBallColor(currentColor);
        ballView.setClickCallback(this::onBallClicked);
        ballView.setDragListener((dx, dy) -> {
            ballX += (int) dx;
            ballY += (int) dy;
            updateViewPosition(ballView, ballX, ballY);
        });

        addToWindow(activity, ballView, ballSize, ballSize, ballX, ballY);

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

        // Position panel to the right of the ball so it doesn't cover the X button
        int ballRight = ballX + dpToPx(activity, BALL_SIZE_DP) + dpToPx(activity, 8);
        int panelX = Math.min(ballRight, dm.widthPixels - panelWidth - dpToPx(activity, 8));
        int panelY = (dm.heightPixels - panelHeight) / 2;
        addToWindow(activity, panelView, panelWidth, panelHeight, panelX, panelY);

        ballView.setDisplayText("X");
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
        ballView.setDisplayText("L");
        ballView.setBallColor(0xFF6200EE);
        ballView.setClickCallback(this::onBallClicked);
    }

    private void showStopButton() {
        Activity activity = activityTracker.getCurrentActivity();
        if (activity == null) return;
        if (stopButton != null && stopButton.getParent() != null) return;

        stopButton = new FloatingBallView(activity);
        stopButton.setDisplayText("Stop");
        stopButton.setBallColor(0xFFF44336);
        stopButton.setClickCallback(() -> {
            hideStopButton();
            resetToIdle();
        });

        int size = dpToPx(activity, 40);
        int stopX = ballX + dpToPx(activity, BALL_SIZE_DP) + dpToPx(activity, 8);
        int stopY = ballY + dpToPx(activity, 8);
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
        try {
            if (view.getParent() != null) return;

            // Check if we can use system overlay (above everything including Dialogs)
            boolean canOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(activity);

            int windowType;
            if (canOverlay) {
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                Log.d(TAG, "Using TYPE_APPLICATION_OVERLAY (above Dialogs)");
            } else {
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width, height,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = x;
            params.y = y;

            // TYPE_APPLICATION_OVERLAY doesn't need a token;
            // TYPE_APPLICATION_PANEL needs the activity's window token
            if (!canOverlay) {
                params.token = activity.getWindow().getDecorView().getWindowToken();
                if (params.token == null) {
                    Log.w(TAG, "Window token still null, falling back to content view");
                    addToContentView(activity, view, width, height, x, y);
                    return;
                }
            }

            activity.getWindowManager().addView(view, params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add view to window, falling back", e);
            addToContentView(activity, view, width, height, x, y);
        }
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

    private int dpToPx(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density);
    }
}
