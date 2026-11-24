package com.c10h.launcher;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 桌面视觉/动画工具（M9）：dp 换算、圆角与涟漪、按压缩放、淡入。
 * 全部 try-catch，动画失败不影响功能。
 */
public final class Ui {

    public static final android.view.animation.Interpolator DECEL =
            new DecelerateInterpolator(2.2f);
    public static final android.view.animation.Interpolator ACCEL =
            new android.view.animation.AccelerateInterpolator(2f);

    private Ui() {
    }

    public static int dp(Context c, float v) {
        float d = c.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    public static int dp(View v, float val) {
        return dp(v.getContext(), val);
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable roundStroke(int color, float radiusPx,
                                              int strokeWidthPx, int strokeColor) {
        GradientDrawable g = round(color, radiusPx);
        if (strokeWidthPx > 0) {
            g.setStroke(strokeWidthPx, strokeColor);
        }
        return g;
    }

    /** 圆形/圆角涟漪；API 21+。 */
    public static Drawable ripple(Context c, int color, float radiusPx, Drawable content) {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                ColorStateList cs = ColorStateList.valueOf(color);
                return new RippleDrawable(cs, content, null);
            }
        } catch (Throwable ignored) {
        }
        return content;
    }

    public static void press(View v, boolean down, float scale) {
        try {
            v.animate().cancel();
            v.animate().scaleX(down ? scale : 1f).scaleY(down ? scale : 1f)
                    .setDuration(down ? 90 : 160).setInterpolator(DECEL).start();
        } catch (Throwable ignored) {
        }
    }

    public static void lift(View v, boolean up, int zPx) {
        try {
            v.animate().cancel();
            v.animate().scaleX(up ? 1.08f : 1f).scaleY(up ? 1.08f : 1f)
                    .translationZ(up ? zPx : 0f)
                    .setDuration(up ? 120 : 160).setInterpolator(DECEL).start();
        } catch (Throwable ignored) {
        }
    }

    public static void fadeInUp(View v, long delay, float fromY) {
        try {
            v.setAlpha(0f);
            v.setTranslationY(fromY);
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                    .setDuration(240).setInterpolator(DECEL).start();
        } catch (Throwable ignored) {
        }
    }

    public static void fadeOutDown(View v, long delay, float toY) {
        try {
            v.animate().alpha(0f).translationY(toY).setStartDelay(delay)
                    .setDuration(180).setInterpolator(ACCEL).start();
        } catch (Throwable ignored) {
        }
    }

    public static void haptic(View v) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignored) {
        }
    }

    public static int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    /** 放大镜图标（AOSP 搜索条左侧）。 */
    public static Drawable searchGlyph(Context c, int color) {
        return new Glyph(c, GLYPH_SEARCH, color);
    }

    /** 麦克风图标（AOSP 搜索条右侧）。 */
    public static Drawable micGlyph(Context c, int color) {
        return new Glyph(c, GLYPH_MIC, color);
    }

    /** 导航三键：0=返回三角、1=主页圆、2=最近任务方。 */
    public static Drawable navGlyph(Context c, int kind, int color) {
        return new Glyph(c, kind == 0 ? GLYPH_BACK
                : kind == 1 ? GLYPH_HOME : GLYPH_RECENTS, color);
    }

    private static final int GLYPH_SEARCH = 0;
    private static final int GLYPH_MIC = 1;
    private static final int GLYPH_BACK = 2;
    private static final int GLYPH_HOME = 3;
    private static final int GLYPH_RECENTS = 4;

    /** 极简矢量绘制，尺寸随 bounds 自适应。 */
    private static final class Glyph extends Drawable {
        private final int kind;
        private final int color;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        Glyph(Context c, int kind, int color) {
            this.kind = kind;
            this.color = color;
            paint.setColor(color);
            if (kind == GLYPH_BACK || kind == GLYPH_HOME || kind == GLYPH_RECENTS) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, c.getResources()
                        .getDisplayMetrics().density * 1.6f));
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            }
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            switch (kind) {
                case GLYPH_SEARCH: {
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1.5f, w * 0.09f));
                    float r = w * 0.30f;
                    canvas.drawCircle(cx - w * 0.06f, cy - h * 0.06f, r, paint);
                    canvas.drawLine(cx + r * 0.62f, cy + r * 0.62f,
                            cx + w * 0.42f, cy + h * 0.42f, paint);
                    break;
                }
                case GLYPH_MIC: {
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    float rw = w * 0.30f;
                    float rh = h * 0.42f;
                    android.graphics.RectF capsule = new android.graphics.RectF(
                            cx - rw, cy - h * 0.40f, cx + rw, cy - h * 0.40f + rh);
                    canvas.drawRoundRect(capsule, rw, rw, paint);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1.2f, w * 0.07f));
                    android.graphics.RectF arc = new android.graphics.RectF(
                            cx - w * 0.38f, cy - h * 0.16f, cx + w * 0.38f, cy + h * 0.34f);
                    canvas.drawArc(arc, 0f, 180f, false, paint);
                    canvas.drawLine(cx, cy + h * 0.34f, cx, cy + h * 0.46f, paint);
                    break;
                }
                case GLYPH_BACK: {
                    float s = Math.min(w, h) * 0.30f;
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx - s, cy);
                    p.lineTo(cx + s, cy - s);
                    p.lineTo(cx + s, cy + s);
                    p.close();
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    canvas.drawPath(p, paint);
                    break;
                }
                case GLYPH_HOME: {
                    float r = Math.min(w, h) * 0.26f;
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, r, paint);
                    break;
                }
                default: {
                    float s = Math.min(w, h) * 0.26f;
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    canvas.drawRect(cx - s, cy - s, cx + s, cy + s, paint);
                    break;
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
