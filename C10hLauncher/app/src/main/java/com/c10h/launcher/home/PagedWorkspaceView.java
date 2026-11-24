package com.c10h.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AOSP PagedView 等价物（M9）：页面横向排布，Scroller 吸附，速度判定翻页，
 * 边缘阻尼回弹；页内空白长按进入编辑态；工作区上滑唤出应用抽屉。
 * 数据/尺寸变化只在下一帧重建，绝不在布局或触摸回调内改数据。
 */
public class PagedWorkspaceView extends ViewGroup {

    public interface Callback {
        void onPageChanged(int page, int pageCount);

        /** 连续滚动进度（浮点页号），用于页点动画。 */
        void onPageProgress(float pageFloat);

        void onEmptyLongPress();

        void onSwipeUp();
    }

    public interface Binder {
        View bind(Item item);
    }

    private final List<Item> items = new ArrayList<Item>();
    private final List<CellLayout> pages = new ArrayList<CellLayout>();
    private Callback cb;
    private Binder binder;

    private int cols = 4;
    private int rows = 4;
    private int pageSize = 16;
    private int page;
    private int pageCount = 1;

    private final Scroller scroller;
    private VelocityTracker vt;
    private final int slop;
    private final int minFling;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private boolean settling;
    private boolean rebuildPosted;
    private int builtW;
    private int builtH;
    private int hintPage = -2;
    private int hintIndex = -1;

    private final Runnable rebuildTask = new Runnable() {
        @Override
        public void run() {
            rebuildPosted = false;
            rebuildNow();
        }
    };

    public PagedWorkspaceView(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        scroller = new Scroller(c, Ui.DECEL);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 2;
    }

    public void setCallback(Callback callback) {
        this.cb = callback;
    }

    public void setBinder(Binder b) {
        this.binder = b;
    }

    public void setGrid(int c, int r) {
        c = Math.max(1, c);
        r = Math.max(1, r);
        int ps = c * r;
        if (c != cols || r != rows || ps != pageSize) {
            cols = c;
            rows = r;
            pageSize = ps;
            requestRebuild();
        }
    }

    public int columns() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public int pageSize() {
        return pageSize;
    }

    public void setItems(List<Item> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        requestRebuild();
    }

    public int itemCount() {
        return items.size();
    }

