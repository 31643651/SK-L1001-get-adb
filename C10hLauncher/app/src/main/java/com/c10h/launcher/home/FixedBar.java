package com.c10h.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 底部固定栏（M9，AOSP Hotseat）：7 个等宽槽位，只放应用，永远存在。
 * 条目由数据库 container=HOTSEAT 提供；拖动加入/移除由 DragController 统一处理。
 */
public class FixedBar {

    public interface Listener {
        void onAppClick(Item item, View anchor);

        void onAppLongClick(Item item, View anchor);

        default void onAppMenu(Item item, View anchor) {
        }
    }

    private final Activity ctx;
    private final Listener listener;
    private final LinearLayout row;
    private final List<Item> items = new ArrayList<Item>();
    private final Map<String, Drawable> icons = new HashMap<String, Drawable>();
    private int slots = 7;
    private int iconPx;
    private float textSp;
    private final FrameLayout[] cells;
    private int hlIndex = -2;
    private Item[] slotItems = new Item[0];

    public FixedBar(Activity activity, LinearLayout container, Listener l,
                    int slots, int iconPx, float textSp) {
        this.ctx = activity;
        this.listener = l;
        this.row = container;
        this.slots = Math.max(1, slots);
        this.iconPx = iconPx;
        this.textSp = textSp;
        this.cells = new FrameLayout[this.slots];
        buildShell();
    }

    private void buildShell() {
        row.removeAllViews();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Ui.dp(ctx, 112), 0, Ui.dp(ctx, 111), 0);
        row.setBackground(null);
        int cellW = Ui.dp(ctx, 151);
        int h = Ui.dp(ctx, 96);
        for (int i = 0; i < slots; i++) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, h);
            row.addView(cell, lp);
            cells[i] = cell;
        }
        row.setMinimumHeight(h);
        row.setVisibility(View.VISIBLE);
    }

    public void setItems(List<Item> list) {
        items.clear();
        slotItems = new Item[slots];
        if (list != null) {
            for (Item it : list) {
                if (it == null || !it.isApp()) {
                    continue;
                }
                int rank = it.rank;
                if (rank < 0 || rank >= slots || slotItems[rank] != null) {
                    rank = -1;
                    for (int i = 0; i < slots; i++) {
                        if (slotItems[i] == null) {
                            rank = i;
                            break;
                        }
                    }
                    if (rank < 0) {
                        break;
                    }
                }
                slotItems[rank] = it;
                items.add(it);
            }
        }
        rebuild();
    }

    public List<Item> items() {
        return items;
    }

    public View getView() {
        return row;
    }

    public void rebuild() {
        for (int i = 0; i < cells.length; i++) {
            FrameLayout cell = cells[i];
            cell.removeAllViews();
            cell.setBackground(null);
            if (slotItems != null && i < slotItems.length && slotItems[i] != null) {
                final Item it = slotItems[i];
                IconView v = new IconView(ctx, iconPx, textSp);
                v.setItem(it, labelOf(it), 0);
                v.setIcon(iconOf(it.pkg));
                v.setLabelVisible(false);
                final IconView iconView = v;
                final Item longItem = it;
                v.setOnTouchListener(new View.OnTouchListener() {
                    private final android.os.Handler hh =
                            new android.os.Handler(android.os.Looper.getMainLooper());
                    private float x0;
                    private float y0;
                    private boolean moved;
                    private boolean fired;
                    private long downAt;
                    private final Runnable fire = new Runnable() {
                        @Override
                        public void run() {
                            if (fired) {
                                return;
                            }
                            fired = true;
                            if (listener != null) {
                                Ui.haptic(iconView);
                                listener.onAppLongClick(longItem, iconView);
                            }
                        }
                    };

                    @Override
                    public boolean onTouch(View vv, android.view.MotionEvent e) {
                        switch (e.getActionMasked()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                x0 = e.getRawX();
                                y0 = e.getRawY();
                                moved = false;
                                fired = false;
                                downAt = android.os.SystemClock.uptimeMillis();
                                hh.postDelayed(fire, 400);
                                break;
                            case android.view.MotionEvent.ACTION_MOVE:
                                if (!fired) {
                                    boolean far = Math.abs(e.getRawX() - x0) > Ui.dp(ctx, 8)
                                            || Math.abs(e.getRawY() - y0) > Ui.dp(ctx, 8);
                                    long held = android.os.SystemClock.uptimeMillis() - downAt;
                                    if (far && held > 120) {
                                        hh.removeCallbacks(fire);
                                        fire.run();
                                    }
                                }
                                break;
                            default:
                                hh.removeCallbacks(fire);
                                break;
                        }
                        return false;
                    }
                });
                v.setListener(new IconView.Listener() {
                    @Override
                    public void onIconClick(IconView view, Item item) {
                        if (listener != null) {
                            listener.onAppClick(item, view);
                        }
                    }

                    @Override
                    public void onIconLongClick(IconView view, Item item) {
                        if (listener != null) {
                            listener.onAppLongClick(item, view);
                        }
                    }

                    @Override
                    public void onIconMenu(IconView view, Item item) {
                        if (listener != null) {
                            listener.onAppMenu(item, view);
                        }
                    }
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
                cell.addView(v, lp);
            } else {
                View blank = new View(ctx);
                cell.addView(blank, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }
        row.requestLayout();
    }

    public void setHighlight(int index) {
        if (hlIndex == index) {
            return;
        }
        hlIndex = index;
        for (int i = 0; i < cells.length; i++) {
            if (i == index) {
                cells[i].setBackground(Ui.round(0x33FFFFFF, Ui.dp(ctx, 12)));
            } else {
                cells[i].setBackground(null);
            }
        }
    }

    public int slotAt(float x) {
        try {
            int best = -1;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < cells.length; i++) {
                FrameLayout cell = cells[i];
                if (cell == null || cell.getWidth() <= 0) {
                    continue;
                }
                float cx = cell.getLeft() + cell.getWidth() / 2f;
                float d = Math.abs(x - cx);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            return Math.max(0, best);
        } catch (Throwable t) {
            return 0;
        }
    }

    public Item itemAt(int index) {
        return (index < 0 || index >= items.size()) ? null : items.get(index);
    }

    public boolean contains(Item item) {
        if (item == null) {
            return false;
        }
        for (Item x : items) {
            if (x.key().equals(item.key())) {
                return true;
            }
        }
        return false;
    }

    private Drawable iconOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return ctx.getDrawable(R.drawable.ic_launcher);
        }
        Drawable d = icons.get(pkg);
        if (d == null) {
            try {
                d = ctx.getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable t) {
                d = ctx.getDrawable(R.drawable.ic_launcher);
            }
            if (d != null) {
                icons.put(pkg, d);
            }
        }
        return d;
    }

    private String labelOf(Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(it.pkg, it.cls);
            List<ResolveInfo> l = ctx.getPackageManager().queryIntentActivities(i, 0);
            if (l != null && !l.isEmpty()) {
                CharSequence cs = l.get(0).loadLabel(ctx.getPackageManager());
                return cs == null ? it.pkg : cs.toString();
            }
        } catch (Throwable ignored) {
        }
        return it.pkg;
    }
}
