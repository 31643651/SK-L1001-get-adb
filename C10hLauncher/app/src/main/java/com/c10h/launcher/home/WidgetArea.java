package com.c10h.launcher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 小组件宿主（规格 §6.3）：
 *  - AppWidgetHost 固定 hostId=1024（包名不变即持久，系统 AppWidgetService 存状态）；
 *  - 添加流：编辑模式「添加小组件」→ provider 列表 → bindAppWidgetIdIfAllowed →
 *    配置页（如有，回 onResume 恢复流兜住）→ inflate 挂区尾；
 *  - 恢复：onResume → startListening() + getAppWidgetIds() 逐个 inflate——杀进程/重启不丢；
 *  - 长按条目：移除 / 应用信息；
 *  - 无 BIND_APPWIDGET → 区域红字（§6.3 降级）。
 */
public class WidgetArea {

    private static final int HOST_ID = 1024;

    private final MainActivity host;
    private final LinearLayout column;
    private final AppWidgetHost wh;
    private final AppWidgetManager wm;
    private final boolean hasPerm;
    private TextView degrade;
    private boolean listening;

    public WidgetArea(MainActivity activity, LinearLayout container) {
        this.host = activity;
        this.column = container;
        this.wh = new AppWidgetHost(activity.getApplicationContext(), HOST_ID);
        this.wm = AppWidgetManager.getInstance(activity.getApplicationContext());
        this.hasPerm = checkPerm();
        if (!hasPerm) {
            degrade = new TextView(activity);
            degrade.setText("未授权添加小组件（需 BIND_APPWIDGET）");
            degrade.setTextColor(0xFFFF6B6B);
            degrade.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            degrade.setPadding(host.dp(14), host.dp(6), host.dp(14), host.dp(6));
            column.addView(degrade);
        }
    }

    private boolean checkPerm() {
        try {
            return host.checkSelfPermission("android.permission.BIND_APPWIDGET")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public void showPicker() {
        if (!hasPerm) {
            Toast.makeText(host, "未授权添加小组件（BIND_APPWIDGET）", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            final List<AppWidgetProviderInfo> provs = new ArrayList<AppWidgetProviderInfo>();
            final List<String> names = new ArrayList<String>();
            for (AppWidgetProviderInfo p : wm.getInstalledProviders()) {
                if (p != null) {
                    provs.add(p);
                    CharSequence label = p.loadLabel(host.getPackageManager());
                    names.add(label == null ? p.provider.getShortClassName() : label.toString());
                }
            }
            if (provs.isEmpty()) {
                Toast.makeText(host, "系统无可用小组件", Toast.LENGTH_SHORT).show();
                return;
            }
            new android.app.AlertDialog.Builder(host)
                    .setTitle("添加小组件")
                    .setItems(names.toArray(new String[0]),
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    bindAndAdd(provs.get(w));
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Toast.makeText(host, "小组件列表失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void bindAndAdd(AppWidgetProviderInfo info) {
        try {
            int id = wh.allocateAppWidgetId();
            boolean ok = wm.bindAppWidgetIdIfAllowed(id, info.provider);
            if (!ok) {
                try {
                    Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                    i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
                    host.startActivityForResult(i, 1001);
                } catch (Throwable t2) {
                    Toast.makeText(host, "绑定被拒（BIND_APPWIDGET 未生效）",
                            Toast.LENGTH_SHORT).show();
                    wh.deleteAppWidgetId(id);
                }
                return;
            }
            if (info.configure != null) {
                try {
                    Intent ci = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                    ci.setComponent(info.configure);
                    ci.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    host.startActivityForResult(ci, 1002);
                } catch (Throwable t) {
                    addViewFor(id, info);
                }
            } else {
                addViewFor(id, info);
            }
        } catch (Throwable t) {
            Toast.makeText(host, "添加失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void addViewFor(int id, AppWidgetProviderInfo info) {
        try {
            wh.startListening();
            AppWidgetHostView v = wh.createView(host, id, info);
            v.setAppWidget(id, info);
            wrapLongPress(v, id);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = host.dp(4);
            lp.bottomMargin = host.dp(4);
            column.addView(v, lp);
            ensureVisible();
            hideDegrade();
        } catch (Throwable t) {
            Toast.makeText(host, "小组件视图失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 区容器初始 GONE（空不占位）——挂上第一个小组件后恢复可见。 */
    private void ensureVisible() {
        try {
            View p = (View) column.getParent();
            if (p != null) {
                p.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    private void wrapLongPress(final AppWidgetHostView v, final int id) {
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    new android.app.AlertDialog.Builder(host)
                            .setTitle("小组件")
                            .setItems(new String[]{"移除", "应用信息"},
                                    new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d,
                                                            int w) {
                                            try {
                                                if (w == 0) {
                                                    wh.deleteAppWidgetId(id);
                                                    View c = findChild(id);
                                                    if (c != null) {
                                                        column.removeView(c);
                                                    }
                                                    relayout();
                                                } else {
                                                    AppWidgetProviderInfo p = wm.getAppWidgetInfo(id);
                                                    if (p != null) {
                                                        Intent i = new Intent(
                                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                                        i.setData(android.net.Uri.parse(
                                                                "package:" + p.provider.getPackageName()));
                                                        host.startActivity(i);
                                                    }
                                                }
                                            } catch (Throwable t) {
                                                Toast.makeText(host, "操作失败：" + t,
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    })
                            .show();
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
    }

    private View findChild(int id) {
        for (int i = 0; i < column.getChildCount(); i++) {
            View c = column.getChildAt(i);
            if (c instanceof AppWidgetHostView
                    && ((AppWidgetHostView) c).getAppWidgetId() == id) {
                return c;
            }
        }
        return null;
    }

    /** 恢复（onResume）：杀进程/重启不丢（§6.3）。 */
    public void restore() {
        if (!hasPerm) {
            return;
        }
        try {
            wh.startListening();
            listening = true;
            int[] ids = wh.getAppWidgetIds();
            if (ids == null || ids.length == 0) {
                return;
            }
            for (int id : ids) {
                if (findChild(id) != null) {
                    continue;   // 已在区中
                }
                try {
                    AppWidgetProviderInfo info = wm.getAppWidgetInfo(id);
                    if (info == null) {
                        continue;
                    }
                    AppWidgetHostView v = wh.createView(host, id, info);
                    v.setAppWidget(id, info);
                    wrapLongPress(v, id);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = host.dp(4);
                    lp.bottomMargin = host.dp(4);
                    column.addView(v, lp);
                } catch (Throwable ignored) {
                }
            }
            ensureVisible();
            hideDegrade();
        } catch (Throwable t) {
            Toast.makeText(host, "小组件恢复失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    public void stopListening() {
        try {
            if (listening) {
                wh.stopListening();
                listening = false;
            }
        } catch (Throwable ignored) {
        }
    }

    private void hideDegrade() {
        if (degrade != null) {
            degrade.setVisibility(View.GONE);
        }
    }

    /** 区域空则不占位（§6.1）。 */
    private void relayout() {
        try {
            View box = (View) column.getParent();
            if (box == null) {
                return;
            }
            boolean empty = column.getChildCount() == 0;
            ViewGroup.LayoutParams lp = box.getLayoutParams();
            lp.height = empty ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
            box.setLayoutParams(lp);
            box.setVisibility(empty ? View.GONE : View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }
}
