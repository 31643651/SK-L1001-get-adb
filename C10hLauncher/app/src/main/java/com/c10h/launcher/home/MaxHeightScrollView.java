package com.c10h.launcher;

import android.content.Context;
import android.widget.ScrollView;

/**
 * 限高 ScrollView（M9）：小组件区最多占屏幕高度 fraction，避免把桌面挤成一条。
 * 子内容超出时在区内滚动。
 */
public class MaxHeightScrollView extends ScrollView {

    private float fraction = 0.28f;

    public MaxHeightScrollView(Context c) {
        super(c);
        setFillViewport(false);
        setVerticalScrollBarEnabled(false);
    }

    public void setMaxHeightFraction(float f) {
        fraction = Math.max(0.1f, Math.min(0.6f, f));
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int screen = getResources().getDisplayMetrics().heightPixels;
        int cap = (int) (screen * fraction);
        int mode = MeasureSpec.getMode(heightSpec);
        int size = MeasureSpec.getSize(heightSpec);
        if (mode != MeasureSpec.UNSPECIFIED) {
            cap = Math.min(cap, size);
        }
        super.onMeasure(widthSpec,
                MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
}
