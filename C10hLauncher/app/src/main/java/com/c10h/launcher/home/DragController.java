package com.c10h.launcher;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.View;

/**
 * 全局拖动控制器（M9，AOSP DragController 等价）：
 * 工作区/固定栏/应用抽屉共用一个 root 拖放监听；落点由 Host 决定数据写入。
 */
public class DragController implements View.OnDragListener {

    public interface Host {
        void onDropWorkspace(Item item, String from, int page, int cell);

        void onDropFixed(Item item, String from, int slot);

        void onDragEndedNoDrop(Item item, View source);
    }

    private static final long EDGE_HOVER_MS = 600;

    private final Host host;
    private final Workspace workspace;
    private final FixedBar fixedBar;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Item dragging;
    private String from = "workspace";
    private View source;
    private boolean dropped;
    private boolean moved;
    private boolean haveFirst;
    private float firstX;
    private float firstY;
    private int hoverEdge;
    private Runnable edgeFlip;

    public DragController(Host host, Workspace workspace, FixedBar fixedBar, View root) {
        this.host = host;
        this.workspace = workspace;
        this.fixedBar = fixedBar;
        this.root = root;
        root.setOnDragListener(this);
    }

    public void start(IconView view, Item item, String sourceType) {
        try {
            dragging = item;
            from = sourceType == null ? "workspace" : sourceType;
            source = view;
            dropped = false;
            moved = false;
            haveFirst = false;
            android.content.ClipData data = android.content.ClipData.newPlainText(
                    "c10h-item", item == null ? "?" : item.key());
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(view);
            boolean started;
            if (Build.VERSION.SDK_INT >= 24) {
                started = view.startDragAndDrop(data, shadow, null, 0);
            } else {
                started = view.startDrag(data, shadow, null, 0);
            }
            if (!started) {
                // 某些 ROM 拒绝系统拖放：直接弹菜单，避免长按无反应。
                dragging = null;
                source = null;
                if (host != null) {
                    host.onDragEndedNoDrop(item, view);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        try {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    handleLocation(event.getX(), event.getY());
                    return true;
                case DragEvent.ACTION_DROP:
                    cancelEdge();
                    fixedBar.setHighlight(-1);
                    clearWorkspaceHint();
                    if (!moved) {
                        // 长按后没有移动：按 AOSP 行为弹菜单，而不是原地“放下”。
                        dropped = false;
                        return true;
                    }
                    handleDrop(event.getX(), event.getY());
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    cancelEdge();
                    fixedBar.setHighlight(-1);
                    clearWorkspaceHint();
                    View src = source;
                    Item it = dragging;
                    boolean ok = dropped;
                    dragging = null;
                    source = null;
                    if (!ok && it != null && host != null) {
                        host.onDragEndedNoDrop(it, src);
                    }
                    return true;
                default:
                    return true;
            }
        } catch (Throwable t) {
            cancelEdge();
            fixedBar.setHighlight(-1);
            return false;
        }
    }

    private void handleLocation(float rx, float ry) {
        if (!haveFirst) {
            firstX = rx;
            firstY = ry;
            haveFirst = true;
        } else if (Math.abs(rx - firstX) > 24f || Math.abs(ry - firstY) > 24f) {
            moved = true;
        }
        View bar = fixedBar.getView();
        int[] bl = loc(bar);
        if (rx >= bl[0] && rx <= bl[0] + bar.getWidth()
                && ry >= bl[1] && ry <= bl[1] + bar.getHeight()) {
            fixedBar.setHighlight(fixedBar.slotAt(rx - bl[0]));
            clearWorkspaceHint();
            hoverEdge = 0;
            return;
        }
        fixedBar.setHighlight(-1);

        View ws = workspace.view();
        int[] wl = loc(ws);
        float x = rx - wl[0];
        if (x < 0 || x > ws.getWidth() || ry < wl[1] || ry > wl[1] + ws.getHeight()) {
            hoverEdge = 0;
            cancelEdge();
            clearWorkspaceHint();
            return;
        }
        // 实时落点占位高亮
        try {
            int[] hit = workspace.hit(x, ry - wl[1]);
            if (hit[0] >= 0 && hit[1] >= 0 && workspace.view() instanceof PagedWorkspaceView) {
                ((PagedWorkspaceView) workspace.view()).setDropHint(hit[0], hit[1]);
            }
        } catch (Throwable ignored) {
        }
        int edge = 0;
        if (x < ws.getWidth() * 0.08f) {
            edge = -1;
        } else if (x > ws.getWidth() * 0.92f) {
            edge = 1;
        }
        if (edge == hoverEdge) {
            return;
        }
        cancelEdge();
        hoverEdge = edge;
        if (edge == 0) {
            return;
        }
        final int dir = edge;
        edgeFlip = new Runnable() {
            @Override
            public void run() {
                if (dir < 0) {
                    workspace.prevPage();
                } else {
                    workspace.nextPage();
                }
            }
        };
        handler.postDelayed(edgeFlip, EDGE_HOVER_MS);
    }

    private void clearWorkspaceHint() {
        try {
            if (workspace != null && workspace.view() instanceof PagedWorkspaceView) {
                ((PagedWorkspaceView) workspace.view()).clearDropHintAll();
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleDrop(float rx, float ry) {
        if (dragging == null) {
            return;
        }
        View bar = fixedBar.getView();
        int[] bl = loc(bar);
        if (rx >= bl[0] && rx <= bl[0] + bar.getWidth()
                && ry >= bl[1] && ry <= bl[1] + bar.getHeight()) {
            int slot = fixedBar.slotAt(rx - bl[0]);
            if (slot >= 0) {
                dropped = true;
                host.onDropFixed(dragging, from, slot);
            }
            return;
        }
        View ws = workspace.view();
        int[] wl = loc(ws);
        if (rx >= wl[0] && rx <= wl[0] + ws.getWidth()
                && ry >= wl[1] && ry <= wl[1] + ws.getHeight()) {
            int[] hit = workspace.hit(rx - wl[0], ry - wl[1]);
            if (hit[0] >= 0) {
                dropped = true;
                host.onDropWorkspace(dragging, from, hit[0], hit[1]);
            }
        }
    }

    private int[] loc(View v) {
        int[] p = new int[2];
        if (v != null) {
            v.getLocationInWindow(p);
        }
        return p;
    }

    private void cancelEdge() {
        hoverEdge = 0;
        if (edgeFlip != null) {
            handler.removeCallbacks(edgeFlip);
            edgeFlip = null;
        }
    }
}
