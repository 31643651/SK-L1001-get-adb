package com.c10h.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
 * AOSP Folder 等价面板：浅色圆角卡片、成员图标带名称。
 * 交互：点击打开；长按成员拖动，松手在卡片外即移出到桌面；空文件夹自动消失。
 */
public class FolderDialog extends Dialog {

    private final Activity host;
    private final Item folder;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout card;
    private FrameLayout dragLayer;
    private View floatView;
    private Item dragMember;
    private boolean dragging;
    private float lastRawX;
    private float lastRawY;

    public FolderDialog(Activity a, Item f) {
        super(a);
        this.host = a;
        this.folder = f;
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setCanceledOnTouchOutside(false);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        } catch (Throwable ignored) {
        }
        try {
            build();
        } catch (Throwable t) {
            Toast.makeText(a, "文件夹面板失败：" + t, Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }

    private void build() {
        float d = host.getResources().getDisplayMetrics().density;
        int pad = (int) (18 * d + 0.5f);

        FrameLayout outer = new FrameLayout(host);
        outer.setBackgroundColor(0x00000000);

        // 壁纸同桌面几何绘制 + 暗层，模拟 AOSP 文件夹的“透壁纸”观感。
        try {
            android.app.WallpaperManager wpm =
                    android.app.WallpaperManager.getInstance(host);
            android.graphics.drawable.Drawable wp = wpm.getDrawable();
            android.graphics.Bitmap bmp = null;
            if (wp instanceof android.graphics.drawable.BitmapDrawable) {
                bmp = ((android.graphics.drawable.BitmapDrawable) wp).getBitmap();
            } else if (wp != null) {
                int iw = Math.max(1, wp.getIntrinsicWidth());
                int ih = Math.max(1, wp.getIntrinsicHeight());
                bmp = android.graphics.Bitmap.createBitmap(iw, ih,
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                wp.setBounds(0, 0, iw, ih);
                wp.draw(cv);
            }
            if (bmp != null) {
                android.widget.ImageView wpv = new android.widget.ImageView(host);
                wpv.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
                wpv.setImageBitmap(bmp);
                android.graphics.Matrix m = new android.graphics.Matrix();
                int sh = host.getResources().getDisplayMetrics().heightPixels;
                m.setTranslate(0f, -(bmp.getHeight() - sh) / 2f);
                wpv.setImageMatrix(m);
                outer.addView(wpv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                View dim = new View(host);
                dim.setBackgroundColor(0x59000000);
                outer.addView(dim, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Throwable ignored) {
        }

        card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF2F2F2);
        bg.setCornerRadius((int) (20 * d + 0.5f));
        card.setBackground(bg);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(host);
        title.setText(folder.name);
        title.setTextColor(0xFF202124);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hint = new TextView(host);
        hint.setText("长按拖出");
        hint.setTextColor(0xFF80868B);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(hint);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridView grid = new GridView(host);
        grid.setNumColumns(4);
        grid.setVerticalSpacing((int) (10 * d));
        grid.setHorizontalSpacing((int) (6 * d));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setCacheColorHint(0x00000000);
        grid.setBackgroundColor(0x00000000);
        grid.setPadding(0, (int) (10 * d), 0, 0);
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

        int h = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.42f);
        card.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        int cardW = (int) (host.getResources().getDisplayMetrics().widthPixels * 0.52f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        outer.addView(card, clp);

        dragLayer = new FrameLayout(host);
        dragLayer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        outer.addView(dragLayer);

        outer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!dragging) {
                    dismiss();
                }
            }
        });
        setContentView(outer);
        try {
            getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.35f;
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }

        try {
            grid.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    return trackDrag(e);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void beginDrag(Item member, View anchor) {
        if (member == null) {
            return;
        }
        dragMember = member;
        dragging = true;
        lastRawX = anchor == null ? 0 : anchor.getX();
        lastRawY = anchor == null ? 0 : anchor.getY();
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

    /** 全局触摸跟踪：移动浮层；松手在卡片外则移出。返回 false 不阻断原点击。 */
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
            dismiss();
            if (host instanceof MainActivity) {
                ((MainActivity) host).moveMemberOut(folder, member);
            }
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
            dismiss();
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
            v.setItem(it, labelOf(it), 0);
            v.setIcon(iconOf(it));
            v.setLabelColor(0xFF202124);
            return v;
        }

        private String labelOf(Item it) {
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

        private android.graphics.drawable.Drawable iconOf(Item it) {
            try {
                return host.getPackageManager().getApplicationIcon(it.pkg);
            } catch (Throwable t) {
                return host.getDrawable(R.drawable.ic_launcher);
            }
        }
    }
}
