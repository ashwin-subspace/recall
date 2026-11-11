package com.recall.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Confidence over time. Framework Canvas only -- there is no charting library in this
 * build. Always drawn on a fixed 0-100 scale so two concepts can be compared by eye.
 */
public class Sparkline extends View {

    private final List<Integer> values = new ArrayList<Integer>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float progress = 1f;

    public Sparkline(Context c) { this(c, null); }

    public Sparkline(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        int accent = getResources().getColor(com.recall.R.color.accent);
        line.setColor(accent);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor((accent & 0x00FFFFFF) | 0x26000000);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(accent);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f * d);
        grid.setColor(getResources().getColor(com.recall.R.color.line));
    }

    public void setValues(List<Integer> v) {
        values.clear();
        if (v != null) values.addAll(v);
        invalidate();
    }

    public void animateIn() {
        progress = 0f;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(600);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator v) {
                progress = ((Float) v.getAnimatedValue()).floatValue();
                invalidate();
            }
        });
        a.start();
    }

    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 6f * d;
        float w = getWidth() - pad * 2, h = getHeight() - pad * 2;
        if (w <= 0 || h <= 0) return;

        // the 50 line, so a reading can be placed without axis labels
        float mid = pad + h * 0.5f;
        c.drawLine(pad, mid, pad + w, mid, grid);

        if (values.size() < 2) {
            if (values.size() == 1) {
                float y = pad + h * (1f - values.get(0) / 100f);
                c.drawCircle(pad + w, y, 4f * d, dot);
            }
            return;
        }

        int n = values.size();
        float step = w / (n - 1);
        path.reset();
        float lastX = pad, lastY = pad;
        int drawn = 0;
        for (int i = 0; i < n; i++) {
            float x = pad + step * i;
            float y = pad + h * (1f - Math.max(0, Math.min(100, values.get(i))) / 100f);
            if (x > pad + w * progress + 0.5f) break;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            lastX = x; lastY = y; drawn++;
        }
        if (drawn < 2) return;

        Path area = new Path(path);
        area.lineTo(lastX, pad + h);
        area.lineTo(pad, pad + h);
        area.close();
        c.drawPath(area, fill);
        c.drawPath(path, line);
        c.drawCircle(lastX, lastY, 4f * d, dot);
    }
}
