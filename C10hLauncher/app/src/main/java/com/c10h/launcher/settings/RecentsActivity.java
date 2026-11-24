package com.c10h.launcher.settings;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.c10h.launcher.MainActivity;
import com.c10h.launcher.NavBarView;
import com.c10h.launcher.Ui;
import com.c10h.launcher.WorkspaceStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 自研最近任务（AOSP Pie Recents 布局的本地实现，不依赖 SystemUI/Launcher3）：
 *  - 横向卡片流，每张卡片是一个任务的预览；
 *  - 点击卡片切回该任务，点右上角 ✕ 或"全部清除"结束任务；
 *  - 底部保留与桌面同布局的三键导航；
 *  - 平台签名下用 REAL_GET_TASKS 读取全部任务、REMOVE_TASKS 结束任务。
 *
 * 预览图说明：Android 9 的 TaskSnapshot 只给出 GraphicBuffer（@hide），
 * 没有公开的 Bitmap 转换接口；这里用 TaskDescription 的 icon + primaryColor
 * 画卡片预览，和 AOSP 在"快照不可用"时的降级表现一致。
 */
public class RecentsActivity extends Activity {

    // 透壁纸 + 黑色遮罩（AOSP Recents 观感）
    private static final int BG = 0x99000000;
    private static final int CARD = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xCCFFFFFF;
    private static final int CARD_TEXT = 0xFF202124;
    private static final int CARD_DIM = 0xFF5F6368;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TaskCard> shown = new ArrayList<TaskCard>();

    private ActivityManager am;
    private PackageManager pm;
    private FrameLayout root;
    private LinearLayout cards;
    private HorizontalScrollView scroll;
    private TextView empty;
    private TextView clearAll;
    private int cardW;
    private int cardH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            pm = getPackageManager();
            build();
            load();
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("最近任务打开失败\n" + t);
            tv.setTextColor(TEXT);
            tv.setPadding(dp(40), dp(40), dp(40), dp(40));
            setContentView(tv);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 最近任务页自带底栏，避免 MainActivity 的悬浮导航在这里再叠一条。
        try {
            MainActivity.detachFloatingNav();
            MainActivity.setFloatingNavSuppressed(true);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            MainActivity.setFloatingNavSuppressed(false);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            MainActivity.setFloatingNavSuppressed(false);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // 在最近任务页再点"最近任务"键：刷新列表，不再叠一个新页面。
        load();
    }

