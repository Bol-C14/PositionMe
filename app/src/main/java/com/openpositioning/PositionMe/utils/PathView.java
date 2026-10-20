package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Renders the recorded PDR path in the correction screen. Coordinates are local ENU meters. */
public class PathView extends View {
    public interface ScaleListener {
        void onScaleChanged(float scale);
    }

    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final List<float[]> trajectory = new ArrayList<>();
    private float manualScale = 1f;
    private ScaleListener scaleListener;

    public PathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        drawPaint.setColor(Color.BLUE);
        drawPaint.setStrokeWidth(5f);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Replaces the displayed path with a defensive copy of the session-owned PDR snapshot. */
    public void setTrajectory(List<float[]> points) {
        trajectory.clear();
        if (points != null) {
            for (float[] point : points) {
                if (point != null && point.length >= 2
                        && Float.isFinite(point[0]) && Float.isFinite(point[1])) {
                    trajectory.add(new float[]{point[0], point[1]});
                }
            }
        }
        invalidate();
    }

    public void setScaleListener(ScaleListener scaleListener) {
        this.scaleListener = scaleListener;
    }

    /** Rescales the displayed path around its origin for correction passes. */
    public void redraw(float newScale) {
        if (Float.isFinite(newScale) && newScale > 0f) {
            manualScale = newScale;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (trajectory.isEmpty()) return;

        float maxEast = 0f;
        float maxNorth = 0f;
        for (float[] point : trajectory) {
            maxEast = Math.max(maxEast, Math.abs(point[0]));
            maxNorth = Math.max(maxNorth, Math.abs(point[1]));
        }
        float xScale = maxEast == 0f ? Float.POSITIVE_INFINITY : getWidth() * 0.45f / maxEast;
        float yScale = maxNorth == 0f ? Float.POSITIVE_INFINITY : getHeight() * 0.45f / maxNorth;
        float fitScale = Math.min(xScale, yScale);
        if (!Float.isFinite(fitScale) || fitScale <= 0f) fitScale = 1f;
        float scale = fitScale * manualScale;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        path.reset();
        for (int i = 0; i < trajectory.size(); i++) {
            float x = centerX + trajectory.get(i)[0] * scale;
            float y = centerY - trajectory.get(i)[1] * scale;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, drawPaint);
        if (scaleListener != null) scaleListener.onScaleChanged(scale);
    }

}
