package com.c10h.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 全手势上滑（应用内）：
 *  - 从底部上滑，立即把当前屏幕截图缩小叠在模糊的桌面壁纸上；
 *  - 松手位置在屏幕 1/5~4/5 之间进入多任务，否则回主页；
 *  - 视觉是“近似 Quickstep”：静态截图缩放，不是实时窗口。
 */
public class SwipeUpGesture {

    private final MainActivity host;
    private final WindowManager wm;
    private final boolean withHot;
    private final boolean homeOnRelease;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout overlay;
    private ImageView bgView;
    private ImageView fgView;
    private View hot;
    private boolean attached;
    private boolean showing;
    private float y0;
    private float progress;
    private final int screenW;
    private final int screenH;
    private float corner;

    public SwipeUpGesture(MainActivity a) {
        this(a, true, true);
    }

    public SwipeUpGesture(MainActivity a, boolean withHot, boolean homeOnRelease) {
        host = a;
        this.withHot = withHot;
        this.homeOnRelease = homeOnRelease;
        wm = (WindowManager) a.getSystemService(Activity.WINDOW_SERVICE);
        screenW = a.getResources().getDisplayMetrics().widthPixels;
        screenH = a.getResources().getDisplayMetrics().heightPixels;
    }

    public void attach() {
        if (attached) {
            return;
        }
        try {
            buildOverlay();
            if (withHot) {
                addHot();
            }
            attached = true;
            loadWallpaperAsync();
        } catch (Throwable t) {
            detach();
        }
    }

    public void detach() {
        remove(overlay);
        remove(hot);
        overlay = null;
        bgView = null;
        fgView = null;
        hot = null;
        attached = false;
        showing = false;
    }

    private void buildOverlay() {
        overlay = new FrameLayout(host);
        overlay.setVisibility(View.GONE);
        overlay.setBackgroundColor(0x00000000);

        bgView = new ImageView(host);
        bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgView.setAlpha(0.9f);
        overlay.addView(bgView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        fgView = new ImageView(host);
        fgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fgView.setVisibility(View.GONE);
        fgView.setClipToOutline(true);
        fgView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
            }
        });
        overlay.addView(fgView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams p = params(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.addView(overlay, p);
    }

    private void addHot() {
        hot = new View(host);
        WindowManager.LayoutParams p = params(
                FrameLayout.LayoutParams.MATCH_PARENT, host.dp(12), Gravity.BOTTOM);
        p.y = 0;
        hot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            y0 = e.getRawY();
                            begin();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            update(Math.max(0f, y0 - e.getRawY()));
                            return true;
                        case MotionEvent.ACTION_UP: {
                            float stopY = e.getRawY();
                            boolean middle = stopY > screenH * 0.2f
                                    && stopY < screenH * 0.8f;
                            finish(middle);
                            return true;
                        }
                        case MotionEvent.ACTION_CANCEL:
                            finish(false);
                            return true;
                        default:
                            return true;
                    }
                } catch (Throwable ignored) {
                    return true;
                }
            }
        });
        wm.addView(hot, p);
    }

    public void begin() {
        showing = true;
        progress = 0f;
        // 先隐藏 overlay 再截屏，否则会截到 overlay 自己（模糊壁纸）。
        if (overlay != null) {
            overlay.animate().cancel();
            overlay.setVisibility(View.GONE);
        }
        if (fgView != null) {
            fgView.setVisibility(View.GONE);
            fgView.setAlpha(1f);
            fgView.setScaleX(1f);
            fgView.setScaleY(1f);
            fgView.setTranslationY(0f);
        }
        captureAsync();
    }

    public void update(float dy) {
        if (!showing) {
            return;
        }
        progress = Math.max(0f, Math.min(1f, dy / (screenH * 0.72f)));
        applyProgress();
    }

    private void applyProgress() {
        if (fgView != null) {
            float scale = 1f - 0.22f * progress;
            fgView.setScaleX(scale);
            fgView.setScaleY(scale);
            fgView.setTranslationY(-progress * screenH * 0.10f);
            corner = progress * host.dp(24);
            fgView.invalidateOutline();
        }
        if (bgView != null) {
            bgView.setAlpha(0.55f + 0.45f * progress);
        }
    }

    public void finish(final boolean recents) {
        if (!showing) {
            return;
        }
        showing = false;
        try {
            if (fgView != null) {
                fgView.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f)
                        .setDuration(150).start();
            }
            if (bgView != null) {
                bgView.animate().alpha(0f).setDuration(150).start();
            }
            if (overlay != null) {
                overlay.animate().alpha(0f).setDuration(160)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (overlay != null) {
                                    overlay.setVisibility(View.GONE);
                                }
                                if (fgView != null) {
                                    Drawable d = fgView.getDrawable();
                                    fgView.setImageDrawable(null);
                                    if (d instanceof BitmapDrawable) {
                                        Bitmap b = ((BitmapDrawable) d).getBitmap();
                                        if (b != null && !b.isRecycled()) {
                                            b.recycle();
                                        }
                                    }
                                    fgView.setVisibility(View.GONE);
                                    fgView.setAlpha(1f);
                                    fgView.setScaleX(1f);
                                    fgView.setScaleY(1f);
                                    fgView.setTranslationY(0f);
                                }
                                if (bgView != null) {
                                    bgView.setAlpha(0.9f);
                                }
                            }
                        }).start();
            }
            if (recents) {
                NavActions.recents(host);
            } else if (homeOnRelease) {
                NavActions.home(host, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private void captureAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = ScreenCapture.capture(host);
                if (bmp == null) {
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fgView != null && showing) {
                            fgView.setImageBitmap(bmp);
                            fgView.setVisibility(View.VISIBLE);
                            if (overlay != null) {
                                overlay.setVisibility(View.VISIBLE);
                                overlay.setAlpha(1f);
                            }
                            applyProgress();
                        } else if (bmp != null) {
                            bmp.recycle();
                        }
                    }
                });
            }
        }, "c10h-swipe-capture").start();
    }

    private void loadWallpaperAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap blurred = host.blurredWallpaper();
                if (blurred == null) {
                    Bitmap bmp = ScreenCapture.capture(host);
                    if (bmp != null) {
                        blurred = Blur.blur(bmp, 18);
                    }
                }
                final Bitmap result = blurred;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bgView != null && result != null) {
                            bgView.setImageBitmap(result);
                        }
                    }
                });
            }
        }, "c10h-swipe-blur").start();
    }

    private WindowManager.LayoutParams params(int w, int h, int gravity) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                android.os.Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        return p;
    }

    private void remove(View v) {
        if (v == null) {
            return;
        }
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }
}