    // ------------------------------------------------------------ ui

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：标题 + 全部清除
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x4D000000);
        bar.setPadding(dp(24), 0, dp(16), 0);
        TextView title = new TextView(this);
        title.setText("最近任务");
        title.setTextColor(TEXT);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearAll = new TextView(this);
        clearAll.setText("全部清除");
        clearAll.setTextColor(ACCENT);
        clearAll.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        clearAll.setPadding(dp(14), dp(10), dp(14), dp(10));
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAllTasks();
            }
        });
        bar.addView(clearAll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        column.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // 卡片区
        FrameLayout stage = new FrameLayout(this);
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean gesture = WorkspaceStore.gestureNav(this);
        cardH = Math.max(dp(320), screenH - dp(56) - 1
                - (gesture ? 0 : dp(48)) - dp(64));
        cardW = Math.max(dp(240), (int) (cardH * 0.62f));

        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(24), dp(24), dp(24), dp(24));
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setLayoutTransition(buildLayoutTransition());
        scroll.addView(cards, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText("没有最近任务");
        empty.setTextColor(TEXT_DIM);
        empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        stage.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部导航：三键模式才添加；手势模式让卡片延伸到底部。
        if (!gesture) {
            FrameLayout navHost = new FrameLayout(this);
            navHost.setBackgroundColor(0xFF101B26);
            NavBarView nav = new NavBarView(this, true);
            navHost.addView(nav, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            column.addView(navHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }

        setContentView(root);
    }

    // ------------------------------------------------------------ data

    /** 删除/插入卡片时，其余卡片平滑补位（只保留 CHANGE 位移动画）。 */
    private android.animation.LayoutTransition buildLayoutTransition() {
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.setDuration(180);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGE_APPEARING,
                new android.view.animation.DecelerateInterpolator(2f));
        lt.setInterpolator(android.animation.LayoutTransition.CHANGE_DISAPPEARING,
                new android.view.animation.DecelerateInterpolator(2f));
        lt.disableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        return lt;
    }

    private void load() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<TaskCard> out = new ArrayList<TaskCard>();
                try {
                    List<ActivityManager.RecentTaskInfo> tasks =
                            am.getRecentTasks(30, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
                    if (tasks != null) {
                        for (ActivityManager.RecentTaskInfo t : tasks) {
                            TaskCard c = toCard(t);
                            if (c != null) {
                                out.add(c);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                final List<TaskCard> result = out;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        render(result);
                    }
                });
            }
        }, "c10h-recents").start();
    }

    private TaskCard toCard(ActivityManager.RecentTaskInfo t) {
        try {
            if (t == null || t.id <= 0) {
                return null;
            }
            ComponentName cn = t.baseIntent != null ? t.baseIntent.getComponent() : null;
            String pkg = cn != null ? cn.getPackageName()
                    : (t.baseIntent != null ? t.baseIntent.getPackage() : null);
            if (pkg == null) {
                return null;
            }
            // 桌面/设置/最近任务自身不进入卡片流（AOSP 也不把 HOME 放进最近任务）。
            if (pkg.equals(getPackageName())) {
                return null;
            }
            TaskCard c = new TaskCard();
            c.taskId = t.id;
            c.pkg = pkg;
            c.color = 0xFFE8EAED;
            c.label = pkg;
            try {
                if (t.taskDescription != null) {
                    CharSequence l = t.taskDescription.getLabel();
                    if (l != null && l.length() > 0) {
                        c.label = l.toString();
                    }
                    int pc = t.taskDescription.getPrimaryColor();
                    if (pc != 0) {
                        c.color = pc;
                    }
                    c.preview = t.taskDescription.getIcon();
                }
            } catch (Throwable ignored) {
            }
            if (c.label == null || c.label.equals(pkg)) {
                try {
                    c.label = pm.getApplicationLabel(
                            pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Throwable ignored) {
                }
            }
            try {
                c.icon = pm.getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void render(List<TaskCard> list) {
        try {
            cards.removeAllViews();
            shown.clear();
            shown.addAll(list);
            clearAll.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            scroll.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
            for (int i = 0; i < list.size(); i++) {
                View card = makeCard(list.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                lp.leftMargin = dp(10);
                lp.rightMargin = dp(10);
                cards.addView(card, lp);
                // 错峰入场，避免整排卡片"啪"一下全出来。
                card.setAlpha(0f);
                card.setTranslationY(dp(36));
                card.animate().alpha(1f).translationY(0f)
                        .setStartDelay(Math.min(240, i * 35L))
                        .setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                        .start();
            }
            scroll.scrollTo(0, 0);
        } catch (Throwable ignored) {
        }
    }

    private View makeCard(final TaskCard c) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.roundStroke(CARD, dp(14), 1, 0x14000000));
        card.setElevation(dp(6));
        card.setClickable(true);
        card.setTag(c);
        attachSwipe(card, c);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTo(c.taskId);
            }
        });

        // 预览区：primaryColor + 应用图标（AOSP 快照不可用时的降级形态）
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(c.color);
        int headerH = dp(56);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH - headerH);
        card.addView(preview, plp);

        ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (c.preview != null) {
            big.setImageBitmap(c.preview);
        } else if (c.icon != null) {
            big.setImageDrawable(c.icon);
        }
        big.setAlpha(0.92f);
        int bigSize = Math.min(dp(120), cardW / 2);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                bigSize, bigSize, Gravity.CENTER);
        preview.addView(big, blp);

        // 卡片底部信息栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(CARD);
        header.setPadding(dp(12), 0, dp(6), 0);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerH, Gravity.BOTTOM);
        card.addView(header, hlp);

        ImageView small = new ImageView(this);
        small.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (c.icon != null) {
            small.setImageDrawable(c.icon);
        }
        header.addView(small, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView label = new TextView(this);
        label.setText(c.label);
        label.setTextColor(CARD_TEXT);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(10);
        header.addView(label, llp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(CARD_DIM);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.ripple(this, 0x22000000, dp(18), null));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeTask(c);
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));

        return card;
    }

    // ------------------------------------------------------------ actions

    private void switchTo(int taskId) {
        try {
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION);
            finish();
        } catch (Throwable t) {
            Toast.makeText(this, "无法切换任务：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeTask(final TaskCard c) {
        closeTask(c, findCard(c));
    }

    private void closeTask(final TaskCard c, final View card) {
        if (!removeTask(c.taskId)) {
            Toast.makeText(this, "无法结束任务（需 REMOVE_TASKS）", Toast.LENGTH_SHORT).show();
            if (card != null) {
                card.animate().translationY(0f).alpha(1f).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                        .start();
            }
            return;
        }
        shown.remove(c);
        if (card == null) {
            updateEmpty();
            return;
        }
        float dy = card.getTranslationY();
        if (dy == 0f) {
            dy = -dp(140);
        } else {
            dy = dy > 0 ? dp(180) : -dp(180);
        }
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                .translationY(dy)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.6f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        cards.removeView(card);
                        updateEmpty();
                        if (cards.getChildCount() > 0) {
                            scroll.scrollTo(0, 0);
                        }
                    }
                }).start();
    }

    private View findCard(TaskCard c) {
        for (int i = 0; i < cards.getChildCount(); i++) {
            View v = cards.getChildAt(i);
            if (v.getTag() == c) {
                return v;
            }
        }
        return null;
    }

    private void updateEmpty() {
        boolean none = cards.getChildCount() == 0;
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        scroll.setVisibility(none ? View.GONE : View.VISIBLE);
        clearAll.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    /** 上下滑动卡片结束任务；水平滑动仍交给 HorizontalScrollView。 */
    private void attachSwipe(final FrameLayout card, final TaskCard c) {
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        card.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        dragging = false;
                        // 不消费 DOWN，点击与横向滚动保持原样。
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        if (!dragging && Math.abs(dy) > slop
                                && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                            dragging = true;
                            if (card.getParent() != null) {
                                card.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                        if (dragging) {
                            card.setTranslationY(dy);
                            card.setAlpha(Math.max(0.35f,
                                    1f - Math.abs(dy) / (cardH * 0.8f)));
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!dragging) {
                            return false;
                        }
                        dragging = false;
                        float dy = card.getTranslationY();
                        if (Math.abs(dy) > cardH * 0.22f) {
                            closeTask(c, card);
                        } else {
                            card.animate().translationY(0f).alpha(1f)
                                    .setDuration(160)
                                    .setInterpolator(
                                            new android.view.animation.DecelerateInterpolator(2f))
                                    .start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private void clearAllTasks() {
        List<TaskCard> all = new ArrayList<TaskCard>(shown);
        for (TaskCard c : all) {
            removeTask(c.taskId);
        }
        shown.clear();
        cards.removeAllViews();
        updateEmpty();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    /** ActivityManager.removeTask 在本机被裁掉；直接走 IActivityManager.removeTask。 */
    private boolean removeTask(int taskId) {
        try {
            java.lang.reflect.Method getService =
                    ActivityManager.class.getMethod("getService");
            Object iam = getService.invoke(null);
            java.lang.reflect.Method m = iam.getClass().getMethod("removeTask", int.class);
            Object r = m.invoke(iam, taskId);
            boolean ok = !(r instanceof Boolean) || ((Boolean) r);
            android.util.Log.i("C10HRecents", "removeTask " + taskId + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w("C10HRecents", "removeTask " + taskId + " failed: " + t);
            return false;
        }
    }

    private static final class TaskCard {
        int taskId;
        String pkg;
        String label;
        int color;
        Drawable icon;
        Bitmap preview;
    }
}
