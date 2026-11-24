package com.c10h.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * AOSP 风格文件夹图标：白色圆角底板 + 2×2 成员缩略图。
 * 成员不足 4 个时留空槽；无成员时画底板。
 */
public class FolderIconView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Drawable[] minis = new Drawable[4];

    public FolderIconView(Context c) {
        super(c);
        paint.setColor(0xE6000000);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setMembers(Drawable[] icons) {
        for (int i = 0; i < 4; i++) {
            minis[i] = (icons != null && i < icons.length) ? icons[i] : null;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float pad = Math.min(w, h) * 0.075f;
        float radius = Math.min(w, h) * 0.20f;
        rect.set(pad, pad, w - pad, h - pad);
        // 内部 60% 黑；边框 90% 黑紧贴底色，中间无缝
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawRoundRect(rect, radius, radius, paint);
        float sw = Math.max(1f, Math.min(w, h) * 0.018f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(sw);
        paint.setColor(0x4D000000);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);

        float innerPad = Math.min(w, h) * 0.16f;
        float left = innerPad;
        float top = innerPad;
        float right = w - innerPad;
        float bottom = h - innerPad;
        float gap = Math.min(w, h) * 0.055f;
        float cw = (right - left - gap) / 2f;
        float ch = (bottom - top - gap) / 2f;
        for (int i = 0; i < 4; i++) {
            Drawable d = minis[i];
            if (d == null) {
                continue;
            }
            int col = i % 2;
            int row = i / 2;
            float l = left + col * (cw + gap);
            float t = top + row * (ch + gap);
            d.setBounds((int) l, (int) t, (int) (l + cw), (int) (t + ch));
            d.draw(canvas);
        }
    }
}
