package com.c10h.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * EQ 调节面板（规格 §5；2026-11-24 二次改版）：
 * 左侧 dB 刻度 + 10 条竖向滑条（SeekBar 旋转 -90°，进度自下而上），
 * 量程 ±20 dB / 0.5 dB 步；每条滑条下方显示实时数值，最下方标频率 Hz；
 * 标题行右侧为"启用"总开关。
 *
 * 本次针对电视实测问题的修改：
 * - 滑条热区：旋转后触摸宽度原来只有 10dp，几乎点不中。改为每列按权重均分整行宽度、
 *   旋转前高度 48dp（旋转后即 48dp 宽热区），列本身整块可点；
 * - 预设不再使用 Spinner：电视上 Spinner 下拉会错位，且下拉项文字与深色弹窗
 *   同色导致"里面没有预设"。改为自绘深色预设列表弹窗（每行 56dp），选中即应用；
 * - 保存预设改为自绘深色输入弹窗，避免系统对话框深色主题下输入框字看不见。
 *
 * - 只调 EqEngine API，不持有 effect（面板随 Activity 销毁，引擎存活不受影响）；
 * - 降级：引擎为 null → 红字 + 控件禁用；未挂载 → 红字提示，滑条仍可写 prefs；
 * - 全部 try-catch 兜底，打开失败由调用方 toast，绝不闪退。
 */
public class EqPanel extends Dialog {

    private static final int MAX_DB = 20;        // 量程 ±20 dB
    private static final int STEPS = MAX_DB * 4; // 0.5 dB 步 → 80 步

    private static final int BG = 0xFF16222E;
    private static final int BG_CARD = 0xFF22384C;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int SUB = 0xFF9FB3C8;
    private static final int ACCENT = 0xFF3DDC97;

    private final EqEngine eq;    // 可能为 null（引擎初始化失败）
    private final boolean usable; // eq 可用性，构造内一次判定，buildColumn 复用
    private final int sliderLen;  // 竖向滑条长度（px），随屏幕高度自适应

    private final SeekBar[] bars = new SeekBar[EqEngine.FREQS.length]; // 供预设应用时刷新 UI
    private final TextView[] vals = new TextView[EqEngine.FREQS.length];
    private CheckBoxCompat onBox; // 启用开关（应用预设时同步勾选）
    private CheckBoxCompat linkBox; // 附近频段联动
    /** 各段上一次的值（联动增量计算用）。 */
    private final float[] lastDb = new float[EqEngine.FREQS.length];
    /** 联动/预设刷新时置位，避免监听器把程序性 setProgress 当成用户操作。 */
    private boolean linking;
    /** 拖动过程中改动过的段，抬手时统一落盘。 */
    private final boolean[] dirty = new boolean[EqEngine.FREQS.length];

    /** 当前选中的预设名（下拉框已移除，改由按钮 + 弹窗维护）。 */
    private String activePreset;
    private TextView presetBtn;
    private LinearLayout rootLayout;  // 面板根布局
    private Activity panelActivity;   // 弹窗需要使用 Activity 上下文

