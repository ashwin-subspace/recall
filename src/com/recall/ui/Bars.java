package com.recall.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Sessions per week. Bars, framework Canvas, no charting library in this build. */
public class Bars extends View {

    private int[] values = new int[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float progress = 1f;

    public Bars(Context c) { this(c, null); }

    public Bars(Context c, AttributeSet a) {
        super(c, a);
        bar.setColor(getResources().getColor(com.recall.R.color.accent));
        dim.setColor(getResources().getColor(com.recall.R.color.surface2));
    }

    public void setValues(int[] v) {
        values = v == null ? new int[0] : v;
        invalidate();
    }

    public void animateIn() {
        progress = 0f;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(650);
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
        if (values.length == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float gap = 4f * d, radius = 3f * d;
        float w = getWidth(), h = getHeight();
        float bw = (w - gap * (values.length - 1)) / values.length;

        int max = 1;
        for (int i = 0; i < values.length; i++) max = Math.max(max, values[i]);

        for (int i = 0; i < values.length; i++) {
            float x = i * (bw + gap);
            // the track, so empty weeks still read as weeks rather than as absence
            r.set(x, h - 2f * d, x + bw, h);
            c.drawRoundRect(r, radius, radius, dim);
            if (values[i] <= 0) continue;
            float bh = h * (values[i] / (float) max) * progress;
            if (bh < 2f * d) bh = 2f * d;
            r.set(x, h - bh, x + bw, h);
            c.drawRoundRect(r, radius, radius, bar);
        }
    }
}
