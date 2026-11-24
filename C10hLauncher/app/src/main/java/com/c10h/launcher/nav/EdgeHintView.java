package com.c10h.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 手势返回提示：贴边的实心黑色月牙（参考 iPhone 边缘返回）。
 * 上下两端始终以极小宽度平滑贴边，中部按 sin² 曲线鼓出；
 * progress 0→1 只改变凸起量，过渡始终平滑，不会在两端出现折角/线头。
 */
public class EdgeHintView extends View {

    private static final int SEGMENTS = 56;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float progress;
    private boolean left;
    private float density = 1f;

    public EdgeHintView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        // 灰色 + 70% 透明（不透明度 30%）。
        paint.setColor(0x4D808080);
    }

    public void setState(float p, boolean isLeft) {
        progress = Math.max(0f, Math.min(1f, p));
        left = isLeft;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || progress <= 0.01f) {
            return;
        }
        float edgeX = left ? 0f : w;
        float dir = left ? 1f : -1f;
        // 两端保留约 2.5dp 的贴边实心黑，中部最大凸起为窗口宽的 70%。
        float minW = 2.5f * density;
        float bulge = Math.max(minW, w * 0.70f * progress);
        float margin = h * 0.07f;
        float span = Math.max(1f, h - margin * 2f);

        path.reset();
        for (int i = 0; i <= SEGMENTS; i++) {
            float y = h * i / (float) SEGMENTS;
            float t = (y - margin) / span;
            if (t < 0f) {
                t = 0f;
            } else if (t > 1f) {
                t = 1f;
            }
            float s = (float) Math.sin(Math.PI * t);
            s = s * s;
            float x = edgeX + dir * (minW + (bulge - minW) * s);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        // 闭合到屏幕边缘，形成实心月牙。
        path.lineTo(edgeX, h);
        path.lineTo(edgeX, 0f);
        path.close();
        canvas.drawPath(path, paint);
    }
}
