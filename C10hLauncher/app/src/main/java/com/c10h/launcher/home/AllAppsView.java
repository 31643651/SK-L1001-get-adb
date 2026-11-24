package com.c10h.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.TranslateAnimation;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AOSP AllAppsContainerView 的等价实现（M9）：垂直分组列表 + 搜索 + 错峰动画。
 * 始终展示全部已安装应用，不因固定栏变化而过滤。
 */
public class AllAppsView extends FrameLayout {

    public interface Listener {
        void onAppClick(Item item, View anchor);

        void onAppLongClick(Item item, View anchor);

        void onClosed();

        default void onAppMenu(Item item, View anchor) {
        }
    }

    private final Activity ctx;
    private final Listener listener;
    private final View scrim;
    private final LinearLayout panel;
    private final TextView subtitle;
    private final EditText search;
    private final ListView list;
    private final Adapter adapter;
    private final int iconPx;
    private final float textSp;
    private final int cols;
    private final PackageManager pm;
    private final Map<String, Drawable> icons = new HashMap<String, Drawable>();
    private final Map<String, String> labels = new HashMap<String, String>();
    private final List<Item> all = new ArrayList<Item>();
    private boolean open;
    private boolean staggered;

    public AllAppsView(Activity activity, Listener l, int cols, int iconPx, float textSp) {
        super(activity);
        this.ctx = activity;
        this.listener = l;
        this.cols = Math.max(3, cols);
        this.iconPx = iconPx;
        this.textSp = textSp;
        this.pm = activity.getPackageManager();
        setVisibility(GONE);
        setClickable(true);

        scrim = new View(activity);
        scrim.setBackgroundColor(0xCC000000);
        addView(scrim, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        int p = Ui.dp(activity, 14);
        panel.setPadding(p, Ui.dp(activity, 8), p, 0);

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("全部应用");
        title.setTextColor(0xFF202124);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        subtitle = new TextView(activity);
        subtitle.setTextColor(0xFF5F6368);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPadding(0, 0, Ui.dp(activity, 10), 0);
        head.addView(subtitle);

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(0xFF5F6368);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.ripple(activity, 0x22000000, Ui.dp(activity, 20),
                Ui.round(0x11000000, Ui.dp(activity, 20))));
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close(true);
            }
        });
        head.addView(close, new LinearLayout.LayoutParams(
                Ui.dp(activity, 40), Ui.dp(activity, 40)));
        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 54)));

        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("搜索应用");
        search.setTextColor(0xFF202124);
        search.setHintTextColor(0xFF80868B);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        search.setBackground(Ui.ripple(activity, 0x22000000, Ui.dp(activity, 22),
                Ui.round(0xFFF1F3F4, Ui.dp(activity, 22))));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                rebuild(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        slp.bottomMargin = Ui.dp(activity, 6);
        panel.addView(search, slp);

        list = new ListView(activity);
        list.setCacheColorHint(0x00000000);
        list.setBackgroundColor(0xFFFFFFFF);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(true);
        list.setDivider(null);
        list.setPadding(0, 0, 0, Ui.dp(activity, 10));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(panel, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close(true);
            }
        });
    }

    public void setItems(List<Item> items) {
        all.clear();
        if (items != null) {
            all.addAll(items);
        }
        search.setText("");
        rebuild("");
    }

    public boolean isOpen() {
        return open;
    }

    public void open(boolean focusSearch) {
        if (open) {
            return;
        }
        open = true;
        setVisibility(VISIBLE);
        bringToFront();
        try {
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            // 遮罩淡入 + 整块面板从底部滑入（位移取屏幕高的一半以上，
            // 实机上 48dp 的位移几乎看不出来，会被误认为"没有动画"）。
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setTranslationY(screenH * 0.62f);
            panel.animate().translationY(0f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(2.6f)).start();
            if (!staggered) {
                staggered = true;
                list.setLayoutAnimation(stagger());
                list.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (open) {
                                list.scheduleLayoutAnimation();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, 200);
            }
            // 不要在本帧之后 setLayoutAnimation(null)：Android 9 的
            // AbsListView/ViewGroup 在绘制期间可能读到 null 控制器并 NPE
            // （java.lang.NullPointerException: isDone() on a null object）。
            // 控制器动画结束后 isDone() 为 true，保留它不会重复播放。
        } catch (Throwable ignored) {
        }
        if (focusSearch) {
            try {
                search.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) ctx.getSystemService(
                                Activity.INPUT_METHOD_SERVICE);
                imm.showSoftInput(search, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    public void close(final boolean notify) {
        if (!open) {
            hideKeyboard();
            return;
        }
        open = false;
        hideKeyboard();
        try {
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            scrim.animate().alpha(0f).setDuration(200).start();
            panel.animate().cancel();
            panel.animate().translationY(screenH * 0.62f).setDuration(220)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(2f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            setVisibility(GONE);
                            panel.setAlpha(1f);
                            panel.setTranslationY(0f);
                            if (notify && listener != null) {
                                listener.onClosed();
                            }
                        }
                    }).start();
        } catch (Throwable t) {
            setVisibility(GONE);
            if (notify && listener != null) {
                listener.onClosed();
            }
        }
    }

    private void hideKeyboard() {
        try {
            search.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) ctx.getSystemService(
                            Activity.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    private void rebuild(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        List<Item> filtered = new ArrayList<Item>();
        for (Item it : all) {
            if (q.isEmpty() || labelOf(it).toLowerCase(Locale.getDefault()).contains(q)) {
                filtered.add(it);
            }
        }
        Collections.sort(filtered, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                String pa = pinyinOf(labelOf(a));
                String pb = pinyinOf(labelOf(b));
                int c = pa.compareToIgnoreCase(pb);
                if (c != 0) {
                    return c;
                }
                return Collator.getInstance(Locale.CHINA).compare(labelOf(a), labelOf(b));
            }
        });
        List<Object> rows = new ArrayList<Object>();
        String section = null;
        List<Item> line = new ArrayList<Item>();
        for (Item it : filtered) {
            String s = sectionOf(labelOf(it));
            if (section == null || !s.equals(section)) {
                if (!line.isEmpty()) {
                    rows.add(new ArrayList<Item>(line));
                    line.clear();
                }
                rows.add(s);
                section = s;
            }
            line.add(it);
            if (line.size() >= cols) {
                rows.add(new ArrayList<Item>(line));
                line.clear();
            }
        }
        if (!line.isEmpty()) {
            rows.add(line);
        }
        if (filtered.isEmpty()) {
            rows.add("未找到应用");
        }
        adapter.setRows(rows);
        subtitle.setText(filtered.size() + " 个应用");
    }

    /** 中文转拼音（Han-Latin），用于首字母分组；失败回退原字符。 */
    private static String pinyinOf(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        char first = label.charAt(0);
        if (first < 128) {
            return label;
        }
        try {
            android.icu.text.Transliterator t =
                    android.icu.text.Transliterator.getInstance("Han-Latin");
            String latin = t.transliterate(label);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < latin.length(); i++) {
                char c = latin.charAt(i);
                if (Character.isLetter(c) && c < 128) {
                    sb.append(Character.toLowerCase(c));
                } else if (sb.length() > 0) {
                    break;
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return label;
    }

    private static String sectionOf(String label) {
        String p = pinyinOf(label);
        if (p.isEmpty()) {
            return "#";
        }
        char c = Character.toUpperCase(p.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    private String labelOf(Item it) {
        String key = it.pkg + "/" + it.cls;
        String s = labels.get(key);
        if (s != null) {
            return s;
        }
        s = it.pkg;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(it.pkg, it.cls);
            List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
            if (l != null && !l.isEmpty()) {
                CharSequence cs = l.get(0).loadLabel(pm);
                s = cs == null ? it.pkg : cs.toString();
            }
        } catch (Throwable ignored) {
        }
        labels.put(key, s);
        return s;
    }

    private Drawable iconOf(Item it) {
        Drawable d = icons.get(it.pkg);
        if (d == null) {
            try {
                d = pm.getApplicationIcon(it.pkg);
            } catch (Throwable t) {
                d = ctx.getDrawable(R.drawable.ic_launcher);
            }
            if (d != null) {
                icons.put(it.pkg, d);
            }
        }
        return d;
    }

    private LayoutAnimationController stagger() {
        AnimationSet set = new AnimationSet(true);
        AlphaAnimation a = new AlphaAnimation(0f, 1f);
        a.setDuration(220);
        TranslateAnimation t = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0.12f, Animation.RELATIVE_TO_SELF, 0f);
        t.setDuration(220);
        set.addAnimation(a);
        set.addAnimation(t);
        set.setInterpolator(new DecelerateInterpolator(2f));
        LayoutAnimationController c = new LayoutAnimationController(set, 0.02f);
        c.setOrder(LayoutAnimationController.ORDER_NORMAL);
        return c;
    }

    private final class Adapter extends BaseAdapter {

        private final List<Object> rows = new ArrayList<Object>();

        void setRows(List<Object> r) {
            rows.clear();
            rows.addAll(r);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object o = rows.get(position);
            if (o instanceof String) {
                TextView tv;
                if (convertView instanceof TextView) {
                    tv = (TextView) convertView;
                } else {
                    tv = new TextView(ctx);
                    tv.setTextColor(0xFF5F6368);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    tv.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4));
                }
                tv.setText((String) o);
                return tv;
            }
            LinearLayout line;
            if (convertView instanceof LinearLayout) {
                line = (LinearLayout) convertView;
            } else {
                line = new LinearLayout(ctx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.TOP);
                line.setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2));
            }
            line.removeAllViews();
            List<Item> items = (List<Item>) o;
            for (int i = 0; i < cols; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = Ui.dp(ctx, 2);
                lp.rightMargin = Ui.dp(ctx, 2);
                if (i < items.size()) {
                    final Item it = items.get(i);
                    IconView v = new IconView(ctx, iconPx, textSp);
                    v.setItem(it, labelOf(it), 0);
                    v.setIcon(iconOf(it));
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
                    line.addView(v, lp);
                } else {
                    View blank = new View(ctx);
                    line.addView(blank, lp);
                }
            }
            return line;
        }
    }
}
