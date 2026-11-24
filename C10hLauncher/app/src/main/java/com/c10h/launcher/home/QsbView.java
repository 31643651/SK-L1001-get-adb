package com.c10h.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 工作区顶部搜索栏（AOSP Pie SearchBar 等价）：深色圆角条 + 放大镜 + 品牌字样 + 麦克风位。
 * 点击进应用抽屉搜索，长按唤出快捷面板。
 */
public class QsbView extends LinearLayout {

    public interface Listener {
        void onSearchClick();

        void onSearchLongClick();
    }

    public QsbView(Context c, Listener l) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.dp(c, 30), 0, Ui.dp(c, 22), 0);
        setBackground(Ui.ripple(c, 0x33FFFFFF, Ui.dp(c, 6),
                Ui.roundStroke(0xE61B1B1B, Ui.dp(c, 6),
                        Ui.dp(c, 1), 0x66FFFFFF)));

        ImageView lens = new ImageView(c);
        lens.setImageDrawable(Ui.searchGlyph(c, 0xFFD5D5D5));
        addView(lens, new LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));

        TextView brand = new TextView(c);
        brand.setText("Google");
        brand.setTextColor(0xFFD5D5D5);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        brand.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        brand.setPadding(Ui.dp(c, 8), 0, 0, 0);
        brand.setIncludeFontPadding(false);
        addView(brand, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        ImageView mic = new ImageView(c);
        mic.setImageDrawable(Ui.micGlyph(c, 0xFFBDBDBD));
        addView(mic, new LayoutParams(Ui.dp(c, 20), Ui.dp(c, 24)));

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (l != null) {
                    l.onSearchClick();
                }
            }
        });
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (l != null) {
                    l.onSearchLongClick();
                }
                return true;
            }
        });
        setFocusable(true);
    }
}
