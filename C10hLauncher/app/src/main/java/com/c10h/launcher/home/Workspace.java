package com.c10h.launcher;

import java.util.List;

/**
 * 工作区控制器（M9）：把 MainActivity 与 PagedWorkspaceView 解耦，
 * 对外只暴露页容量/翻页/命中查询；旧的「GridView 切数据窗口」实现已废弃。
 */
public class Workspace {

    public interface Callback {
        void onPageChanged(int page, int pageCount);

        void onPageProgress(float pageFloat);

        void onEmptyLongPress();

        void onSwipeUp();
    }

    private PagedWorkspaceView view;

    public void attach(PagedWorkspaceView v, final Callback cb) {
        this.view = v;
        v.setCallback(new PagedWorkspaceView.Callback() {
            @Override
            public void onPageChanged(int page, int pageCount) {
                if (cb != null) {
                    cb.onPageChanged(page, pageCount);
                }
            }

            @Override
            public void onPageProgress(float pageFloat) {
                if (cb != null) {
                    cb.onPageProgress(pageFloat);
                }
            }

            @Override
            public void onEmptyLongPress() {
                if (cb != null) {
                    cb.onEmptyLongPress();
                }
            }

            @Override
            public void onSwipeUp() {
                if (cb != null) {
                    cb.onSwipeUp();
                }
            }
        });
    }

    public PagedWorkspaceView view() {
        return view;
    }

    public void setBinder(PagedWorkspaceView.Binder b) {
        if (view != null) {
            view.setBinder(b);
        }
    }

    public void setGrid(int cols, int rows) {
        if (view != null) {
            view.setGrid(cols, rows);
        }
    }

    public void setData(List<Item> items) {
        if (view != null) {
            view.setItems(items);
        }
    }

    public int pageSize() {
        return view == null ? 1 : Math.max(1, view.pageSize());
    }

    public int currentPage() {
        return view == null ? 0 : view.currentPage();
    }

    public int pageCount() {
        return view == null ? 1 : view.pageCount();
    }

    public void nextPage() {
        if (view != null) {
            view.nextPage();
        }
    }

    public void prevPage() {
        if (view != null) {
            view.prevPage();
        }
    }

    public void setPage(int page, boolean animate) {
        if (view != null) {
            view.setPage(page, animate);
        }
    }

    public int[] hit(float x, float y) {
        return view == null ? new int[]{-1, -1} : view.hit(x, y);
    }

    public int indexOf(Item item) {
        return view == null ? -1 : view.indexOf(item);
    }
}
