package com.c10h.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AOSP BubbleTextView 等价物（M9）：图标在上、标签两行固定在下、右上角标。
 * 支持按压缩放、长按抬升 + 触感、D-pad 焦点态；标签永远在图标下方。
 */
public class IconView extends LinearLayout {

    public interface Listener {
        void onIconClick(IconView v, Item item);

        void onIconLongClick(IconView v, Item item);

        /** 长按后未移动即松手：弹 AOSP 菜单。 */
        default void onIconMenu(IconView v, Item item) {
        }
    }

    private final ImageView icon;
    private final FolderIconView folderIcon;
    private final TextView label;
    private final TextView badge;
    private final int iconPx;

    private Item item;
    private Listener listener;
    private boolean lifted;
    private float downX;
    private float downY;
    private final int slop;

    public IconView(Context c, int iconPx) {
        this(c, iconPx, 12f);
    }

    public IconView(Context c, int iconPx, float textSp) {
        super(c);
        this.iconPx = iconPx;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);
        int padH = Ui.dp(c, 2);
        int padV = Ui.dp(c, 4);
        setPadding(padH, padV, padH, padV);
        setFocusable(true);
        setSoundEffectsEnabled(false);

        FrameLayout wrap = new FrameLayout(c);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setAdjustViewBounds(false);
        wrap.addView(icon, new FrameLayout.LayoutParams(iconPx, iconPx));

        folderIcon = new FolderIconView(c);
        folderIcon.setVisibility(GONE);
        wrap.addView(folderIcon, new FrameLayout.LayoutParams(iconPx, iconPx));

        badge = new TextView(c);
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setMinWidth(Ui.dp(c, 18));
        badge.setMinHeight(Ui.dp(c, 18));
        badge.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(0xFFF44336);
        bg.setCornerRadius(Ui.dp(c, 10));
        bg.setStroke(Ui.dp(c, 1.5f), 0xFFFFFFFF);
        badge.setBackground(bg);
        badge.setVisibility(GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(c, 18),
                Gravity.TOP | Gravity.END);
        blp.topMargin = -Ui.dp(c, 3);
        blp.rightMargin = -Ui.dp(c, 2);
        wrap.addView(badge, blp);

        addView(wrap, new LayoutParams(iconPx, iconPx));

        label = new TextView(c);
        label.setTextColor(0xFF1F1F1F);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setMinLines(1);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        label.setLineSpacing(0f, 1.05f);
        LayoutParams llp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(c, 4);
        addView(label, llp);

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onIconClick(IconView.this, item);
                }
            }
        });
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener == null) {
                    return false;
                }
                lifted = true;
                Ui.lift(IconView.this, true, Ui.dp(IconView.this, 6));
                Ui.haptic(IconView.this);
                listener.onIconLongClick(IconView.this, item);
                return true;
            }
        });
    }

    private boolean pressed;
    private boolean moved;
    private float downRawX;
    private float downRawY;

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 底部固定栏用：单行小标签，节省高度。 */
    public void setLabelSingleLine() {
        try {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            label.setSingleLine(true);
            label.setMinLines(1);
            label.setMaxLines(1);
        } catch (Throwable ignored) {
        }
    }

    /** 固定栏无文字（AOSP Hotseat 形态）。 */
    public void setLabelVisible(boolean visible) {
        try {
            label.setVisibility(visible ? VISIBLE : GONE);
        } catch (Throwable ignored) {
        }
    }

    /** 标签颜色（浅色面板用深色字）。 */
    public void setLabelColor(int color) {
        try {
            label.setTextColor(color);
        } catch (Throwable ignored) {
        }
    }

    public void setItem(Item it, String labelText, int badgeCount) {
        this.item = it;
        setTag(it);
        if (it != null && it.isFolder()) {
            showFolder(true);
            label.setText(labelText == null ? it.name : labelText);
            badge.setVisibility(GONE);
        } else {
            showFolder(false);
            label.setText(labelText == null ? "" : labelText);
            if (badgeCount > 0) {
                badge.setText(badgeCount > 99 ? "99+" : String.valueOf(badgeCount));
                badge.setVisibility(VISIBLE);
            } else {
                badge.setVisibility(GONE);
            }
        }
    }

    public Item getItem() {
        return item;
    }

    public void setIcon(Drawable d) {
        try {
            icon.setImageDrawable(d);
        } catch (Throwable ignored) {
        }
    }

    public void setFolderIcons(Drawable[] minis) {
        try {
            folderIcon.setMembers(minis);
        } catch (Throwable ignored) {
        }
    }

    private void showFolder(boolean folder) {
        icon.setVisibility(folder ? GONE : VISIBLE);
        folderIcon.setVisibility(folder ? VISIBLE : GONE);
    }

    public void setBadgeCount(int n) {
        if (n > 0) {
            badge.setText(n > 99 ? "99+" : String.valueOf(n));
            badge.setVisibility(VISIBLE);
        } else {
            badge.setVisibility(GONE);
        }
    }

    public void clearLift() {
        if (lifted) {
            lifted = false;
            Ui.lift(this, false, 0);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    Ui.press(this, true, 0.92f);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - downX) > slop
                            || Math.abs(e.getY() - downY) > slop) {
                        Ui.press(this, false, 0.92f);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Ui.press(this, false, 0.92f);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        try {
            if (gain) {
                setBackground(Ui.ripple(getContext(), 0x55FFFFFF,
                        Ui.dp(getContext(), 12),
                        Ui.roundStroke(0x33FFFFFF, Ui.dp(getContext(), 12),
                                Ui.dp(getContext(), 1), 0x88FFFFFF)));
                Ui.press(this, true, 1.04f);
            } else {
                setBackground(null);
                Ui.press(this, false, 0.92f);
            }
        } catch (Throwable ignored) {
        }
    }
}