    public EqPanel(Activity a) {
        super(a);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        eq = LauncherApp.eq();
        usable = (eq != null);
        panelActivity = a;

        // 竖条长度：取屏高一半，且为标题/数值/Hz 区域留出空间
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int rest = screenH - dp(a, 190);
        sliderLen = Math.max(dp(a, 140), Math.min(screenH / 2, rest));

        for (int i = 0; i < lastDb.length; i++) {
            lastDb[i] = usable ? eq.getBand(i) : 0f;
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(a, BG, 18));
        int pad = dp(a, 16);
        root.setPadding(pad, pad, pad, pad);

        // ---- 标题行：标题 + 启用 ----
        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(a);
        title.setText("音效均衡器");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        onBox = new CheckBoxCompat(a, "启用");
        onBox.setChecked(usable && eq.isEnabled());
        onBox.setEnabled(usable);
        onBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !onBox.isChecked();
                onBox.setChecked(next);
                if (usable) {
                    eq.setEnabled(next);
                }
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48));
        olp.leftMargin = dp(a, 8);
        titleRow.addView(onBox, olp);

        linkBox = new CheckBoxCompat(a, "附近联动");
        linkBox.setChecked(usable && eq.linkBands());
        linkBox.setEnabled(usable);
        linkBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !linkBox.isChecked();
                linkBox.setChecked(next);
                if (usable) {
                    eq.setLinkBands(next);
                }
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48));
        llp.leftMargin = dp(a, 8);
        titleRow.addView(linkBox, llp);
        root.addView(titleRow);

        // ---- 降级提示 ----
        String warn = null;
        if (eq == null) {
            warn = "音效引擎不可用（初始化失败），本面板仅供查看";
        } else if (!eq.isAttached() || !eq.hasRoutingPermission()) {
            warn = "未授权全局音效（需 priv-app 部署），参数已保存、授权后自动生效";
        }
        if (warn != null) {
            TextView banner = new TextView(a);
            banner.setText(warn);
            banner.setTextColor(0xFFFF6B6B);
            banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(a, 4);
            blp.bottomMargin = dp(a, 4);
            root.addView(banner, blp);
        }

        // ---- 主体：左刻度 + 10 条竖滑条（每列等宽，整列可点）----
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.TOP);

        mid.addView(new ScaleView(a), new LinearLayout.LayoutParams(
                dp(a, 46), sliderLen));

        for (int i = 0; i < EqEngine.FREQS.length; i++) {
            mid.addView(buildColumn(a, i), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(mid);

        // ---- 预设行：预设按钮（点击弹深色列表）+ 保存/删除 ----
        rootLayout = root;
        root.addView(buildPresetBar(a), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void show() {
        super.show();
        // 主题里的 windowMinWidth 会覆盖构造函数里的 setLayout，这里在显示后再定一次。
        try {
            Window w = getWindow();
            if (w != null && panelActivity != null) {
                int sw = panelActivity.getResources().getDisplayMetrics().widthPixels;
                int target = Math.min(dp(panelActivity, 1120), sw - dp(panelActivity, 32));
                w.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 单列：竖滑条（旋转的 SeekBar）+ 实时数值 + 底部频率。 */
    private View buildColumn(final Activity a, final int idx) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // 整列都可点：SeekBar 旋转前高度即旋转后的触摸宽度。
        final int thick = dp(a, 48);

        FrameLayout frame = new FrameLayout(a);
        SeekBar sb = new SeekBar(a);
        sb.setMax(STEPS);
        float init = usable ? eq.getBand(idx) : 0f;
        int prog = Math.round((init + MAX_DB) * 2f);
        prog = Math.max(0, Math.min(STEPS, prog));
        sb.setProgress(prog);
        sb.setEnabled(usable);
        sb.setRotation(-90f);
        bars[idx] = sb;
        frame.addView(sb, new FrameLayout.LayoutParams(
                sliderLen, thick, Gravity.CENTER));
        col.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sliderLen));

        // 实时数值
        final TextView val = new TextView(a);
        val.setText(String.format(Locale.US, "%+.1f", init));
        val.setTextColor(TEXT);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        val.setGravity(Gravity.CENTER_HORIZONTAL);
        vals[idx] = val;
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 24));
        vlp.topMargin = dp(a, 6);
        col.addView(val, vlp);

        // 底部频率
        TextView hz = new TextView(a);
        hz.setText(fmtFreq(EqEngine.FREQS[idx]));
        hz.setTextColor(SUB);
        hz.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hz.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(hz, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 18)));

        // 点滑条区域任意位置：把触摸 Y 映射成进度（比细滑条好点得多）。
        View.OnTouchListener touch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent ev) {
                if (!usable || linking) {
                    return false;
                }
                int act = ev.getActionMasked();
                float t = 1f - Math.max(0f, Math.min(1f,
                        ev.getY() / Math.max(1, v.getHeight())));
                float db = t * STEPS / 2f - MAX_DB;
                if (act == android.view.MotionEvent.ACTION_DOWN
                        || act == android.view.MotionEvent.ACTION_MOVE) {
                    applyUserBand(idx, db);
                    return true;
                }
                if (act == android.view.MotionEvent.ACTION_UP
                        || act == android.view.MotionEvent.ACTION_CANCEL) {
                    applyUserBand(idx, db);
                    persistDirty();
                    return true;
                }
                return true;
            }
        };
        frame.setOnTouchListener(touch);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float db = progress / 2f - MAX_DB;
                val.setText(String.format(Locale.US, "%+.1f", db));
                if (fromUser) {
                    // 拖动中只改音效；联动附近频段由 applyUserBand 处理。
                    applyUserBand(idx, db);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistDirty();
            }
        });

        return col;
    }

    /**
     * 用户调某一段：先更新本段，再（开关打开时）按距离权重联动其他段。
     * 权重 = exp(-d²/2)，d 为相隔段数：相邻约 0.61，隔一段约 0.14，隔两段约 0.01。
     * 拖动过程中只实时改音效，抬手时由 persistDirty() 统一落盘。
     */
    private void applyUserBand(int idx, float rawDb) {
        if (linking || idx < 0 || idx >= bars.length) {
            return;
        }
        float db = clamp(rawDb);
        float delta = db - lastDb[idx];
        if (Math.abs(delta) < 0.001f) {
            return;
        }
        lastDb[idx] = db;
        setBarProgress(idx, db);
        dirty[idx] = true;
        if (eq != null) {
            eq.setBandLive(idx, db);
        }
        if (eq == null || !eq.linkBands()) {
            return;
        }
        linking = true;
        try {
            for (int j = 0; j < bars.length; j++) {
                if (j == idx) {
                    continue;
                }
                int d = Math.abs(j - idx);
                double weight = Math.exp(-(d * (double) d) / 2.0);
                if (weight < 0.02) {
                    continue;
                }
                float nv = clamp(lastDb[j] + delta * (float) weight);
                if (Math.abs(nv - lastDb[j]) < 0.001f) {
                    continue;
                }
                lastDb[j] = nv;
                setBarProgress(j, nv);
                dirty[j] = true;
                eq.setBandLive(j, nv);
            }
        } finally {
            linking = false;
        }
    }

    private void setBarProgress(int i, float db) {
        try {
            bars[i].setProgress(Math.round((db + MAX_DB) * 2f));
        } catch (Throwable ignored) {
        }
    }

    private void persistDirty() {
        if (eq == null) {
            return;
        }
        for (int i = 0; i < dirty.length; i++) {
            if (dirty[i]) {
                // 落盘按 0.5dB 量化（与滑条步长一致），避免联动累计出小数尾巴。
                eq.setBand(i, Math.round(lastDb[i] * 2f) / 2f);
                dirty[i] = false;
            }
        }
    }

    private static float clamp(float db) {
        return Math.max(-MAX_DB, Math.min(MAX_DB, db));
    }

    /**
     * 底部预设行：大号"预设"按钮（点击弹出深色预设列表）+ 「保存」「删除」按钮。
     * 不再使用 Spinner：电视上下拉弹层会错位，且默认弹层与深色面板文字同色，
     * 视觉上像"里面没有预设"。
     */
    private View buildPresetBar(final Activity a) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(a, 10);
        bar.setPadding(0, pad, 0, pad);

        presetBtn = makeChip(a, presetLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPresetPicker(a);
            }
        });
        presetBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        presetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        presetBtn.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        presetBtn.setMaxLines(1);
        presetBtn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(presetBtn, new LinearLayout.LayoutParams(
                0, dp(a, 52), 1f));

        bar.addView(makeChip(a, "保存当前", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSavePresetDialog(a);
            }
        }));
        bar.addView(makeChip(a, "删除预设", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteActivePreset(a);
            }
        }));

        return bar;
    }

    private String presetLabel() {
        return activePreset == null || activePreset.isEmpty()
                ? "预设：未选择 ▾" : "预设：" + activePreset + " ▾";
    }

    private void refreshPresetLabel() {
        try {
            if (presetBtn != null) {
                presetBtn.setText(presetLabel());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 深色预设列表：每行 56dp，选中即应用（替代会错位的 Spinner）。 */
    private void showPresetPicker(final Activity a) {
        if (eq == null) {
            Toast.makeText(a, "音效引擎不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> names = eq.presetNames();
        if (names == null || names.isEmpty()) {
            Toast.makeText(a, "暂无预设", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog d = new Dialog(a);
        try {
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(a, BG, 18));
        int pad = dp(a, 16);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(a);
        title.setText("选择预设");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setPadding(dp(a, 4), 0, 0, dp(a, 2));
        box.addView(title);

        TextView hint = new TextView(a);
        hint.setText("点击应用 · 长按预设可删除");
        hint.setTextColor(SUB);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(dp(a, 4), 0, 0, dp(a, 10));
        box.addView(hint);

        ScrollView sv = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int sh = a.getResources().getDisplayMetrics().heightPixels;
        int listH = Math.min(dp(a, 400), Math.max(dp(a, 160), sh - dp(a, 260)));
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final boolean selected = name.equals(activePreset);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(a, 60));
            row.setPadding(dp(a, 16), dp(a, 6), dp(a, 16), dp(a, 6));
            row.setBackground(round(a, selected ? 0xFF2F6F55 : BG_CARD, 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 60));
            rlp.topMargin = dp(a, 4);
            rlp.bottomMargin = dp(a, 4);

            TextView label = new TextView(a);
            label.setText(name);
            label.setTextColor(selected ? 0xFF0E1620 : TEXT);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tag = new TextView(a);
            tag.setText(EqEngine.isBuiltinPreset(name) ? "出厂" : "自定义");
            tag.setTextColor(selected ? 0xFF0E1620 : SUB);
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.addView(tag);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activePreset = name;
                    applyPresetToUi(a, name);
                    refreshPresetLabel();
                    try {
                        d.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDeletePreset(a, name, d);
                    return true;
                }
            });
            list.addView(row, rlp);
        }

        TextView cancel = makeChip(a, "关闭", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    d.dismiss();
                } catch (Throwable ignored) {
                }
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52));
        clp.topMargin = dp(a, 10);
        box.addView(cancel, clp);

        d.setContentView(box);
        try {
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                int sw = a.getResources().getDisplayMetrics().widthPixels;
                w.setLayout(Math.min(dp(a, 560), sw - dp(a, 64)),
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
        d.show();
    }

    /** 长按预设：确认后删除（出厂预设不可删），再重开列表刷新。 */
    private void confirmDeletePreset(final Activity a, final String name,
                                     final Dialog picker) {
        if (EqEngine.isBuiltinPreset(name)) {
            Toast.makeText(a, "出厂预设不可删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("删除预设")
                .setMessage("删除「" + name + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        if (eq == null) {
                            return;
                        }
                        if (!eq.deletePreset(name)) {
                            Toast.makeText(a, "删除失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name.equals(activePreset)) {
                            activePreset = null;
                            refreshPresetLabel();
                        }
                        Toast.makeText(a, "已删除「" + name + "」",
                                Toast.LENGTH_SHORT).show();
                        try {
                            picker.dismiss();
                        } catch (Throwable ignored) {
                        }
                        final Activity act = a;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        showPresetPicker(act);
                                    }
                                }, 180);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除当前选中的预设（出厂预设拒绝并提示）。 */
    private void deleteActivePreset(final Activity a) {
        if (eq == null) {
            return;
        }
        if (activePreset == null || activePreset.isEmpty()) {
            Toast.makeText(a, "请先选择要删除的预设", Toast.LENGTH_SHORT).show();
            return;
        }
        final String name = activePreset;
        if (EqEngine.isBuiltinPreset(name)) {
            Toast.makeText(a, "出厂预设不可删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("删除预设")
                .setMessage("删除「" + name + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (eq.deletePreset(name)) {
                            activePreset = null;
                            refreshPresetLabel();
                            Toast.makeText(a, "已删除", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用预设：引擎写参 + 刷新全部滑条/数值 + 同步勾选启用。 */
    private void applyPresetToUi(Activity a, String name) {
        if (!usable) {
            return;
        }
        if (!eq.applyPreset(name)) {
            Toast.makeText(a, "预设不存在：" + name, Toast.LENGTH_SHORT).show();
            return;
        }
        for (int i = 0; i < bars.length; i++) {
            float db = eq.getBand(i);
            int prog = Math.max(0, Math.min(EqPanel.STEPS, Math.round((db + MAX_DB) * 2f)));
            bars[i].setProgress(prog); // 触发 onProgressChanged(fromUser=false) 刷新数值
            vals[i].setText(String.format(Locale.US, "%+.1f", db));
            lastDb[i] = db;
            dirty[i] = false;
        }
        onBox.setChecked(true); // 应用预设自动启用（监听器会写 eq_enabled）
        Toast.makeText(a, "已应用预设「" + name + "」", Toast.LENGTH_SHORT).show();
    }

    /** "另存为预设"：自绘深色输入弹窗（系统 AlertDialog 深色主题下输入字不可见）。 */
    private void showSavePresetDialog(final Activity a) {
        if (eq == null) {
            Toast.makeText(a, "音效引擎不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog d = new Dialog(a);
        try {
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(a, BG, 18));
        int pad = dp(a, 20);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(a);
        title.setText("保存当前设置");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        box.addView(title);

        final EditText input = new EditText(a);
        input.setHint("预设名称");
        input.setHintTextColor(SUB);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(round(a, 0xFF0E1620, 10));
        input.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        if (activePreset != null && !EqEngine.isBuiltinPreset(activePreset)) {
            input.setText(activePreset);
            input.setSelection(activePreset.length());
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 56));
        ilp.topMargin = dp(a, 14);
        box.addView(input, ilp);

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(a, 16);

        TextView cancel = makeChip(a, "取消", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    d.dismiss();
                } catch (Throwable ignored) {
                }
            }
        });
        TextView save = makeChip(a, "保存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(a, "名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                eq.savePreset(name, eq.currentGains());
                activePreset = name;
                refreshPresetLabel();
                Toast.makeText(a, "已保存预设「" + name + "」", Toast.LENGTH_SHORT).show();
                try {
                    d.dismiss();
                } catch (Throwable ignored) {
                }
            }
        });
        btns.addView(cancel);
        btns.addView(save);
        box.addView(btns, blp);

        d.setContentView(box);
        try {
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                int sw = a.getResources().getDisplayMetrics().widthPixels;
                w.setLayout(Math.min(dp(a, 620), sw - dp(a, 64)),
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
        d.show();
    }

    /** 预设/操作按钮：52dp 高、大内边距，遥控与触摸都容易点中。 */
    private TextView makeChip(Context c, String text, View.OnClickListener click) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setBackground(round(c, BG_CARD, 10));
        int padH = dp(c, 20);
        tv.setPadding(padH, 0, padH, 0);
        tv.setMinWidth(dp(c, 96));
        tv.setOnClickListener(click);
        if (!usable) {
            tv.setEnabled(false);
            tv.setAlpha(0.5f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 52));
        lp.leftMargin = dp(c, 6);
        lp.rightMargin = dp(c, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static GradientDrawable round(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    private static String fmtFreq(float hz) {
        if (hz >= 1000) {
            return String.format(Locale.US, "%dkHz", Math.round(hz / 1000));
        }
        return String.format(Locale.US, "%dHz", Math.round(hz));
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 启用/联动开关的简易替代：48dp 高、整块可点的文字按钮。 */
    private static class CheckBoxCompat extends TextView {
        private final String label;
        private boolean checked;

        CheckBoxCompat(Context c, String label) {
            super(c);
            this.label = label == null ? "" : label;
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            setGravity(Gravity.CENTER);
            setPadding(dp(c, 16), 0, dp(c, 16), 0);
            setBackground(round(c, BG_CARD, 10));
            setChecked(false);
        }

        void setChecked(boolean on) {
            checked = on;
            setText(label + (on ? "：开" : "：关"));
            setTextColor(on ? ACCENT : SUB);
        }

        boolean isChecked() {
            return checked;
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.5f);
        }
    }

    /**
     * 最左侧 dB 刻度：主刻度（每 10 dB）带数值，副刻度每 5 dB。
     * 刻度线在右缘（贴近滑条），数值左对齐于刻度线。
     */
    private static class ScaleView extends View {

        private final Paint labelPaint;
        private final Paint tickPaint;
        private final float density;

        ScaleView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(0xFF8FA3B8);
            labelPaint.setTextSize(10 * c.getResources().getDisplayMetrics().scaledDensity);
            tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            tickPaint.setColor(0xFF8FA3B8);
            tickPaint.setStrokeWidth(Math.max(1f, density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float inset = 7 * density; // 首尾标签防裁边
            Paint.FontMetrics fm = labelPaint.getFontMetrics();

            for (int db = MAX_DB; db >= -MAX_DB; db -= 5) {
                float y = inset + (MAX_DB - db) * (h - 2 * inset) / (2f * MAX_DB);
                boolean major = (db % 10 == 0);
                float tickLen = (major ? 9f : 5f) * density;
                canvas.drawLine(w - tickLen, y, w, y, tickPaint);
                if (major) {
                    String s = db > 0 ? "+" + db : String.valueOf(db);
                    float tw = labelPaint.measureText(s);
                    float baseline = y - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(s, w - tickLen - 4 * density - tw, baseline, labelPaint);
                }
            }
        }
    }
}
