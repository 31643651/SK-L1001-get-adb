package com.c10h.launcher.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.c10h.launcher.R;

/**
 * AOSP Settings（android-9.0.0_r61）行/栏构造器。
 * 尺寸取自 AOSP：磁贴 72dp / 图标 36dp / 图标边距 18dp；
 * preference 行最小 48dp、左右 16dp；搜索卡片 48dp 高、16dp 外边距、2dp 圆角。
 */
public final class SettingsUi {

    public static final int BG = 0xFFFFFFFF;
    public static final int PRIMARY = 0xFFF5F5F5;
    public static final int ACCENT = 0xFF1976D2;
    public static final int TEXT = 0xFF212121;
    public static final int SUB = 0xFF757575;
    public static final int DIVIDER = 0xFFE0E0E0;
    public static final int ICON = 0xFF757575;

    private static Typeface medium;

    private SettingsUi() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static Typeface medium(Context c) {
        if (medium == null) {
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
        return medium;
    }

    public static Drawable ripple(Context c) {
        try {
            // mask 不能为 null：null 是无界涟漪，会扩散到全屏。
            return new RippleDrawable(ColorStateList.valueOf(0x1F000000), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void selectable(Context c, View v) {
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackground(ripple(c));
    }

    /** 涟漪叠在指定底图之上（用于白色卡片，避免底图被涟漪覆盖）。 */
    private static Drawable rippleOn(Context c, Drawable content) {
        try {
            return new RippleDrawable(ColorStateList.valueOf(0x1F000000), content,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
        } catch (Throwable t) {
            return content;
        }
    }

    public static void makeSelectable(Context c, View v) {
        selectable(c, v);
    }

    /** 二级页顶栏：48dp 返回箭头（触控目标）+ 20sp 标题。 */
    public static LinearLayout appBar(Context c, String title, final Runnable onBack) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setMinimumHeight(dp(c, 56));
        bar.setPadding(dp(c, 4), 0, dp(c, 16), 0);

        if (onBack != null) {
            ImageView back = new ImageView(c);
            back.setImageDrawable(SettingsIcons.icon(c, SettingsIcons.ARROW_BACK, TEXT));
            back.setScaleType(ImageView.ScaleType.CENTER);
            back.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
            selectable(c, back);
            back.setFocusable(false);
            back.setFocusableInTouchMode(false);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBack.run();
                }
            });
            bar.addView(back, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        }

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(medium(c));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = onBack != null ? dp(c, 8) : dp(c, 12);
        bar.addView(t, lp);
        return bar;
    }

    /** 顶层搜索卡片：grey_100 容器 + 白色 48dp 卡片（2dp 圆角、2dp 阴影）。 */
    public static LinearLayout searchCard(Context c, final Runnable onClick) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(PRIMARY);
        wrap.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(c, 2));
        card.setBackground(rippleOn(c, bg));
        card.setElevation(dp(c, 2));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onClick != null) {
                    onClick.run();
                }
            }
        });

        ImageView lens = new ImageView(c);
        lens.setImageDrawable(SettingsIcons.icon(c, SettingsIcons.SEARCH, SUB));
        lens.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
        ilp.leftMargin = dp(c, 16);
        card.addView(lens, ilp);

        TextView hint = new TextView(c);
        hint.setText("搜索设置");
        hint.setTextColor(SUB);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(c, 16);
        card.addView(hint, tlp);

        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)));
        return wrap;
    }

    /** 搜索态顶栏：返回箭头 + 全宽输入框。 */
    public static SearchField searchField(Context c, final Runnable onBack,
                                         TextWatcher watcher) {
        SearchField holder = new SearchField();
        LinearLayout bar = new LinearLayout(c);
        holder.root = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setMinimumHeight(dp(c, 56));
        bar.setPadding(dp(c, 4), 0, dp(c, 16), 0);

        ImageView back = new ImageView(c);
        back.setImageDrawable(SettingsIcons.icon(c, SettingsIcons.ARROW_BACK, TEXT));
        back.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        selectable(c, back);
        back.setFocusable(false);
        back.setFocusableInTouchMode(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBack != null) {
                    onBack.run();
                }
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));

        EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setHint("搜索设置");
        input.setHintTextColor(SUB);
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setBackground(null);
        input.setPadding(dp(c, 8), 0, 0, 0);
        input.addTextChangedListener(watcher);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(c, 4);
        bar.addView(input, lp);
        holder.input = input;
        return holder;
    }

    /** 72dp 仪表盘磁贴：36dp 图标 + 18dp 边距 + 16sp 标题 + 14sp 摘要。 */
    public static LinearLayout tile(Context c, int iconType, String title, String summary,
                                    View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 72));
        row.setPadding(dp(c, 18), 0, dp(c, 16), 0);
        selectable(c, row);
        row.setOnClickListener(l);
        row.setContentDescription(summary == null || summary.length() == 0
                ? title : title + "，" + summary);

        ImageView icon = new ImageView(c);
        icon.setImageDrawable(SettingsIcons.icon(c, iconType, ICON));
        row.addView(icon, new LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)));

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(c, 18), dp(c, 8), 0, dp(c, 8));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setSingleLine(true);
        mid.addView(t);
        if (summary != null && summary.length() > 0) {
            TextView s = new TextView(c);
            s.setText(summary);
            s.setTextColor(SUB);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s.setPadding(0, dp(c, 2), 0, 0);
            mid.addView(s);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** AOSP preference 行：24dp 图标 + 标题/摘要 + 右侧控件。 */
    public static LinearLayout pref(Context c, int iconType, Drawable iconDrawable,
                                    String title, CharSequence summary, View right,
                                    View.OnClickListener l) {
        return prefInternal(c, iconType, iconDrawable, 24, title, summary, right, l);
    }

    /** 应用列表行：40dp 应用图标。 */
    public static LinearLayout prefBig(Context c, Drawable iconDrawable, String title,
                                       CharSequence summary, View right,
                                       View.OnClickListener l) {
        return prefInternal(c, -1, iconDrawable, 40, title, summary, right, l);
    }

    private static LinearLayout prefInternal(Context c, int iconType, Drawable iconDrawable,
                                             int iconDp, String title, CharSequence summary,
                                             View right, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        if (l != null) {
            selectable(c, row);
            row.setOnClickListener(l);
            row.setContentDescription(summary == null || summary.length() == 0
                    ? title : title + "，" + summary);
        } else {
            row.setBackgroundColor(BG);
        }

        Drawable d = iconDrawable;
        if (d == null && iconType >= 0) {
            d = SettingsIcons.icon(c, iconType, ICON);
        }
        if (d != null) {
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(d);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    dp(c, iconDp), dp(c, iconDp));
            ilp.rightMargin = dp(c, 20);
            row.addView(icon, ilp);
        }

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(t);
        if (summary != null && summary.length() > 0) {
            TextView s = new TextView(c);
            s.setText(summary);
            s.setTextColor(SUB);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            s.setPadding(0, dp(c, 2), 0, 0);
            mid.addView(s);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (right != null) {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.leftMargin = dp(c, 8);
            row.addView(right, rlp);
        }
        return row;
    }

    public static LinearLayout pref(Context c, int iconType, String title, CharSequence summary,
                                    View right, View.OnClickListener l) {
        return pref(c, iconType, null, title, summary, right, l);
    }

    /** AOSP 开关行：整行可点，Switch 本身 clickable=false。 */
    public static LinearLayout switchPref(Context c, int iconType, String title, String summary,
                                          boolean checked,
                                          final CompoundButton.OnCheckedChangeListener l) {
        final Switch sw = switchWidget(c);
        sw.setChecked(checked);
        LinearLayout row = pref(c, iconType, title, summary, sw, null);
        makeSelectable(c, row);
        row.setContentDescription(summary == null || summary.length() == 0
                ? title : title + "，" + summary);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        sw.setOnCheckedChangeListener(l);
        return row;
    }

    public static Switch switchWidget(Context c) {
        Switch sw = new Switch(c);
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setShowText(false);
        return sw;
    }

    /**
     * AOSP SwitchBar：48dp 高；开启时 accent 底白字，关闭时 grey_100 底深字。
     * 整条可点，Switch 本身不可点。
     */
    public static SwitchBar switchBar(Context c, String label, boolean on,
                                      CompoundButton.OnCheckedChangeListener l) {
        SwitchBar h = new SwitchBar();
        h.ctx = c;
        LinearLayout row = new LinearLayout(c);
        h.root = row;
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 48));
        row.setPadding(dp(c, 16), 0, dp(c, 16), 0);

        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(medium(c));
        h.label = t;
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(c);
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setShowText(false);
        h.toggle = sw;
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        h.set(on);
        sw.setChecked(on);
        row.setContentDescription(label);
        CompoundButton.OnCheckedChangeListener proxy =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        h.set(checked);
                        if (l != null) {
                            l.onCheckedChanged(b, checked);
                        }
                    }
                };
        h.listener = proxy;
        sw.setOnCheckedChangeListener(proxy);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                h.toggle.toggle();
            }
        });
        return h;
    }

    public static final class SwitchBar {
        public LinearLayout root;
        public TextView label;
        public Switch toggle;
        private Context ctx;
        private CompoundButton.OnCheckedChangeListener listener;

        /** 仅刷新外观，不触发回调（用于跨进程状态回报）。 */
        public void setSilently(boolean on) {
            try {
                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(on);
                set(on);
                toggle.setOnCheckedChangeListener(listener);
            } catch (Throwable ignored) {
            }
        }

        public void set(boolean on) {
            try {
                GradientDrawable g = new GradientDrawable();
                g.setColor(on ? ACCENT : PRIMARY);
                root.setBackground(rippleOn(ctx, g));
                label.setTextColor(on ? 0xFFFFFFFF : TEXT);
                // AOSP SwitchBar：开启时白色滑块 + 半透明白轨道，避免与 accent 底同色。
                if (on) {
                    toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
                    toggle.setTrackTintList(ColorStateList.valueOf(0x66FFFFFF));
                } else {
                    toggle.setThumbTintList(null);
                    toggle.setTrackTintList(null);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** AOSP 分组标题：14sp、colorAccent、sans-serif-medium。 */
    public static TextView header(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(medium(c));
        t.setPadding(dp(c, 16), dp(c, 22), dp(c, 16), dp(c, 6));
        return t;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.8f))));
        return v;
    }

    public static TextView body(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLineSpacing(dp(c, 3), 1f);
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        return t;
    }

    /** AOSP 头部动作按钮：primary=accent 实心；否则描边 + accent 文字。 */
    public static TextView actionButton(Context c, String text, boolean primary,
                                       View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(medium(c));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(c, 64));
        t.setMinimumHeight(dp(c, 36));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 2));
        if (primary) {
            g.setColor(ACCENT);
            t.setTextColor(0xFFFFFFFF);
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, dp(c, 1)), 0xFFBDBDBD);
            t.setTextColor(ACCENT);
        }
        t.setBackground(rippleOn(c, g));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** AOSP 滑杆行：标题 + 右侧数值 + SeekBar。 */
    public static Slider slider(Context c, String title, int max, int progress) {
        Slider s = new Slider();
        s.root = new LinearLayout(c);
        s.root.setOrientation(LinearLayout.VERTICAL);
        s.root.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 6));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        s.value = new TextView(c);
        s.value.setTextColor(SUB);
        s.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        head.addView(s.value);
        s.root.addView(head);

        s.bar = new SeekBar(c);
        s.bar.setMax(max);
        s.bar.setProgress(progress);
        s.root.addView(s.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    /** AOSP 文本按钮（14sp、accent、48dp 触控高）。 */
    public static TextView flatButton(Context c, String text, View.OnClickListener l) {        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(medium(c));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinimumHeight(dp(c, 48));
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        selectable(c, t);
        t.setOnClickListener(l);
        return t;
    }

    /** 滑杆持有者。 */
    public static final class Slider {
        public LinearLayout root;
        public SeekBar bar;
        public TextView value;

        public void setValueText(CharSequence s) {
            if (value != null) {
                value.setText(s);
            }
        }
    }

    /** 搜索栏持有者。 */
    public static final class SearchField {
        public LinearLayout root;
        public EditText input;
    }
}
