package com.baozi.laninjector.payload;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.List;

/**
 * Manages the floating language menu using the Activity's own window (DecorView).
 * No SYSTEM_ALERT_WINDOW permission needed.
 */
public class FloatingMenuManager {

    private static final String TAG = "LanInjector";

    private enum State {
        IDLE,
        PANEL_OPEN,
        CYCLING,
        DONE
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
        Log.d(TAG, "Showing floating ball (no overlay permission needed)");
        showBall(activity);
    }

    private void showBall(Activity activity) {
        if (ballView != null && ballView.getParent() != null) return;

        FrameLayout decorContent = getDecorContent(activity);
        if (decorContent == null) return;

        int ballSize = dpToPx(activity, BALL_SIZE_DP);

        ballView = new FloatingBallView(activity);
        ballView.setDisplayText("L");
        ballView.setClickCallback(this::onBallClicked);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ballSize, ballSize);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = ballX;
        lp.topMargin = ballY;

        ballView.setDragListener((dx, dy) -> {
            ballX += (int) dx;
            ballY += (int) dy;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) ballView.getLayoutParams();
            params.leftMargin = ballX;
            params.topMargin = ballY;
            ballView.setLayoutParams(params);
        });

        decorContent.addView(ballView, lp);
        Log.d(TAG, "Ball attached to activity: " + activity.getClass().getSimpleName());
    }

    private void reattachBall(Activity activity) {
        if (ballView == null) return;

        // Remove from old parent
        ViewGroup oldParent = (ViewGroup) ballView.getParent();
        if (oldParent != null) oldParent.removeView(ballView);
        if (panelView != null && panelView.getParent() != null) {
            ((ViewGroup) panelView.getParent()).removeView(panelView);
        }
        if (stopButton != null && stopButton.getParent() != null) {
            ((ViewGroup) stopButton.getParent()).removeView(stopButton);
        }

        // Re-add to new activity
        FrameLayout decorContent = getDecorContent(activity);
        if (decorContent == null) return;

        int ballSize = dpToPx(activity, BALL_SIZE_DP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ballSize, ballSize);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = ballX;
        lp.topMargin = ballY;
        decorContent.addView(ballView, lp);

        // Re-add stop button if cycling
        if (state == State.CYCLING && stopButton != null) {
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
            case DONE:
                resetToIdle();
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

        FrameLayout decorContent = getDecorContent(activity);
        if (decorContent == null) return;

        panelView = new LanguagePanelView(activity, locales);
        panelView.setStartCallback(selected -> {
            hidePanel();
            startCycling(selected);
        });

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int panelWidth = Math.min(dpToPx(activity, 300), dm.widthPixels - dpToPx(activity, 32));
        int panelHeight = Math.min(dpToPx(activity, 400), dm.heightPixels - dpToPx(activity, 100));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth, panelHeight);
        lp.gravity = Gravity.CENTER;
        decorContent.addView(panelView, lp);

        ballView.setDisplayText("X");
        ballView.setClickCallback(() -> {
            hidePanel();
            resetToIdle();
        });
    }

    private void hidePanel() {
        if (panelView != null && panelView.getParent() != null) {
            ((ViewGroup) panelView.getParent()).removeView(panelView);
            panelView = null;
        }
    }

    private void startCycling(List<String> selected) {
        Log.d(TAG, "Start cycling, selected " + selected.size() + " locales: " + selected);
        this.selectedLocales = selected;
        this.currentIndex = 0;
        state = State.CYCLING;
        switchToCurrentLocale();
    }

    private void switchToCurrentLocale() {
        if (currentIndex >= selectedLocales.size()) {
            onCyclingDone();
            return;
        }

        String locale = selectedLocales.get(currentIndex);
        Log.d(TAG, "Switching to locale: " + locale + " (" + (currentIndex + 1) + "/" + selectedLocales.size() + ")");

        String progress = (currentIndex + 1) + "/" + selectedLocales.size();
        ballView.setDisplayText(progress);
        ballView.setBallColor(0xFF03DAC5);
        ballView.setClickCallback(this::onBallClicked);

        showStopButton();

        Activity activity = activityTracker.getCurrentActivity();
        if (activity != null) {
            LocaleSwitcher.switchLocale(activity, locale);
            // After recreate, ball will be reattached via listener
        }
    }

    private void switchToNext() {
        currentIndex++;
        if (currentIndex >= selectedLocales.size()) {
            onCyclingDone();
        } else {
            switchToCurrentLocale();
        }
    }

    private void onCyclingDone() {
        Log.d(TAG, "Cycling done");
        state = State.DONE;
        hideStopButton();
        ballView.setDisplayText("OK");
        ballView.setBallColor(0xFF4CAF50);
        ballView.setClickCallback(this::onBallClicked);
        handler.postDelayed(this::resetToIdle, 2000);
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

        FrameLayout decorContent = getDecorContent(activity);
        if (decorContent == null) return;

        stopButton = new FloatingBallView(activity);
        stopButton.setDisplayText("Stop");
        stopButton.setBallColor(0xFFF44336);
        stopButton.setClickCallback(() -> {
            hideStopButton();
            resetToIdle();
        });

        int size = dpToPx(activity, 40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = ballX + dpToPx(activity, BALL_SIZE_DP) + dpToPx(activity, 8);
        lp.topMargin = ballY + dpToPx(activity, 8);
        decorContent.addView(stopButton, lp);
    }

    private void hideStopButton() {
        if (stopButton != null && stopButton.getParent() != null) {
            ((ViewGroup) stopButton.getParent()).removeView(stopButton);
            stopButton = null;
        }
    }

    private FrameLayout getDecorContent(Activity activity) {
        try {
            View decorView = activity.getWindow().getDecorView();
            // android.R.id.content's parent is a FrameLayout
            return (FrameLayout) decorView.findViewById(android.R.id.content).getParent();
        } catch (Exception e) {
            Log.e(TAG, "Cannot get DecorView content", e);
            // Fallback: try to cast decorView itself
            try {
                return (FrameLayout) activity.getWindow().getDecorView();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private int dpToPx(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density);
    }
}
