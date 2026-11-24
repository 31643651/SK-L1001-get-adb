package com.c10h.launcher;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用内导航（系统导航条已在设备层删除）：
 *  - 桌面 onPause 后挂载，onResume 立即卸载；桌面内只有自带导航条，不会出现第二条；
 *  - 应用内从屏幕底部 8dp 热区上滑，唤出与桌面同布局的三键导航；
 *  - 导航条半透明（70% 深色底）、圆角、480×48dp、底部居中上移 16dp；
 *  - 3 秒无操作自动隐藏；每次按键/触摸都会重新计时。
 */
public class FloatingNav {

    private static final int AUTO_HIDE_MS = 3000;
    private static boolean sSuppressed;

    private final MainActivity host;
    private final WindowManager wm;
    private final Handler h = new Handler();
    private FrameLayout bar;
    private WindowManager.LayoutParams barParams;
    private View hot;
    private boolean attached;
    private boolean barShown;
    private final List<View> gestureViews = new ArrayList<View>();
    private SwipeUpGesture swipeUp;
    private EdgeHintView backHint;
    private WindowManager.LayoutParams backHintParams;
    private boolean hintLeft;
    private int hintW;
    private int hintH;

    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    public FloatingNav(MainActivity activity) {
        this.host = activity;
        this.wm = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    /** 最近任务页前台时禁止挂载（该页自带底栏）。 */
    public static void setSuppressed(boolean suppressed) {
        sSuppressed = suppressed;
    }

    public void attach() {
        attach(0);
    }

    private void attach(final int attempt) {
        if (attached || sSuppressed) {
            return;
        }
        if (host.hasWindowFocus()) {
            // 应用启动动画期间桌面窗口可能还有焦点；稍后重试，避免一次失败后
            // 整段时间都唤不出导航。
            if (attempt < 5) {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        attach(attempt + 1);
                    }
                }, 400);
            }
            return;
        }
        try {
            if (WorkspaceStore.gestureNav(host)) {
                swipeUp = new SwipeUpGesture(host);
                swipeUp.attach();
                addGestureZones();
            } else {
                addHotZone();
                addBar();
            }
            attached = true;
        } catch (Throwable t) {
            detach();
            if (attempt < 3) {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        attach(attempt + 1);
                    }
                }, 600);
            }
        }
    }

    public void detach() {
        try {
            h.removeCallbacks(hideTask);
        } catch (Throwable ignored) {
        }
        remove(bar);
        remove(hot);
        if (swipeUp != null) {
            swipeUp.detach();
            swipeUp = null;
        }
        for (View v : gestureViews) {
            remove(v);
        }
        remove(backHint);
        backHint = null;
        gestureViews.clear();
        bar = null;
        hot = null;
        barShown = false;
        attached = false;
    }

    public boolean isAttached() {
        return attached;
    }

    /** 手势导航：左右边缘内滑返回、底部上滑回主页、上滑悬停多任务。 */
    private void addGestureZones() {
        int edge = host.dp(10);
        addBackHint();
        addGestureView(edgeZone(Gravity.START, edge, true));
        addGestureView(edgeZone(Gravity.END, edge, false));
    }

    /** 边缘返回时的“突起”提示：随向内滑动距离变大，滑回则缩回。 */
    private void addBackHint() {
        backHint = new EdgeHintView(host);
        backHint.setVisibility(View.GONE);
        hintW = host.dp(36);
        hintH = host.dp(160);
        backHintParams = params(hintW, hintH, Gravity.TOP | Gravity.START);
        backHintParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        backHintParams.x = -hintW;
        backHintParams.y = 0;
        wm.addView(backHint, backHintParams);
    }

    private void addGestureView(View v) {
        WindowManager.LayoutParams p;
        if (v.getTag() != null && "bottom".equals(v.getTag())) {
            p = params(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(12), Gravity.BOTTOM);
        } else {
            p = params(host.dp(10), ViewGroup.LayoutParams.MATCH_PARENT,
                    v.getTag() != null && "left".equals(v.getTag())
                            ? Gravity.START : Gravity.END);
        }
        p.y = 0;
        gestureViews.add(v);
        wm.addView(v, p);
    }

    private View edgeZone(int gravity, int width, final boolean left) {
        View v = new View(host);
        v.setTag(left ? "left" : "right");
        v.setOnTouchListener(new View.OnTouchListener() {
            private float x0;
            private float y0;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            x0 = e.getRawX();
                            y0 = e.getRawY();
                            hintLeft = left;
                            if (backHint != null) {
                                backHint.setVisibility(View.VISIBLE);
                                backHint.setAlpha(1f);
                                backHint.setState(0f, left);
                                updateHint(0f, y0);
                            }
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float d = left ? e.getRawX() - x0 : x0 - e.getRawX();
                            updateHint(Math.max(0f, d), e.getRawY());
                            return true;
                        }
                        case MotionEvent.ACTION_UP: {
                            float d = left ? e.getRawX() - x0 : x0 - e.getRawX();
                            boolean fired = d >= host.dp(70);
                            hideHint(fired);
                            if (fired) {
                                NavActions.back(host, null);
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_CANCEL:
                            hideHint(false);
                            return true;
                        default:
                            return true;
                    }
                } catch (Throwable ignored) {
                    return true;
                }
            }
        });
        return v;
    }

    private void updateHint(float d, float rawY) {
        if (backHint == null || backHintParams == null) {
            return;
        }
        try {
            float ratio = Math.min(1f, d / (float) host.dp(70));
            int screenW = host.getResources().getDisplayMetrics().widthPixels;
            int screenH = host.getResources().getDisplayMetrics().heightPixels;
            backHintParams.x = hintLeft ? 0 : (screenW - hintW);
            backHintParams.y = (int) Math.max(0, Math.min(screenH - hintH,
                    rawY - hintH / 2f));
            wm.updateViewLayout(backHint, backHintParams);
            backHint.setState(ratio, hintLeft);
        } catch (Throwable ignored) {
        }
    }

    private void hideHint(boolean fired) {
        if (backHint == null) {
            return;
        }
        try {
            backHint.animate().cancel();
            backHint.animate().alpha(0f)
                    .setDuration(130)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (backHint != null) {
                                backHint.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        } catch (Throwable ignored) {
        }
    }

    private View bottomZone() {
        View v = new View(host);
        v.setTag("bottom");
        v.setOnTouchListener(new View.OnTouchListener() {
            private float y0;
            private boolean armed;
            private boolean fired;
            private final Runnable recentsTask = new Runnable() {
                @Override
                public void run() {
                    if (!fired) {
                        fired = true;
                        NavActions.recents(host);
                    }
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            y0 = e.getRawY();
                            armed = false;
                            fired = false;
                            h.removeCallbacks(recentsTask);
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dy = y0 - e.getRawY();
                            if (!fired && dy > host.dp(150) && !armed) {
                                armed = true;
                                h.postDelayed(recentsTask, 320);
                            } else if (!fired && armed && dy < host.dp(120)) {
                                armed = false;
                                h.removeCallbacks(recentsTask);
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP: {
                            h.removeCallbacks(recentsTask);
                            float dy = y0 - e.getRawY();
                            if (!fired && dy > host.dp(70)) {
                                NavActions.home(host, null);
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_CANCEL:
                            h.removeCallbacks(recentsTask);
                            return true;
                        default:
                            return true;
                    }
                } catch (Throwable ignored) {
                    return true;
                }
            }
        });
        return v;
    }

    private void addHotZone() {
        int hPx = host.dp(8);
        hot = new View(host);
        WindowManager.LayoutParams p = params(
                ViewGroup.LayoutParams.MATCH_PARENT, hPx, Gravity.BOTTOM);
        p.y = 0;
        hot.setOnTouchListener(new View.OnTouchListener() {
            private float y0;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                try {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            y0 = e.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (e.getRawY() - y0 < -host.dp(24)) {
                                show();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
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

    private void addBar() {
        bar = new FrameLayout(host);
        // 与主桌面导航同布局：整条贴底、通栏 48dp；半透明深色底。
        bar.setBackgroundColor(0xB3101B26);
        NavBarView inner = new NavBarView(host, true);
        inner.setActionListener(new Runnable() {
            @Override
            public void run() {
                resetTimer();
            }
        });
        bar.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 保持窗口常驻（透明、屏外），避免 GONE 后 surface 被回收导致
        // “上滑后 show() 被调用但导航条不出现”。隐藏时用 NOT_TOUCHABLE 穿透。
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(1f);
        bar.setTranslationY(host.dp(48));

        WindowManager.LayoutParams p = params(
                ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48), Gravity.BOTTOM);
        p.y = 0;
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        barParams = p;
        wm.addView(bar, p);
    }

    private void show() {
        try {
            if (bar == null) {
                return;
            }
            bar.animate().cancel();
            bar.setVisibility(View.VISIBLE);
            if (!barShown) {
                // 从屏幕下方滑入。
                bar.setTranslationY(host.dp(48));
            }
            // GONE 后窗口 surface 可能被回收，强制让 WindowManager 重新布局。
            if (barParams != null) {
                try {
                    barParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    wm.updateViewLayout(bar, barParams);
                } catch (Throwable ignored) {
                }
            }
            bar.animate().translationY(0f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                    .start();
            barShown = true;
            resetTimer();
        } catch (Throwable ignored) {
        }
    }

    private void hide() {
        try {
            h.removeCallbacks(hideTask);
            if (bar == null || !barShown) {
                if (bar != null && barParams != null) {
                    try {
                        barParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        wm.updateViewLayout(bar, barParams);
                    } catch (Throwable ignored) {
                    }
                }
                barShown = false;
                return;
            }
            barShown = false;
            // 向下滑出 + 淡出，结束后再 GONE。
            bar.animate().cancel();
            bar.animate().translationY(host.dp(48))
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.8f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!barShown && bar != null && barParams != null) {
                                    barParams.flags |=
                                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                    wm.updateViewLayout(bar, barParams);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    })
                    .start();
        } catch (Throwable ignored) {
        }
    }

    private void resetTimer() {
        try {
            if (!barShown) {
                return;
            }
            h.removeCallbacks(hideTask);
            h.postDelayed(hideTask, AUTO_HIDE_MS);
        } catch (Throwable ignored) {
        }
    }

    private WindowManager.LayoutParams params(int w, int hgt, int gravity) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, hgt,
                android.os.Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        return p;
    }

    private void remove(View v) {
        if (v == null) {
            return;
        }
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable t) {
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
            }
        }
    }
}
