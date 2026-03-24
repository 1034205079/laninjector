package com.baozi.laninjector.payload;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
    private final Paint arrowPaint;
    private final RectF rect;
    private final Path arrowPath;
    private OnClickCallback clickCallback;

    private String displayText = "Start";
    private int ballColor = 0xFF6200EE;
    private boolean showArrow = false;

    // Touch tracking
    private float touchStartX, touchStartY;
    private long touchStartTime;
    private boolean isDragging;
    private static final int CLICK_THRESHOLD = 10;
    private static final long CLICK_TIME_THRESHOLD = 300;

    private OnDragListener dragListener;

    public interface OnDragListener {
        void onDrag(float deltaX, float deltaY);
    }

    public FloatingBallView(Context context) {
        super(context);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(ballColor);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setShadowLayer(8f, 0f, 3f, 0x50000000);
        setLayerType(LAYER_TYPE_SOFTWARE, bgPaint);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.FILL);

        rect = new RectF();
        arrowPath = new Path();
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

    public void setShowArrow(boolean show) {
        this.showArrow = show;
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
        float pad = 4f;
        float radius = w * 0.16f;

        // Rounded rectangle background
        rect.set(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);

        if (showArrow) {
            // Vertical: text on top ~55%, arrow on bottom ~45%
            float splitY = h * 0.5f;

            // Top: locale text, bold and prominent
            float textSize = w * 0.17f;
            textPaint.setTextSize(textSize);
            float maxTextW = w - pad * 4;
            float textW = textPaint.measureText(displayText);
            if (textW > maxTextW && displayText.length() > 1) {
                textPaint.setTextSize(textSize * maxTextW / textW);
            }
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textCY = (pad + splitY) / 2f;
            float textY = textCY - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(displayText, w / 2f, textY, textPaint);

            // Bottom: big right arrow →
            float arrowCX = w / 2f;
            float arrowCY = (splitY + h - pad) / 2f;
            float arrowW = w * 0.38f;
            float arrowH = (h - splitY) * 0.5f;

            float shaftH = arrowH * 0.22f;
            float headW = arrowW * 0.4f;

            arrowPath.reset();
            // Shaft left
            arrowPath.moveTo(arrowCX - arrowW * 0.5f, arrowCY - shaftH);
            // Shaft to head junction
            arrowPath.lineTo(arrowCX + arrowW * 0.5f - headW, arrowCY - shaftH);
            // Head top
            arrowPath.lineTo(arrowCX + arrowW * 0.5f - headW, arrowCY - arrowH * 0.5f);
            // Arrow tip
            arrowPath.lineTo(arrowCX + arrowW * 0.5f, arrowCY);
            // Head bottom
            arrowPath.lineTo(arrowCX + arrowW * 0.5f - headW, arrowCY + arrowH * 0.5f);
            // Back to shaft
            arrowPath.lineTo(arrowCX + arrowW * 0.5f - headW, arrowCY + shaftH);
            // Shaft bottom left
            arrowPath.lineTo(arrowCX - arrowW * 0.5f, arrowCY + shaftH);
            arrowPath.close();

            canvas.drawPath(arrowPath, arrowPaint);
        } else {
            // Centered text only (Start, Close, Stop)
            float textSize = Math.min(w * 0.22f, h * 0.4f);
            textPaint.setTextSize(textSize);
            float maxTextW = w - pad * 6;
            float textW = textPaint.measureText(displayText);
            if (textW > maxTextW && displayText.length() > 1) {
                textPaint.setTextSize(textSize * maxTextW / textW);
            }
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(displayText, w / 2f, textY, textPaint);
        }
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
