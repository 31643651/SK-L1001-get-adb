package com.c10h.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 页点（M9，AOSP LinePageIndicator 观感）：当前页为长条胶囊，其余为圆点；
 * 支持拖动过程中的连续进度与切页动画。
 */
public class PageIndicatorView extends View {

    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float position;
    private int count = 0;
    private ValueAnimator anim;

    public PageIndicatorView(Context c) {
        super(c);
        active.setColor(0xFFFFFFFF);
        idle.setColor(0x66FFFFFF);
    }

    public void update(int pageCount, int page) {
        count = pageCount;
        setVisibility(VISIBLE);
        animateTo(page);
    }

    public void setProgress(float pageFloat) {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        position = pageFloat;
        invalidate();
    }

    private void animateTo(float target) {
        try {
            if (anim != null) {
                anim.cancel();
            }
            if (Math.abs(target - position) < 0.01f) {
                position = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(position, target);
            anim.setDuration(220);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    position = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        } catch (Throwable t) {
            position = target;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count <= 1) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        float dot = 3.5f * d;
        float pill = 15f * d;
        float slot = 13f * d;
        float total = count * slot;
        float cx = (getWidth() - total) / 2f + slot / 2f;
        float cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float dist = Math.abs(position - i);
            float t = Math.max(0f, 1f - dist);
            float w = dot * 2 + (pill - dot * 2) * t;
            float h = dot * 2;
            active.setAlpha((int) (255 * (0.45f + 0.55f * t)));
            idle.setAlpha((int) (120 * (1f - t)));
            if (t > 0.02f) {
                android.graphics.RectF r = new android.graphics.RectF(
                        cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
                canvas.drawRoundRect(r, h / 2f, h / 2f, active);
            }
            if (t < 0.98f) {
                canvas.drawCircle(cx, cy, dot, idle);
            }
            cx += slot;
        }
    }
}
