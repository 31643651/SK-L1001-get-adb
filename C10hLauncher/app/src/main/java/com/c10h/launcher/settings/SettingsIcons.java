package com.c10h.launcher.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * AOSP Settings 风格的 24dp 矢量图标。
 * 全部在 24×24 归一化坐标系内绘制（线宽 2），随 Drawable bounds 缩放；
 * 与 AOSP framework 图标同样的书写习惯：线框为主、实心为辅。
 */
public final class SettingsIcons {

    public static final int ARROW_BACK = 0;
    public static final int WIFI = 1;
    public static final int BT = 2;
    public static final int APPS = 3;
    public static final int BATTERY = 4;
    public static final int DISPLAY = 5;
    public static final int SOUND = 6;
    public static final int STORAGE = 7;
    public static final int SYSTEM = 8;
    public static final int ABOUT = 9;
    public static final int DEV = 10;
    public static final int BLOCK = 11;
    public static final int GUARD = 12;
    public static final int NOTIFY = 13;
    public static final int HOME = 14;
    public static final int GRID = 15;
    public static final int DOTS = 16;
    public static final int ROTATE = 17;
    public static final int EQ = 18;
    public static final int CLOCK = 19;
    public static final int FILE = 20;
    public static final int RESET = 21;
    public static final int WALLPAPER = 22;
    public static final int VERSION = 23;
    public static final int SEARCH = 24;
    public static final int CHEVRON = 25;

    private SettingsIcons() {
    }

    public static Drawable icon(Context c, int type, int color) {
        return new Ic(type, color);
    }

    private static final class Ic extends Drawable {
        private final int type;
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ic(int type, int color) {
            this.type = type;
            this.color = color;
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) {
                return;
            }
            float s = Math.min(b.width(), b.height()) / 24f;
            cv.save();
            cv.translate(b.exactCenterX() - 12f * s, b.exactCenterY() - 12f * s);
            cv.scale(s, s);
            p.setColor(color);
            p.setStrokeWidth(2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);
            try {
                drawType(cv);
            } catch (Throwable ignored) {
            }
            cv.restore();
        }

