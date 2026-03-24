package com.baozi.laninjector.payload;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

@SuppressLint("ViewConstructor")
public class FloatingBallView extends View {

    public interface OnClickCallback {
        void onClick();
    }

    private final Paint bgPaint;
    private final Paint textPaint;
    private final RectF oval;
    private OnClickCallback clickCallback;

    private String displayText = "L";
    private int ballColor = 0xFF6200EE;

    // Touch tracking
    private float touchStartX, touchStartY;
    private long touchStartTime;
    private boolean isDragging;
    private static final int CLICK_THRESHOLD = 10;
    private static final long CLICK_TIME_THRESHOLD = 300;

    // For drag via WindowManager (set by FloatingMenuManager)
    private OnDragListener dragListener;

    public interface OnDragListener {
        void onDrag(float deltaX, float deltaY);
    }

    public FloatingBallView(Context context) {
        super(context);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(ballColor);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setShadowLayer(8f, 0f, 2f, 0x40000000);
        setLayerType(LAYER_TYPE_SOFTWARE, bgPaint);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        oval = new RectF();
    }

    public void setClickCallback(OnClickCallback callback) {
        this.clickCallback = callback;
    }

    public void setDragListener(OnDragListener listener) {
        this.dragListener = listener;
    }

    public void setDisplayText(String text) {
        this.displayText = text;
        invalidate();
    }

    public String getDisplayText() {
        return displayText;
    }

    public void setBallColor(int color) {
        this.ballColor = color;
        bgPaint.setColor(color);
        invalidate();
    }

    public int getBallColor() {
        return ballColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);
        float padding = 8f;

        oval.set(padding, padding, size - padding, size - padding);
        canvas.drawOval(oval, bgPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = (h - fm.ascent - fm.descent) / 2f;

        // Auto-size text to fit
        float maxTextWidth = size - padding * 4;
        float currentWidth = textPaint.measureText(displayText);
        if (currentWidth > maxTextWidth && displayText.length() > 1) {
            textPaint.setTextSize(textPaint.getTextSize() * maxTextWidth / currentWidth);
        }

        canvas.drawText(displayText, w / 2f, textY, textPaint);

        // Reset text size
        textPaint.setTextSize(32f);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                touchStartTime = System.currentTimeMillis();
                isDragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                if (Math.abs(dx) > CLICK_THRESHOLD || Math.abs(dy) > CLICK_THRESHOLD) {
                    isDragging = true;
                    if (dragListener != null) {
                        dragListener.onDrag(dx, dy);
                    }
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!isDragging && (System.currentTimeMillis() - touchStartTime) < CLICK_TIME_THRESHOLD) {
                    if (clickCallback != null) {
                        clickCallback.onClick();
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