    /** 角标广播后只更新内存角标，不重建页面。 */
    public void setBadges(Map<String, Integer> badges) {
        try {
            for (CellLayout page : pages) {
                for (int i = 0; i < page.getChildCount(); i++) {
                    View v = page.getChildAt(i);
                    if (v instanceof IconView) {
                        IconView iv = (IconView) v;
                        Item it = iv.getItem();
                        int n = (it == null || it.pkg == null || badges == null)
                                ? 0 : (badges.containsKey(it.pkg) ? badges.get(it.pkg) : 0);
                        iv.setBadgeCount(n);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public int pageCount() {
        return pageCount;
    }

    public int currentPage() {
        return page;
    }

    public CellLayout pageAt(int p) {
        return (p < 0 || p >= pages.size()) ? null : pages.get(p);
    }

    /** 全量下标 → 页（数据侧），拖动/菜单可用。 */
    public int indexOf(Item it) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == it) {
                return i;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Item x = items.get(i);
            if (!x.isFolder() && it != null && !it.isFolder()
                    && x.key().equals(it.key())) {
                return i;
            }
        }
        return -1;
    }

    /** 视图坐标命中：返回 {page, 页内 index}，未命中页内图标时 index=-1。 */
    public int[] hit(float x, float y) {
        int w = getWidth();
        if (w <= 0) {
            return new int[]{-1, -1};
        }
        int p = (int) Math.floor((getScrollX() + x) / (float) w);
        p = Math.max(0, Math.min(pageCount - 1, p));
        CellLayout cl = pageAt(p);
        int idx = cl == null ? -1 : cl.indexAt((getScrollX() + x) - p * w, y);
        return new int[]{p, idx};
    }

    /** 拖动落点提示：page 页内第 index 格高亮；index<0 清除。 */
    public void setDropHint(int page, int index) {
        try {
            if (hintPage == page && hintIndex == index) {
                return;
            }
            if (hintPage >= 0) {
                CellLayout old = pageAt(hintPage);
                if (old != null) {
                    old.clearDropHint();
                }
            }
            if (index >= 0) {
                CellLayout cl = pageAt(page);
                if (cl != null) {
                    int cols = Math.max(1, cl.columns());
                    cl.setDropHint(index % cols, index / cols);
                }
            }
            hintPage = page;
            hintIndex = index;
        } catch (Throwable ignored) {
        }
    }

    public void clearDropHintAll() {
        try {
            if (hintPage == -2 && hintIndex < 0) {
                return;
            }
            for (int i = 0; i < pageCount; i++) {
                CellLayout cl = pageAt(i);
                if (cl != null) {
                    cl.clearDropHint();
                }
            }
            hintPage = -2;
            hintIndex = -1;
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ build

    private void requestRebuild() {
        if (rebuildPosted) {
            return;
        }
        rebuildPosted = true;
        handler.post(rebuildTask);
    }

    private void rebuildNow() {
        try {
            int w = getWidth();
            if (w <= 0) {
                return;
            }
            int newCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
            if (w == builtW && getHeight() == builtH && newCount == pageCount) {
                // 尺寸未变时仍允许数据替换：重建内容，保留页
            }
            builtW = w;
            builtH = getHeight();
            removeAllViews();
            pages.clear();
            for (int p = 0; p < newCount; p++) {
                final CellLayout cl = new CellLayout(getContext());
                cl.setGrid(cols, rows);
                cl.setSoundEffectsEnabled(false);
                cl.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (cb != null) {
                            Ui.haptic(cl);
                            cb.onEmptyLongPress();
                        }
                        return true;
                    }
                });
                if (binder != null) {
                    int from = p * pageSize;
                    int to = Math.min(items.size(), from + pageSize);
                    for (int i = from; i < to; i++) {
                        try {
                            Item item = items.get(i);
                            View icon = binder.bind(item);
                            if (icon != null) {
                                if (item.cellX >= 0 && item.cellY >= 0) {
                                    icon.setTag(new int[]{item.cellX, item.cellY});
                                }
                                cl.addView(icon);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                addView(cl);
                pages.add(cl);
            }
            pageCount = newCount;
            page = Math.max(0, Math.min(page, pageCount - 1));
            requestLayout();
            post(new Runnable() {
                @Override
                public void run() {
                    scrollTo(page * getWidth(), 0);
                    notifyProgress();
                    notifyPage();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            int x = i * w;
            getChildAt(i).layout(x, 0, x + w, h);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            requestRebuild();
        }
    }

    // ------------------------------------------------------------ paging

    public void setPage(int p, boolean animate) {
        int target = Math.max(0, Math.min(p, pageCount - 1));
        if (animate) {
            snapToPage(target);
            return;
        }
        page = target;
        try {
            scrollTo(target * getWidth(), 0);
        } catch (Throwable ignored) {
        }
        notifyProgress();
        notifyPage();
    }

    public void nextPage() {
        if (page < pageCount - 1) {
            snapToPage(page + 1);
        }
    }

    public void prevPage() {
        if (page > 0) {
            snapToPage(page - 1);
        }
    }

    private void snapToPage(int target) {
        int w = getWidth();
        if (w <= 0) {
            page = target;
            return;
        }
        target = Math.max(0, Math.min(target, pageCount - 1));
        int sx = getScrollX();
        int dx = target * w - sx;
        if (dx == 0) {
            page = target;
            notifyProgress();
            notifyPage();
            return;
        }
        int duration = Math.min(460, Math.max(180, Math.abs(dx) * 260 / w));
        settling = true;
        page = target;
        scroller.startScroll(sx, 0, dx, 0, duration);
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            notifyProgress();
            postInvalidateOnAnimation();
        } else if (settling) {
            settling = false;
            scrollTo(page * getWidth(), 0);
            notifyProgress();
            notifyPage();
        }
    }

    private void notifyPage() {
        if (cb != null) {
            cb.onPageChanged(page, pageCount);
        }
    }

    private void notifyProgress() {
        if (cb == null) {
            return;
        }
        int w = getWidth();
        float f = w <= 0 ? page : getScrollX() / (float) w;
        f = Math.max(0f, Math.min(pageCount - 1f, f));
        cb.onPageProgress(f);
    }

    // ------------------------------------------------------------ touch

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        try {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    lastX = downX;
                    dragging = false;
                    if (!scroller.isFinished()) {
                        scroller.abortAnimation();
                        settling = false;
                    }
                    obtainVt();
                    vt.addMovement(ev);
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    if (vt != null) {
                        vt.addMovement(ev);
                    }
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!dragging && Math.abs(dx) > slop
                            && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                        dragging = true;
                        lastX = ev.getX();
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP: {
                    if (vt != null) {
                        vt.addMovement(ev);
                    }
                    if (!dragging) {
                        float dy = downY - ev.getY();
                        float dx = Math.abs(ev.getX() - downX);
                        if (dy > Ui.dp(this, 56) && dy > dx * 1.2f && cb != null) {
                            cb.onSwipeUp();
                        }
                    }
                    releaseVt();
                    return false;
                }
                case MotionEvent.ACTION_CANCEL:
                    releaseVt();
                    return false;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        try {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    lastX = downX;
                    obtainVt();
                    vt.addMovement(ev);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (vt != null) {
                        vt.addMovement(ev);
                    }
                    float x = ev.getX();
                    float dx = lastX - x;
                    lastX = x;
                    dragBy(dx);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (vt != null) {
                        vt.addMovement(ev);
                        vt.computeCurrentVelocity(1000);
                    }
                    float vx = vt == null ? 0 : vt.getXVelocity();
                    int w = Math.max(1, getWidth());
                    int nearest = (int) Math.floor((getScrollX() + w / 2f) / w);
                    int target = nearest;
                    if (vx < -minFling) {
                        target = nearest + 1;
                    } else if (vx > minFling) {
                        target = nearest - 1;
                    }
                    releaseVt();
                    dragging = false;
                    snapToPage(target);
                    return true;
                }
                default:
                    return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private void dragBy(float dx) {
        int max = Math.max(0, (pageCount - 1) * getWidth());
        int nx = getScrollX() + (int) dx;
        if (nx < 0) {
            nx = (int) (nx / 3f);
        } else if (nx > max) {
            nx = max + (int) ((nx - max) / 3f);
        }
        scrollTo(nx, 0);
        notifyProgress();
    }

    private void obtainVt() {
        if (vt == null) {
            vt = VelocityTracker.obtain();
        }
    }

    private void releaseVt() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }
}
