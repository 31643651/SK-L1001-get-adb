package com.c10h.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * AOSP CellLayout 等价物（M9）：按列/行均分单元格，图标居中。
 * 子视图顺序 = 页内顺序；空格不建 View（拖动落点用 indexAt 计算）。
 */
public class CellLayout extends ViewGroup {

    private int cols = 4;
    private int rows = 4;
    private int hintX = -1;
    private int hintY = -1;

    public CellLayout(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
    }

    /** 拖动落点占位高亮（AOSP ReorderPreview 等价）。 */
    public void setDropHint(int x, int y) {
        if (hintX == x && hintY == y) {
            return;
        }
        hintX = x;
        hintY = y;
        invalidate();
    }

    public void clearDropHint() {
        if (hintX >= 0 || hintY >= 0) {
            hintX = -1;
            hintY = -1;
            invalidate();
        }
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        try {
            if (hintX < 0 || hintY < 0) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            int cw = Math.max(1, w / Math.max(1, cols));
            int ch = Math.max(1, h / Math.max(1, rows));
            float inset = 6f * getResources().getDisplayMetrics().density;
            android.graphics.RectF r = new android.graphics.RectF(
                    hintX * cw + inset, hintY * ch + inset,
                    (hintX + 1) * cw - inset, (hintY + 1) * ch - inset);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x33FFFFFF);
            float rad = 14f * getResources().getDisplayMetrics().density;
            canvas.drawRoundRect(r, rad, rad, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
            p.setColor(0x88FFFFFF);
            canvas.drawRoundRect(r, rad, rad, p);
        } catch (Throwable ignored) {
        }
    }

    public void setGrid(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        requestLayout();
    }

    public int columns() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = View.MeasureSpec.getSize(widthSpec);
        int h = View.MeasureSpec.getSize(heightSpec);
        int cw = Math.max(1, w / Math.max(1, cols));
        int ch = Math.max(1, h / Math.max(1, rows));
        int count = Math.min(getChildCount(), cols * rows);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            child.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int cw = Math.max(1, w / Math.max(1, cols));
        int ch = Math.max(1, h / Math.max(1, rows));
        int count = Math.min(getChildCount(), cols * rows);
        for (int i = 0; i < count; i++) {
            int col = i % cols;
            int row = i / cols;
            Object tag = getChildAt(i).getTag();
            if (tag instanceof int[]) {
                int[] xy = (int[]) tag;
                col = Math.max(0, Math.min(cols - 1, xy[0]));
                row = Math.max(0, Math.min(rows - 1, xy[1]));
            }
            int x = col * cw;
            int y = row * ch;
            getChildAt(i).layout(x, y, x + cw, y + ch);
        }
    }

    /** 命中页内下标；空白返回 -1。 */
    public int indexAt(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
            return -1;
        }
        int cw = Math.max(1, w / Math.max(1, cols));
        int ch = Math.max(1, h / Math.max(1, rows));
        int col = Math.min(cols - 1, (int) (x / cw));
        int row = Math.min(rows - 1, (int) (y / ch));
        // 空格也要返回单元格下标：拖动落点需要“最近空格”，不能把空格当成无效。
        return row * cols + col;
    }
}
