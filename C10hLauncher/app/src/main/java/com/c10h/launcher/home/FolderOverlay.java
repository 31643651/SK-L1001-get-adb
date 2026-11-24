package com.c10h.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * AOSP 风格文件夹：从桌面文件夹图标位置放大展开；展开后桌面内容隐藏、仅留壁纸并整体变暗；
 * 全程卡片 100% 透明（无边框），只有不同程度的暗化。
 */
public class FolderOverlay extends FrameLayout {

    private final Activity host;
    private final Item folder;
    private final int[] anchorRect = new int[4];
    private final Runnable onClosed;
    private LinearLayout card;
    private View dim;
    private FrameLayout content;
    private FrameLayout dragLayer;
    private View floatView;
    private Item dragMember;
    private boolean dragging;
    private float lastRawX;
    private float lastRawY;

    public FolderOverlay(Activity a, Item f, View anchor, Runnable closed) {
        super(a);
        this.host = a;
        this.folder = f;
        this.onClosed = closed;
        if (anchor != null) {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            anchorRect[0] = loc[0];
            anchorRect[1] = loc[1];
            anchorRect[2] = anchor.getWidth();
            anchorRect[3] = anchor.getHeight();
        }
        build();
    }

    private void build() {
        float d = host.getResources().getDisplayMetrics().density;
        int pad = (int) (18 * d + 0.5f);
        setBackgroundColor(0x00000000);
        dim = new View(host);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content = new FrameLayout(host);
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(null);       // 100% 透明，无边框
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(host);
        title.setText(folder.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int) (12 * d));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridView grid = new GridView(host);
        grid.setNumColumns(4);
        grid.setVerticalSpacing((int) (10 * d));
        grid.setHorizontalSpacing((int) (6 * d));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setCacheColorHint(0x00000000);
        grid.setBackgroundColor(0x00000000);
        final MemberAdapter adapter = new MemberAdapter();
        grid.setAdapter(adapter);
        adapter.setData(folder.members);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (dragging) {
                    return;
                }
                start(adapter.getItem(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                beginDrag(adapter.getItem(position), view);
                return true;
            }
        });
        grid.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return trackDrag(e);
            }
        });

        int h = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.42f);
        card.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        int cardW = (int) (host.getResources().getDisplayMetrics().widthPixels * 0.52f);
        content.addView(card, new FrameLayout.LayoutParams(cardW,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        dragLayer = new FrameLayout(host);
        dragLayer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(dragLayer);

        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!dragging) {
                    close();
                }
            }
        });

        // 背景整体渐暗（不缩放）；内容层从图标位置平滑放大。
        post(new Runnable() {
            @Override
            public void run() {
                try {
                    dim.animate().alpha(0.6f).setDuration(260)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                            .start();
                    float cw = content.getWidth() > 0 ? content.getWidth() : getWidth();
                    if (anchorRect[2] <= 0 || cw <= 0) {
                        content.setScaleX(0.85f);
                        content.setScaleY(0.85f);
                        content.setAlpha(0f);
                        content.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(260)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                                .start();
                        return;
                    }
                    float px = anchorRect[0] + anchorRect[2] / 2f;
                    float py = anchorRect[1] + anchorRect[3] / 2f;
                    content.setPivotX(px);
                    content.setPivotY(py);
                    float start = Math.max(0.18f, anchorRect[2] / cw);
                    content.setScaleX(start);
                    content.setScaleY(start);
                    content.setAlpha(0f);
                    content.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(280)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(2.4f))
                            .start();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void close() {
        try {
            dim.animate().alpha(0f).setDuration(200)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.6f))
                    .start();
            float cw = content.getWidth() > 0 ? content.getWidth() : getWidth();
            if (anchorRect[2] > 0 && cw > 0) {
                float px = anchorRect[0] + anchorRect[2] / 2f;
                float py = anchorRect[1] + anchorRect[3] / 2f;
                content.setPivotX(px);
                content.setPivotY(py);
                float end = Math.max(0.18f, anchorRect[2] / cw);
                content.animate().scaleX(end).scaleY(end).alpha(0f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator(1.8f))
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                removeSelf();
                            }
                        }).start();
            } else {
                removeSelf();
            }
        } catch (Throwable ignored) {
            removeSelf();
        }
    }

    private void removeSelf() {
        try {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) {
                p.removeView(this);
            }
            if (onClosed != null) {
                onClosed.run();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 供宿主返回键调用。 */
    public void closeOverlay() {
        close();
    }

    private void beginDrag(Item member, View anchor) {
        if (member == null) {
            return;
        }
        dragMember = member;
        dragging = true;
        try {
            IconView fv = new IconView(host, Ui.dp(host, 60), 12f);
            fv.setItem(member, labelOfMember(member), 0);
            fv.setIcon(host.getPackageManager().getApplicationIcon(member.pkg));
            fv.setLabelColor(0xFF202124);
            fv.setAlpha(0.9f);
            floatView = fv;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Ui.dp(host, 80), Ui.dp(host, 96));
            int[] loc = new int[2];
            if (anchor != null) {
                anchor.getLocationOnScreen(loc);
            }
            lp.leftMargin = Math.max(0, loc[0]);
            lp.topMargin = Math.max(0, loc[1]);
            dragLayer.addView(floatView, lp);
            Ui.haptic(card);
        } catch (Throwable ignored) {
        }
    }

    private boolean trackDrag(MotionEvent e) {
        try {
            if (!dragging) {
                return false;
            }
            lastRawX = e.getRawX();
            lastRawY = e.getRawY();
            if (floatView != null) {
                floatView.setX(lastRawX - Ui.dp(host, 40));
                floatView.setY(lastRawY - Ui.dp(host, 48));
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                finishDrag();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void finishDrag() {
        Item member = dragMember;
        dragMember = null;
        dragging = false;
        boolean outside = false;
        try {
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            outside = lastRawX < loc[0] || lastRawX > loc[0] + card.getWidth()
                    || lastRawY < loc[1] || lastRawY > loc[1] + card.getHeight();
        } catch (Throwable ignored) {
        }
        try {
            if (floatView != null && dragLayer != null) {
                dragLayer.removeView(floatView);
            }
        } catch (Throwable ignored) {
        }
        floatView = null;
        if (outside && member != null) {
            if (host instanceof MainActivity) {
                ((MainActivity) host).moveMemberOut(folder, member);
            }
            removeSelf();
        }
    }

    private String labelOfMember(Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(it.pkg, it.cls);
            List<android.content.pm.ResolveInfo> l =
                    host.getPackageManager().queryIntentActivities(i, 0);
            if (l != null && !l.isEmpty()) {
                CharSequence cs = l.get(0).loadLabel(host.getPackageManager());
                return cs == null ? it.pkg : cs.toString();
            }
        } catch (Throwable ignored) {
        }
        return it.pkg;
    }

    private void start(Item it) {
        if (it == null || it.isFolder()) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(it.pkg, it.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(i);
            removeSelf();
        } catch (Throwable t) {
            Toast.makeText(host, "无法启动 " + it.pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private final class MemberAdapter extends BaseAdapter {
        private final List<Item> data = new ArrayList<Item>();

        void setData(List<Item> list) {
            data.clear();
            if (list != null) {
                data.addAll(list);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Item getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item it = data.get(position);
            IconView v = new IconView(host, Ui.dp(host, 56), 12f);
            v.setItem(it, labelOfMember(it), 0);
            try {
                v.setIcon(host.getPackageManager().getApplicationIcon(it.pkg));
            } catch (Throwable ignored) {
            }
            v.setLabelColor(0xFFFFFFFF);
            return v;
        }
    }
}
