package com.c10h.launcher;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 桌面内导航条（AOSP Pie NavigationBar 几何）：
 * 高度 48dp、透明底、三个 24dp 图标居中成簇（间距 160dp），
 * 位置/尺寸与系统导航条一致，仅替代被隐藏的系统导航条。
 */
public class NavBarView extends LinearLayout {

    private final ImageView back;
    private final ImageView home;
    private final ImageView recents;
    private Runnable actionListener;

    /** 每次按键动作后回调（悬浮导航用来重置 3s 自动隐藏计时）。 */
    public void setActionListener(Runnable r) {
        actionListener = r;
    }

    private void notifyAction() {
        try {
            if (actionListener != null) {
                actionListener.run();
            }
        } catch (Throwable ignored) {
        }
    }

    public NavBarView(final Activity ctx) {
        this(ctx, false);
    }

    /**
     * @param overlay true = 悬浮导航形态：三键作用于当前前台应用，
     *                不把点按交给（可能已暂停的）MainActivity。
     */
    public NavBarView(final Activity ctx, boolean overlay) {
        super(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setBackground(null);

        final MainActivity host = (!overlay && ctx instanceof MainActivity)
                ? (MainActivity) ctx : null;

        back = makeIcon(ctx, 0);
        home = makeIcon(ctx, 1);
        recents = makeIcon(ctx, 2);

        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                notifyAction();
                NavActions.back(ctx, host == null ? null : new NavActions.HostBack() {
                    @Override
                    public void hostBack() {
                        ctx.onBackPressed();
                    }
                });
            }
        });
        home.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                notifyAction();
                NavActions.home(ctx, host == null ? null : new Runnable() {
                    @Override
                    public void run() {
                        if (host != null) {
                            host.dispatchHome();
                        }
                    }
                });
            }
        });
        recents.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                notifyAction();
                NavActions.recents(ctx);
            }
        });

        LinearLayout cluster = new LinearLayout(ctx);
        cluster.setOrientation(HORIZONTAL);
        cluster.setGravity(Gravity.CENTER);
        int slot = Ui.dp(ctx, 160);
        View[] order = WorkspaceStore.navReversed(ctx)
                ? new View[]{recents, home, back}
                : new View[]{back, home, recents};
        for (View v : order) {
            cluster.addView(wrap(ctx, v), new LinearLayout.LayoutParams(slot,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        addView(cluster, new LinearLayout.LayoutParams(Ui.dp(ctx, 480),
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private FrameLayout wrap(Activity ctx, View icon) {
        FrameLayout f = new FrameLayout(ctx);
        f.addView(icon, new FrameLayout.LayoutParams(Ui.dp(ctx, 30),
                Ui.dp(ctx, 30), Gravity.CENTER));
        return f;
    }

    private ImageView makeIcon(Activity ctx, int kind) {
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(Ui.navGlyph(ctx, kind, 0xE6FFFFFF));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setFocusable(true);
        return iv;
    }
}