        private void drawType(Canvas cv) {
            switch (type) {
                case ARROW_BACK: {
                    cv.drawLine(20f, 12f, 5.5f, 12f, p);
                    Path pth = new Path();
                    pth.moveTo(11.5f, 5.5f);
                    pth.lineTo(5f, 12f);
                    pth.lineTo(11.5f, 18.5f);
                    cv.drawPath(pth, p);
                    break;
                }
                case CHEVRON: {
                    Path pth = new Path();
                    pth.moveTo(9.5f, 5f);
                    pth.lineTo(16.5f, 12f);
                    pth.lineTo(9.5f, 19f);
                    cv.drawPath(pth, p);
                    break;
                }
                case SEARCH: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(10.5f, 10.5f, 6.2f, p);
                    cv.drawLine(15.2f, 15.2f, 20.5f, 20.5f, p);
                    break;
                }
                case WIFI: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawArc(new RectF(0.5f, 8f, 23.5f, 31f), 225f, 90f, false, p);
                    cv.drawArc(new RectF(4f, 11.5f, 20f, 27.5f), 225f, 90f, false, p);
                    cv.drawArc(new RectF(7.5f, 15f, 16.5f, 24f), 225f, 90f, false, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12f, 20.2f, 1.6f, p);
                    break;
                }
                case BT: {
                    Path pth = new Path();
                    pth.moveTo(8.5f, 7.5f);
                    pth.lineTo(16f, 17f);
                    pth.lineTo(12f, 21f);
                    pth.lineTo(12f, 3f);
                    pth.lineTo(16f, 7f);
                    pth.lineTo(8.5f, 16.5f);
                    cv.drawPath(pth, p);
                    break;
                }
                case APPS: {
                    p.setStyle(Paint.Style.FILL);
                    float r = 2.2f;
                    cv.drawRoundRect(new RectF(2.5f, 2.5f, 10.5f, 10.5f), r, r, p);
                    cv.drawRoundRect(new RectF(13.5f, 2.5f, 21.5f, 10.5f), r, r, p);
                    cv.drawRoundRect(new RectF(2.5f, 13.5f, 10.5f, 21.5f), r, r, p);
                    cv.drawRoundRect(new RectF(13.5f, 13.5f, 21.5f, 21.5f), r, r, p);
                    break;
                }
                case BATTERY: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawRoundRect(new RectF(2.5f, 7f, 18.5f, 17f), 2.2f, 2.2f, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRoundRect(new RectF(19.5f, 9.8f, 21.8f, 14.2f), 1f, 1f, p);
                    cv.drawRoundRect(new RectF(4.6f, 9.1f, 13f, 14.9f), 1f, 1f, p);
                    break;
                }
                case DISPLAY: {
                    cv.drawCircle(12f, 12f, 4.2f, p);
                    p.setStyle(Paint.Style.STROKE);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4.0;
                        float x1 = (float) (12f + Math.cos(a) * 6.6f);
                        float y1 = (float) (12f + Math.sin(a) * 6.6f);
                        float x2 = (float) (12f + Math.cos(a) * 9.4f);
                        float y2 = (float) (12f + Math.sin(a) * 9.4f);
                        cv.drawLine(x1, y1, x2, y2, p);
                    }
                    break;
                }
                case SOUND: {
                    p.setStyle(Paint.Style.FILL);
                    Path pth = new Path();
                    pth.moveTo(3f, 9f);
                    pth.lineTo(7f, 9f);
                    pth.lineTo(11f, 5f);
                    pth.lineTo(11f, 19f);
                    pth.lineTo(7f, 15f);
                    pth.lineTo(3f, 15f);
                    pth.close();
                    cv.drawPath(pth, p);
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawArc(new RectF(8.5f, 7f, 19f, 17f), -55f, 110f, false, p);
                    cv.drawArc(new RectF(8.5f, 3.5f, 22.5f, 20.5f), -50f, 100f, false, p);
                    break;
                }
                case STORAGE: {
                    Path pth = new Path();
                    pth.moveTo(5f, 2.8f);
                    pth.lineTo(15.5f, 2.8f);
                    pth.lineTo(19.5f, 6.8f);
                    pth.lineTo(19.5f, 21.2f);
                    pth.lineTo(5f, 21.2f);
                    pth.close();
                    cv.drawPath(pth, p);
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 4; i++) {
                        float x = 7.2f + i * 3.1f;
                        cv.drawRect(x, 4.4f, x + 1.7f, 7.6f, p);
                    }
                    break;
                }
                case SYSTEM: {
                    cv.drawCircle(12f, 12f, 5.2f, p);
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 8; i++) {
                        cv.save();
                        cv.rotate(i * 45f, 12f, 12f);
                        cv.drawRoundRect(new RectF(10.6f, 2.4f, 13.4f, 6.2f), 1.2f, 1.2f, p);
                        cv.restore();
                    }
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(12f, 12f, 2.1f, p);
                    break;
                }
                case ABOUT:
                case VERSION: {
                    cv.drawCircle(12f, 12f, 9f, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12f, 7.4f, 1.35f, p);
                    cv.drawRoundRect(new RectF(11f, 10.4f, 13f, 17.2f), 1f, 1f, p);
                    break;
                }
                case DEV: {
                    Path a = new Path();
                    a.moveTo(9f, 7f);
                    a.lineTo(4f, 12f);
                    a.lineTo(9f, 17f);
                    cv.drawPath(a, p);
                    Path b = new Path();
                    b.moveTo(15f, 7f);
                    b.lineTo(20f, 12f);
                    b.lineTo(15f, 17f);
                    cv.drawPath(b, p);
                    break;
                }
                case BLOCK: {
                    Path pth = shield();
                    cv.drawPath(pth, p);
                    cv.drawLine(8f, 12f, 16f, 12f, p);
                    break;
                }
                case GUARD: {
                    Path pth = shield();
                    cv.drawPath(pth, p);
                    Path ck = new Path();
                    ck.moveTo(8.2f, 12.2f);
                    ck.lineTo(11f, 15f);
                    ck.lineTo(16f, 9.4f);
                    cv.drawPath(ck, p);
                    break;
                }
                case NOTIFY: {
                    Path pth = new Path();
                    pth.moveTo(5.6f, 17.4f);
                    pth.lineTo(18.4f, 17.4f);
                    pth.lineTo(16.8f, 15.2f);
                    pth.lineTo(16.8f, 10.4f);
                    pth.cubicTo(16.8f, 7.6f, 14.7f, 5.4f, 12f, 5.4f);
                    pth.cubicTo(9.3f, 5.4f, 7.2f, 7.6f, 7.2f, 10.4f);
                    pth.lineTo(7.2f, 15.2f);
                    pth.close();
                    cv.drawPath(pth, p);
                    cv.drawLine(10.2f, 20f, 13.8f, 20f, p);
                    break;
                }
                case HOME: {
                    Path pth = new Path();
                    pth.moveTo(3.2f, 11.2f);
                    pth.lineTo(12f, 3.4f);
                    pth.lineTo(20.8f, 11.2f);
                    cv.drawPath(pth, p);
                    cv.drawRoundRect(new RectF(6.2f, 11f, 17.8f, 20.6f), 1.4f, 1.4f, p);
                    break;
                }
                case GRID: {
                    p.setStyle(Paint.Style.FILL);
                    for (int y = 0; y < 3; y++) {
                        for (int x = 0; x < 3; x++) {
                            cv.drawCircle(6f + x * 6f, 6f + y * 6f, 1.6f, p);
                        }
                    }
                    break;
                }
                case DOTS: {
                    cv.drawCircle(12f, 12f, 8.4f, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12f, 12f, 3f, p);
                    break;
                }
                case ROTATE: {
                    cv.drawArc(new RectF(4f, 4f, 20f, 20f), -60f, 285f, false, p);
                    p.setStyle(Paint.Style.FILL);
                    Path pth = new Path();
                    pth.moveTo(18.2f, 3.2f);
                    pth.lineTo(20.6f, 8.6f);
                    pth.lineTo(14.9f, 7.4f);
                    pth.close();
                    cv.drawPath(pth, p);
                    break;
                }
                case EQ: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(6f, 4f, 6f, 20f, p);
                    cv.drawLine(12f, 4f, 12f, 20f, p);
                    cv.drawLine(18f, 4f, 18f, 20f, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(6f, 8f, 2.1f, p);
                    cv.drawCircle(12f, 15f, 2.1f, p);
                    cv.drawCircle(18f, 10f, 2.1f, p);
                    break;
                }
                case CLOCK: {
                    cv.drawCircle(12f, 12f, 8.6f, p);
                    cv.drawLine(12f, 7f, 12f, 12f, p);
                    cv.drawLine(12f, 12f, 15.8f, 13.8f, p);
                    break;
                }
                case FILE: {
                    Path pth = new Path();
                    pth.moveTo(2.8f, 6.4f);
                    pth.lineTo(9.4f, 6.4f);
                    pth.lineTo(11.2f, 8.6f);
                    pth.lineTo(21.2f, 8.6f);
                    pth.lineTo(21.2f, 19.4f);
                    pth.lineTo(2.8f, 19.4f);
                    pth.close();
                    cv.drawPath(pth, p);
                    break;
                }
                case RESET: {
                    cv.drawArc(new RectF(4f, 4f, 20f, 20f), 40f, 280f, false, p);
                    p.setStyle(Paint.Style.FILL);
                    Path pth = new Path();
                    pth.moveTo(20.8f, 2.6f);
                    pth.lineTo(21.2f, 8.6f);
                    pth.lineTo(15.6f, 6.4f);
                    pth.close();
                    cv.drawPath(pth, p);
                    break;
                }
                case WALLPAPER: {
                    cv.drawRoundRect(new RectF(2.8f, 4.8f, 21.2f, 19.2f), 1.6f, 1.6f, p);
                    Path pth = new Path();
                    pth.moveTo(5f, 16.4f);
                    pth.lineTo(9.6f, 11.2f);
                    pth.lineTo(13f, 15.2f);
                    pth.lineTo(15.4f, 12.8f);
                    pth.lineTo(19.2f, 16.4f);
                    cv.drawPath(pth, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(7.6f, 8.6f, 1.3f, p);
                    break;
                }
                default:
                    cv.drawCircle(12f, 12f, 8.4f, p);
                    break;
            }
        }

        private Path shield() {
            Path pth = new Path();
            pth.moveTo(12f, 2.6f);
            pth.lineTo(20f, 5.6f);
            pth.lineTo(20f, 11.8f);
            pth.cubicTo(20f, 16.6f, 16.6f, 19.8f, 12f, 21.4f);
            pth.cubicTo(7.4f, 19.8f, 4f, 16.6f, 4f, 11.8f);
            pth.lineTo(4f, 5.6f);
            pth.close();
            return pth;
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
